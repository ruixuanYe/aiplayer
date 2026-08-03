package com.aiplayercompanion.navigation;

import com.aiplayercompanion.carpet.CarpetAIPlayerManager;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

public final class CarpetFollowController {
    private static final int TICK_INTERVAL = 5;
    private static long lastTick;
    private static boolean moving;
    private static boolean sprinting;
    private static int stuckTicks;
    private static int useCooldown;
    private static Vec3d lastPos = Vec3d.ZERO;

    private CarpetFollowController() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(CarpetFollowController::tick);
    }

    public static int follow(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("这个 AIPlayer 不属于你，不能控制。").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "FOLLOWING";
        AIPlayerCleanConfig.save();
        resetMotionState();
        owner.sendMessage(Text.literal("AIPlayer 开始跟随你。").formatted(Formatting.GREEN), false);
        tickNow(source.getServer());
        return 1;
    }

    public static int stop(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("这个 AIPlayer 不属于你，不能控制。").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "WAITING";
        AIPlayerCleanConfig.save();
        findBot(source.getServer()).ifPresent(bot -> stopAll(source, bot));
        owner.sendMessage(Text.literal("AIPlayer 正在原地等待。").formatted(Formatting.YELLOW), false);
        return 1;
    }

    public static int come(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("这个 AIPlayer 不属于你，不能控制。").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "FOLLOWING";
        AIPlayerCleanConfig.save();
        resetMotionState();
        owner.sendMessage(Text.literal("AIPlayer 正在过来。").formatted(Formatting.GREEN), false);
        tickNow(source.getServer());
        return 1;
    }

    public static void stopAll(ServerCommandSource source, ServerPlayerEntity bot) {
        command(source, bot, "stop");
        command(source, bot, "unsprint");
        moving = false;
        sprinting = false;
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() - lastTick < TICK_INTERVAL) {
            return;
        }
        lastTick = server.getTicks();
        if (useCooldown > 0) {
            useCooldown--;
        }
        tickNow(server);
    }

    private static void tickNow(MinecraftServer server) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        Optional<ServerPlayerEntity> botOpt = findBot(server);
        if (botOpt.isEmpty()) {
            resetMotionState();
            return;
        }

        ServerPlayerEntity bot = botOpt.get();
        ServerCommandSource source = bot.getCommandSource();
        if ("WAITING".equals(config.behaviorMode)) {
            stopAll(source, bot);
            return;
        }

        ServerPlayerEntity owner = findOwner(server, config);
        if (owner == null || owner.getWorld() != bot.getWorld()) {
            stopAll(source, bot);
            return;
        }

        double distance = bot.distanceTo(owner);
        if (distance > config.teleportDistance) {
            stopAll(source, bot);
            owner.sendMessage(Text.literal("AIPlayer 距离过远，等待后续安全传送模块处理。").formatted(Formatting.YELLOW), false);
            return;
        }

        face(bot, owner.getEyePos());
        if (distance <= config.stopFollowDistance) {
            stopAll(source, bot);
            return;
        }

        if (distance >= config.startFollowDistance) {
            boolean shouldSprint = distance >= config.sprintDistance;
            setSprint(source, bot, shouldSprint);
            openDoorIfNeeded(source, bot);
            jumpIfStuckOrBlocked(source, bot);
            if (!moving) {
                command(source, bot, "move forward");
                moving = true;
            }
        }
    }

    private static Optional<ServerPlayerEntity> findBot(MinecraftServer server) {
        return CarpetAIPlayerManager.findManaged(server, AIPlayerCleanConfig.get());
    }

    private static ServerPlayerEntity findOwner(MinecraftServer server, AIPlayerCleanConfig config) {
        if (config.ownerUuid == null || config.ownerUuid.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerManager().getPlayer(UUID.fromString(config.ownerUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isOwner(ServerPlayerEntity owner, AIPlayerCleanConfig config) {
        return config.ownerUuid == null
                || config.ownerUuid.isBlank()
                || owner.getUuidAsString().equals(config.ownerUuid);
    }

    private static void setSprint(ServerCommandSource source, ServerPlayerEntity bot, boolean shouldSprint) {
        if (shouldSprint == sprinting) {
            return;
        }
        command(source, bot, shouldSprint ? "sprint" : "unsprint");
        sprinting = shouldSprint;
    }

    private static void jumpIfStuckOrBlocked(ServerCommandSource source, ServerPlayerEntity bot) {
        Vec3d current = bot.getPos();
        double moved = current.squaredDistanceTo(lastPos);
        lastPos = current;
        if (moving && moved < 0.006) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        if (bot.horizontalCollision || stuckTicks >= 3) {
            command(source, bot, "jump once");
            stuckTicks = 0;
        }
    }

    private static void openDoorIfNeeded(ServerCommandSource source, ServerPlayerEntity bot) {
        if (useCooldown > 0) {
            return;
        }
        BlockPos ahead = bot.getBlockPos().offset(bot.getHorizontalFacing());
        if (isDoorLike(bot.getWorld().getBlockState(ahead)) || isDoorLike(bot.getWorld().getBlockState(ahead.up()))) {
            command(source, bot, "use once");
            useCooldown = 8;
        }
    }

    private static boolean isDoorLike(BlockState state) {
        Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof TrapdoorBlock)
                && state.contains(Properties.OPEN)
                && !state.get(Properties.OPEN);
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

    private static void resetMotionState() {
        moving = false;
        sprinting = false;
        stuckTicks = 0;
        lastPos = Vec3d.ZERO;
        useCooldown = 0;
    }
}
