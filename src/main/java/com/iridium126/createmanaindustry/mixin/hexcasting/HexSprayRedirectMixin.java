package com.iridium126.createmanaindustry.mixin.hexcasting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.HexSpecs;
import com.iridium126.createmanaindustry.config.ClientConfig;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.common.msgs.MsgCastParticleS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Redirects Hexcasting's cast/conjure particle sprays to the GPU particle
 * engine: {@code MsgCastParticleS2C.Handler.handle} runs on the client for
 * every {@code ParticleSpray} emitted server-side — staff casting, spell
 * circles, mishaps, and CMI's own actions ({@code OpLightBurner} et al. ride
 * the same pipeline). When the redirect config is on and the engine is
 * available, the spray is re-sampled into the engine's conjure replication
 * (spawnStyle 4) and the vanilla particle spawn is cancelled; otherwise the
 * vanilla path falls through untouched.
 * <p>
 * Pigment colors survive via spawn-time sampling: the spray's FrozenPigment
 * is sampled over 8 gradient-axis directions into the wheel
 * ({@link HexSpecs#sampleWheel}) — exactly vanilla's own sampling instant
 * (each vanilla particle freezes its color at spawn), so the per-spray
 * direction shimmer and the per-spray time evolution both survive.
 */
@Mixin(targets = "at.petrak.hexcasting.common.msgs.MsgCastParticleS2C$Handler")
public class HexSprayRedirectMixin {

    @Inject(method = "handle(Lat/petrak/hexcasting/common/msgs/MsgCastParticleS2C;)V",
            at = @At("HEAD"), cancellable = true)
    private static void cmi$redirectHexSpray(MsgCastParticleS2C msg, CallbackInfo ci) {
        if (!ClientConfig.hexSprayRedirect)
            return;
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!engine.available())
            return; // engine down: vanilla fallback
        // The vanilla handler hops to the main thread inside; the hexSprays
        // queue is render-thread state, so the GPU spawn hops too (execute
        // runs inline when already on the main thread).
        ParticleSpray spray = msg.spray();
        Minecraft.getInstance().execute(() -> {
            float[] wheel = HexSpecs.sampleWheel(msg.colorizer().getColorProvider());
            engine.spawnHexSpray(spray.getPos(), spray.getVel(), spray.getFuzziness(),
                    spray.getSpread(), spray.getCount(), wheel);
        });
        ci.cancel();
    }
}
