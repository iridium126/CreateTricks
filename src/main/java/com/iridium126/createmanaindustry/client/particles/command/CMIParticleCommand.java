package com.iridium126.createmanaindustry.client.particles.command;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.config.ClientConfig;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Debug / benchmark interface for the GPU particle engine.
 * <p>
 * Registered only on the client (client-side commands); every command body
 * first verifies the engine is available (Veil loaded + programs compiled) so
 * nothing touches Veil/heap classes when the dependency is absent.
 *
 * <pre>
 *   /cmip spawn &lt;preset&gt; [count]        burst at the player's feet
 *   /cmip stream &lt;preset&gt; &lt;rate&gt; [sec]  streaming (sec &lt;= 0 = until /cmip clear)
 *   /cmip anim &lt;preset&gt; &lt;animation&gt;     live-switch MODEL animation (fly/dance/hold)
 *   /cmip allaystorm [count ≤4096] [radius]  persistent boids bait ball (stop subcommand)
 *   /cmip bench &lt;count&gt;                 unthrottled stress test
 *   /cmip clear                          drop all particles and streams
 *   /cmip stats                          live count / budget / frame cost
 *   /cmip budget &lt;ms&gt;                   override the throttle budget
 * </pre>
 */
@EventBusSubscriber(modid = CreateManaIndustry.MODID, value = Dist.CLIENT)
public final class CMIParticleCommand {

