package com.aiplayercompanion;

import com.aiplayercompanion.entity.AIPlayerModel;
import com.aiplayercompanion.entity.AIPlayerRenderer;
import com.aiplayercompanion.client.ClientCompanionInteraction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AIPlayerCompanionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(AIPlayerModel.MODEL_LAYER, AIPlayerModel::getTexturedModelData);
        EntityRendererRegistry.register(AIPlayerCompanionMod.AI_PLAYER, AIPlayerRenderer::new);
        ClientCompanionInteraction.register();
    }
}
