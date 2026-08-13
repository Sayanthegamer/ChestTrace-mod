package com.yourserver.chestlogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourserver.chestlogger.ChestLoggerMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Proxy;

public final class CommandRegistrar {
    private CommandRegistrar() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        try {
            Object proxy = Proxy.newProxyInstance(
                CommandRegistrationCallback.class.getClassLoader(),
                new Class<?>[]{ CommandRegistrationCallback.class },
                (p, method, args) -> {
                    if (args != null && args.length >= 1 && args[0] instanceof CommandDispatcher) {
                        ChestLogCommand.register((CommandDispatcher<CommandSourceStack>) args[0]);
                    }
                    return null;
                }
            );

            CommandRegistrationCallback.EVENT.register((CommandRegistrationCallback) proxy);
            ChestLoggerMod.LOGGER.info("ChestLogger: /chestlog command successfully registered!");
        } catch (Throwable e) {
            ChestLoggerMod.LOGGER.error("ChestLogger: Failed to register /chestlog command: ", e);
        }
    }
}
