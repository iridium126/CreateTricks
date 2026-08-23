package com.iridium126.createmanaindustry.client.particles.command;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterPresets;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;

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
                        .then(Commands.literal("bench")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8_000_000))
                                        .executes(CMIParticleCommand::bench)))
                        .then(Commands.literal("clear")
                                .executes(CMIParticleCommand::clear))
                        .then(Commands.literal("stats")
                                .executes(CMIParticleCommand::stats))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("ms", FloatArgumentType.floatArg(1f, 50f))
                                        .executes(CMIParticleCommand::budget))));
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
