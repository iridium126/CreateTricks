package com.iridium126.createmanaindustry.storm;

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
 *   /cmip allaystorm [ball|vortex] [count ≤131072] [radius 2..64] [omega]
 *   /cmip allaystorm stop
 * </pre>
 */
@EventBusSubscriber(modid = com.iridium126.createmanaindustry.CreateManaIndustry.MODID)
public final class StormCommand {

    private static final SuggestionProvider<CommandSourceStack> MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(new String[] { "ball", "vortex" }, builder);

    private StormCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cmip")
                        .then(Commands.literal("allaystorm")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0f, 0.6f))
                                .then(Commands.literal("stop")
                                        .executes(StormCommand::allayStormStop))
                                .then(Commands.literal("ball")
                                        .executes(ctx -> allayStorm(ctx, "ball", 2048, 8.0f, 0.6f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "ball",
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f, 0.6f))
                                                .then(Commands.argument("radius", FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx, "ball",
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"), 0.6f)))))
                                .then(Commands.literal("vortex")
                                        .executes(ctx -> allayStorm(ctx, "vortex", 2048, 8.0f, 0.6f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f, 0.6f))
                                                .then(Commands.argument("radius", FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx, "vortex",
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"), 0.6f))
                                                        .then(Commands.argument("omega", FloatArgumentType.floatArg(0.05f, 3.0f))
                                                                .executes(ctx -> allayStorm(ctx, "vortex",
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        FloatArgumentType.getFloat(ctx, "radius"),
                                                                        FloatArgumentType.getFloat(ctx, "omega")))))))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(MODES)
                                        .executes(ctx -> allayStorm(ctx,
                                                StringArgumentType.getString(ctx, "mode"), 2048, 8.0f, 0.6f))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                                .executes(ctx -> allayStorm(ctx,
                                                        StringArgumentType.getString(ctx, "mode"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), 8.0f, 0.6f))
                                                .then(Commands.argument("radius", FloatArgumentType.floatArg(2.0f, 64.0f))
                                                        .executes(ctx -> allayStorm(ctx,
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                FloatArgumentType.getFloat(ctx, "radius"), 0.6f)))))));
    }

    private static int allayStorm(CommandContext<CommandSourceStack> ctx, String mode,
            int count, float radius, float omega) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        StormManager.setStorm(level,
                net.minecraft.core.BlockPos.containing(src.getPosition().add(0.0, 1.0, 0.0)),
                count, radius, "vortex".equals(mode) ? 2 : 1, omega);
        src.sendSuccess(() -> Component.literal(
                "§b[CMI storm]§r Allay Storm (§e" + mode + "§r): §e" + count + "§r members, radius §e" + (int) radius
                + ("vortex".equals(mode) ? "§r, ω §e" + omega : "")
                + "§r — persisted & synced (stop: /cmip allaystorm stop)"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStop(CommandContext<CommandSourceStack> ctx) {
        StormManager.stopStorm(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("Allay Storm dispersed."), false);
        return Command.SINGLE_SUCCESS;
    }
}
