package com.aiplayercompanion.respawn;

import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import com.aiplayercompanion.navigation.CarpetFollowController;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

public final class AIPlayerRespawnController {
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int MANUAL_REMOVE_SUPPRESS_TICKS = 20 * 30;

    private static long lastCheckTick;
    private static long missingSinceTick = -1L;
    private static long lastRespawnAttemptTick = -1L;
    private static long lastTeleportAttemptTick = -1L;
    private static long suppressRespawnUntilTick = -1L;
    private static boolean respawnMessageSent;

    private AIPlayerRespawnController() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(AIPlayerRespawnController::tick);
    }

    public static void suppressRespawn(ServerPlayerEntity owner) {
        suppressRespawnUntilTick = owner.getServer().getTicks() + MANUAL_REMOVE_SUPPRESS_TICKS;
        resetMissingState();
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() - lastCheckTick < CHECK_INTERVAL_TICKS) {
            return;
        }
        lastCheckTick = server.getTicks();

        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!config.autoRespawn || config.ownerUuid == null || config.ownerUuid.isBlank() || config.botName == null || config.botName.isBlank()) {
            resetMissingState();
            return;
        }
        if (server.getTicks() < suppressRespawnUntilTick) {
            return;
        }

        ServerPlayerEntity owner = findOwner(server, config);
        if (owner == null) {
            resetMissingState();
            return;
        }

        Optional<ServerPlayerEntity> managed = CarpetAIPlayerManager.findManaged(server, config);
        if (managed.isPresent()) {
            resetMissingState();
            tryTeleportIfNeeded(server, owner, managed.get(), config);
            return;
        }

        ServerPlayerEntity byName = server.getPlayerManager().getPlayer(config.botName);
        if (byName != null) {
            CarpetAIPlayerManager.finishSpawn(owner, byName);
            CarpetAIPlayerManager.forceSurvival(byName);
            owner.sendMessage(Text.literal("AIPlayer has respawned at spawn.").formatted(Formatting.GREEN), false);
            resetMissingState();
            if (canWalkFromSpawnToOwner(byName, owner, config)) {
                owner.sendMessage(Text.literal("AIPlayer will walk back from spawn.").formatted(Formatting.YELLOW), false);
            } else if (byName.getWorld() == owner.getWorld()) {
                CarpetFollowController.teleportBotNearOwner(byName.getCommandSource(), byName, owner, "respawned");
            } else {
                owner.sendMessage(Text.literal("AIPlayer respawned in another dimension and will wait there.").formatted(Formatting.YELLOW), false);
            }
            return;
        }

        if (missingSinceTick < 0L) {
            missingSinceTick = server.getTicks();
            respawnMessageSent = false;
            return;
        }

        long delayTicks = Math.max(1, config.respawnDelaySeconds) * 20L;
        if (server.getTicks() - missingSinceTick < delayTicks) {
            if (!respawnMessageSent) {
                owner.sendMessage(Text.literal("AIPlayer fell. It will respawn at spawn soon.").formatted(Formatting.YELLOW), false);
                respawnMessageSent = true;
            }
            return;
        }

        long retryTicks = Math.max(1, config.respawnRetrySeconds) * 20L;
        if (lastRespawnAttemptTick >= 0L && server.getTicks() - lastRespawnAttemptTick < retryTicks) {
            return;
        }
        lastRespawnAttemptTick = server.getTicks();
        spawnAtWorldSpawn(server, owner, config);
    }

    private static void spawnAtWorldSpawn(MinecraftServer server, ServerPlayerEntity owner, AIPlayerCleanConfig config) {
        if (!CarpetAIPlayerManager.isCarpetLoaded()) {
            owner.sendMessage(Text.literal("AIPlayer cannot respawn because Carpet is not loaded.").formatted(Formatting.RED), false);
            return;
        }
        ServerWorld world = server.getOverworld();
        BlockPos spawnPos = world.getSpawnPos();
        ServerCommandSource spawnSource = owner.getCommandSource()
                .withWorld(world)
                .withPosition(Vec3d.ofBottomCenter(spawnPos))
                .withRotation(Vec2f.ZERO);
        CarpetAIPlayerManager.executeCarpetCommand(spawnSource, "player " + config.botName + " spawn");
        ServerPlayerEntity spawned = server.getPlayerManager().getPlayer(config.botName);
        if (spawned != null) {
            CarpetAIPlayerManager.forceSurvival(spawned);
        }
    }

    private static void tryTeleportIfNeeded(MinecraftServer server, ServerPlayerEntity owner, ServerPlayerEntity bot, AIPlayerCleanConfig config) {
        CarpetAIPlayerManager.forceSurvival(bot);
        if (owner.getWorld() != bot.getWorld()) {
            return;
        }
        if (canWalkFromSpawnToOwner(bot, owner, config)) {
            return;
        }
        long retryTicks = Math.max(1, config.respawnRetrySeconds) * 20L;
        if (lastTeleportAttemptTick >= 0L && server.getTicks() - lastTeleportAttemptTick < retryTicks) {
            return;
        }
        lastTeleportAttemptTick = server.getTicks();
        CarpetFollowController.teleportBotNearOwner(bot.getCommandSource(), bot, owner, "respawned");
    }

    private static boolean canWalkFromSpawnToOwner(ServerPlayerEntity bot, ServerPlayerEntity owner, AIPlayerCleanConfig config) {
        if (bot.getWorld() != owner.getWorld()) {
            return false;
        }
        double distance = bot.distanceTo(owner);
        if (distance <= config.stopFollowDistance) {
            return true;
        }
        if (distance > config.teleportDistance) {
            return false;
        }
        double yDiff = Math.abs(bot.getY() - owner.getY());
        if (yDiff > 5.0) {
            return false;
        }
        return roughlyClearHorizontalRoute(bot, owner);
    }

    private static boolean roughlyClearHorizontalRoute(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        Vec3d start = bot.getPos();
        Vec3d end = owner.getPos();
        int steps = Math.max(1, (int) Math.ceil(start.distanceTo(end)));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = start.x + (end.x - start.x) * t;
            double z = start.z + (end.z - start.z) * t;
            int y = MathHelper.floor(start.y + (end.y - start.y) * t);
            BlockPos feet = BlockPos.ofFloored(x, y, z);
            if (!bot.getWorld().getBlockState(feet).getCollisionShape(bot.getWorld(), feet).isEmpty()
                    || !bot.getWorld().getBlockState(feet.up()).getCollisionShape(bot.getWorld(), feet.up()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ServerPlayerEntity findOwner(MinecraftServer server, AIPlayerCleanConfig config) {
        try {
            return server.getPlayerManager().getPlayer(UUID.fromString(config.ownerUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void resetMissingState() {
        missingSinceTick = -1L;
        lastRespawnAttemptTick = -1L;
        respawnMessageSent = false;
    }
}
