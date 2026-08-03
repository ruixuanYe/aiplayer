package com.aiplayercompanion;

import com.aiplayercompanion.command.AIPlayerCommand;
import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import com.aiplayercompanion.navigation.CarpetFollowController;
import com.aiplayercompanion.respawn.AIPlayerRespawnController;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AIPlayerCompanionMod implements ModInitializer {
    public static final String MOD_ID = "aiplayer_companion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        AIPlayerCleanConfig.load();
        CarpetAIPlayerManager.initialize();
        CarpetFollowController.initialize();
        AIPlayerRespawnController.initialize();
        AIPlayerCommand.register();
        LOGGER.info("AIPlayer Companion Carpet adapter loaded");
    }
}
