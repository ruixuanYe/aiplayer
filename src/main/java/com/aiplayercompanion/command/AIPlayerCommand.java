package com.aiplayercompanion.command;

import com.aiplayercompanion.ai.LMStudioClient;
import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.aiplayercompanion.chat.AIChatBridge;
import com.aiplayercompanion.navigation.CarpetFollowController;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public final class AIPlayerCommand {
    private AIPlayerCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("aiplayer")
                .then(literal("spawn").executes(context -> spawn(context.getSource())))
                .then(literal("remove").executes(context -> remove(context.getSource())))
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("list").executes(context -> list(context.getSource())))
                .then(literal("follow").executes(context -> follow(context.getSource())))
                .then(literal("stop").executes(context -> stop(context.getSource())))
                .then(literal("come").executes(context -> come(context.getSource())))
                .then(literal("teleport").executes(context -> teleport(context.getSource())))
                .then(literal("ai")
                        .then(literal("test").executes(context -> aiTest(context.getSource())))
                        .then(literal("chat")
                                .then(argument("message", StringArgumentType.greedyString())
                                        .executes(context -> aiChat(context.getSource(), StringArgumentType.getString(context, "message")))))));
    }

    private static int spawn(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetAIPlayerManager.spawn(source, owner);
    }

    private static int remove(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetAIPlayerManager.remove(source, owner);
    }

    private static int status(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetAIPlayerManager.status(owner);
    }

    private static int list(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetAIPlayerManager.list(owner);
    }

    private static int follow(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetFollowController.follow(source, owner);
    }

    private static int stop(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetFollowController.stop(source, owner);
    }

    private static int come(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetFollowController.come(source, owner);
    }

    private static int teleport(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return CarpetFollowController.teleport(source, owner);
    }

    private static int aiTest(ServerCommandSource source) {
        ServerPlayerEntity player = requirePlayer(source);
        player.sendMessage(Text.literal("AIPlayer：正在测试 AI 连接...").formatted(Formatting.YELLOW), false);
        LMStudioClient.test().thenAccept(response -> source.getServer().execute(() -> {
            if (response.ok()) {
                player.sendMessage(Text.literal("AIPlayer：AI 连接成功，回复：" + response.content()).formatted(Formatting.GREEN), false);
            } else {
                player.sendMessage(Text.literal("AIPlayer：AI 连接失败：" + response.error()).formatted(Formatting.RED), false);
            }
        }));
        return 1;
    }

    private static int aiChat(ServerCommandSource source, String message) {
        ServerPlayerEntity player = requirePlayer(source);
        AIChatBridge.sendToAi(source.getServer(), player, message, true);
        return 1;
    }

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("This command can only be used by a player.");
        }
        return player;
    }
}
