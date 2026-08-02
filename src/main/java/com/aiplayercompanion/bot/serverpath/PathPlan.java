package com.aiplayercompanion.bot.serverpath;

import net.minecraft.util.math.BlockPos;

import java.util.List;

public record PathPlan(BlockPos goal, List<PathStep> steps) {
    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
