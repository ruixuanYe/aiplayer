package com.aiplayercompanion.mixin.client;

import com.aiplayercompanion.client.AIPlayerSkinResolver;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(
            method = "getTexture(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Lnet/minecraft/util/Identifier;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aiplayer$useModelSkin(PlayerEntityRenderState state, CallbackInfoReturnable<Identifier> cir) {
        if (AIPlayerSkinResolver.isAIPlayerBotName(state.name)) {
            cir.setReturnValue(AIPlayerSkinResolver.textureForCurrentModel());
        }
    }
}
