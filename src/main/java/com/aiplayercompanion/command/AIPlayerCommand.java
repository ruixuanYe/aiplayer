package com.aiplayercompanion.command;

import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

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
                .then(literal("list").executes(context -> list(context.getSource()))));
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

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("This command can only be used by a player.");
        }
        return player;
    }
}
