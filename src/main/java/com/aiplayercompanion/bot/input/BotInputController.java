package com.aiplayercompanion.bot.input;

import com.aiplayercompanion.bot.AIPlayerBot;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BotInputController {
    private static final double REACHED_SQ = 1.0D;
    private static final double STUCK_EPSILON_SQ = 0.0025D;
    private static final int STUCK_JUMP_TICKS = 8;
    private final Map<UUID, MoveMemory> memories = new HashMap<>();

    public void tickMoveToward(AIPlayerBot bot, Entity target, double stopDistance, boolean sprint) {
        tickMoveToward(bot, target.getPos(), stopDistance, sprint);
    }

    public void tickMoveToward(AIPlayerBot bot, Vec3d target, double stopDistance, boolean sprint) {
        enforceSurvivalBody(bot);
        long now = bot.getWorld().getTime();
        if (shouldYieldToVanillaPhysics(bot, now)) {
            stopInputs(bot);
            return;
        }

        double distanceSq = bot.getPos().squaredDistanceTo(target);
        if (distanceSq <= stopDistance * stopDistance) {
            stop(bot);
            return;
        }

        Vec3d delta = target.subtract(bot.getPos());
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        if (horizontal.lengthSquared() <= REACHED_SQ) {
            stop(bot);
            return;
        }

        MoveMemory memory = memories.get(bot.getUuid());
        boolean stuck = memory != null
                && now - memory.tick <= 12L
                && bot.getPos().squaredDistanceTo(memory.position) < STUCK_EPSILON_SQ;

        float yaw = yawFrom(horizontal);
        lookToward(bot, yaw, 0.0F);
        bot.setSneaking(false);
        bot.setSprinting(sprint && bot.isOnGround());
        bot.setMovementSpeed(sprint ? 0.135F : 0.1F);
        bot.setPlayerInput(new PlayerInput(true, false, false, false, false, false, sprint && bot.isOnGround()));
        openDoorAhead(bot, horizontal);

        boolean shouldJump = shouldJump(bot, horizontal) || (stuck && now - bot.getLastJumpAttemptTick() > STUCK_JUMP_TICKS);
        bot.setJumping(shouldJump);
        if (shouldJump) {
            bot.setPlayerInput(new PlayerInput(true, false, false, false, true, false, sprint && bot.isOnGround()));
        }
        if (shouldJump && bot.isOnGround()) {
            bot.jump();
            bot.markJumpAttempt(now);
        }

        memories.put(bot.getUuid(), new MoveMemory(bot.getPos(), now));
    }

    public void stop(AIPlayerBot bot) {
        memories.remove(bot.getUuid());
        stopInputs(bot);
    }

    public void stopInputs(AIPlayerBot bot) {
        bot.setJumping(false);
        bot.setSprinting(false);
        bot.setPlayerInput(PlayerInput.DEFAULT);
        bot.setMovementSpeed(0.0F);
    }

    public void tickVanillaPhysics(AIPlayerBot bot) {
        enforceSurvivalBody(bot);
        stopInputs(bot);
    }

    public void lookAt(AIPlayerBot bot, Entity entity, float fallbackYaw) {
        Vec3d eyeDelta = entity.getEyePos().subtract(bot.getEyePos());
        double horizontal = Math.sqrt(eyeDelta.x * eyeDelta.x + eyeDelta.z * eyeDelta.z);
        float yaw = horizontal > 0.001D
                ? (float) (MathHelper.atan2(eyeDelta.z, eyeDelta.x) * 57.2957763671875D) - 90.0F
                : fallbackYaw;
        float pitch = (float) -(MathHelper.atan2(eyeDelta.y, horizontal) * 57.2957763671875D);
        lookToward(bot, yaw, MathHelper.clamp(pitch, -60.0F, 60.0F));
    }

    public void idleLook(AIPlayerBot bot, ServerPlayerEntity owner, long now) {
        if (isOwnerLookingAtBot(owner, bot)) {
            lookAt(bot, owner, bot.getYaw());
            return;
        }
        if (bot.isLookingAround(now)) {
            lookToward(bot, bot.getLookAroundYaw(), 0.0F);
            return;
        }
        if (now % 90L == Math.floorMod(bot.getUuid().getLeastSignificantBits(), 90L)) {
            float yaw = (float) MathHelper.wrapDegrees(bot.getYaw() + 70.0F + Math.floorMod(now * 31L + bot.getUuid().getMostSignificantBits(), 120L));
            bot.startLookingAround(now, 45);
            lookToward(bot, yaw, 0.0F);
        }
    }

    public void lookToward(AIPlayerBot bot, float yaw, float pitch) {
        bot.setYaw(yaw);
        bot.setPitch(pitch);
        bot.setBodyYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.lastYaw = yaw;
        bot.lastPitch = pitch;
        bot.lastBodyYaw = yaw;
        bot.lastHeadYaw = yaw;
    }

    public void enforceSurvivalBody(AIPlayerBot bot) {
        bot.noClip = false;
        bot.setNoGravity(false);
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
    }

    private boolean shouldJump(AIPlayerBot bot, Vec3d horizontal) {
        if (!bot.isOnGround() || horizontal.lengthSquared() < 0.001D) {
            return false;
        }
        Vec3d dir = horizontal.normalize();
        ServerWorld world = bot.getWorld();
        BlockPos frontFeet = BlockPos.ofFloored(bot.getX() + dir.x * 0.62D, bot.getY(), bot.getZ() + dir.z * 0.62D);
        BlockState front = world.getBlockState(frontFeet);
        BlockState frontHead = world.getBlockState(frontFeet.up());
        BlockState aboveHead = world.getBlockState(bot.getBlockPos().up(2));
        Box landing = bot.getBoundingBox().offset(dir.x * 0.7D, 0.45D, dir.z * 0.7D);
        return !front.getCollisionShape(world, frontFeet).isEmpty()
                && frontHead.getCollisionShape(world, frontFeet.up()).isEmpty()
                && aboveHead.getCollisionShape(world, bot.getBlockPos().up(2)).isEmpty()
                && world.isSpaceEmpty(bot, landing);
    }

    private void openDoorAhead(AIPlayerBot bot, Vec3d horizontal) {
        if (horizontal.lengthSquared() < 0.001D) {
            return;
        }
        Vec3d dir = horizontal.normalize();
        ServerWorld world = bot.getWorld();
        BlockPos base = BlockPos.ofFloored(bot.getX() + dir.x * 0.75D, bot.getY(), bot.getZ() + dir.z * 0.75D);
        for (BlockPos pos : new BlockPos[]{base, base.up()}) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.OPEN) && !state.get(DoorBlock.OPEN)) {
                world.setBlockState(pos, state.with(DoorBlock.OPEN, true), 10);
                world.playSound(null, pos, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, 0.8F, 1.0F);
            }
        }
    }

    private boolean shouldYieldToVanillaPhysics(AIPlayerBot bot, long now) {
        if (bot.isControlPaused(now) || bot.hurtTime > 0 || bot.timeUntilRegen > 0) {
            return true;
        }
        if (!bot.isOnGround() && !isOnClimbable(bot) && !bot.isTouchingWater()) {
            return true;
        }
        return false;
    }

    private boolean isOnClimbable(AIPlayerBot bot) {
        return bot.getWorld().getBlockState(bot.getBlockPos()).isIn(BlockTags.CLIMBABLE)
                || bot.getWorld().getBlockState(bot.getBlockPos().up()).isIn(BlockTags.CLIMBABLE);
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

    private record MoveMemory(Vec3d position, long tick) {
    }
}
