package com.aiplayercompanion.bot;

import com.aiplayercompanion.config.ModConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

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

final class AIPlayerBotController {
    private static final long PATH_RECOMPUTE_TICKS = 30L;
    private static final int MAX_PATH_NODES = 420;
    private static final int MAX_PATH_DISTANCE = 34;
    private static final double WAYPOINT_REACHED_DISTANCE = 0.55D;
    private static final double STUCK_DISTANCE_SQ = 0.0004D;
    private static final double GRAVITY_STEP = -0.08D;
    private static final double DEFEND_SCAN_RANGE = 12.0D;
    private static final double ATTACK_REACH = 2.8D;

    private AIPlayerBotController() {
    }

    static void tick(AIPlayerBot bot, ServerPlayerEntity owner) {
        long now = owner.getWorld().getTime();
        enforceSurvivalBody(bot);

        if (bot.getBotState() == AIPlayerBot.BotState.WAITING) {
            stopOnGround(bot);
            bot.setSneaking(true);
            lookAtOwner(bot, owner, bot.getYaw());
            return;
        }

        if (bot.getWorld() != owner.getWorld()) {
            teleportNearOwner(owner, bot, true);
            return;
        }

        double ownerDistanceSq = bot.squaredDistanceTo(owner);
        double teleportDistance = ModConfig.get().teleportDistance;
        if (ownerDistanceSq > teleportDistance * teleportDistance) {
            teleportNearOwner(owner, bot, true);
            return;
        }

        Optional<HostileEntity> threat = findThreat(owner, bot);
        if (threat.isPresent() && engageThreat(bot, owner, threat.get(), now)) {
            return;
        }

        double stopDistance = Math.max(ModConfig.get().stopFollowDistance, bot.getStopDistance());
        if (ownerDistanceSq <= stopDistance * stopDistance) {
            stopOnGround(bot);
            bot.setSneaking(false);
            if (bot.isLookingAround(now)) {
                lookTowardYaw(bot, bot.getLookAroundYaw());
            } else if (bot.shouldLookAtOwnerWhenIdle()) {
                lookAtOwner(bot, owner, bot.getYaw());
            } else {
                lookTowardYaw(bot, owner.getYaw());
            }
            return;
        }

        Optional<BlockPos> target = chooseGroundFollowTarget(owner, bot);
        if (target.isEmpty()) {
            stopOnGround(bot);
            lookTowardYaw(bot, owner.getYaw());
            return;
        }

        if (shouldRepath(bot, target.get(), now)) {
            List<BlockPos> path = findPath(owner.getWorld(), bot.getBlockPos(), target.get());
            if (path.isEmpty()) {
                stopOnGround(bot);
                lookTowardYaw(bot, owner.getYaw());
                return;
            }
            bot.setPath(path, target.get(), now);
        }

        followPath(bot, owner, target.get(), ownerDistanceSq, now);
    }

