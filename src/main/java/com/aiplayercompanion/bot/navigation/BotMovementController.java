package com.aiplayercompanion.bot.navigation;

import com.aiplayercompanion.bot.AIPlayerBot;
import com.aiplayercompanion.config.ModConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

final class BotMovementController {
    private static final double WAYPOINT_REACHED_DISTANCE = 0.62D;
    private static final double GRAVITY_STEP = -0.08D;
    private static final double JUMP_STEP = 0.42D;

    private BotMovementController() {
    }

    static boolean followPath(AIPlayerBot bot, ServerPlayerEntity owner, double sprintDistanceSq) {
        List<BlockPos> path = bot.getPath();
        if (path.isEmpty() || bot.getPathIndex() >= path.size()) {
            stop(bot);
            return false;
        }
        BlockPos currentNode = path.get(bot.getPathIndex());
        Vec3d waypoint = Vec3d.ofBottomCenter(currentNode);
        Vec3d delta = waypoint.subtract(bot.getPos());
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        openDoorsAround(bot, horizontal);

        if (horizontal.lengthSquared() < WAYPOINT_REACHED_DISTANCE * WAYPOINT_REACHED_DISTANCE && Math.abs(delta.y) < 0.95D) {
            bot.advancePath();
            applyGroundPhysics(bot);
            return true;
        }

        boolean sprint = sprintDistanceSq > ModConfig.get().sprintFollowDistance * ModConfig.get().sprintFollowDistance;
        double baseSpeed = sprint ? 0.255D : 0.165D;
        double speed = baseSpeed * bot.getSpeedScale();
        double horizontalLength = horizontal.length();
        Vec3d horizontalMove = horizontalLength < 0.001D
                ? Vec3d.ZERO
                : horizontal.normalize().multiply(Math.min(speed, horizontalLength));

        double yVelocity = verticalVelocity(bot, currentNode, delta.y, horizontal);
        Vec3d movement = new Vec3d(horizontalMove.x, yVelocity, horizontalMove.z);
        bot.setSneaking(false);
        bot.setSprinting(sprint);
        if (horizontalMove.lengthSquared() > 0.0001D) {
            lookTowardYaw(bot, yawFrom(horizontalMove));
        }
        bot.setVelocity(movement);
        bot.move(MovementType.SELF, movement);
        applyGroundPhysics(bot);
        bot.velocityModified = true;
        return true;
    }

    static void stop(AIPlayerBot bot) {
        bot.clearPath();
        bot.setJumping(false);
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        applyGroundPhysics(bot);
        bot.velocityModified = true;
    }

    private static double verticalVelocity(AIPlayerBot bot, BlockPos target, double targetDeltaY, Vec3d horizontal) {
        if (isOnClimbable(bot)) {
            return MathHelper.clamp(targetDeltaY, -0.18D, 0.20D);
        }
        if (shouldHopForward(bot, horizontal, target) && bot.isOnGround()) {
            bot.setJumping(true);
            bot.jump();
            return Math.max(JUMP_STEP, bot.getVelocity().y);
        }
        bot.setJumping(false);
        return bot.isOnGround() ? 0.0D : Math.max(bot.getVelocity().y + GRAVITY_STEP, -0.8D);
    }

    private static boolean shouldHopForward(AIPlayerBot bot, Vec3d horizontal, BlockPos target) {
        if (horizontal.lengthSquared() < 0.0001D || target.getY() < bot.getBlockY()) {
            return false;
        }
        Vec3d direction = horizontal.normalize();
        BlockPos frontFeet = BlockPos.ofFloored(bot.getX() + direction.x * 0.58D, bot.getY(), bot.getZ() + direction.z * 0.58D);
        BlockState front = bot.getWorld().getBlockState(frontFeet);
        BlockState frontHead = bot.getWorld().getBlockState(frontFeet.up());
        BlockState aboveHead = bot.getWorld().getBlockState(bot.getBlockPos().up(2));
        return !front.getCollisionShape(bot.getWorld(), frontFeet).isEmpty()
                && frontHead.getCollisionShape(bot.getWorld(), frontFeet.up()).isEmpty()
                && aboveHead.getCollisionShape(bot.getWorld(), bot.getBlockPos().up(2)).isEmpty();
    }

    private static void applyGroundPhysics(AIPlayerBot bot) {
        if (bot.isOnGround() || isOnClimbable(bot)) {
            return;
        }
        bot.move(MovementType.SELF, new Vec3d(0.0D, GRAVITY_STEP, 0.0D));
        bot.setVelocity(bot.getVelocity().x, Math.max(bot.getVelocity().y + GRAVITY_STEP, -0.8D), bot.getVelocity().z);
        bot.velocityModified = true;
    }

    private static boolean isOnClimbable(AIPlayerBot bot) {
        return BotPathingUtil.isClimbable(bot.getWorld().getBlockState(bot.getBlockPos()))
                || BotPathingUtil.isClimbable(bot.getWorld().getBlockState(bot.getBlockPos().up()));
    }

    private static void openDoorsAround(AIPlayerBot bot, Vec3d horizontal) {
        ServerWorld world = bot.getWorld();
        BlockPos base = bot.getBlockPos();
        for (BlockPos pos : List.of(
                base, base.up(),
                base.north(), base.north().up(),
                base.south(), base.south().up(),
                base.east(), base.east().up(),
                base.west(), base.west().up())) {
            openDoorAt(world, pos);
        }
        if (horizontal.lengthSquared() > 0.0001D) {
            Vec3d direction = horizontal.normalize();
            BlockPos front = BlockPos.ofFloored(bot.getX() + direction.x * 1.15D, bot.getY(), bot.getZ() + direction.z * 1.15D);
            openDoorAt(world, front);
            openDoorAt(world, front.up());
        }
    }

    private static void openDoorAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (BotPathingUtil.canOpenDoor(state)) {
            world.setBlockState(pos, state.with(DoorBlock.OPEN, true), DoorBlock.NOTIFY_LISTENERS);
            world.syncWorldEvent(null, 1006, pos, 0);
        }
    }

    static void lookAtEntity(AIPlayerBot bot, LivingEntity entity, float fallbackYaw) {
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

    static void lookTowardYaw(AIPlayerBot bot, float yaw) {
        bot.setYaw(yaw);
        bot.setPitch(0.0F);
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = 0.0F;
        bot.lastBodyYaw = yaw;
        bot.lastHeadYaw = yaw;
    }

    private static float yawFrom(Vec3d movement) {
        return (float) (MathHelper.atan2(movement.z, movement.x) * 57.2957763671875D) - 90.0F;
    }
}
