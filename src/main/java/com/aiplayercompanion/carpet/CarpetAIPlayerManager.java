package com.aiplayercompanion.carpet;

import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CarpetAIPlayerManager {
    public static final String AI_TAG = "aiplayer_companion.managed";
    public static final String TEAM_NAME = "aiplayer_companion";

    private static final String CARPET_MOD_ID = "carpet";
    private static final Pattern SAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_]");
    private static final int SPAWN_CONFIRM_TICKS = 40;
    private static final Map<UUID, PendingSpawn> PENDING_SPAWNS = new HashMap<>();

    private CarpetAIPlayerManager() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(CarpetAIPlayerManager::tickPendingSpawns);
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
        applyModelNameIfUnbound(config);
        Optional<ServerPlayerEntity> existingManaged = findManaged(owner.getServer(), config);
        if (existingManaged.isPresent()) {
            ServerPlayerEntity bot = existingManaged.get();
            markAsAIPlayer(bot, owner);
            owner.sendMessage(Text.literal("AIPlayer 已存在：" + bot.getName().getString()).formatted(Formatting.YELLOW), false);
            return 0;
        }

        ServerPlayerEntity occupied = owner.getServer().getPlayerManager().getPlayer(config.botName);
        if (occupied != null) {
            owner.sendMessage(Text.literal("名称 " + config.botName + " 已被占用。为避免影响你用 Carpet 手动召唤的假人，本次不会覆盖。").formatted(Formatting.RED), false);
            return 0;
        }

        executeCarpetCommand(source, "player " + config.botName + " spawn");
        PENDING_SPAWNS.put(owner.getUuid(), new PendingSpawn(owner.getUuid(), config.botName, owner.getWorld().getTime(), false));

        ServerPlayerEntity spawned = owner.getServer().getPlayerManager().getPlayer(config.botName);
        if (spawned != null) {
            finishSpawn(owner, spawned);
            PENDING_SPAWNS.remove(owner.getUuid());
            return 1;
        }

        owner.sendMessage(Text.literal("AIPlayer 正在通过 Carpet 生成，稍后会自动确认。").formatted(Formatting.YELLOW), false);
        return 1;
    }

    public static int remove(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        PENDING_SPAWNS.remove(owner.getUuid());
        com.aiplayercompanion.respawn.AIPlayerRespawnController.suppressRespawn(owner);
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
        markAsAIPlayer(bot, owner);
        String message = "AIPlayer 状态：在线，分类=AIPlayer 管理的 Carpet 假人，名称="
                + bot.getName().getString()
                + "，生命="
                + String.format("%.1f/%.1f", bot.getHealth(), bot.getMaxHealth())
                + "，坐标="
                + String.format("%.1f %.1f %.1f", bot.getX(), bot.getY(), bot.getZ())
                + "，游戏模式=生存";
        owner.sendMessage(Text.literal(message).formatted(Formatting.GREEN), false);
        return 1;
    }

    public static int list(ServerPlayerEntity requester) {
        List<String> managed = new ArrayList<>();
        List<String> carpetOthers = new ArrayList<>();

        for (ServerPlayerEntity player : requester.getServer().getPlayerManager().getPlayerList()) {
            if (isManagedAIPlayer(player)) {
                managed.add(player.getName().getString());
            } else if (isCarpetFakePlayer(player)) {
                carpetOthers.add(player.getName().getString());
            }
        }

        requester.sendMessage(Text.literal("AIPlayer 分类：").formatted(Formatting.AQUA), false);
        requester.sendMessage(Text.literal("AIPlayer 管理的假人：" + formatList(managed)).formatted(Formatting.GREEN), false);
        requester.sendMessage(Text.literal("其他 Carpet 假人：" + formatList(carpetOthers)).formatted(Formatting.YELLOW), false);
        return managed.size() + carpetOthers.size();
    }

    private static void tickPendingSpawns(MinecraftServer server) {
        if (PENDING_SPAWNS.isEmpty()) {
            return;
        }

        for (PendingSpawn pending : Map.copyOf(PENDING_SPAWNS).values()) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(pending.ownerUuid());
            if (owner == null) {
                PENDING_SPAWNS.remove(pending.ownerUuid());
                continue;
            }

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(pending.botName());
            if (bot != null) {
                finishSpawn(owner, bot);
                PENDING_SPAWNS.remove(pending.ownerUuid());
                continue;
            }

            if (owner.getWorld().getTime() - pending.startedTick() >= SPAWN_CONFIRM_TICKS && !pending.notified()) {
                owner.sendMessage(Text.literal("Carpet 假玩家生成未确认。请测试 /player " + pending.botName() + " spawn 是否可用，并查看 latest.log。").formatted(Formatting.RED), false);
                PENDING_SPAWNS.put(pending.ownerUuid(), new PendingSpawn(pending.ownerUuid(), pending.botName(), pending.startedTick(), true));
            }
        }
    }

    public static Optional<ServerPlayerEntity> findManaged(MinecraftServer server, AIPlayerCleanConfig config) {
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

    public static void finishSpawn(ServerPlayerEntity owner, ServerPlayerEntity spawned) {
        markAsAIPlayer(spawned, owner);
        AIPlayerCleanConfig.get().remember(spawned.getName().getString(), spawned.getUuidAsString(), owner.getUuidAsString());
        owner.sendMessage(Text.literal("AIPlayer Carpet 假玩家已生成：" + spawned.getName().getString() + "，分类=AIPlayer，已切换为生存模式。").formatted(Formatting.GREEN), false);
    }

    private static void markAsAIPlayer(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        bot.addCommandTag(AI_TAG);
        bot.addCommandTag("aiplayer_companion.owner." + owner.getUuidAsString());
        forceSurvival(bot);
        addToAIPlayerTeam(bot);
    }

    private static boolean isManagedAIPlayer(ServerPlayerEntity player) {
        return player.getCommandTags().contains(AI_TAG);
    }

    private static boolean isCarpetFakePlayer(ServerPlayerEntity player) {
        String className = player.getClass().getName().toLowerCase();
        return className.contains("carpet") && className.contains("fake");
    }

    private static void addToAIPlayerTeam(ServerPlayerEntity bot) {
        Scoreboard scoreboard = bot.getServer().getScoreboard();
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.addTeam(TEAM_NAME);
            team.setDisplayName(Text.literal("AIPlayer"));
            team.setPrefix(Text.literal("[AIPlayer] ").formatted(Formatting.AQUA));
            team.setColor(Formatting.AQUA);
        }
        scoreboard.addScoreHolderToTeam(bot.getNameForScoreboard(), team);
    }

    public static void forceSurvival(ServerPlayerEntity bot) {
        bot.changeGameMode(GameMode.SURVIVAL);
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
        bot.getAbilities().invulnerable = false;
        bot.sendAbilitiesUpdate();
    }

    private static String formatList(List<String> names) {
        return names.isEmpty() ? "无" : String.join(", ", names);
    }

    public static void executeCarpetCommand(ServerCommandSource source, String command) {
        source.getServer().getCommandManager().executeWithPrefix(source.withLevel(4).withSilent(), command);
    }

    public static String deriveBotNameFromModel(String modelName) {
        String lower = modelName == null ? "" : modelName.toLowerCase();
        String detected;
        if (lower.contains("deepseek")) {
            detected = "AIDeepSeekR1";
        } else if (lower.contains("claude")) {
            detected = "AIClaude";
        } else if (lower.contains("gemini")) {
            detected = "AIGemini";
        } else if (lower.contains("qwen")) {
            detected = "AIQwen";
        } else {
            String[] parts = lower.split("[/:_-]+");
            detected = parts.length == 0 || parts[parts.length - 1].isBlank() ? "AIPlayerBot" : "AI" + capitalize(parts[parts.length - 1]);
        }
        detected = SAFE_NAME_CHARS.matcher(detected).replaceAll("");
        if (detected.isBlank()) {
            detected = "AIPlayerBot";
        }
        return detected.length() > 16 ? detected.substring(0, 16) : detected;
    }

    private static void applyModelNameIfUnbound(AIPlayerCleanConfig config) {
        if (!config.autoNameFromModel || (config.botUuid != null && !config.botUuid.isBlank())) {
            return;
        }
        config.botName = deriveBotNameFromModel(config.modelName);
        AIPlayerCleanConfig.save();
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record PendingSpawn(UUID ownerUuid, String botName, long startedTick, boolean notified) {
    }
}
