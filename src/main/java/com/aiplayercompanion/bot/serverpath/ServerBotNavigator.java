package com.aiplayercompanion.bot.serverpath;

import com.aiplayercompanion.bot.AIPlayerBot;
import com.aiplayercompanion.config.ModConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
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

public final class ServerBotNavigator {
    private static final long REPLAN_TICKS = 30L;
    private static final double REACHED_SQ = 0.48D;
    private static final double GRAVITY = -0.08D;
    private static final double JUMP = 0.42D;
    private final ServerAStarPlanner planner = new ServerAStarPlanner();
    private final StuckDetector stuckDetector = new StuckDetector();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public boolean moveTo(AIPlayerBot bot, ServerPlayerEntity owner, BlockPos goal, double stopDistance, double distanceSq, long now) {
        enforceSurvivalBody(bot);
        if (bot.getBlockPos().getSquaredDistance(goal) <= stopDistance * stopDistance && Math.abs(bot.getY() - goal.getY()) < 1.25D) {
            stop(bot);
            return true;
        }
        Session session = sessions.get(bot.getUuid());
        if (session == null
                || session.plan.goal().getSquaredDistance(goal) > 4.0D
                || session.index >= session.plan.steps().size()
                || now - session.lastPlanTick >= REPLAN_TICKS
                || stuckDetector.isStuck(bot, goal, now)) {
            session = plan(bot, owner.getWorld(), goal, now);
            if (session.plan.isEmpty()) {
                stop(bot);
                return false;
            }
            sessions.put(bot.getUuid(), session);
        }
        return execute(bot, owner, session, distanceSq);
    }

