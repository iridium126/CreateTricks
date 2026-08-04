package com.iridium126.createmanaindustry.mixin.trickster;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.compat.trickster.TricksterDisplaySync;
import com.iridium126.createmanaindustry.content.display.SpellConstructDisplayArguments;

import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(targets = {
        "dev.enjarai.trickster.block.ModularSpellConstructBlockEntity",
        "dev.enjarai.trickster.block.SpellConstructBlockEntity"
})
public class TricksterSpellConstructSyncMixin {
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void createmanaindustry$syncDisplayArguments(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() == null || be.getLevel().isClientSide())
            return;
        if (!be.getPersistentData().contains(SpellConstructDisplayArguments.NBT_KEY))
            return;

        TricksterDisplaySync.syncExecutors(be);
    }
}
