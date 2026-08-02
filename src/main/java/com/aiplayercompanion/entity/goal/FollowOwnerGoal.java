package com.aiplayercompanion.entity.goal;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.AIPlayerEntity;
import com.aiplayercompanion.service.CompanionLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.EnumSet;

public class FollowOwnerGoal extends Goal {
    private final AIPlayerEntity companion;
    private final double speed;
    private int updateCountdownTicks;
    private long lastTeleportFailLogTick;

    public FollowOwnerGoal(AIPlayerEntity companion, double speed) {
        this.companion = companion;
        this.speed = speed;
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        ServerPlayerEntity owner = companion.getOwnerPlayer();
        if (companion.isFleeing()) {
            return false;
        }
        if (owner == null || companion.getCompanionState() != AIPlayerEntity.CompanionState.FOLLOWING) {
            return false;
        }
        if (owner.getWorld() != companion.getWorld()) {
            return false;
        }
        return companion.squaredDistanceTo(owner) > squared(ModConfig.get().startFollowDistance);
    }

    @Override
    public boolean shouldContinue() {
        ServerPlayerEntity owner = companion.getOwnerPlayer();
        if (companion.isFleeing()) {
            return false;
        }
        if (owner == null || companion.getCompanionState() != AIPlayerEntity.CompanionState.FOLLOWING) {
            return false;
        }
        if (owner.getWorld() != companion.getWorld()) {
            return false;
        }
        return companion.squaredDistanceTo(owner) > squared(ModConfig.get().stopFollowDistance);
    }

    @Override
    public void stop() {
        companion.getNavigation().stop();
        companion.setSprinting(false);
    }

    @Override
    public void tick() {
        ServerPlayerEntity owner = companion.getOwnerPlayer();
        if (owner == null || owner.getWorld() != companion.getWorld()) {
            companion.getNavigation().stop();
            return;
        }

        companion.getLookControl().lookAt(owner, 10.0F, companion.getMaxLookPitchChange());
        double distanceSq = companion.squaredDistanceTo(owner);
        double stopDistance = ModConfig.get().stopFollowDistance;
        if (distanceSq <= squared(stopDistance)) {
            companion.getNavigation().stop();
            companion.setSprinting(false);
            return;
        }

        double teleportDistance = ModConfig.get().teleportDistance;
        if (distanceSq > squared(teleportDistance)) {
            tryTeleportNear(owner);
            return;
        }

        if (--updateCountdownTicks <= 0) {
            updateCountdownTicks = 10;
            boolean shouldSprint = distanceSq > squared(ModConfig.get().sprintFollowDistance);
            companion.setSprinting(shouldSprint);
            companion.getNavigation().startMovingTo(owner, shouldSprint ? speed * 1.35D : speed);
        }
    }

    private void tryTeleportNear(ServerPlayerEntity owner) {
        if (!(companion.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockPos ownerPos = owner.getBlockPos();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) < 2 && Math.abs(z) < 2) {
                    continue;
                }
                BlockPos candidate = ownerPos.add(x, 0, z);
                BlockPos safe = findSafeLanding(serverWorld, candidate);
                if (safe != null) {
                    companion.requestTeleport(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
                    companion.getNavigation().stop();
                    CompanionLog.player(owner, "TELEPORT", "companion teleported near owner to " + safe.toShortString());
                    return;
                }
            }
        }
        long time = companion.getWorld().getTime();
        if (time - lastTeleportFailLogTick > 100L) {
            lastTeleportFailLogTick = time;
            CompanionLog.player(owner, "TELEPORT", "failed to find safe landing position near owner");
        }
    }

    private BlockPos findSafeLanding(WorldView world, BlockPos near) {
        int minY = world.getBottomY() + 1;
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 3);
        for (int y = maxY; y >= Math.max(minY, near.getY() - 4); y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isSafeStandingPosition(world, feet)) {
                return feet;
            }
        }
        return null;
    }

    private boolean isSafeStandingPosition(WorldView world, BlockPos feet) {
        BlockPos below = feet.down();
        BlockState floor = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (!floor.isSolidBlock(world, below) || isDangerous(floor) || isDangerous(feetState) || isDangerous(headState)) {
            return false;
        }
        if (!feetState.getCollisionShape(world, feet).isEmpty() || !headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }

        Box box = Box.of(Vec3d.ofBottomCenter(feet), companion.getWidth(), companion.getHeight(), companion.getWidth());
        return companion.getWorld().isSpaceEmpty(companion, box);
    }

    private boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    private double squared(double value) {
        return value * value;
    }
}
