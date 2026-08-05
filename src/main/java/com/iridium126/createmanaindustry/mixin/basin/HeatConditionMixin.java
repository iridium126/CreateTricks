package com.iridium126.createmanaindustry.mixin.basin;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.recipes.CMIHeatConditions;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.recipe.HeatCondition;

import net.createmod.catnip.codecs.stream.CatnipStreamCodecBuilders;
import net.minecraft.util.StringRepresentable;

import sun.misc.Unsafe;

/**
 * Injects a new {@code ALLAYHEATED} constant into Create's {@link HeatCondition}
 * enum reflectively (enums are closed in Java), so recipe JSONs can use
 * {@code "heat_requirement": "allayheated"}.
 * <p>
 * The new constant rejects all {@link HeatCondition#testBlazeBurner} calls by
 * default; the actual "only an actively burning Allay Burner satisfies this"
 * check lives in {@link BasinRecipeMixin}, which has the basin context.
 * {@code visualizeAsBlazeBurner} maps it to {@code SEETHING} so JEI shows the
 * heater lit.
 * <p>
 * Java 21 forbids {@code Constructor.newInstance} on enum constructors and no
 * longer ships {@code Field.modifiers}, so the constant is created with
 * {@link Unsafe#allocateInstance} and all fields are written through Unsafe,
 * which bypasses both the enum restriction and the {@code final} check.
 */
@Mixin(value = HeatCondition.class, remap = false)
public abstract class HeatConditionMixin {

    private static final Unsafe UNSAFE = getUnsafe();

    /**
     * {@code objectFieldOffset(Field)} / {@code staticFieldOffset(Field)} are
     * deprecated since JDK 18 with no replacement — the {@code (Class, String)}
     * overloads do not exist in JDK 21.
     */
    @SuppressWarnings("deprecation")
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void createmanaindustry$injectAllayHeated(CallbackInfo ci) {
        try {
            Class<HeatCondition> hc = HeatCondition.class;
            // Allocate without invoking the private enum constructor (reflection
            // may no longer call it), then write the fields directly.
            HeatCondition constant = (HeatCondition) UNSAFE.allocateInstance(hc);
            UNSAFE.putObject(constant, UNSAFE.objectFieldOffset(Enum.class.getDeclaredField("name")), "ALLAYHEATED");
            UNSAFE.putInt(constant, UNSAFE.objectFieldOffset(Enum.class.getDeclaredField("ordinal")), 3);
            UNSAFE.putInt(constant, UNSAFE.objectFieldOffset(hc.getDeclaredField("color")), 0xC88AFF); // media pink

            // Append the constant to $VALUES (a static final field; Unsafe
            // ignores the final flag).
            long valuesOffset = UNSAFE.staticFieldOffset(hc.getDeclaredField("$VALUES"));
            HeatCondition[] newValues = Arrays.copyOf((HeatCondition[]) UNSAFE.getObject(hc, valuesOffset), 4);
            newValues[3] = constant;
            UNSAFE.putObject(hc, valuesOffset, newValues);

            CMIHeatConditions.ALLAY_HEATED = constant;

            // Both codecs captured the pre-injection value array at class init.
            // Rebuild them so "allayheated" parses from recipe JSON and packets.
            UNSAFE.putObject(hc, UNSAFE.staticFieldOffset(hc.getDeclaredField("CODEC")),
                StringRepresentable.fromEnum(HeatCondition::values));
            UNSAFE.putObject(hc, UNSAFE.staticFieldOffset(hc.getDeclaredField("STREAM_CODEC")),
                CatnipStreamCodecBuilders.ofEnum(HeatCondition.class));
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.warn("Failed to inject ALLAYHEATED into HeatCondition", e);
        }
    }

    @Inject(method = "testBlazeBurner", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$allayHeatedRejectsAll(BlazeBurnerBlock.HeatLevel level,
            CallbackInfoReturnable<Boolean> cir) {
        HeatCondition allayHeated = CMIHeatConditions.ALLAY_HEATED;
        if (allayHeated != null && (Object) this == allayHeated)
            cir.setReturnValue(false);
    }

    @Inject(method = "visualizeAsBlazeBurner", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$visualizeAllayHeated(CallbackInfoReturnable<BlazeBurnerBlock.HeatLevel> cir) {
        HeatCondition allayHeated = CMIHeatConditions.ALLAY_HEATED;
        if (allayHeated != null && (Object) this == allayHeated)
            cir.setReturnValue(BlazeBurnerBlock.HeatLevel.SEETHING);
    }

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to obtain sun.misc.Unsafe", e);
        }
    }
}
