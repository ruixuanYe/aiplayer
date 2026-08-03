package com.aiplayercompanion;

import com.aiplayercompanion.command.AIPlayerCommand;
import com.aiplayercompanion.fakeplayer.AIPlayerFakePlayerManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AIPlayerCompanionMod implements ModInitializer {
    public static final String MOD_ID = "aiplayer_companion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        AIPlayerFakePlayerManager.initialize();
        AIPlayerCommand.register();
        LOGGER.info("AIPlayer Companion clean fake-player foundation loaded");
    }
}
