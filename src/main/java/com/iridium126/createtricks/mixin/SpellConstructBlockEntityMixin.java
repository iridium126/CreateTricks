package com.iridium126.createtricks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = "dev.enjarai.trickster.block.SpellConstructBlockEntity")
public class SpellConstructBlockEntityMixin {
	@Redirect(
		method = "refreshExecutor",
		at = @At(
			value = "INVOKE",
			target = "Ldev/enjarai/trickster/spell/execution/executor/DefaultSpellExecutor;<init>(Ldev/enjarai/trickster/spell/SpellPart;Ljava/util/List;)V"
		)
	)
	private Object createtricks$createExecutor(Object spell, List<?> ignored) {
		BlockEntity self = (BlockEntity) (Object) this;
		return TricksterReflection.createDefaultSpellExecutor(spell, self);
	}
}
