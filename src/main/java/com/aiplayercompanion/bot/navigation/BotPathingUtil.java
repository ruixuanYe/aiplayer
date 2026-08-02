package com.aiplayercompanion.bot.navigation;

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

public final class BotPathingUtil {
    public static final double BOT_WIDTH = 0.6D;
    public static final double BOT_HEIGHT = 1.8D;

    private BotPathingUtil() {
    }

    public static Optional<BlockPos> findGroundLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 3);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, false)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> findWalkableLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 3);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, true)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> nearestSafeGround(ServerWorld world, BlockPos center, int radius) {
        return nearestSafeGround(world, center, 0, radius);
    }

    public static Optional<BlockPos> nearestSafeGround(ServerWorld world, BlockPos center, int minRadius, int radius) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = Math.max(0, minRadius); r <= radius; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (r > 0 && Math.abs(x) != r && Math.abs(z) != r) {
                        continue;
                    }
                    Optional<BlockPos> safe = findGroundLanding(world, center.add(x, 0, z), 32);
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

    public static boolean isWalkablePosition(WorldView world, BlockPos feet, boolean allowClosedDoors) {
        BlockPos below = feet.down();
        BlockState floor = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (floor.getCollisionShape(world, below).isEmpty()
                || isDangerous(floor)
                || isDangerous(feetState)
                || isDangerous(headState)) {
            return false;
        }
        if (!isPassableForBot(world, feet, feetState, allowClosedDoors)
                || !isPassableForBot(world, feet.up(), headState, allowClosedDoors)) {
            return false;
        }
        if (allowClosedDoors) {
            return true;
        }
        if (!(world instanceof ServerWorld serverWorld)) {
            return true;
        }
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - 0.3D, y, z - 0.3D, x + 0.3D, y + BOT_HEIGHT, z + 0.3D);
        return serverWorld.isSpaceEmpty(null, box);
    }

    public static boolean isPassableForBot(WorldView world, BlockPos pos, BlockState state, boolean allowClosedDoors) {
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

    public static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA);
    }
}
