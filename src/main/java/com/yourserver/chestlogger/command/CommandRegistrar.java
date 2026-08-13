package com.yourserver.chestlogger.command;

import com.yourserver.chestlogger.ChestLoggerMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class CommandRegistrar {
    private CommandRegistrar() {}

    public static void register() {
        try {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                ChestLogCommand.register(dispatcher);
            });
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("Failed to register /chestlog command: ", e);
        }
    }
}
