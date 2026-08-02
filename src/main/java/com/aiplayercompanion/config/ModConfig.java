package com.aiplayercompanion.config;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_NAME = "aiplayer_companion.json";
    private static ModConfig INSTANCE = defaults();

    public String lmStudioApiUrl = "http://127.0.0.1:1234/v1/chat/completions";
    public String modelName = "local-model";
    public int requestTimeoutSeconds = 15;
    public String aiName = "AI Companion";
    public boolean aiChatEnabled = true;
    public double startFollowDistance = 4.0D;
    public double stopFollowDistance = 2.2D;
    public double teleportDistance = 30.0D;
    public String ownerPlayerName = "";
    public String ownerPlayerUuid = "";
    public String apiKey = "";
    public boolean enableFileLog = true;
    public boolean enableOwnerDebugMessages = false;
    public boolean logChatContent = false;
    public int maxLogMessageLength = 300;
    public int maxTokens = 1024;
    public boolean disableThinking = true;
    public boolean autoNameFromModel = true;
    public boolean enableChatListener = true;
    public boolean requireWakeWordForActions = true;
    public String wakeWords = "AI,ai,伙伴,aiplayer";
    public double sprintFollowDistance = 8.0D;
    public int fleeSeconds = 5;
    public String companionMode = "PLAYER_BOT";
    public String botPlayerUuid = "";
    public String botState = "FOLLOWING";
    public boolean botAutoCombat = true;
    public boolean botAutoPickup = true;
    public boolean botAutoEquip = true;
    public boolean botAutoWeapon = true;
    public int configVersion = 5;

    public static ModConfig get() {
        return INSTANCE;
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            INSTANCE = defaults();
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            INSTANCE = loaded == null ? defaults() : loaded.sanitized();
        } catch (Exception e) {
            INSTANCE = defaults();
            AIPlayerCompanionMod.LOGGER.error("Failed to load config/aiplayer_companion.json, using defaults: {}", e.toString());
        }
    }

    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE.sanitized(), writer);
            }
        } catch (IOException e) {
            AIPlayerCompanionMod.LOGGER.error("Failed to save config/aiplayer_companion.json: {}", e.toString());
        }
    }

    public static void rememberOwner(String name, String uuid) {
        INSTANCE.ownerPlayerName = name == null ? "" : name;
        INSTANCE.ownerPlayerUuid = uuid == null ? "" : uuid;
        save();
    }

    private ModConfig sanitized() {
        if (lmStudioApiUrl == null || lmStudioApiUrl.isBlank()) {
            lmStudioApiUrl = defaults().lmStudioApiUrl;
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = defaults().modelName;
        }
        if (requestTimeoutSeconds <= 0) {
            requestTimeoutSeconds = defaults().requestTimeoutSeconds;
        }
        if (aiName == null || aiName.isBlank()) {
            aiName = defaults().aiName;
        }
        if (startFollowDistance < 1.0D) {
            startFollowDistance = defaults().startFollowDistance;
        }
        if (stopFollowDistance < 1.0D || stopFollowDistance >= startFollowDistance) {
            stopFollowDistance = defaults().stopFollowDistance;
        }
        if (teleportDistance < startFollowDistance) {
            teleportDistance = defaults().teleportDistance;
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (maxLogMessageLength < 80) {
            maxLogMessageLength = 80;
        }
        if (maxTokens < 32) {
            maxTokens = defaults().maxTokens;
        }
        if (maxTokens > 4096) {
            maxTokens = 4096;
        }
        if (configVersion < 2) {
            disableThinking = true;
        }
        if (configVersion < 3) {
            if (maxTokens < 1024) {
                maxTokens = 1024;
            }
            autoNameFromModel = true;
            enableChatListener = true;
            requireWakeWordForActions = true;
            if (wakeWords == null || wakeWords.isBlank()) {
                wakeWords = defaults().wakeWords;
            }
            enableOwnerDebugMessages = false;
            logChatContent = false;
            configVersion = 3;
        }
        if (wakeWords == null || wakeWords.isBlank()) {
            wakeWords = defaults().wakeWords;
        }
        if (sprintFollowDistance < stopFollowDistance) {
            sprintFollowDistance = defaults().sprintFollowDistance;
        }
        if (fleeSeconds < 1) {
            fleeSeconds = defaults().fleeSeconds;
        }
        if (fleeSeconds > 20) {
            fleeSeconds = 20;
        }
        if (companionMode == null || companionMode.isBlank()) {
            companionMode = defaults().companionMode;
        }
        if (!companionMode.equals("PLAYER_BOT") && !companionMode.equals("NPC")) {
            companionMode = defaults().companionMode;
        }
        if (botPlayerUuid == null) {
            botPlayerUuid = "";
        }
        if (botState == null || (!botState.equals("FOLLOWING") && !botState.equals("WAITING"))) {
            botState = "FOLLOWING";
        }
        if (configVersion < 4) {
            companionMode = "PLAYER_BOT";
            botState = "FOLLOWING";
            if (botPlayerUuid == null) {
                botPlayerUuid = "";
            }
            configVersion = 4;
        }
        if (configVersion < 5) {
            botAutoCombat = true;
            botAutoPickup = true;
            botAutoEquip = true;
            botAutoWeapon = true;
            configVersion = 5;
        }
        return this;
    }

    private static ModConfig defaults() {
        return new ModConfig();
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME);
    }
}
