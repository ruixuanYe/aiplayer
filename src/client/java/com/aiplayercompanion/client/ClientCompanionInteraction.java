package com.aiplayercompanion.client;

import com.aiplayercompanion.network.OpenCompanionMenuPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class ClientCompanionInteraction {
    private static KeyBinding openConfigKey;

    private ClientCompanionInteraction() {
    }

    public static void register() {
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aiplayer_companion.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.aiplayer_companion"
        ));

        ClientPlayNetworking.registerGlobalReceiver(OpenCompanionMenuPayload.ID, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new CompanionMenuScreen()))
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.currentScreen != null) {
                return;
            }
            while (openConfigKey.wasPressed()) {
                client.setScreen(new CompanionMenuScreen());
            }
        });
    }
}
