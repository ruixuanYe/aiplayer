package com.aiplayercompanion.bot.serverpath;

import com.aiplayercompanion.bot.AIPlayerBot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class StuckDetector {
    private static final long STUCK_TICKS = 30L;
    private static final double MIN_PROGRESS_SQ = 0.004D;
    private final Map<UUID, State> states = new HashMap<>();

    boolean isStuck(AIPlayerBot bot, BlockPos goal, long now) {
        State state = states.get(bot.getUuid());
        Vec3d pos = bot.getPos();
        if (state == null || state.goal == null || state.goal.getSquaredDistance(goal) > 4.0D) {
            states.put(bot.getUuid(), new State(pos, goal, now));
            return false;
        }
        if (pos.squaredDistanceTo(state.lastPos) > MIN_PROGRESS_SQ) {
            states.put(bot.getUuid(), new State(pos, goal, now));
            return false;
        }
        if (now - state.sinceTick >= STUCK_TICKS) {
            states.put(bot.getUuid(), new State(pos, goal, now));
            return true;
        }
        return false;
    }

    void clear(AIPlayerBot bot) {
        states.remove(bot.getUuid());
    }

    private record State(Vec3d lastPos, BlockPos goal, long sinceTick) {
    }
}
