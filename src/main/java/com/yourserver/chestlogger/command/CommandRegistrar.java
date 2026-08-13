package com.yourserver.chestlogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourserver.chestlogger.ChestLoggerMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Proxy;

public final class CommandRegistrar {
    private CommandRegistrar() {}

    public static void register() {
        try {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                ChestLogCommand.register(dispatcher);
            });
            ChestLoggerMod.LOGGER.info("ChestLogger: /chestlog command successfully registered!");
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ChestLogger: Failed to register /chestlog command: ", e);
        }
    }
}
