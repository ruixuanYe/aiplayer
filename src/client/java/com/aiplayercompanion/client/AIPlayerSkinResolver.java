package com.aiplayercompanion.client;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.util.Identifier;

import java.util.Locale;

public final class AIPlayerSkinResolver {
    private static final Identifier CLAUDE = skin("claude");
    private static final Identifier DEEPSEEK = skin("deepseek");
    private static final Identifier GEMINI = skin("gemini");
    private static final Identifier COMMON_USE = skin("common_use");

    private AIPlayerSkinResolver() {
    }

    public static Identifier textureForCurrentModel() {
        String value = (ModConfig.get().modelName + " " + ModelNameUtil.companionName()).toLowerCase(Locale.ROOT);
        if (value.contains("claude")) {
            return CLAUDE;
        }
        if (value.contains("deepseek")) {
            return DEEPSEEK;
        }
        if (value.contains("gemini")) {
            return GEMINI;
        }
        return COMMON_USE;
    }

    public static boolean isAIPlayerBotName(String playerName) {
        return playerName != null && playerName.equalsIgnoreCase(ModelNameUtil.botPlayerName());
    }

    private static Identifier skin(String name) {
        return Identifier.of(AIPlayerCompanionMod.MOD_ID, "textures/entity/skins/" + name + ".png");
    }
}
