package com.yourserver.chestlogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourserver.chestlogger.ChestLoggerMod;
import com.yourserver.chestlogger.gui.ChestLogGui;
import com.yourserver.chestlogger.logging.ChestLogEvent;
import com.yourserver.chestlogger.logging.ChestLogReader;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import com.yourserver.chestlogger.rollback.ChestLogRollback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChestLogCommand {
    private ChestLogCommand() {}

    private static boolean hasPermissionLevel(CommandSourceStack source, int level) {
        try {
            for (java.lang.reflect.Method m : source.getClass().getMethods()) {
                if ((m.getName().equals("hasPermission") || m.getName().equals("hasPermissionLevel"))
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class) {
                    return (Boolean) m.invoke(source, level);
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var statusNode = Commands.literal("status")
            .executes(ctx -> {
                ChestLogWriter w = ChestLoggerMod.writer();
                ctx.getSource().sendSuccess(() -> Component.literal(String.format("§7[ChestLogger] Engine: %s §7| Dropped Events: §f%d",
                    w.isDisabled() ? "§cDISABLED (Circuit Broken)" : "§aOK (Active)", w.getDroppedEventCount())), false);
                return 1;
            });

        var inspectNode = Commands.literal("inspect")
            .executes(ctx -> {
                CommandSourceStack source = ctx.getSource();
                if (source.getEntity() instanceof ServerPlayer player) {
                    net.minecraft.world.phys.HitResult hit = player.pick(5.0D, 0.0F, false);
                    if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        BlockPos pos = blockHit.getBlockPos();
                        ChestLogGui.open(player, pos);
                    } else {
                        source.sendFailure(Component.literal("§c[ChestLogger] No block targeted. Look at a chest or specify coordinates: /chestlog inspect <x> <y> <z>"));
                    }
                } else {
                    source.sendFailure(Component.literal("§c[ChestLogger] GUI inspect command must be executed by a player in-game."));
                }
                return 1;
            })
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                    if (source.getEntity() instanceof ServerPlayer player) {
                        ChestLogGui.open(player, pos);
                    } else {
                        source.sendFailure(Component.literal("§c[ChestLogger] GUI inspect command must be executed by a player in-game."));
                    }
                    return 1;
                })
                .then(Commands.literal("chat")
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                        long packedPos = pos.asLong();
                        Path logDir = ChestLoggerMod.logDirectory();
                        source.sendSuccess(() -> Component.literal("§7[ChestLogger] Inspecting " + pos.toShortString() + "..."), false);
                        CompletableFuture.supplyAsync(() -> ChestLogReader.queryAll(logDir, ChestLoggerMod.writer(), packedPos))
                            .thenAcceptAsync(q -> {
                                if (!q.isComplete()) {
                                    source.sendFailure(Component.literal("§c[ChestLogger] Historical read incomplete. Failed segments: " + q.failedSegments().size()));
                                }
                                SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");
                                for (ChestLogEvent e : q.events()) {
                                    String action = e.countDiff > 0 ? "§a+ " + e.countDiff : "§c" + e.countDiff;
                                    source.sendSuccess(() -> Component.literal(String.format("§8[%s] §7Player: §f%s §7| %s §7item: §f%s §8(Tx: %d)",
                                        df.format(new Date(e.timestampMillis)),
                                        e.playerId == null ? "unknown" : e.playerId.toString().substring(0, 8),
                                        action, e.itemId, e.transactionId)), false);
                                }
                            }, source.getServer());
                        return 1;
                    })));

        var rollbackNode = Commands.literal("rollback")
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("time_range", StringArgumentType.word())
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        ServerLevel world = source.getLevel();
                        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                        long duration = parseDuration(StringArgumentType.getString(ctx, "time_range"));
                        long cutoffMillis = System.currentTimeMillis() - duration;
                        long packedPos = pos.asLong();
                        Path logDir = ChestLoggerMod.logDirectory();
                        ChestLogWriter writer = ChestLoggerMod.writer();
                        if (writer.isDisabled()) {
                            source.sendFailure(Component.literal("§c[ChestLogger] Rollback Aborted: Writer circuit breaker active. Zero changes made."));
                            return 0;
                        }
                        source.sendSuccess(() -> Component.literal("§7[ChestLogger] Querying transactions off-thread..."), false);
                        CompletableFuture.supplyAsync(() -> ChestLogReader.queryAll(logDir, writer, packedPos))
                            .thenAcceptAsync(q -> {
                                if (writer.isDisabled()) {
                                    source.sendFailure(Component.literal("§c[ChestLogger] Rollback Aborted: Writer circuit breaker active. Zero changes made."));
                                    return;
                                }
                                if (!q.isComplete()) {
                                    source.sendFailure(Component.literal("§c[ChestLogger] Rollback Aborted: Could not read all segment files safely. Failed files: " + q.failedSegments().size()));
                                    return;
                                }
                                ChestLogRollback.Result result = ChestLogRollback.rollback(world, pos, q.events(), cutoffMillis);
                                if (!result.success()) {
                                    source.sendFailure(Component.literal("§c[ChestLogger] Rollback Aborted: " + result.errorMessage()));
                                    return;
                                }
                                UUID adminId = source.getEntity() != null ? source.getEntity().getUUID() : new UUID(0L, 0L);
                                long droppedBefore = writer.getDroppedEventCount();
                                writer.enqueue(ChestLogEvent.adminRollback(System.currentTimeMillis(), adminId, packedPos, result.transactionCount()));
                                boolean auditRejected = writer.isDisabled() || writer.getDroppedEventCount() > droppedBefore;
                                if (auditRejected) {
                                    source.sendSuccess(() -> Component.literal(String.format("§e[ChestLogger] Rollback applied, but audit event was rejected or dropped. restored=%d removed=%d", result.restoredItems(), result.removedItems())), true);
                                } else {
                                    source.sendSuccess(() -> Component.literal(String.format("§a[ChestLogger] Rollback committed: %d restored, %d removed, %d dropped (%d txs). Audit queued.", result.restoredItems(), result.removedItems(), result.droppedItems(), result.transactionCount())), true);
                                }
                            }, world.getServer());
                        return 1;
                    })));

        dispatcher.register(Commands.literal("chestlog")
            .requires(s -> hasPermissionLevel(s, 2))
            .then(statusNode)
            .then(inspectNode)
            .then(rollbackNode));
    }

    private static long parseDuration(String input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("Time range required");
        char suffix = input.charAt(input.length() - 1);
        long value = Long.parseLong(input.substring(0, input.length() - 1));
        if (value <= 0) throw new IllegalArgumentException("Time range must be positive");
        return switch (suffix) {
            case 'm' -> Math.multiplyExact(value, 60_000L);
            case 'h' -> Math.multiplyExact(value, 3_600_000L);
            case 'd' -> Math.multiplyExact(value, 86_400_000L);
            default -> throw new IllegalArgumentException("Invalid suffix. Use m, h, or d");
        };
    }
}
