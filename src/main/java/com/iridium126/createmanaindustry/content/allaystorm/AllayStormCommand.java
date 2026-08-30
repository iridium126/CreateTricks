package com.iridium126.createmanaindustry.content.allaystorm;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.iridium126.createmanaindustry.config.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side Allay Storm command (op, permission 2). Migrated from the old
 * CLIENT-only debug command so the storm is server-authoritative: the
 * definition persists in the level attachment and every player sees the same
 * swarm. Re-running moves / resizes / retypes the storm (identity and HP are
 * kept while the population is unchanged); {@code stop} ends it everywhere.
 * <pre>
 *   /cmip allaystorm ball [count ≤131072] [radius 2..64]
 *   /cmip allaystorm vortex [count ≤131072]
 *   /cmip allaystorm stop
 * </pre>
 * Vortex takes NO radius/omega: the radius derives from the population
 * ({@code sqrt(count)/8}, see {@link AllayStormData#vortexRadius}) and the
 * angular velocity follows client-side ({@code 6/radius}, handedness from
 * the seed's low bit). The generic {@code mode} word branch still parses a
 * radius for ball; vortex ignores it. Count changes restart the storm; the
 * generic branch exists for scripting convenience.
 */
@EventBusSubscriber(modid = com.iridium126.createmanaindustry.CreateManaIndustry.MODID)
public final class AllayStormCommand {

    private static final SuggestionProvider<CommandSourceStack> MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[] { "ball", "vortex" }, builder);

    private AllayStormCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cmip")
                        .then(Commands.literal("allaystorm")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0f))
                                .then(Commands.literal("stop")
                                        .executes(AllayStormCommand::allayStormStop))
                                .then(Commands.literal("ball")
                                        .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "ball",
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f))
                                                .then(Commands.argument("radius", FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx, "ball",
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"))))))
                                .then(Commands.literal("vortex")
                                        .executes(ctx -> allayStorm(ctx, "vortex", 2048, 8.0f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f))))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(MODES)
                                        .executes(ctx -> allayStorm(ctx,
                                                StringArgumentType.getString(ctx, "mode"), 2048, 8.0f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx,
                                                        StringArgumentType.getString(ctx, "mode"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f))
                                                .then(Commands.argument("radius", FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx,
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"))))))));
    }

    private static int allayStorm(CommandContext<CommandSourceStack> ctx, String mode,
            int count, float radius) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        AllayStormManager.setStorm(level,
                net.minecraft.core.BlockPos.containing(src.getPosition().add(0.0, 1.0, 0.0)),
                count, radius, "vortex".equals(mode) ? 2 : 1);
        // display the CANONICAL derived values for vortex (the passed radius
        // is ignored there; ω is client-derived, magnitude 6/R)
        boolean vortex = "vortex".equals(mode);
        float effRadius = vortex ? AllayStormData.vortexRadius(count) : radius;
        String radiusText = vortex
                ? String.format("%.1f (auto)", effRadius)
                : String.valueOf((int) radius);
        String omegaText = vortex
                ? String.format(", ω §e±%.3f§r rad/s (auto)", AllayStormData.vortexOmega(effRadius, 0))
                : "";
        String growthText = "§r, initial spawn §e" + count + "§r, growing to §e"
                + ServerConfig.stormMaxCount + "§r at §e" + String.format("%.1f", ServerConfig.stormGrowthPerSecond)
                + "/s";
        String finalText = "§b[CMI storm]§r Allay Storm (§e" + mode + "§r): §e" + count + "§r members, radius §e"
                + radiusText + omegaText + growthText + "§r — persisted & synced (stop: /cmip allaystorm stop)";
        src.sendSuccess(() -> Component.literal(finalText), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStop(CommandContext<CommandSourceStack> ctx) {
        AllayStormManager.stopStorm(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("Allay Storm dispersed."), false);
        return Command.SINGLE_SUCCESS;
    }
}
