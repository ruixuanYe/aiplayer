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
    public String apiUrl = "http://127.0.0.1:1234/v1/chat/completions";
    public String modelName = "local-model";
    public String apiKey = "";
    public int timeoutSeconds = 15;
    public boolean aiChatEnabled = true;
    public String systemPrompt = "你是一个 Minecraft AI 陪伴玩家。你的回复应简短、自然、友好，不超过两句话。你不能声称完成了实际未完成的游戏操作。";

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
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "http://127.0.0.1:1234/v1/chat/completions";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "local-model";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            timeoutSeconds = 15;
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是一个 Minecraft AI 陪伴玩家。你的回复应简短、自然、友好，不超过两句话。你不能声称完成了实际未完成的游戏操作。";
        }
    }
}
