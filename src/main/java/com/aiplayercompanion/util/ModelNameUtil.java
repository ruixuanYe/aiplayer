package com.aiplayercompanion.util;

import com.aiplayercompanion.config.ModConfig;

import java.util.Locale;

public final class ModelNameUtil {
    private ModelNameUtil() {
    }

    public static String companionName() {
        if (!ModConfig.get().autoNameFromModel) {
            return blankToDefault(ModConfig.get().aiName);
        }
        return displayNameFromModel(ModConfig.get().modelName);
    }

    public static String displayNameFromModel(String modelId) {
        String value = modelId == null ? "" : modelId.strip();
        if (value.isBlank() || value.equals("local-model")) {
            return blankToDefault(ModConfig.get().aiName);
        }

        String leaf = value;
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0 && slash < leaf.length() - 1) {
            leaf = leaf.substring(slash + 1);
        }

        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.contains("deepseek-r1") || lower.contains("deepseek_r1")) {
            return "DeepSeek R1";
        }
        if (lower.contains("qwen3.5") || lower.contains("qwen-3.5") || lower.contains("qwen_3.5")) {
            return "Qwen 3.5";
        }
        if (lower.contains("qwen3") || lower.contains("qwen-3") || lower.contains("qwen_3")) {
            return "Qwen 3";
        }
        if (lower.contains("qwen2.5") || lower.contains("qwen-2.5") || lower.contains("qwen_2.5")) {
            return "Qwen 2.5";
        }
        if (lower.contains("llama")) {
            return titleToken(leaf, "Llama");
        }
        if (lower.contains("mistral")) {
            return titleToken(leaf, "Mistral");
        }

        String cleaned = leaf.replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").strip();
        if (cleaned.length() > 24) {
            cleaned = cleaned.substring(0, 24).strip();
        }
        return cleaned.isBlank() ? blankToDefault(ModConfig.get().aiName) : titleCase(cleaned);
    }

    private static String titleToken(String leaf, String fallback) {
        String cleaned = leaf.replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").strip();
        return cleaned.isBlank() ? fallback : titleCase(cleaned.length() > 24 ? cleaned.substring(0, 24).strip() : cleaned);
    }

    private static String titleCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (String token : value.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            if (token.length() <= 2 || token.matches(".*\\d.*")) {
                builder.append(token);
            } else {
                builder.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank() ? "AI Companion" : value.strip();
    }
}
