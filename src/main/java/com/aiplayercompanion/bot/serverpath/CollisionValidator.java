package com.aiplayercompanion.bot.serverpath;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.WorldView;

import java.util.Optional;

public final class CollisionValidator {
    private static final double WIDTH = 0.6D;
    private static final double HEIGHT = 1.8D;

    private CollisionValidator() {
    }

    public static Optional<BlockPos> findStandable(ServerWorld world, BlockPos near, int verticalRange, boolean allowClosedDoors) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + verticalRange);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (canStandAt(world, feet, allowClosedDoors)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    public static boolean canStandAt(ServerWorld world, BlockPos feet, boolean allowClosedDoors) {
        BlockPos floorPos = feet.down();
        BlockState floor = world.getBlockState(floorPos);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (floor.getCollisionShape(world, floorPos).isEmpty()) {
            return false;
        }
        if (isDangerous(floor) || isDangerous(feetState) || isDangerous(headState)) {
            return false;
        }
        if (!isPassable(world, feet, feetState, allowClosedDoors)
                || !isPassable(world, feet.up(), headState, allowClosedDoors)) {
            return false;
        }
        if (allowClosedDoors) {
            return true;
        }
        return isEntitySpaceEmpty(world, feet);
    }

    public static boolean isEntitySpaceEmpty(ServerWorld world, BlockPos feet) {
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - WIDTH / 2.0D, y, z - WIDTH / 2.0D, x + WIDTH / 2.0D, y + HEIGHT, z + WIDTH / 2.0D);
        return world.isSpaceEmpty(null, box);
    }

    public static boolean isPassable(WorldView world, BlockPos pos, BlockState state, boolean allowClosedDoors) {
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return allowClosedDoors && canOpenDoor(state);
    }

    public static boolean canOpenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.isIn(BlockTags.WOODEN_DOORS)
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN);
    }

    public static boolean isClimbable(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE);
    }

    private static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA);
    }
}
