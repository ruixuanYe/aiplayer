package com.aiplayercompanion.entity;

import com.aiplayercompanion.client.AIPlayerSkinResolver;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

public class AIPlayerRenderer extends MobEntityRenderer<AIPlayerEntity, BipedEntityRenderState, AIPlayerModel> {
    public AIPlayerRenderer(EntityRendererFactory.Context context) {
        super(context, new AIPlayerModel(context.getPart(AIPlayerModel.MODEL_LAYER)), 0.5F);
    }

    @Override
    public boolean shouldRender(AIPlayerEntity entity, Frustum frustum, double x, double y, double z) {
        return !entity.isNavigationProxy() && super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public Identifier getTexture(BipedEntityRenderState state) {
        return AIPlayerSkinResolver.textureForCurrentModel();
    }

    @Override
    public BipedEntityRenderState createRenderState() {
        return new BipedEntityRenderState();
    }
}
