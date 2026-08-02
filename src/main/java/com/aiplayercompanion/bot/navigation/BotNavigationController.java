package com.aiplayercompanion.bot.navigation;

import com.aiplayercompanion.bot.AIPlayerBot;
import com.aiplayercompanion.config.ModConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BotNavigationController {
    private static final long PATH_RECOMPUTE_TICKS = 28L;
    private static final long STUCK_RESET_TICKS = 25L;
    private static final double STUCK_DISTANCE_SQ = 0.0025D;
    private static final Map<UUID, StuckState> STUCK = new HashMap<>();

    private BotNavigationController() {
    }

    public static boolean moveTo(AIPlayerBot bot, ServerPlayerEntity owner, BlockPos target, double stopDistance, double sprintDistanceSq, long now) {
        if (bot.getBlockPos().getSquaredDistance(target) <= stopDistance * stopDistance && Math.abs(bot.getY() - target.getY()) < 1.25D) {
            stop(bot);
            return true;
        }
        if (isStuck(bot, target, now)) {
            bot.clearPath();
        }
        if (needsPath(bot, target, now)) {
            List<BotPathNode> typedPath = BotPathPlanner.findPath(owner.getWorld(), bot.getBlockPos(), target);
            if (typedPath.isEmpty()) {
                BotMovementController.stop(bot);
                return maybeTeleportWhenNoPath(bot, owner, sprintDistanceSq);
            }
            bot.setPath(typedPath.stream().map(BotPathNode::pos).toList(), target, now);
        }
        return BotMovementController.followPath(bot, owner, sprintDistanceSq);
    }

    public static Optional<BlockPos> chooseFollowTarget(ServerPlayerEntity owner, AIPlayerBot bot) {
        ServerWorld world = owner.getWorld();
        Optional<BlockPos> ownerGround = BotPathingUtil.findGroundLanding(world, owner.getBlockPos(), 48);
        if (ownerGround.isEmpty()) {
            return Optional.empty();
        }
        Vec3d preferred = preferredSideRearPosition(owner, bot, ownerGround.get().getY());
        Optional<BlockPos> preferredSafe = BotPathingUtil.nearestSafeGround(world, BlockPos.ofFloored(preferred), 4);
        if (preferredSafe.isPresent()) {
            return preferredSafe;
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos ownerGroundPos = ownerGround.get();
        for (int radius = 5; radius <= 10; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    Optional<BlockPos> safe = BotPathingUtil.findGroundLanding(world, ownerGroundPos.add(x, 0, z), 12);
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

    public static boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        Optional<BlockPos> ownerGround = BotPathingUtil.findGroundLanding(owner.getWorld(), owner.getBlockPos(), 64);
        Optional<BlockPos> safe = ownerGround.flatMap(pos -> BotPathingUtil.nearestSafeGround(owner.getWorld(), pos, 3, 9));
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
        stop(bot);
        enforceSurvivalBody(bot);
        if (feedback) {
            owner.sendMessage(Text.literal(bot.getName().getString() + ": 距离太远了，我回到你附近的安全地面。").formatted(Formatting.GREEN), false);
        }
        return true;
    }

    private static boolean maybeTeleportWhenNoPath(AIPlayerBot bot, ServerPlayerEntity owner, double distanceSq) {
        double teleportDistance = Math.max(12.0D, ModConfig.get().teleportDistance);
        if (distanceSq < teleportDistance * teleportDistance) {
            return false;
        }
        return teleportNearOwner(owner, bot, true);
    }

    public static void stop(AIPlayerBot bot) {
        STUCK.remove(bot.getUuid());
        BotMovementController.stop(bot);
    }

    public static void idleLook(AIPlayerBot bot, ServerPlayerEntity owner, long now) {
        if (isOwnerLookingAtBot(owner, bot)) {
            lookAtEntity(bot, owner, bot.getYaw());
            return;
        }
        if (bot.isLookingAround(now)) {
            lookTowardYaw(bot, bot.getLookAroundYaw());
            return;
        }
        if (now % 90L == Math.floorMod(bot.getUuid().getLeastSignificantBits(), 90L)) {
            float yaw = (float) MathHelper.wrapDegrees(bot.getYaw() + 70.0F + Math.floorMod(now * 31L + bot.getUuid().getMostSignificantBits(), 120L));
            bot.startLookingAround(now, 45);
            lookTowardYaw(bot, yaw);
        }
    }

    public static void lookAtEntity(AIPlayerBot bot, LivingEntity entity, float fallbackYaw) {
        BotMovementController.lookAtEntity(bot, entity, fallbackYaw);
    }

    public static void lookTowardYaw(AIPlayerBot bot, float yaw) {
        BotMovementController.lookTowardYaw(bot, yaw);
    }

    public static void enforceSurvivalBody(AIPlayerBot bot) {
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
    }

    private static boolean needsPath(AIPlayerBot bot, BlockPos target, long now) {
        return bot.getPath().isEmpty()
                || bot.getPathIndex() >= bot.getPath().size()
                || bot.getLastPathTarget() == null
                || bot.getLastPathTarget().getSquaredDistance(target) > 5.0D
                || now - bot.getLastPathComputeTick() >= PATH_RECOMPUTE_TICKS;
    }

    private static boolean isStuck(AIPlayerBot bot, BlockPos target, long now) {
        StuckState state = STUCK.get(bot.getUuid());
        Vec3d pos = bot.getPos();
        if (state == null || state.target == null || state.target.getSquaredDistance(target) > 4.0D) {
            STUCK.put(bot.getUuid(), new StuckState(pos, target, now, 0));
            return false;
        }
        if (pos.squaredDistanceTo(state.lastPos) > STUCK_DISTANCE_SQ) {
            STUCK.put(bot.getUuid(), new StuckState(pos, target, now, 0));
            return false;
        }
        long stuckTicks = now - state.sinceTick;
        if (stuckTicks >= STUCK_RESET_TICKS) {
            STUCK.put(bot.getUuid(), new StuckState(pos, target, now, state.replans + 1));
            return true;
        }
        return false;
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

    private static boolean isOwnerLookingAtBot(ServerPlayerEntity owner, AIPlayerBot bot) {
        if (owner.squaredDistanceTo(bot) > 49.0D) {
            return false;
        }
        Vec3d look = owner.getRotationVec(1.0F).normalize();
        Vec3d toBot = bot.getEyePos().subtract(owner.getEyePos());
        double distance = toBot.length();
        if (distance < 0.001D) {
            return false;
        }
        return look.dotProduct(toBot.normalize()) > 0.985D;
    }

    private record StuckState(Vec3d lastPos, BlockPos target, long sinceTick, int replans) {
    }
}
