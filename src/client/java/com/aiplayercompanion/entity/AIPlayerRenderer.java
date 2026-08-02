package com.aiplayercompanion.entity;

import com.aiplayercompanion.AIPlayerCompanionMod;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

public class AIPlayerRenderer extends MobEntityRenderer<AIPlayerEntity, BipedEntityRenderState, AIPlayerModel> {
    private static final Identifier TEXTURE = Identifier.of(
            AIPlayerCompanionMod.MOD_ID,
            "textures/entity/ai_player.png"
    );

    public AIPlayerRenderer(EntityRendererFactory.Context context) {
        super(context, new AIPlayerModel(context.getPart(AIPlayerModel.MODEL_LAYER)), 0.5F);
    }

    @Override
    public boolean shouldRender(AIPlayerEntity entity, Frustum frustum, double x, double y, double z) {
        return !entity.isNavigationProxy() && super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public Identifier getTexture(BipedEntityRenderState state) {
        return TEXTURE;
    }

    @Override
    public BipedEntityRenderState createRenderState() {
        return new BipedEntityRenderState();
    }
}
