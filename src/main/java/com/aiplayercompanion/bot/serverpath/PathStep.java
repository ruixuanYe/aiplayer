package com.aiplayercompanion.bot.serverpath;

import net.minecraft.util.math.BlockPos;

public record PathStep(BlockPos pos, PathStepType type) {
}
