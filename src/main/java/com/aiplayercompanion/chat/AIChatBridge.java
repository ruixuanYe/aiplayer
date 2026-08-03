package com.aiplayercompanion.chat;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class AIChatBridge {
    private AIChatBridge() {
    }

    public static void initialize() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            if (shouldHandle(sender, text)) {
                sendToAi(sender.getServer(), sender, text, false);
            }
        });
    }

    public static void sendToAi(MinecraftServer server, ServerPlayerEntity player, String text, boolean showErrors) {
        LMStudioClient.chat(text).thenAccept(response -> server.execute(() -> {
            ServerPlayerEntity currentPlayer = server.getPlayerManager().getPlayer(player.getUuid());
            if (currentPlayer == null) {
                return;
            }
            if (response.ok()) {
                String botName = AIPlayerCleanConfig.get().botName;
                currentPlayer.sendMessage(Text.literal(botName + "：" + response.content()).formatted(Formatting.AQUA), false);
            } else if (showErrors) {
                currentPlayer.sendMessage(Text.literal("AIPlayer：没有可显示的回复：" + response.error()).formatted(Formatting.RED), false);
            }
        }));
    }

    private static boolean shouldHandle(ServerPlayerEntity sender, String text) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!config.aiChatEnabled || text == null || text.isBlank()) {
            return false;
        }
        if (config.ownerUuid == null || config.ownerUuid.isBlank()) {
            return false;
        }
        if (!sender.getUuidAsString().equals(config.ownerUuid)) {
            return false;
        }
        return !sender.getName().getString().equals(config.botName);
    }
}
