package com.aiplayercompanion.service;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class CompanionLog {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_MAX_LENGTH = 300;

    private CompanionLog() {
    }

    public static void info(String category, String message) {
        write(category, message, null);
    }

    public static void player(ServerPlayerEntity player, String category, String message) {
        write(category, message, player);
    }

    public static List<String> readLastLines(int count) {
        int safeCount = Math.max(1, Math.min(count, 50));
        Path path = logPath();
        if (!Files.exists(path)) {
            return List.of("No AIPlayer Companion log file yet.");
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - safeCount);
            return new ArrayList<>(lines.subList(from, lines.size()));
        } catch (IOException e) {
            return List.of("Failed to read AIPlayer Companion log: " + e);
        }
    }

    public static Path logPath() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aiplayer_companion.log");
    }

    private static void write(String category, String message, ServerPlayerEntity player) {
        String safeCategory = sanitize(category, 40);
        String safeMessage = sanitize(message, ModConfig.get().maxLogMessageLength <= 0 ? DEFAULT_MAX_LENGTH : ModConfig.get().maxLogMessageLength);
        String playerName = player == null ? "-" : sanitize(player.getName().getString(), 40);
        String line = "[%s] [%s] [player=%s] %s%n".formatted(LocalDateTime.now().format(FORMATTER), safeCategory, playerName, safeMessage);

        AIPlayerCompanionMod.LOGGER.info("[{}] [player={}] {}", safeCategory, playerName, safeMessage);

        if (ModConfig.get().enableFileLog) {
            try {
                Files.createDirectories(logPath().getParent());
                Files.writeString(logPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                AIPlayerCompanionMod.LOGGER.warn("Failed to write AIPlayer Companion file log: {}", e.toString());
            }
        }

        if (player != null && ModConfig.get().enableOwnerDebugMessages) {
            player.sendMessage(Text.literal("[AI日志] " + safeCategory + ": " + safeMessage).formatted(Formatting.GRAY), false);
        }
    }

    private static String sanitize(String value, int maxLength) {
        String cleaned = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
