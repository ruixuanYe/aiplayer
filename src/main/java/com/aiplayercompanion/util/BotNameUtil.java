package com.aiplayercompanion.util;

import java.util.regex.Pattern;

public final class BotNameUtil {
    private static final Pattern SAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_]");

    private BotNameUtil() {
    }

    public static String deriveFromModel(String modelName) {
        String lower = modelName == null ? "" : modelName.toLowerCase();
        String detected;
        if (lower.contains("deepseek")) {
            detected = "AIDeepSeekR1";
        } else if (lower.contains("claude")) {
            detected = "AIClaude";
        } else if (lower.contains("gemini")) {
            detected = "AIGemini";
        } else if (lower.contains("qwen")) {
            detected = "AIQwen";
        } else {
            String[] parts = lower.split("[/:_-]+");
            detected = parts.length == 0 || parts[parts.length - 1].isBlank() ? "AIPlayerBot" : "AI" + capitalize(parts[parts.length - 1]);
        }
        detected = SAFE_NAME_CHARS.matcher(detected).replaceAll("");
        if (detected.isBlank()) {
            detected = "AIPlayerBot";
        }
        return detected.length() > 16 ? detected.substring(0, 16) : detected;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
