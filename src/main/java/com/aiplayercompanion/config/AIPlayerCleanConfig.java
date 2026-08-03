package com.aiplayercompanion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AIPlayerCleanConfig {
    public static final String DEFAULT_BOT_NAME = "AIPlayerBot";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aiplayer_companion_clean.json");

    public String botName = DEFAULT_BOT_NAME;
    public String botUuid = "";
    public String ownerUuid = "";

    private static AIPlayerCleanConfig INSTANCE;

    public static AIPlayerCleanConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            INSTANCE = new AIPlayerCleanConfig();
            save();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            INSTANCE = GSON.fromJson(json, AIPlayerCleanConfig.class);
            if (INSTANCE == null) {
                INSTANCE = new AIPlayerCleanConfig();
            }
            INSTANCE.sanitize();
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger("aiplayer_companion").warn("Failed to load clean config, using defaults: {}", e.toString());
            INSTANCE = new AIPlayerCleanConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LoggerFactory.getLogger("aiplayer_companion").warn("Failed to save clean config: {}", e.toString());
        }
    }

    public void remember(String botName, String botUuid, String ownerUuid) {
        this.botName = botName;
        this.botUuid = botUuid;
        this.ownerUuid = ownerUuid;
        save();
    }

    public void clearBinding() {
        botUuid = "";
        ownerUuid = "";
        save();
    }

    private void sanitize() {
        if (botName == null || botName.isBlank()) {
            botName = DEFAULT_BOT_NAME;
        }
    }
}
