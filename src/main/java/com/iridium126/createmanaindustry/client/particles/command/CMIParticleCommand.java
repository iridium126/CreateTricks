package com.iridium126.createmanaindustry.client.particles.command;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.HexSpecs;
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
 * first verifies the engine is available (GL capable + programs compiled).
 * The hex-spray command gates on {@code CreateManaIndustry.HEX_ACTIVE}
 * instead: hexcasting is an optional dependency and {@code HexSpecs} links
 * its API types, so both the handler and the pigment tab-completion must
 * stay behind that flag or they throw {@code NoClassDefFoundError} with the
 * mod absent.
 *
 * <pre>
 *   /cmip spawn &lt;preset&gt; [count]        burst at the player's feet
 *   /cmip stream &lt;preset&gt; &lt;rate&gt; [sec]  streaming (sec &lt;= 0 = until /cmip clear)
 *   /cmip anim &lt;preset&gt; &lt;animation&gt;     live-switch MODEL animation (fly/dance/hold)
 *   /cmip spray &lt;pigment&gt; [count]       Hexcasting conjure spray at the player
 *                                       (amethyst / uuid / rainbow)
 *   /cmip allaystorm [count ≤4096] [radius]  MOVED to the server command (storm.StormCommand)
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

    // Behind HEX_ACTIVE as well: resolving HexSpecs.Pigment loads HexSpecs,
    // which links hexcasting API types — tab-completing "/cmip spray <tab>"
    // with the mod absent must not throw before the handler's guard runs.
    private static final SuggestionProvider<CommandSourceStack> PIGMENTS = (ctx, builder) ->
            CreateManaIndustry.HEX_ACTIVE
                    ? SharedSuggestionProvider.suggest(java.util.Arrays.stream(HexSpecs.Pigment.values())
                            .map(p -> p.name().toLowerCase(java.util.Locale.ROOT)).toList(), builder)
                    : builder.buildFuture();

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
                        .then(Commands.literal("spray")
                                .then(Commands.argument("pigment", StringArgumentType.word())
                                        .suggests(PIGMENTS)
                                        .executes(ctx -> spray(ctx, 30))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2000))
                                                .executes(ctx -> spray(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
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
                        // allaystorm moved to the SERVER command (storm.StormCommand):
                        // the storm is server-authoritative — persisted in the level
                        // attachment and synced to every client.
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

    private static int spray(CommandContext<CommandSourceStack> ctx, int count) {
        // Hexcasting is optional: HexSpecs (and the ColorProvider below) link
        // its API types, so the whole handler must return before touching them.
        if (!CreateManaIndustry.HEX_ACTIVE) {
            tell(ctx, "Conjure sprays require Hexcasting, which is not loaded.");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "pigment");
        HexSpecs.Pigment pigment;
        try {
            pigment = HexSpecs.Pigment.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            pigment = null;
        }
        if (pigment == null) {
            tell(ctx, "Unknown pigment. Try: amethyst, uuid, rainbow");
            return 0;
        }
        if (!engine(ctx)) {
            return 0;
        }
        // vanilla StaffCastEnv caster spray: origin = the player's position,
        // velocity straight up 1.5 b/s, fuzziness 0.4, spread π/3, 30 motes.
        // NeoForge client-command sources carry no player entity, so the
        // local player comes straight off Minecraft.
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            tell(ctx, "A player is required (the uuid pigment keys off the caster's UUID).");
            return 0;
        }
        at.petrak.hexcasting.api.pigment.ColorProvider provider =
                HexSpecs.pigment(pigment, player.getUUID());
        Vec3 pos = player.position();
        CMIParticleEngine.INSTANCE.spawnHexSpray(pos, new Vec3(0.0, 1.5, 0.0),
                0.4, Math.PI / 3, count, HexSpecs.sampleWheel(provider));
        tell(ctx, "Hex spray: " + count + " conjure motes, pigment " + name + ".");
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