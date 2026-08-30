package com.iridium126.createmanaindustry.content.allaystorm;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.iridium126.createmanaindustry.config.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side Allay Storm command (op, permission 2). Migrated from the old
 * CLIENT-only debug command so the storm is server-authoritative: the
 * definition persists in the level attachment and every player sees the same
 * swarm. Re-running moves the chased anchor (identity and HP are preserved —
 * the count argument only sizes a NEW storm); {@code stop} ends it everywhere.
 * <pre>
 *   /cmip allaystorm [count ≤131072]   (default 2048)
 *   /cmip allaystorm stop
 * </pre>
 * The storm radius derives from the population
 * ({@link AllayStormData#vortexRadius}) and the angular velocity follows
 * client-side ({@link AllayStormData#vortexOmega}: {@code SPIN_K / radius},
 * handedness from the seed's low bit) — neither takes a command argument.
 */
@EventBusSubscriber(modid = com.iridium126.createmanaindustry.CreateManaIndustry.MODID)
public final class AllayStormCommand {

    private AllayStormCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cmip")
                        .then(Commands.literal("allaystorm")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> allayStorm(ctx, 2048))
                                .then(Commands.literal("stop")
                                        .executes(AllayStormCommand::allayStormStop))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 131072))
                                        .executes(ctx -> allayStorm(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    private static int allayStorm(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        AllayStormManager.setStorm(level,
                net.minecraft.core.BlockPos.containing(src.getPosition().add(0.0, 1.0, 0.0)), count);
        // display the CANONICAL derived values — the radius derives from the
        // population and ω follows from it (neither takes a command argument)
        float radius = AllayStormData.vortexRadius(count);
        String finalText = "§b[CMI storm]§r Allay Storm: §e" + count + "§r members, radius §e"
                + String.format("%.1f (auto)", radius)
                + String.format("§r, ω §e±%.3f§r rad/s (auto)", AllayStormData.vortexOmega(radius, 0))
                + "§r, initial spawn §e" + count + "§r, growing to §e"
                + ServerConfig.stormMaxCount + "§r at §e" + String.format("%.1f", ServerConfig.stormGrowthPerSecond)
                + "/s§r — persisted & synced (stop: /cmip allaystorm stop)";
        src.sendSuccess(() -> Component.literal(finalText), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int allayStormStop(CommandContext<CommandSourceStack> ctx) {
        AllayStormManager.stopStorm(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("Allay Storm dispersed."), false);
        return Command.SINGLE_SUCCESS;
    }
}
