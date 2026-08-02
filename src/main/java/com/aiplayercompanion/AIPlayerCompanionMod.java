package com.aiplayercompanion;

import com.aiplayercompanion.command.AIPlayerCommand;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.AIPlayerEntity;
import com.aiplayercompanion.network.ModNetworking;
import com.aiplayercompanion.bot.AIPlayerBotManager;
import com.aiplayercompanion.service.AIPlayerManager;
import com.aiplayercompanion.service.CompanionInteractionService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AIPlayerCompanionMod implements ModInitializer {
    public static final String MOD_ID = "aiplayer_companion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier AI_PLAYER_ID = Identifier.of(MOD_ID, "ai_player");
    public static final RegistryKey<EntityType<?>> AI_PLAYER_KEY = RegistryKey.of(RegistryKeys.ENTITY_TYPE, AI_PLAYER_ID);

    public static final EntityType<AIPlayerEntity> AI_PLAYER = Registry.register(
            Registries.ENTITY_TYPE,
            AI_PLAYER_ID,
            EntityType.Builder.create(AIPlayerEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .maxTrackingRange(10)
                    .trackingTickInterval(3)
                    .build(AI_PLAYER_KEY)
    );

    @Override
    public void onInitialize() {
        ModConfig.load();
        ModNetworking.registerCommon();
        FabricDefaultAttributeRegistry.register(AI_PLAYER, AIPlayerEntity.createAIPlayerAttributes());
        AIPlayerManager.initialize();
        AIPlayerBotManager.initialize();
        CompanionInteractionService.register();
        AIPlayerCommand.register();
        LOGGER.info("AIPlayer Companion loaded.");
    }
}
