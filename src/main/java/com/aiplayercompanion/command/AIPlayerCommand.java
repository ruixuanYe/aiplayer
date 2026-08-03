package com.aiplayercompanion.command;

import com.aiplayercompanion.fakeplayer.AIPlayerFakePlayer;
import com.aiplayercompanion.fakeplayer.AIPlayerFakePlayerManager;
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
                .then(literal("status").executes(context -> status(context.getSource()))));
    }

    private static int spawn(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        AIPlayerFakePlayerManager.spawn(owner);
        return 1;
    }

    private static int remove(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        int removed = AIPlayerFakePlayerManager.removeOwned(owner);
        if (removed == 0) {
            owner.sendMessage(Text.literal("没有找到属于你的 AIPlayer 假玩家。").formatted(Formatting.YELLOW), false);
            return 0;
        }
        owner.sendMessage(Text.literal("已移除 AIPlayer 假玩家：" + removed + " 个。").formatted(Formatting.GREEN), false);
        return removed;
    }

    private static int status(ServerCommandSource source) {
        ServerPlayerEntity owner = requirePlayer(source);
        return AIPlayerFakePlayerManager.findOwned(owner)
                .map(fake -> sendStatus(owner, fake))
                .orElseGet(() -> {
                    owner.sendMessage(Text.literal("AIPlayer 状态：未生成。").formatted(Formatting.YELLOW), false);
                    return 0;
                });
    }

    private static int sendStatus(ServerPlayerEntity owner, AIPlayerFakePlayer fake) {
        String message = "AIPlayer 状态：在线，名称="
                + fake.getName().getString()
                + "，生命="
                + String.format("%.1f/%.1f", fake.getHealth(), fake.getMaxHealth())
                + "，坐标="
                + String.format("%.1f %.1f %.1f", fake.getX(), fake.getY(), fake.getZ())
                + "，模式=Survival ServerPlayerEntity";
        owner.sendMessage(Text.literal(message).formatted(Formatting.GREEN), false);
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