    public Optional<BlockPos> chooseFollowTarget(ServerPlayerEntity owner, AIPlayerBot bot) {
        ServerWorld world = owner.getWorld();
        Optional<BlockPos> ownerGround = CollisionValidator.findStandable(world, owner.getBlockPos(), 48, false);
        if (ownerGround.isEmpty()) {
            return Optional.empty();
        }
        Vec3d preferred = preferredSideRearPosition(owner, bot, ownerGround.get().getY());
        Optional<BlockPos> preferredSafe = nearestSafeGround(world, BlockPos.ofFloored(preferred), 0, 4);
        if (preferredSafe.isPresent()) {
            return preferredSafe;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 5; r <= 10; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) {
                        continue;
                    }
                    Optional<BlockPos> safe = CollisionValidator.findStandable(world, ownerGround.get().add(x, 0, z), 16, false);
                    if (safe.isEmpty()) {
                        continue;
                    }
                    double ownerDist = safe.get().getSquaredDistance(ownerGround.get());
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

    public boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        Optional<BlockPos> ownerGround = CollisionValidator.findStandable(owner.getWorld(), owner.getBlockPos(), 64, false);
        Optional<BlockPos> safe = ownerGround.flatMap(pos -> nearestSafeGround(owner.getWorld(), pos, 3, 9));
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

    public void stop(AIPlayerBot bot) {
        sessions.remove(bot.getUuid());
        stuckDetector.clear(bot);
        bot.clearPath();
        bot.setJumping(false);
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        applyGravity(bot);
        bot.velocityModified = true;
    }

    public void idleLook(AIPlayerBot bot, ServerPlayerEntity owner, long now) {
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

    public void lookAtEntity(AIPlayerBot bot, LivingEntity entity, float fallbackYaw) {
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

    public void lookTowardYaw(AIPlayerBot bot, float yaw) {
        bot.setYaw(yaw);
        bot.setPitch(0.0F);
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = 0.0F;
        bot.lastBodyYaw = yaw;
        bot.lastHeadYaw = yaw;
    }

    public void enforceSurvivalBody(AIPlayerBot bot) {
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
    }

    private Session plan(AIPlayerBot bot, ServerWorld world, BlockPos goal, long now) {
        PathPlan plan = planner.plan(world, bot.getBlockPos(), goal);
        bot.setPath(plan.steps().stream().map(PathStep::pos).toList(), goal, now);
        return new Session(plan, 0, now);
    }

    private boolean execute(AIPlayerBot bot, ServerPlayerEntity owner, Session session, double distanceSq) {
        if (session.index >= session.plan.steps().size()) {
            stop(bot);
            return false;
        }
        PathStep step = session.plan.steps().get(session.index);
        openDoorsForStep(bot, step);
        Vec3d target = Vec3d.ofBottomCenter(step.pos());
        Vec3d delta = target.subtract(bot.getPos());
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        if (horizontal.lengthSquared() <= REACHED_SQ && Math.abs(delta.y) < 0.95D) {
            sessions.put(bot.getUuid(), session.advance());
            bot.advancePath();
            return true;
        }
        boolean sprint = distanceSq > ModConfig.get().sprintFollowDistance * ModConfig.get().sprintFollowDistance;
        double speed = (sprint ? 0.255D : 0.165D) * bot.getSpeedScale();
        Vec3d horizontalMove = horizontal.lengthSquared() < 0.0001D
                ? Vec3d.ZERO
                : horizontal.normalize().multiply(Math.min(speed, horizontal.length()));
        double y = verticalVelocity(bot, step, delta.y, horizontal);
        Vec3d movement = new Vec3d(horizontalMove.x, y, horizontalMove.z);
        bot.setSneaking(false);
        bot.setSprinting(sprint);
        if (horizontalMove.lengthSquared() > 0.0001D) {
            lookTowardYaw(bot, yawFrom(horizontalMove));
        }
        bot.setVelocity(movement);
        bot.move(MovementType.SELF, movement);
        applyGravity(bot);
        bot.velocityModified = true;
        return true;
    }

    private double verticalVelocity(AIPlayerBot bot, PathStep step, double deltaY, Vec3d horizontal) {
        if (isOnClimbable(bot)) {
            return MathHelper.clamp(deltaY, -0.18D, 0.20D);
        }
        if ((step.type() == PathStepType.STEP_UP || shouldJumpForward(bot, horizontal, step.pos())) && bot.isOnGround()) {
            bot.setJumping(true);
            bot.jump();
            return Math.max(JUMP, bot.getVelocity().y);
        }
        bot.setJumping(false);
        return bot.isOnGround() ? 0.0D : Math.max(bot.getVelocity().y + GRAVITY, -0.8D);
    }

    private boolean shouldJumpForward(AIPlayerBot bot, Vec3d horizontal, BlockPos target) {
        if (!bot.isOnGround() || horizontal.lengthSquared() < 0.0001D || target.getY() < bot.getBlockY()) {
            return false;
        }
        Vec3d dir = horizontal.normalize();
        BlockPos frontFeet = BlockPos.ofFloored(bot.getX() + dir.x * 0.58D, bot.getY(), bot.getZ() + dir.z * 0.58D);
        BlockState front = bot.getWorld().getBlockState(frontFeet);
        BlockState frontHead = bot.getWorld().getBlockState(frontFeet.up());
        BlockState aboveHead = bot.getWorld().getBlockState(bot.getBlockPos().up(2));
        return !front.getCollisionShape(bot.getWorld(), frontFeet).isEmpty()
                && frontHead.getCollisionShape(bot.getWorld(), frontFeet.up()).isEmpty()
                && aboveHead.getCollisionShape(bot.getWorld(), bot.getBlockPos().up(2)).isEmpty();
    }

    private void applyGravity(AIPlayerBot bot) {
        if (bot.isOnGround() || isOnClimbable(bot)) {
            return;
        }
        bot.move(MovementType.SELF, new Vec3d(0.0D, GRAVITY, 0.0D));
        bot.setVelocity(bot.getVelocity().x, Math.max(bot.getVelocity().y + GRAVITY, -0.8D), bot.getVelocity().z);
        bot.velocityModified = true;
    }

    private void openDoorsForStep(AIPlayerBot bot, PathStep step) {
        ServerWorld world = bot.getWorld();
        BlockPos base = bot.getBlockPos();
        for (BlockPos pos : List.of(base, base.up(), step.pos(), step.pos().up(), base.north(), base.south(), base.east(), base.west())) {
            openDoorAt(world, pos);
        }
    }

    private void openDoorAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (CollisionValidator.canOpenDoor(state)) {
            world.setBlockState(pos, state.with(DoorBlock.OPEN, true), DoorBlock.NOTIFY_LISTENERS);
            world.syncWorldEvent(null, 1006, pos, 0);
        }
    }

    private Optional<BlockPos> nearestSafeGround(ServerWorld world, BlockPos center, int minRadius, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = minRadius; r <= radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) != r && Math.abs(z) != r) {
                        continue;
                    }
                    Optional<BlockPos> safe = CollisionValidator.findStandable(world, center.add(x, 0, z), 32, false);
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
        }
        return Optional.ofNullable(best);
    }

    private Vec3d preferredSideRearPosition(ServerPlayerEntity owner, AIPlayerBot bot, int groundY) {
        double yaw = Math.toRadians(owner.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3d right = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
        double sideSign = (bot.getUuid().getLeastSignificantBits() & 1L) == 0L ? 1.0D : -1.0D;
        Vec3d base = new Vec3d(owner.getX(), groundY, owner.getZ());
        return base.subtract(forward.multiply(bot.getFollowDistance()))
                .add(right.multiply(bot.getSideDistance() * sideSign));
    }

    private boolean isOnClimbable(AIPlayerBot bot) {
        return CollisionValidator.isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || CollisionValidator.isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()));
    }

    private boolean isOwnerLookingAtBot(ServerPlayerEntity owner, AIPlayerBot bot) {
        if (owner.squaredDistanceTo(bot) > 49.0D) {
            return false;
        }
        Vec3d look = owner.getRotationVec(1.0F).normalize();
        Vec3d toBot = bot.getEyePos().subtract(owner.getEyePos());
        double distance = toBot.length();
        return distance >= 0.001D && look.dotProduct(toBot.normalize()) > 0.985D;
    }

    private float yawFrom(Vec3d movement) {
        return (float) (MathHelper.atan2(movement.z, movement.x) * 57.2957763671875D) - 90.0F;
    }

    private record Session(PathPlan plan, int index, long lastPlanTick) {
        Session advance() {
            return new Session(plan, index + 1, lastPlanTick);
        }
    }
}