    private CMIParticleCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> PRESETS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EmitterPresets.names(), builder);

    private static final SuggestionProvider<CommandSourceStack> ANIMATIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(EmitterPresets.animationNames(), builder);

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cmip")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESETS)
                                        .executes(ctx -> spawn(ctx, 1000))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 4_000_000))
                                                .executes(ctx -> spawn(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("stream")
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESETS)
                                        .then(Commands.argument("rate", IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(ctx -> stream(ctx, -1f))
                                                .then(Commands.argument("seconds",
                                                                FloatArgumentType.floatArg(0.1f, 3600f))
                                                        .executes(ctx -> stream(ctx,
                                                                FloatArgumentType.getFloat(ctx, "seconds")))))))
                        .then(Commands.literal("anim")
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESETS)
                                        .then(Commands.argument("animation", StringArgumentType.word())
                                                .suggests(ANIMATIONS)
                                                .executes(CMIParticleCommand::anim))))
                        .then(Commands.literal("bench")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8_000_000))
                                        .executes(CMIParticleCommand::bench)))
                        .then(Commands.literal("clear")
                                .executes(CMIParticleCommand::clear))
                        .then(Commands.literal("stats")
                                .executes(CMIParticleCommand::stats))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("ms", FloatArgumentType.floatArg(1f, 50f))
                                        .executes(CMIParticleCommand::budget)))
                        .then(Commands.literal("allaystorm")
                                .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0, 0.6f))
                                .then(Commands.literal("stop")
                                        .executes(CMIParticleCommand::allayStormStop))
                                .then(Commands.literal("ball")
                                        .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0, 0.6f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "ball",
                                                                IntegerArgumentType.getInteger(ctx, "count"), 8.0, 0.6f))
                                                        .then(Commands.argument("radius",
                                                                        FloatArgumentType.floatArg(2.0f, 64.0f))
                                                                .executes(ctx -> allayStorm(ctx, "ball",
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        FloatArgumentType.getFloat(ctx, "radius"), 0.6f)))))
                                .then(Commands.literal("vortex")
                                        .executes(ctx -> allayStorm(ctx, "vortex", 2048, 8.0, 0.6f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                                IntegerArgumentType.getInteger(ctx, "count"), 8.0, 0.6f))
                                                        .then(Commands.argument("radius",
                                                                        FloatArgumentType.floatArg(2.0f, 64.0f))
                                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        FloatArgumentType.getFloat(ctx, "radius"), 0.6f))
                                                                        .then(Commands.argument("omega",
                                                                                        FloatArgumentType.floatArg(0.05f, 3.0f))
                                                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                                        FloatArgumentType.getFloat(ctx, "radius"),
                                                                                        FloatArgumentType.getFloat(ctx, "omega")))))))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                        .executes(ctx -> allayStorm(ctx, "ball",
                                                                IntegerArgumentType.getInteger(ctx, "count"), 8.0, 0.6f))
                                                .then(Commands.argument("radius",
                                                                FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx, "ball",
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"), 0.6f)))))
                        .then(Commands.literal("shaderpack")
                                .then(Commands.literal("status")
                                        .executes(CMIParticleCommand::shaderPackStatus))));
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
        EmitterSpec spec = EmitterPresets.byName(StringArgumentType.getString(ctx, "preset"));
        if (spec == null) {
            tell(ctx, "Unknown preset. Try: " + String.join(", ", EmitterPresets.names()));
            return 0;
        }
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        Vec3 pos = ctx.getSource().getPosition().add(0, 0.2, 0);
        CMIParticleEngine.INSTANCE.spawn(spec, pos, count);
        tell(ctx, "Spawning " + count + " × " + StringArgumentType.getString(ctx, "preset") + " (throttled).");
        return Command.SINGLE_SUCCESS;
    }

    private static int stream(CommandContext<CommandSourceStack> ctx, float seconds) {
        EmitterSpec spec = EmitterPresets.byName(StringArgumentType.getString(ctx, "preset"));
        if (spec == null) {
            tell(ctx, "Unknown preset. Try: " + String.join(", ", EmitterPresets.names()));
            return 0;
        }
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        int rate = IntegerArgumentType.getInteger(ctx, "rate");
        Vec3 pos = ctx.getSource().getPosition().add(0, 0.2, 0);
        CMIParticleEngine.INSTANCE.stream(spec, pos, rate, seconds);
        String secs = seconds <= 0 ? "forever" : seconds + "s";
        tell(ctx, "Streaming " + rate + "/s × " + StringArgumentType.getString(ctx, "preset") + " for " + secs + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int anim(CommandContext<CommandSourceStack> ctx) {
        String presetName = StringArgumentType.getString(ctx, "preset");
        EmitterSpec spec = EmitterPresets.byName(presetName);
        if (spec == null || spec.material != EmitterSpec.Material.MODEL) {
            tell(ctx, "Unknown MODEL preset. Try: allay_fly, allay_dance, allay_hold");
            return 0;
        }
        String animName = StringArgumentType.getString(ctx, "animation");
        EmitterSpec.Animation anim = switch (animName) {
            case "fly" -> EmitterSpec.Animation.FLY;
            case "dance" -> EmitterSpec.Animation.DANCE;
            case "hold" -> EmitterSpec.Animation.HOLD;
            default -> null;
        };
        if (anim == null) {
            // the DEATH pose is pool-driven now (HP-death corpse countdown) --
            // a per-emitter header switch to it has no valid meaning
            tell(ctx, "Unknown animation. Try: fly, dance, hold");
            return 0;
        }
        if (!engine(ctx)) {
            return 0;
        }
        CMIParticleEngine.INSTANCE.setAnimation(spec, anim);
        tell(ctx, "Animation switch queued: " + presetName + " -> " + animName
                + " (live particles switch next frame).");
        return Command.SINGLE_SUCCESS;
    }

    private static int bench(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        Vec3 pos = ctx.getSource().getPosition().add(0, 0.2, 0);
        CMIParticleEngine.INSTANCE.spawnUnthrottled(EmitterPresets.MANA_BURST, pos, count);
        tell(ctx, "Bench: " + count + " unthrottled particles. Watch the frame cost with /cmip stats.");
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        CMIParticleEngine.INSTANCE.clear();
        tell(ctx, "Cleared all particles and streams.");
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable (Veil absent or shaders not ready).");
            return 0;
        }
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        tell(ctx, "", "§b[CMI particles]§r live=§e" + e.liveCount() + "§r/" + e.capacity()
                + "  streams=" + e.streamCount()
                + "  emission=" + Math.round(e.emissionScale() * 100) + "%"
                + "  gpu=" + String.format("%.2f", e.emaMs()) + "ms (budget " + e.budgetMs() + "ms)");
        return Command.SINGLE_SUCCESS;
    }

    private static int budget(CommandContext<CommandSourceStack> ctx) {
        float ms = FloatArgumentType.getFloat(ctx, "ms");
        if (!engine(ctx)) {
            tell(ctx, "Particle engine unavailable.");
            return 0;
        }
        CMIParticleEngine.INSTANCE.setBudget(ms);
        tell(ctx, "Particle frame budget set to " + ms + " ms.");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /cmip allaystorm [ball|vortex] [count ≤131072] [radius 2..64] [omega]
     * -- persistent storm anchored at the player; ball = boids bait ball,
     * vortex = rotating-frame orbital swarm (omega rad/s, signed flip is
     * chosen per storm). Re-running moves/re-sizes/re-types it; stop disperses.
     */
    private static int allayStorm(CommandContext<CommandSourceStack> ctx, String mode,
            int count, double radius, float omega) {
        if (!engine(ctx)) {
            return 0;
        }
        Vec3 pos = ctx.getSource().getPosition().add(0, 1.0, 0);
        CMIParticleEngine.INSTANCE.startStorm(pos, count, radius,
                "vortex".equals(mode) ? 2 : 1, omega);
        tell(ctx, "§b[CMI particles]§r Allay Storm (§e" + mode + "§r) assembling: §e" + count
                + "§r members, radius §e" + (int) radius
                + ("vortex".equals(mode) ? "§r, ω §e" + omega : "")
                + "§r (stop: /cmip allaystorm stop).");
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStop(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx)) {
            return 0;
        }
        CMIParticleEngine.INSTANCE.stopStorm();
        tell(ctx, "Allay Storm dispersed.");
        return Command.SINGLE_SUCCESS;
    }

    private static int shaderPackStatus(CommandContext<CommandSourceStack> ctx) {
        if (!engine(ctx))
            return 0;
        CMIParticleEngine e = CMIParticleEngine.INSTANCE;
        String integration = ClientConfig.shaderPackIntegration ? "auto" : "off";
        tell(ctx, "", "§b[CMI particles]§r shader-pack path:"
                + " config=§e" + integration + "§r"
                + "  irisveil=§e" + (CreateManaIndustry.IRISVEIL_ACTIVE ? "loaded" : "absent") + "§r"
                + "  path=§e" + e.shaderPackPathStatus + "§r"
                + "  depth=§e" + e.shaderPackDepthStatus + "§r"
                + "  shadow=§e" + e.shaderPackShadowStatus);
        tell(ctx, "§7", "permutation: §e" + e.shaderPackPermStatus);
        if (!e.shaderPackErrorStatus.isEmpty())
            tell(ctx, "§7", "last fallback reason: §c" + e.shaderPackErrorStatus + "§r");
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean engine(CommandContext<CommandSourceStack> ctx) {
        // The engine is self-hosted GL — Veil is no longer required to spawn.
        if (!CMIParticleEngine.INSTANCE.available()) {
            tell(ctx, "§cParticle engine unavailable (GL/GPU too old or shaders not compiled; F3+T to recompile).§r");
            return false;
        }
        return true;
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String message) {
        tell(ctx, "§7", message);
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String prefix, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(prefix + message), false);
    }
}