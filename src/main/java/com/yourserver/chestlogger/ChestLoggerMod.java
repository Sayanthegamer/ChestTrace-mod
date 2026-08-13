package com.yourserver.chestlogger;

import com.yourserver.chestlogger.command.CommandRegistrar;
import com.yourserver.chestlogger.logging.ChestLogWriter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class ChestLoggerMod implements ModInitializer {
    public static final String MOD_ID = "chestlogger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ChestLogWriter WRITER;
    private static Path LOG_DIRECTORY;
    private static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing ChestLogger Mod...");

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            SERVER = server;
            LOG_DIRECTORY = FabricLoader.getInstance().getGameDir().resolve("chestlogger");
            // Flush threshold: 5000 events, flush interval: 12000 ms (12 sec), retention: 30 days
            WRITER = new ChestLogWriter(LOG_DIRECTORY, 5000, 12000L, 30);
            WRITER.start();
            LOGGER.info("ChestLogger background writer thread started at {}", LOG_DIRECTORY);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (WRITER != null) {
                LOGGER.info("Server stopping: Shutting down and flushing ChestLogger writer...");
                WRITER.shutdownAndFlush();
                LOGGER.info("ChestLogger writer shutdown complete.");
            }
            SERVER = null;
        });

        try {
            CommandRegistrar.register();
        } catch (Throwable e) {
            LOGGER.error("ChestLogger: Failed to initialize command registration: ", e);
        }
    }

    public static ChestLogWriter writer() {
        return WRITER;
    }

    public static Path logDirectory() {
        return LOG_DIRECTORY;
    }

    public static MinecraftServer server() {
        return SERVER;
    }
}
