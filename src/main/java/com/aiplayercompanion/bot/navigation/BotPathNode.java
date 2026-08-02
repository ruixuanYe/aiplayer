package com.aiplayercompanion.bot.navigation;

import net.minecraft.util.math.BlockPos;

public record BotPathNode(BlockPos pos, NodeType type) {
    public enum NodeType {
        WALK,
        STEP_UP,
        STEP_DOWN,
        JUMP,
        CLIMB,
        DOOR
    }
}