    private static Optional<HostileEntity> findThreat(ServerPlayerEntity owner, AIPlayerBot bot) {
        ServerWorld world = owner.getWorld();
        Box scanBox = owner.getBoundingBox().expand(DEFEND_SCAN_RANGE);
        List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class, scanBox, hostile ->
                hostile.isAlive()
                        && !hostile.isRemoved()
                        && hostile.squaredDistanceTo(owner) <= DEFEND_SCAN_RANGE * DEFEND_SCAN_RANGE
                        && hasLineOrClose(owner, bot, hostile));
        HostileEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (HostileEntity hostile : hostiles) {
            double ownerDistance = hostile.squaredDistanceTo(owner);
            double botDistance = hostile.squaredDistanceTo(bot);
            LivingEntity target = hostile.getTarget();
            double score = ownerDistance + botDistance * 0.35D;
            if (target != null && target.getUuid().equals(owner.getUuid())) {
                score -= 80.0D;
            }
            if (score < bestScore) {
                best = hostile;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean hasLineOrClose(ServerPlayerEntity owner, AIPlayerBot bot, HostileEntity hostile) {
        return hostile.squaredDistanceTo(owner) < 36.0D
                || hostile.squaredDistanceTo(bot) < 36.0D
                || hostile.canSee(owner);
    }

    private static boolean engageThreat(AIPlayerBot bot, ServerPlayerEntity owner, HostileEntity hostile, long now) {
        bot.setSneaking(false);
        double distanceSq = bot.squaredDistanceTo(hostile);
        if (distanceSq <= ATTACK_REACH * ATTACK_REACH) {
            stopCombatMovement(bot);
            lookAtEntity(bot, hostile, bot.getYaw());
            if (bot.canAttackAt(now)) {
                bot.attack(hostile);
                bot.swingHand(Hand.MAIN_HAND);
                bot.markAttacked(now);
            }
            return true;
        }

        Optional<BlockPos> target = findGroundLanding(owner.getWorld(), hostile.getBlockPos(), 4);
        if (target.isEmpty()) {
            return false;
        }
        if (shouldRepath(bot, target.get(), now)) {
            List<BlockPos> path = findPath(owner.getWorld(), bot.getBlockPos(), target.get());
            if (path.isEmpty()) {
                return false;
            }
            bot.setPath(path, target.get(), now);
        }
        followPath(bot, owner, target.get(), distanceSq, now);
        return true;
    }

    private static void stopCombatMovement(AIPlayerBot bot) {
        bot.clearPath();
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        applyGroundPhysics(bot);
        bot.velocityModified = true;
    }

    static boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        Optional<BlockPos> safe = chooseTeleportTarget(owner);
        if (safe.isEmpty()) {
            if (feedback) {
                owner.sendMessage(Text.literal(bot.getName().getString() + ": 找不到安全传送点，我先在原地等。").formatted(Formatting.YELLOW), false);
            }
            return false;
        }
        Vec3d position = Vec3d.ofBottomCenter(safe.get());
        if (bot.getWorld() != owner.getWorld()) {
            bot.teleport(owner.getWorld(), position.x, position.y, position.z, Set.<PositionFlag>of(), owner.getYaw(), 0.0F, true);
        } else {
            bot.requestTeleport(position.x, position.y, position.z);
        }
        bot.setVelocity(Vec3d.ZERO);
        bot.clearPath();
        bot.velocityModified = true;
        enforceSurvivalBody(bot);
        if (feedback) {
            owner.sendMessage(Text.literal(bot.getName().getString() + ": 距离太远了，我回到你附近的安全地面。").formatted(Formatting.GREEN), false);
        }
        return true;
    }

    private static void followPath(AIPlayerBot bot, ServerPlayerEntity owner, BlockPos target, double ownerDistanceSq, long now) {
        openDoorsAround(bot);
        Vec3d waypoint = nextWaypoint(bot, target);
        Vec3d delta = waypoint.subtract(bot.getPos());
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);

        if (horizontal.lengthSquared() < WAYPOINT_REACHED_DISTANCE * WAYPOINT_REACHED_DISTANCE && Math.abs(delta.y) < 0.85D) {
            bot.advancePath();
            applyGroundPhysics(bot);
            return;
        }

        boolean sprint = ownerDistanceSq > ModConfig.get().sprintFollowDistance * ModConfig.get().sprintFollowDistance;
        double speed = (sprint ? 0.28D : 0.17D) * bot.getSpeedScale();
        double horizontalLength = horizontal.length();
        Vec3d horizontalMove = horizontalLength < 0.001D
                ? Vec3d.ZERO
                : horizontal.normalize().multiply(Math.min(speed, horizontalLength));
        double verticalMove = verticalMoveFor(bot, delta.y);
        Vec3d movement = new Vec3d(horizontalMove.x, verticalMove, horizontalMove.z);
        Vec3d before = bot.getPos();

        bot.setSneaking(false);
        bot.setSprinting(sprint);
        if (horizontalMove.lengthSquared() > 0.0001D) {
            float yaw = (float) (MathHelper.atan2(horizontalMove.z, horizontalMove.x) * 57.2957763671875D) - 90.0F;
            lookTowardYaw(bot, yaw);
        }
        bot.setVelocity(movement);
        bot.move(MovementType.SELF, movement);
        applyGroundPhysics(bot);

        if (bot.getPos().squaredDistanceTo(before) < STUCK_DISTANCE_SQ) {
            bot.clearPath();
            bot.setPath(Collections.emptyList(), target, now - PATH_RECOMPUTE_TICKS + 8L);
        } else if (bot.getPos().squaredDistanceTo(waypoint) < WAYPOINT_REACHED_DISTANCE * WAYPOINT_REACHED_DISTANCE) {
            bot.advancePath();
        }
        bot.velocityModified = true;
    }

