package com.iridium126.createmanaindustry.mixin.basin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 */
@Mixin(value = HeatCondition.class, remap = false)
public abstract class HeatConditionMixin {

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void createmanaindustry$injectAllayHeated(CallbackInfo ci) {
        try {
            Class<HeatCondition> hc = HeatCondition.class;
            Constructor<?> ctor = hc.getDeclaredConstructors()[0]; // HeatCondition(int color)
            ctor.setAccessible(true);
            HeatCondition constant = (HeatCondition) ctor.newInstance(0xC88AFF); // media pink

            Field nameField = Enum.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(constant, "ALLAYHEATED");
            Field ordinalField = Enum.class.getDeclaredField("ordinal");
            ordinalField.setAccessible(true);
            ordinalField.set(constant, 3);

            Field valuesField = hc.getDeclaredField("$VALUES");
            makeNonFinal(valuesField);
            valuesField.setAccessible(true);
            HeatCondition[] oldValues = (HeatCondition[]) valuesField.get(null);
            HeatCondition[] newValues = Arrays.copyOf(oldValues, oldValues.length + 1);
            newValues[oldValues.length] = constant;
            valuesField.set(null, newValues);

            CMIHeatConditions.ALLAY_HEATED = constant;

            // Both codecs captured the pre-injection value array at class init.
            // Rebuild them so "allayheated" parses from recipe JSON and packets.
            setStatic(hc, "CODEC", StringRepresentable.fromEnum(HeatCondition::values));
            setStatic(hc, "STREAM_CODEC", CatnipStreamCodecBuilders.ofEnum(HeatCondition.class));
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

    private static void makeNonFinal(Field field) throws Exception {
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }

    private static void setStatic(Class<?> clazz, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        makeNonFinal(field);
        field.setAccessible(true);
        field.set(null, value);
    }
}
