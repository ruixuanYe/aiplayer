package com.aiplayercompanion.pathing;

import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class CarpetPathExecutor {
    private static List<PathStep> path = List.of();
    private static int index;
    private static boolean moving;
    private static boolean sprinting;
    private static int jumpCooldown;
    private static int useCooldown;
    private static int stuckTicks;
    private static Vec3d lastPos = Vec3d.ZERO;

    private CarpetPathExecutor() {
    }

    public static void setPath(List<PathStep> newPath) {
        path = List.copyOf(newPath);
        index = 0;
        stuckTicks = 0;
        lastPos = Vec3d.ZERO;
    }

    public static void reset(ServerCommandSource source, ServerPlayerEntity bot) {
        command(source, bot, "stop");
        command(source, bot, "unsprint");
        path = List.of();
        index = 0;
        moving = false;
        sprinting = false;
        jumpCooldown = 0;
        useCooldown = 0;
        stuckTicks = 0;
        lastPos = Vec3d.ZERO;
    }

    public static boolean hasPath() {
        return index < path.size();
    }

    public static ExecutionState tick(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity owner, AIPlayerCleanConfig config) {
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }
        if (useCooldown > 0) {
            useCooldown--;
        }
        if (!hasPath()) {
            stopMoving(source, bot);
            return ExecutionState.DONE;
        }

        PathStep step = path.get(index);
        Vec3d target = Vec3d.ofBottomCenter(step.pos());
        double horizontalSq = horizontalSquaredDistance(bot.getPos(), target);
        double vertical = target.y - bot.getY();
        if (horizontalSq < 0.55 && Math.abs(vertical) < 1.25) {
            index++;
            if (!hasPath()) {
                stopMoving(source, bot);
                return ExecutionState.DONE;
            }
            step = path.get(index);
            target = Vec3d.ofBottomCenter(step.pos());
        }

        if (step.movementType() == MovementType.OPEN_DOOR && step.interactionPos() != null && useCooldown == 0) {
            face(bot, Vec3d.ofCenter(step.interactionPos()));
            command(source, bot, "use once");
            useCooldown = 8;
        } else {
            face(bot, target);
        }

        double ownerDistance = bot.distanceTo(owner);
        setSprint(source, bot, ownerDistance >= config.sprintDistance);
        if (!moving) {
            command(source, bot, "move forward");
            moving = true;
        }

        Vec3d current = bot.getPos();
        if (lastPos != Vec3d.ZERO && current.squaredDistanceTo(lastPos) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = current;

        if ((step.movementType() == MovementType.STEP_UP || bot.horizontalCollision) && jumpCooldown == 0) {
            command(source, bot, "jump once");
            jumpCooldown = 7;
        }

        return stuckTicks >= 8 ? ExecutionState.STUCK : ExecutionState.RUNNING;
    }

    private static void stopMoving(ServerCommandSource source, ServerPlayerEntity bot) {
        if (moving || sprinting) {
            command(source, bot, "stop");
            command(source, bot, "unsprint");
        }
        moving = false;
        sprinting = false;
    }

    private static void setSprint(ServerCommandSource source, ServerPlayerEntity bot, boolean shouldSprint) {
        if (shouldSprint == sprinting) {
            return;
        }
        command(source, bot, shouldSprint ? "sprint" : "unsprint");
        sprinting = shouldSprint;
    }

    private static double horizontalSquaredDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static void face(ServerPlayerEntity bot, Vec3d target) {
        Vec3d delta = target.subtract(bot.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (MathHelper.atan2(delta.z, delta.x) * 57.2957763671875) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(delta.y, horizontal) * 57.2957763671875));
        bot.setYaw(yaw);
        bot.setPitch(pitch);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
    }

    private static void command(ServerCommandSource source, ServerPlayerEntity bot, String action) {
        CarpetAIPlayerManager.executeCarpetCommand(source, "player " + bot.getName().getString() + " " + action);
    }

    public enum ExecutionState {
        RUNNING,
        DONE,
        STUCK
    }
}