    private static boolean shouldRepath(AIPlayerBot bot, BlockPos target, long now) {
        return bot.getPath().isEmpty()
                || bot.getPathIndex() >= bot.getPath().size()
                || bot.getLastPathTarget() == null
                || bot.getLastPathTarget().getSquaredDistance(target) > 6.0D
                || now - bot.getLastPathComputeTick() >= PATH_RECOMPUTE_TICKS;
    }

    private static Optional<BlockPos> chooseGroundFollowTarget(ServerPlayerEntity owner, AIPlayerBot bot) {
        ServerWorld world = owner.getWorld();
        Optional<BlockPos> ownerGround = findGroundLanding(world, owner.getBlockPos(), 32);
        if (ownerGround.isEmpty()) {
            return Optional.empty();
        }

        Vec3d preferred = preferredSideRearPosition(owner, bot, ownerGround.get().getY());
        Optional<BlockPos> preferredSafe = nearestSafeGround(world, BlockPos.ofFloored(preferred), 4);
        if (preferredSafe.isPresent()) {
            return preferredSafe;
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos ownerGroundPos = ownerGround.get();
        for (int radius = 5; radius <= 9; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    Optional<BlockPos> safe = findGroundLanding(world, ownerGroundPos.add(x, 0, z), 8);
                    if (safe.isEmpty()) {
                        continue;
                    }
                    double ownerDist = safe.get().getSquaredDistance(ownerGroundPos);
                    double preferredDist = safe.get().getSquaredDistance(BlockPos.ofFloored(preferred));
                    double botDist = safe.get().getSquaredDistance(bot.getBlockPos());
                    double ideal = bot.getFollowDistance() * bot.getFollowDistance();
                    double score = Math.abs(ownerDist - ideal) * 2.0D + preferredDist * 1.5D + botDist * 0.25D;
                    if (score < bestScore) {
                        best = safe.get();
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<BlockPos> chooseTeleportTarget(ServerPlayerEntity owner) {
        Optional<BlockPos> ownerGround = findGroundLanding(owner.getWorld(), owner.getBlockPos(), 48);
        if (ownerGround.isEmpty()) {
            return Optional.empty();
        }
        return nearestSafeGround(owner.getWorld(), ownerGround.get(), 8);
    }

    private static Vec3d preferredSideRearPosition(ServerPlayerEntity owner, AIPlayerBot bot, int groundY) {
        double yaw = Math.toRadians(owner.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3d right = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
        double sideSign = (bot.getUuid().getLeastSignificantBits() & 1L) == 0L ? 1.0D : -1.0D;
        Vec3d base = new Vec3d(owner.getX(), groundY, owner.getZ());
        return base.subtract(forward.multiply(bot.getFollowDistance()))
                .add(right.multiply(bot.getSideDistance() * sideSign));
    }

    private static Vec3d nextWaypoint(AIPlayerBot bot, BlockPos target) {
        List<BlockPos> path = bot.getPath();
        if (!path.isEmpty() && bot.getPathIndex() < path.size()) {
            return Vec3d.ofBottomCenter(path.get(bot.getPathIndex()));
        }
        return Vec3d.ofBottomCenter(target);
    }

    private static double verticalMoveFor(AIPlayerBot bot, double targetDeltaY) {
        if (isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()))) {
            return MathHelper.clamp(targetDeltaY, -0.15D, 0.16D);
        }
        if (targetDeltaY > 0.35D && targetDeltaY < 1.35D && bot.isOnGround()) {
            return 0.42D;
        }
        if (targetDeltaY < -0.75D) {
            return -0.22D;
        }
        return 0.0D;
    }

    private static void stopOnGround(AIPlayerBot bot) {
        bot.clearPath();
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        applyGroundPhysics(bot);
        bot.velocityModified = true;
    }

    private static void applyGroundPhysics(AIPlayerBot bot) {
        if (bot.isOnGround()
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()))) {
            return;
        }
        bot.move(MovementType.SELF, new Vec3d(0.0D, GRAVITY_STEP, 0.0D));
        bot.setVelocity(bot.getVelocity().x, Math.max(bot.getVelocity().y + GRAVITY_STEP, -0.8D), bot.getVelocity().z);
        bot.velocityModified = true;
    }

    private static void enforceSurvivalBody(AIPlayerBot bot) {
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
    }

    private static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
        Optional<BlockPos> safeStart = findWalkableLanding(world, start, 8);
        if (safeStart.isEmpty() || safeStart.get().getManhattanDistance(goal) > MAX_PATH_DISTANCE) {
            return List.of();
        }

        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::score));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        HashSet<BlockPos> closed = new HashSet<>();
        BlockPos startPos = safeStart.get();
        open.add(new PathNode(startPos, 0.0D, heuristic(startPos, goal)));
        cost.put(startPos, 0.0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_PATH_NODES) {
            PathNode current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            if (current.pos().getSquaredDistance(goal) <= 1.5D) {
                return reconstructPath(cameFrom, current.pos());
            }
            for (BlockPos neighbor : neighbors(world, current.pos())) {
                if (closed.contains(neighbor)) {
                    continue;
                }
                double newCost = cost.get(current.pos()) + moveCost(current.pos(), neighbor);
                if (newCost >= cost.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }
                cameFrom.put(neighbor, current.pos());
                cost.put(neighbor, newCost);
                open.add(new PathNode(neighbor, newCost, newCost + heuristic(neighbor, goal)));
            }
        }
        return List.of();
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
        Optional<BlockPos> safe = findWalkableLanding(world, pos.add(dx, 0, dz), 2);
        if (safe.isEmpty() || Math.abs(safe.get().getY() - pos.getY()) > 1) {
            return;
        }
        if (dx != 0 && dz != 0) {
            Optional<BlockPos> sideA = findWalkableLanding(world, pos.add(dx, 0, 0), 2);
            Optional<BlockPos> sideB = findWalkableLanding(world, pos.add(0, 0, dz), 2);
            if (sideA.isEmpty() || sideB.isEmpty()) {
                return;
            }
        }
        result.add(safe.get());

        if (isClimbable(world.getBlockState(pos)) || isClimbable(world.getBlockState(pos.up()))) {
            Optional<BlockPos> up = findWalkableLanding(world, pos.up(), 1);
            Optional<BlockPos> down = findWalkableLanding(world, pos.down(), 1);
            up.ifPresent(result::add);
            down.ifPresent(result::add);
        }
    }

    private static Optional<BlockPos> nearestSafeGround(ServerWorld world, BlockPos center, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Optional<BlockPos> safe = findGroundLanding(world, center.add(x, 0, z), 24);
                if (safe.isEmpty()) {
                    continue;
                }
                double score = safe.get().getSquaredDistance(center);
                if (score < bestScore) {
                    best = safe.get();
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<BlockPos> findGroundLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, false)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findWalkableLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, true)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
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
        if (allowClosedDoors) {
            return true;
        }
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        return ((ServerWorld) world).isSpaceEmpty(null, box);
    }

    private static boolean isPassableForBot(WorldView world, BlockPos pos, BlockState state, boolean allowClosedDoors) {
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return allowClosedDoors && canOpenDoor(state);
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

    private static double heuristic(BlockPos from, BlockPos to) {
        return Math.abs(from.getX() - to.getX()) + Math.abs(from.getY() - to.getY()) + Math.abs(from.getZ() - to.getZ());
    }

    private static double moveCost(BlockPos from, BlockPos to) {
        double horizontal = Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ());
        double vertical = Math.abs(from.getY() - to.getY()) * 1.8D;
        return horizontal + vertical;
    }

    private static void lookAtOwner(AIPlayerBot bot, ServerPlayerEntity owner, float fallbackYaw) {
        lookAtEntity(bot, owner, fallbackYaw);
    }

    private static void lookAtEntity(AIPlayerBot bot, LivingEntity entity, float fallbackYaw) {
        Vec3d eyeDelta = entity.getEyePos().subtract(bot.getEyePos());
        double horizontal = Math.sqrt(eyeDelta.x * eyeDelta.x + eyeDelta.z * eyeDelta.z);
        float yaw = horizontal > 0.001D
                ? (float) (MathHelper.atan2(eyeDelta.z, eyeDelta.x) * 57.2957763671875D) - 90.0F
                : fallbackYaw;
        float pitch = (float) -(MathHelper.atan2(eyeDelta.y, horizontal) * 57.2957763671875D);
        bot.setYaw(yaw);
        bot.setPitch(MathHelper.clamp(pitch, -60.0F, 60.0F));
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = bot.getPitch();
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

    private record PathNode(BlockPos pos, double cost, double score) {
    }
}
