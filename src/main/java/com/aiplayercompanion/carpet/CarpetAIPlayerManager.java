package com.aiplayercompanion.carpet;

import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;
import java.util.UUID;

public final class CarpetAIPlayerManager {
    private static final String CARPET_MOD_ID = "carpet";

    private CarpetAIPlayerManager() {
    }

    public static boolean isCarpetLoaded() {
        return FabricLoader.getInstance().isModLoaded(CARPET_MOD_ID);
    }

    public static int spawn(ServerCommandSource source, ServerPlayerEntity owner) {
        if (!isCarpetLoaded()) {
            owner.sendMessage(Text.literal("未检测到 Carpet，请先把 Carpet 放进 mods 目录。").formatted(Formatting.RED), false);
            return 0;
        }

        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        Optional<ServerPlayerEntity> existingManaged = findManaged(owner.getServer(), config);
        if (existingManaged.isPresent()) {
            owner.sendMessage(Text.literal("AIPlayer 已存在：" + existingManaged.get().getName().getString()).formatted(Formatting.YELLOW), false);
            return 0;
        }

        ServerPlayerEntity occupied = owner.getServer().getPlayerManager().getPlayer(config.botName);
        if (occupied != null) {
            owner.sendMessage(Text.literal("名称 " + config.botName + " 已被占用。为避免影响你用 Carpet 手动召唤的假人，本次不会覆盖。").formatted(Formatting.RED), false);
            return 0;
        }

        executeCarpetCommand(source, "player " + config.botName + " spawn");
        ServerPlayerEntity spawned = owner.getServer().getPlayerManager().getPlayer(config.botName);
        if (spawned == null) {
            owner.sendMessage(Text.literal("Carpet 假玩家生成失败。请确认 /player 命令可用，并查看 latest.log。").formatted(Formatting.RED), false);
            return 0;
        }

        config.remember(spawned.getName().getString(), spawned.getUuidAsString(), owner.getUuidAsString());
        owner.sendMessage(Text.literal("AIPlayer Carpet 假玩家已生成：" + spawned.getName().getString()).formatted(Formatting.GREEN), false);
        return 1;
    }

    public static int remove(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        Optional<ServerPlayerEntity> managed = findManaged(owner.getServer(), config);
        if (managed.isEmpty()) {
            owner.sendMessage(Text.literal("没有找到 AIPlayer 管理的 Carpet 假玩家。").formatted(Formatting.YELLOW), false);
            config.clearBinding();
            return 0;
        }

        ServerPlayerEntity bot = managed.get();
        if (!owner.getUuidAsString().equals(config.ownerUuid)) {
            owner.sendMessage(Text.literal("这个 AIPlayer 不属于你，不能移除。").formatted(Formatting.RED), false);
            return 0;
        }

        executeCarpetCommand(source, "player " + bot.getName().getString() + " kill");
        config.clearBinding();
        owner.sendMessage(Text.literal("已移除 AIPlayer Carpet 假玩家。").formatted(Formatting.GREEN), false);
        return 1;
    }

    public static int status(ServerPlayerEntity owner) {
        if (!isCarpetLoaded()) {
            owner.sendMessage(Text.literal("AIPlayer 状态：Carpet 未加载。").formatted(Formatting.RED), false);
            return 0;
        }
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        Optional<ServerPlayerEntity> managed = findManaged(owner.getServer(), config);
        if (managed.isEmpty()) {
            owner.sendMessage(Text.literal("AIPlayer 状态：未生成。记录名称=" + config.botName).formatted(Formatting.YELLOW), false);
            return 0;
        }

        ServerPlayerEntity bot = managed.get();
        String message = "AIPlayer 状态：Carpet 假玩家在线，名称="
                + bot.getName().getString()
                + "，生命="
                + String.format("%.1f/%.1f", bot.getHealth(), bot.getMaxHealth())
                + "，坐标="
                + String.format("%.1f %.1f %.1f", bot.getX(), bot.getY(), bot.getZ());
        owner.sendMessage(Text.literal(message).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static Optional<ServerPlayerEntity> findManaged(MinecraftServer server, AIPlayerCleanConfig config) {
        if (config.botUuid == null || config.botUuid.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(config.botUuid);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && player.getName().getString().equals(config.botName)) {
                return Optional.of(player);
            }
        } catch (IllegalArgumentException ignored) {
            config.clearBinding();
        }
        return Optional.empty();
    }

    private static void executeCarpetCommand(ServerCommandSource source, String command) {
        source.getServer().getCommandManager().executeWithPrefix(source.withLevel(4).withSilent(), command);
    }
}
