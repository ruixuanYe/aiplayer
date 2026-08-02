package com.aiplayercompanion.entity;

import com.aiplayercompanion.AIPlayerCompanionMod;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.util.Identifier;

public class AIPlayerModel extends BipedEntityModel<BipedEntityRenderState> {
    public static final EntityModelLayer MODEL_LAYER = new EntityModelLayer(
            Identifier.of(AIPlayerCompanionMod.MOD_ID, "ai_player"),
            "main"
    );

    public AIPlayerModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = BipedEntityModel.getModelData(Dilation.NONE, 0.0F);
        return TexturedModelData.of(modelData, 64, 64);
    }
}
