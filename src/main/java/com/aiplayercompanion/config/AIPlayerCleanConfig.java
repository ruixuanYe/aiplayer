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
    public static final String DEFAULT_API_URL = "http://127.0.0.1:1234/v1/chat/completions";
    public static final String DEFAULT_SYSTEM_PROMPT = "\u4f60\u662f\u4e00\u4e2a Minecraft AI \u966a\u4f34\u73a9\u5bb6\u3002\u4f60\u7684\u56de\u590d\u5e94\u7b80\u77ed\u3001\u81ea\u7136\u3001\u53cb\u597d\uff0c\u4e0d\u8d85\u8fc7\u4e24\u53e5\u8bdd\u3002\u4f60\u4e0d\u80fd\u58f0\u79f0\u5b8c\u6210\u4e86\u5b9e\u9645\u672a\u5b8c\u6210\u7684\u6e38\u620f\u64cd\u4f5c\u3002";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aiplayer_companion_clean.json");

    public String botName = DEFAULT_BOT_NAME;
    public String botUuid = "";
    public String ownerUuid = "";
    public String apiUrl = DEFAULT_API_URL;
    public String modelName = "local-model";
    public String apiKey = "";
    public int timeoutSeconds = 15;
    public boolean aiChatEnabled = true;
    public String systemPrompt = DEFAULT_SYSTEM_PROMPT;

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
            apiUrl = DEFAULT_API_URL;
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
        systemPrompt = DEFAULT_SYSTEM_PROMPT;
    }
}
