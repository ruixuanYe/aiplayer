package com.aiplayercompanion.pathing;

import net.minecraft.util.math.BlockPos;

public record PathStep(BlockPos pos, MovementType movementType, BlockPos interactionPos) {
    public static PathStep walk(BlockPos pos) {
        return new PathStep(pos.toImmutable(), MovementType.WALK, null);
    }

    public static PathStep stepUp(BlockPos pos) {
        return new PathStep(pos.toImmutable(), MovementType.STEP_UP, null);
    }

    public static PathStep drop(BlockPos pos) {
        return new PathStep(pos.toImmutable(), MovementType.DROP, null);
    }

    public static PathStep openDoor(BlockPos pos, BlockPos interactionPos) {
        return new PathStep(pos.toImmutable(), MovementType.OPEN_DOOR, interactionPos == null ? null : interactionPos.toImmutable());
    }
}
