package com.aiplayercompanion.bot;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.service.CompanionLog;
import com.aiplayercompanion.service.AIPlayerManager;
import com.aiplayercompanion.util.ModelNameUtil;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.MovementType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.WorldView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class AIPlayerBotManager {
    private static final long RESPAWN_RETRY_TICKS = 60L;
    private static final long DEATH_RESPAWN_DELAY_TICKS = 60L;
    private static final long PATH_RECOMPUTE_TICKS = 35L;
    private static final int MAX_PATH_NODES = 320;
    private static final int MAX_PATH_DISTANCE = 28;
    private static final Map<UUID, PendingRespawn> PENDING_RESPAWNS = new HashMap<>();

    private AIPlayerBotManager() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(AIPlayerBotManager::tickBots);
    }

    public static Optional<AIPlayerBot> findOwnedBot(ServerPlayerEntity owner) {
        for (ServerPlayerEntity player : owner.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof AIPlayerBot bot && bot.getOwnerUuid().equals(owner.getUuid())) {
                return Optional.of(bot);
            }
        }

        String uuidText = ModConfig.get().botPlayerUuid;
        if (uuidText == null || uuidText.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID botUuid = UUID.fromString(uuidText);
            ServerPlayerEntity player = owner.getServer().getPlayerManager().getPlayer(botUuid);
            if (player instanceof AIPlayerBot bot && bot.getOwnerUuid().equals(owner.getUuid())) {
                return Optional.of(bot);
            }
        } catch (IllegalArgumentException ignored) {
            ModConfig.get().botPlayerUuid = "";
            ModConfig.save();
        }
        return Optional.empty();
    }

    public static AIPlayerBot spawnFor(ServerPlayerEntity owner) {
        return spawnFor(owner, owner.getPos().add(1.5D, 0.0D, 1.5D));
    }

    private static AIPlayerBot spawnFor(ServerPlayerEntity owner, Vec3d position) {
        MinecraftServer server = owner.getServer();
        ServerWorld world = owner.getWorld();
        AIPlayerManager.findOwnedCompanion(owner).ifPresent(entity -> {
            entity.discard();
            CompanionLog.player(owner, "BOT", "removed legacy companion entity before player bot spawn");
        });
        UUID botUuid = configuredOrNewBotUuid(owner);
        String botName = botPlayerName();
        GameProfile profile = new GameProfile(botUuid, botName);
        AIPlayerBot bot = new AIPlayerBot(server, world, profile, owner.getUuid());
        bot.setBotState(readState());
        reviveVisualState(bot);
        bot.refreshPositionAndAngles(position.x, position.y, position.z, owner.getYaw(), 0.0F);
        ModConfig.get().botPlayerUuid = botUuid.toString();
        ModConfig.rememberOwner(owner.getName().getString(), owner.getUuidAsString());
        ModConfig.save();
        try {
            server.getPlayerManager().onPlayerConnect(new DummyClientConnection(), bot, ConnectedClientData.createDefault(profile, false));
        } catch (Throwable throwable) {
            ServerPlayerEntity listed = server.getPlayerManager().getPlayer(botUuid);
            if (!(listed instanceof AIPlayerBot)) {
                ModConfig.get().botPlayerUuid = "";
                ModConfig.save();
                throw throwable;
            }
            AIPlayerCompanionMod.LOGGER.warn("Player bot connect completed with a recoverable sync error: {}", throwable.toString());
        }
        reviveVisualState(bot);
        bot.refreshPositionAndAngles(position.x, position.y, position.z, owner.getYaw(), 0.0F);
        CompanionLog.player(owner, "BOT", "spawned player bot " + botName);
        return bot;
    }

    public static void remove(ServerPlayerEntity owner, AIPlayerBot bot) {
        AIPlayerBotController.discardNavigationProxy(bot);
        owner.getServer().getPlayerManager().remove(bot);
        bot.remove(Entity.RemovalReason.DISCARDED);
        ModConfig.get().botPlayerUuid = "";
        ModConfig.save();
        CompanionLog.player(owner, "BOT", "removed player bot");
    }

    public static void setState(ServerPlayerEntity owner, AIPlayerBot.BotState state) {
        findOwnedBot(owner).ifPresent(bot -> {
            bot.setBotState(state);
            ModConfig.get().botState = state.name();
            ModConfig.save();
        });
    }

    public static AIPlayerBot respawnFor(ServerPlayerEntity owner, String reason) {
        findOwnedBot(owner).ifPresent(bot -> {
            owner.getServer().getPlayerManager().remove(bot);
            bot.discard();
        });
        Optional<Vec3d> safe = findSafePositionNearOwner(owner);
        if (safe.isEmpty()) {
            PENDING_RESPAWNS.put(owner.getUuid(), new PendingRespawn(reason, owner.getWorld().getTime() - RESPAWN_RETRY_TICKS + 1L, false));
            owner.sendMessage(Text.literal(ModelNameUtil.companionName() + " 暂时找不到安全复活点，会继续尝试。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(owner, "BOT", "respawn delayed: no safe position");
            return null;
        }
        ModConfig.get().botState = AIPlayerBot.BotState.FOLLOWING.name();
        ModConfig.get().botPlayerUuid = "";
        ModConfig.save();
        AIPlayerBot bot = spawnFor(owner, safe.get());
        reviveVisualState(bot);
        owner.sendMessage(Text.literal(bot.getName().getString() + " 已复活并回到你身边。").formatted(Formatting.GREEN), false);
        CompanionLog.player(owner, "BOT", "respawned player bot: " + reason);
        return bot;
    }

    public static boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        boolean useGroundedController = System.nanoTime() >= 0L;
        if (useGroundedController) {
            boolean teleported = AIPlayerBotController.teleportNearOwner(owner, bot, feedback);
            CompanionLog.player(owner, "BOT", teleported ? "teleported bot near owner" : "teleport failed: no safe position");
            return teleported;
        }
        Optional<Vec3d> safe = findSafePositionNearOwner(owner);
        if (safe.isEmpty()) {
            owner.sendMessage(Text.literal(bot.getName().getString() + ": no safe teleport position near you.").formatted(Formatting.YELLOW), false);
            CompanionLog.player(owner, "BOT", "teleport failed: no safe position");
            return false;
        }
        Vec3d position = safe.get();
        if (bot.getWorld() != owner.getWorld()) {
            bot.teleport(owner.getWorld(), position.x, position.y, position.z, Set.<PositionFlag>of(), owner.getYaw(), owner.getPitch(), true);
        } else {
            bot.requestTeleport(position.x, position.y, position.z);
        }
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityModified = true;
        owner.sendMessage(Text.literal(bot.getName().getString() + (feedback ? "：距离太远了，我传送到你附近。" : "：我过来了。")).formatted(Formatting.GREEN), false);
        CompanionLog.player(owner, "BOT", "teleported bot near owner");
        return true;
    }

    private static void tickBots(MinecraftServer server) {
        tickPendingRespawns(server);
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (!(player instanceof AIPlayerBot bot)) {
                continue;
            }
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(bot.getOwnerUuid());
            if (owner == null) {
                bot.setSneaking(true);
                bot.setSprinting(false);
                continue;
            }
            if (bot.isDead() || !bot.isAlive() || bot.getHealth() <= 0.0F) {
                AIPlayerBotController.discardNavigationProxy(bot);
                owner.getServer().getPlayerManager().remove(bot);
                bot.discard();
                PENDING_RESPAWNS.put(owner.getUuid(), new PendingRespawn("death detected", owner.getWorld().getTime() - RESPAWN_RETRY_TICKS + DEATH_RESPAWN_DELAY_TICKS, false));
                owner.sendMessage(Text.literal(bot.getName().getString() + "：我倒下了，等一下会找安全位置回来。").formatted(Formatting.RED), false);
                CompanionLog.player(owner, "BOT", "bot died, respawn scheduled");
                continue;
            }
            AIPlayerBotController.tick(bot, owner);
        }
    }

    private static void tickPendingRespawns(MinecraftServer server) {
        if (PENDING_RESPAWNS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingRespawn> entry : new ArrayList<>(PENDING_RESPAWNS.entrySet())) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            long now = owner.getWorld().getTime();
            PendingRespawn pending = entry.getValue();
            if (now - pending.lastAttemptTick() < RESPAWN_RETRY_TICKS) {
                continue;
            }
            Optional<Vec3d> safe = findSafePositionNearOwner(owner);
            if (safe.isEmpty()) {
                PENDING_RESPAWNS.put(entry.getKey(), new PendingRespawn(pending.reason(), now, pending.notified()));
                if (!pending.notified()) {
                    owner.sendMessage(Text.literal(ModelNameUtil.companionName() + " 暂时找不到安全复活点，会继续尝试。").formatted(Formatting.YELLOW), false);
                    PENDING_RESPAWNS.put(entry.getKey(), new PendingRespawn(pending.reason(), now, true));
                }
                CompanionLog.player(owner, "BOT", "respawn retry failed: no safe position");
                continue;
            }
            PENDING_RESPAWNS.remove(entry.getKey());
            ModConfig.get().botState = AIPlayerBot.BotState.FOLLOWING.name();
            ModConfig.get().botPlayerUuid = "";
            ModConfig.save();
            AIPlayerBot bot = spawnFor(owner, safe.get());
            reviveVisualState(bot);
            owner.sendMessage(Text.literal(bot.getName().getString() + " 找到安全位置并复活了。").formatted(Formatting.GREEN), false);
            CompanionLog.player(owner, "BOT", "respawn retry succeeded: " + pending.reason());
        }
    }

    private static void tickBot(AIPlayerBot bot, ServerPlayerEntity owner) {
        if (bot.getBotState() == AIPlayerBot.BotState.WAITING) {
            bot.setSneaking(true);
            bot.setVelocity(Vec3d.ZERO);
            bot.clearPath();
            lookAtOwner(bot, owner, bot.getYaw());
            return;
        }
        if (bot.getWorld() != owner.getWorld()) {
            teleportNearOwner(owner, bot, true);
            return;
        }
        double distanceSq = bot.squaredDistanceTo(owner);
        double teleport = ModConfig.get().teleportDistance;
        if (distanceSq > teleport * teleport) {
            teleportNearOwner(owner, bot, true);
            return;
        }
        long now = owner.getWorld().getTime();
        double stop = Math.max(ModConfig.get().stopFollowDistance, bot.getStopDistance());
        if (distanceSq <= stop * stop) {
            bot.setSneaking(false);
            bot.setSprinting(false);
            bot.setVelocity(Vec3d.ZERO);
            bot.clearPath();
            if (bot.isLookingAround(now)) {
                lookTowardYaw(bot, bot.getLookAroundYaw());
            } else if (bot.shouldLookAtOwnerWhenIdle()) {
                lookAtOwner(bot, owner, bot.getYaw());
            } else {
                lookTowardYaw(bot, owner.getYaw());
            }
            return;
        }

        Optional<BlockPos> maybeTarget = findFollowTarget(owner, bot);
        if (maybeTarget.isEmpty()) {
            bot.setSneaking(false);
            bot.setSprinting(false);
            bot.setVelocity(Vec3d.ZERO);
            bot.clearPath();
            applyGroundedPhysics(bot);
            lookTowardYaw(bot, owner.getYaw());
            return;
        }
        BlockPos target = maybeTarget.get();
        boolean targetChanged = bot.getLastPathTarget() == null || bot.getLastPathTarget().getSquaredDistance(target) > 9.0D;
        boolean needsPath = bot.getPath().isEmpty()
                || bot.getPathIndex() >= bot.getPath().size()
                || targetChanged
                || now - bot.getLastPathComputeTick() >= PATH_RECOMPUTE_TICKS;
        if (needsPath) {
            List<BlockPos> path = findPath(owner.getWorld(), bot.getBlockPos(), target);
            if (path.isEmpty()) {
                bot.setVelocity(Vec3d.ZERO);
                applyGroundedPhysics(bot);
                lookTowardYaw(bot, owner.getYaw());
                return;
            }
            bot.setPath(path, target, now);
        }
        Vec3d moveTarget = nextPathPoint(bot, target);
        openDoorsAround(bot);
        Vec3d direction = moveTarget.subtract(bot.getPos());
        Vec3d horizontal = new Vec3d(direction.x, 0.0D, direction.z);
        if (horizontal.lengthSquared() < 0.001D) {
            moveVerticallyIfNeeded(bot, direction.y);
            bot.advancePath();
            return;
        }
        boolean sprint = distanceSq > ModConfig.get().sprintFollowDistance * ModConfig.get().sprintFollowDistance;
        double step = (sprint ? 0.34D : 0.20D) * bot.getSpeedScale();
        double travel = Math.min(step, horizontal.length());
        Vec3d horizontalMove = horizontal.normalize().multiply(travel);
        double verticalMove = verticalMovement(bot, direction.y);
        Vec3d movement = new Vec3d(horizontalMove.x, verticalMove, horizontalMove.z);
        float yaw = (float) (MathHelper.atan2(movement.z, movement.x) * 57.2957763671875D) - 90.0F;
        Vec3d before = bot.getPos();
        bot.setSneaking(false);
        bot.setSprinting(sprint);
        lookTowardYaw(bot, yaw);
        bot.setVelocity(movement.x, bot.getVelocity().y, movement.z);
        bot.move(MovementType.SELF, movement);
        applyGroundedPhysics(bot);
        if (bot.getPos().squaredDistanceTo(before) < 0.0004D) {
            bot.clearPath();
            bot.setPath(Collections.emptyList(), target, now - PATH_RECOMPUTE_TICKS + 5L);
        } else if (bot.getPos().squaredDistanceTo(moveTarget) < 0.45D) {
            bot.advancePath();
        }
        bot.velocityModified = true;
    }

    private static double verticalMovement(AIPlayerBot bot, double targetDeltaY) {
        if (isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()))) {
            return MathHelper.clamp(targetDeltaY, -0.18D, 0.22D);
        }
        if (targetDeltaY > 0.25D) {
            return 0.42D;
        }
        if (targetDeltaY < -0.75D) {
            return -0.35D;
        }
        return 0.0D;
    }

    private static void applyGroundedPhysics(AIPlayerBot bot) {
        if (bot.isOnGround()
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()))) {
            return;
        }
        bot.move(MovementType.SELF, new Vec3d(0.0D, -0.08D, 0.0D));
        bot.setVelocity(bot.getVelocity().x, Math.max(bot.getVelocity().y - 0.08D, -0.8D), bot.getVelocity().z);
        bot.velocityModified = true;
    }

    private static void moveVerticallyIfNeeded(AIPlayerBot bot, double targetDeltaY) {
        double verticalMove = verticalMovement(bot, targetDeltaY);
        if (Math.abs(verticalMove) > 0.001D) {
            bot.move(MovementType.SELF, new Vec3d(0.0D, verticalMove, 0.0D));
            bot.setVelocity(0.0D, verticalMove, 0.0D);
            bot.velocityModified = true;
        }
    }

    private static void openDoorsAround(AIPlayerBot bot) {
        ServerWorld world = bot.getWorld();
        BlockPos base = bot.getBlockPos();
        for (BlockPos pos : List.of(base, base.up(), base.north(), base.south(), base.east(), base.west())) {
            BlockState state = world.getBlockState(pos);
            if (canOpenDoor(state)) {
                world.setBlockState(pos, state.with(DoorBlock.OPEN, true), DoorBlock.NOTIFY_LISTENERS);
                world.syncWorldEvent(null, 1006, pos, 0);
            }
        }
    }

    private static Optional<BlockPos> findFollowTarget(ServerPlayerEntity owner, AIPlayerBot bot) {
        ServerWorld world = owner.getWorld();
        BlockPos ownerPos = owner.getBlockPos();
        Vec3d preferred = preferredCompanionPosition(owner, bot);
        BlockPos preferredPos = BlockPos.ofFloored(preferred);
        Optional<BlockPos> preferredSafe = findSafeGroundNear(world, preferredPos, 4);
        if (preferredSafe.isPresent()) {
            return preferredSafe;
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 5; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    BlockPos safe = findGroundLanding(world, ownerPos.add(x, 0, z), 20);
                    if (safe == null) {
                        continue;
                    }
                    double ownerDistance = safe.getSquaredDistance(ownerPos);
                    double botDistance = safe.getSquaredDistance(bot.getBlockPos());
                    double preferredDistance = safe.getSquaredDistance(preferredPos);
                    double idealDistanceSq = bot.getFollowDistance() * bot.getFollowDistance();
                    double score = Math.abs(ownerDistance - idealDistanceSq) * 2.0D + preferredDistance * 1.5D + botDistance * 0.35D;
                    if (score < bestScore) {
                        best = safe;
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Vec3d preferredCompanionPosition(ServerPlayerEntity owner, AIPlayerBot bot) {
        double yaw = Math.toRadians(owner.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3d right = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
        double sideSign = (bot.getUuid().getLeastSignificantBits() & 1L) == 0L ? 1.0D : -1.0D;
        return owner.getPos()
                .subtract(forward.multiply(bot.getFollowDistance()))
                .add(right.multiply(bot.getSideDistance() * sideSign));
    }

    private static Optional<BlockPos> findSafeNear(ServerWorld world, BlockPos center, int radius) {
        return findSafeGroundNear(world, center, radius);
    }

    private static Optional<BlockPos> findSafeGroundNear(ServerWorld world, BlockPos center, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos safe = findGroundLanding(world, center.add(x, 0, z), 24);
                if (safe == null) {
                    continue;
                }
                double score = safe.getSquaredDistance(center);
                if (score < bestScore) {
                    best = safe;
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Vec3d nextPathPoint(AIPlayerBot bot, BlockPos fallbackTarget) {
        List<BlockPos> path = bot.getPath();
        if (!path.isEmpty() && bot.getPathIndex() < path.size()) {
            return Vec3d.ofBottomCenter(path.get(bot.getPathIndex()));
        }
        return Vec3d.ofBottomCenter(fallbackTarget);
    }

    private static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
        BlockPos safeStart = findWalkableLanding(world, start);
        if (safeStart == null) {
            safeStart = findGroundLanding(world, start, 8);
        }
        if (safeStart == null) {
            return List.of();
        }
        if (safeStart.getManhattanDistance(goal) > MAX_PATH_DISTANCE) {
            return List.of();
        }

        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        open.add(new PathNode(safeStart, 0.0D, heuristic(safeStart, goal)));
        cost.put(safeStart, 0.0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_PATH_NODES) {
            PathNode current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            if (current.pos().getSquaredDistance(goal) <= 2.0D) {
                return reconstructPath(cameFrom, current.pos());
            }
            for (BlockPos neighbor : neighbors(world, current.pos())) {
                if (closed.contains(neighbor)) {
                    continue;
                }
                double moveCost = cost.get(current.pos()) + movementCost(current.pos(), neighbor);
                if (moveCost >= cost.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }
                cameFrom.put(neighbor, current.pos());
                cost.put(neighbor, moveCost);
                open.add(new PathNode(neighbor, moveCost, moveCost + heuristic(neighbor, goal)));
            }
        }
        return List.of();
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        ArrayList<BlockPos> path = new ArrayList<>();
        BlockPos current = end;
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(current);
        }
        Collections.reverse(path);
        if (!path.isEmpty()) {
            path.remove(0);
        }
        return path;
    }

    private static List<BlockPos> neighbors(ServerWorld world, BlockPos pos) {
        ArrayList<BlockPos> result = new ArrayList<>(8);
        addNeighbor(world, result, pos, 1, 0);
        addNeighbor(world, result, pos, -1, 0);
        addNeighbor(world, result, pos, 0, 1);
        addNeighbor(world, result, pos, 0, -1);
        addNeighbor(world, result, pos, 1, 1);
        addNeighbor(world, result, pos, 1, -1);
        addNeighbor(world, result, pos, -1, 1);
        addNeighbor(world, result, pos, -1, -1);
        return result;
    }

    private static void addNeighbor(ServerWorld world, List<BlockPos> result, BlockPos pos, int dx, int dz) {
        BlockPos near = pos.add(dx, 0, dz);
        BlockPos safe = findWalkableLanding(world, near);
        if (safe == null || Math.abs(safe.getY() - pos.getY()) > 1) {
            return;
        }
        if (dx != 0 && dz != 0) {
            BlockPos sideA = findWalkableLanding(world, pos.add(dx, 0, 0));
            BlockPos sideB = findWalkableLanding(world, pos.add(0, 0, dz));
            if (sideA == null || sideB == null) {
                return;
            }
        }
        result.add(safe);

        if (isClimbable(world.getBlockState(pos)) || isClimbable(world.getBlockState(pos.up()))) {
            BlockPos up = pos.up();
            if (isWalkablePosition(world, up, true)) {
                result.add(up);
            }
            BlockPos down = pos.down();
            if (isWalkablePosition(world, down, true)) {
                result.add(down);
            }
        }
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) + Math.abs(from.getZ() - to.getZ());
    }

    private static double movementCost(BlockPos from, BlockPos to) {
        double horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
        double vertical = Math.abs(from.getY() - to.getY()) * 1.5D;
        return horizontal + vertical;
    }

    private static void lookAtOwner(AIPlayerBot bot, ServerPlayerEntity owner, float fallbackYaw) {
        Vec3d eyeDelta = owner.getEyePos().subtract(bot.getEyePos());
        double horizontal = Math.sqrt(eyeDelta.x * eyeDelta.x + eyeDelta.z * eyeDelta.z);
        float yaw = horizontal > 0.001D
                ? (float) (MathHelper.atan2(eyeDelta.z, eyeDelta.x) * 57.2957763671875D) - 90.0F
                : fallbackYaw;
        float pitch = (float) -(MathHelper.atan2(eyeDelta.y, horizontal) * 57.2957763671875D);
        pitch = MathHelper.clamp(pitch, -60.0F, 60.0F);

        bot.setYaw(yaw);
        bot.setPitch(pitch);
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = pitch;
        bot.lastBodyYaw = yaw;
        bot.lastHeadYaw = yaw;
    }

    private static void lookTowardYaw(AIPlayerBot bot, float yaw) {
        bot.setYaw(yaw);
        bot.setPitch(0.0F);
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = 0.0F;
        bot.lastBodyYaw = yaw;
        bot.lastHeadYaw = yaw;
    }

    private static AIPlayerBot.BotState readState() {
        try {
            return AIPlayerBot.BotState.valueOf(ModConfig.get().botState);
        } catch (IllegalArgumentException e) {
            return AIPlayerBot.BotState.FOLLOWING;
        }
    }

    private static UUID configuredOrNewBotUuid(ServerPlayerEntity owner) {
        String uuidText = ModConfig.get().botPlayerUuid;
        if (uuidText != null && !uuidText.isBlank()) {
            try {
                return UUID.fromString(uuidText);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(("aiplayer:" + owner.getUuidAsString() + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    }

    private static String botPlayerName() {
        return ModelNameUtil.botPlayerName();
    }

    private static Optional<Vec3d> findSafePositionNearOwner(ServerPlayerEntity owner) {
        ServerWorld world = owner.getWorld();
        BlockPos ownerPos = owner.getBlockPos();
        for (int radius = 3; radius <= 7; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    BlockPos safe = findSafeLanding(world, ownerPos.add(x, 0, z));
                    if (safe != null) {
                        return Optional.of(Vec3d.ofBottomCenter(safe));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static BlockPos findSafeLanding(WorldView world, BlockPos near) {
        return findLanding(world, near, false);
    }

    private static BlockPos findWalkableLanding(WorldView world, BlockPos near) {
        return findLanding(world, near, true);
    }

    private static BlockPos findGroundLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, false)) {
                return feet;
            }
        }
        return null;
    }

    private static BlockPos findLanding(WorldView world, BlockPos near, boolean allowClosedDoors) {
        int minY = world.getBottomY() + 1;
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 5);
        for (int y = maxY; y >= Math.max(minY, near.getY() - 6); y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, allowClosedDoors)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isSafeStandingPosition(WorldView world, BlockPos feet) {
        return isWalkablePosition(world, feet, false);
    }

    private static boolean isWalkablePosition(WorldView world, BlockPos feet, boolean allowClosedDoors) {
        BlockPos below = feet.down();
        BlockState floor = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (floor.getCollisionShape(world, below).isEmpty() || isDangerous(floor) || isDangerous(feetState) || isDangerous(headState)) {
            return false;
        }
        if (!isPassableForBot(world, feet, feetState, allowClosedDoors)
                || !isPassableForBot(world, feet.up(), headState, allowClosedDoors)) {
            return false;
        }

        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        return allowClosedDoors || ((ServerWorld) world).isSpaceEmpty(null, box);
    }

    private static boolean isPassableForBot(WorldView world, BlockPos pos, BlockState state, boolean allowClosedDoors) {
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return allowClosedDoors && canOpenDoor(state);
    }

    private static boolean canOpenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.isIn(BlockTags.WOODEN_DOORS)
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN);
    }

    private static boolean isClimbable(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE);
    }

    private static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA);
    }

    private static void reviveVisualState(AIPlayerBot bot) {
        bot.changeGameMode(GameMode.SURVIVAL);
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
        bot.sendAbilitiesUpdate();
        bot.deathTime = 0;
        bot.hurtTime = 0;
        bot.maxHurtTime = 0;
        bot.setHealth(bot.getMaxHealth());
        bot.setPose(EntityPose.STANDING);
        bot.setSneaking(false);
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityModified = true;
    }

    private record PendingRespawn(String reason, long lastAttemptTick, boolean notified) {
    }

    private record PathNode(BlockPos pos, double cost, double score) {
    }
}
