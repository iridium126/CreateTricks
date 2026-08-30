package com.iridium126.createmanaindustry.client.particles.allaystorm;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.network.ClientboundStormCenterPacket;
import com.iridium126.createmanaindustry.network.ClientboundStormDamagePacket;
import com.iridium126.createmanaindustry.network.ClientboundStormPositionsPacket;
import com.iridium126.createmanaindustry.network.ClientboundStormStatePacket;

import net.minecraft.world.phys.Vec3;

/**
 * Client receiver for the Allay Storm network protocol. Payload handlers run
 * on the render thread ({@code enqueueWork} — the Minecraft main thread IS
 * the render thread), which is the thread every storm state lives on, so the
 * engine calls below are direct rather than queued.
 * <p>
 * All heavy work stays in {@link CMIParticleEngine}: this class only unwraps
 * packet fields (quantized values arrive pre-scaled by the codecs) and
 * forwards. It also decodes the dead-member bitmap into the engine's mirror
 * on ACTIVATE (identity/counts are then a pure client-side derivation).
 */
public final class AllayStormClientHandler {

    private AllayStormClientHandler() {
    }

    /** {@link ClientboundStormStatePacket}: lifecycle + authority flips. */
    public static void onState(ClientboundStormStatePacket packet) {
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        Vec3 anchor = new Vec3(packet.anchor().getX(), packet.anchor().getY(), packet.anchor().getZ());
        e.applyStormState(packet.action(), packet.authority(), packet.correctionHz(),
                anchor, packet.count(), packet.stormSeed(), packet.creationRadius(),
                packet.finalRadius(), packet.growthPerSecond(), packet.createdAt(),
                packet.deadBitmap());
    }

    /** {@link ClientboundStormDamagePacket}: authoritative damage + hit correction. */
    public static void onDamage(ClientboundStormDamagePacket packet) {
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        Vec3 rel = new Vec3(packet.relX(), packet.relY(), packet.relZ());
        e.applyStormDamage(packet.memberIdx(), packet.damage(), packet.kbX(), packet.kbZ(),
                packet.light(), packet.died(), rel, gameClock());
    }

    /** {@link ClientboundStormPositionsPacket}: relayed authority snapshot. */
    public static void onPositions(ClientboundStormPositionsPacket packet) {
        CMIParticleEngine.INSTANCE.applyCorrections(packet.entries(), packet.gameTime());
    }

    /** {@link ClientboundStormCenterPacket}: continuous chased-center + growth snapshot. */
    public static void onCenter(ClientboundStormCenterPacket packet) {
        CMIParticleEngine.INSTANCE.applyStormCenter(packet.x(), packet.y(), packet.z(),
                packet.velX(), packet.velZ(), packet.count(), packet.gameTime());
    }

    /** {@link ClientboundStormWavePacket}: dive-wave launch / abort event. */
    public static void onWave(
            com.iridium126.createmanaindustry.network.ClientboundStormWavePacket packet) {
        CMIParticleEngine.INSTANCE.applyWave(packet.waveId(), packet.abort(), packet.waveSeed(),
                packet.fraction(), packet.targetEntityId(), packet.path(),
                packet.assembleSec(), packet.diveUntilSec(), packet.swordTier());
    }

    /** The client's shared simulation clock (game ticks; the engine mod-masks). */
    private static long gameClock() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }
}
