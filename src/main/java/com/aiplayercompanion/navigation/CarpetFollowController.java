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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

public final class CarpetFollowController {
    private static final int TICK_INTERVAL = 5;
    private static final int MAX_JUMP_ATTEMPTS = 6;

    private static long lastTick;
    private static boolean moving;
    private static boolean sprinting;
    private static int stuckTicks;
    private static int jumpAttempts;
    private static int useCooldown;
    private static int teleportCooldown;
    private static Vec3d lastPos = Vec3d.ZERO;

    private CarpetFollowController() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(CarpetFollowController::tick);
    }

    public static int follow(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("AIPlayer: only the owner can control this bot.").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "FOLLOWING";
        AIPlayerCleanConfig.save();
        resetMotionState();
        owner.sendMessage(Text.literal("AIPlayer is following you.").formatted(Formatting.GREEN), false);
        tickNow(source.getServer());
        return 1;
    }

    public static int stop(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("AIPlayer: only the owner can control this bot.").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "WAITING";
        AIPlayerCleanConfig.save();
        findBot(source.getServer()).ifPresent(bot -> stopAll(source, bot));
        owner.sendMessage(Text.literal("AIPlayer is waiting here.").formatted(Formatting.YELLOW), false);
        return 1;
    }

    public static int come(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("AIPlayer: only the owner can control this bot.").formatted(Formatting.RED), false);
            return 0;
        }
        config.behaviorMode = "FOLLOWING";
        AIPlayerCleanConfig.save();
        resetMotionState();
        owner.sendMessage(Text.literal("AIPlayer is coming.").formatted(Formatting.GREEN), false);
        tickNow(source.getServer());
        return 1;
    }

    public static int teleport(ServerCommandSource source, ServerPlayerEntity owner) {
        AIPlayerCleanConfig config = AIPlayerCleanConfig.get();
        if (!isOwner(owner, config)) {
            owner.sendMessage(Text.literal("AIPlayer: only the owner can control this bot.").formatted(Formatting.RED), false);
            return 0;
        }
        Optional<ServerPlayerEntity> bot = findBot(source.getServer());
        if (bot.isEmpty()) {
            owner.sendMessage(Text.literal("AIPlayer is not spawned.").formatted(Formatting.YELLOW), false);
            return 0;
        }
        teleportCooldown = 0;
        return tryTeleportNearOwner(source, bot.get(), owner, "requested") ? 1 : 0;
    }

    public static boolean teleportBotNearOwner(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity owner, String reason) {
        teleportCooldown = 0;
        return tryTeleportNearOwner(source, bot, owner, reason);
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
        if (teleportCooldown > 0) {
            teleportCooldown--;
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
            tryTeleportNearOwner(source, bot, owner, "too far");
            return;
        }

        if (tryOpenDoor(source, bot, owner)) {
            return;
        }

        face(bot, owner.getEyePos());
        if (distance <= config.stopFollowDistance) {
            stopAll(source, bot);
            return;
        }

        if (distance >= config.startFollowDistance) {
            setSprint(source, bot, distance >= config.sprintDistance);
            jumpIfStuckOrBlocked(source, bot, owner);
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

    private static void jumpIfStuckOrBlocked(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity owner) {
        Vec3d current = bot.getPos();
        double moved = current.squaredDistanceTo(lastPos);
        lastPos = current;
        if (moving && moved < 0.006) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        if (bot.horizontalCollision || stuckTicks >= 3) {
            jumpAttempts++;
            if (jumpAttempts >= MAX_JUMP_ATTEMPTS) {
                stopAll(source, bot);
                if (tryTeleportNearOwner(source, bot, owner, "stuck")) {
                    jumpAttempts = 0;
                }
                return;
            }
            command(source, bot, "jump once");
            stuckTicks = 0;
        } else if (stuckTicks == 0) {
            jumpAttempts = 0;
        }
    }

    private static boolean tryOpenDoor(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity owner) {
        if (useCooldown > 0) {
            return false;
        }
        BlockPos door = findDoorToOpen(bot, owner);
        if (door == null) {
            return false;
        }
        face(bot, Vec3d.ofCenter(door));
        command(source, bot, "use once");
        useCooldown = 6;
        return true;
    }

    private static BlockPos findDoorToOpen(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        Vec3d delta = owner.getPos().subtract(bot.getPos());
        Direction pathDirection = horizontalDirection(delta);
        Direction facing = bot.getHorizontalFacing();
        Direction[] directions = new Direction[]{pathDirection, facing, facing.rotateYClockwise(), facing.rotateYCounterclockwise()};
        BlockPos base = bot.getBlockPos();
        for (Direction direction : directions) {
            for (int step = 1; step <= 2; step++) {
                BlockPos door = closedDoorAt(bot.getWorld(), base.offset(direction, step));
                if (door != null) {
                    return door;
                }
            }
        }
        return null;
    }

    private static BlockPos closedDoorAt(World world, BlockPos pos) {
        if (isClosedDoorLike(world.getBlockState(pos))) {
            return pos;
        }
        if (isClosedDoorLike(world.getBlockState(pos.up()))) {
            return pos.up();
        }
        return null;
    }

    private static boolean isClosedDoorLike(BlockState state) {
        Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof TrapdoorBlock)
                && state.contains(Properties.OPEN)
                && !state.get(Properties.OPEN);
    }

    private static Direction horizontalDirection(Vec3d delta) {
        if (Math.abs(delta.x) > Math.abs(delta.z)) {
            return delta.x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return delta.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
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

    private static boolean tryTeleportNearOwner(ServerCommandSource source, ServerPlayerEntity bot, ServerPlayerEntity owner, String reason) {
        if (teleportCooldown > 0) {
            return false;
        }
        BlockPos safe = findSafeTeleportPos(bot, owner);
        if (safe == null) {
            owner.sendMessage(Text.literal("AIPlayer " + reason + ", no safe teleport position nearby.").formatted(Formatting.YELLOW), false);
            teleportCooldown = 40;
            return false;
        }
        command(source, bot, "stop");
        command(source, bot, "unsprint");
        bot.requestTeleportAndDismount(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
        owner.sendMessage(Text.literal("AIPlayer " + reason + ", teleported nearby.").formatted(Formatting.GREEN), false);
        resetMotionState();
        teleportCooldown = 60;
        return true;
    }

    private static BlockPos findSafeTeleportPos(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        BlockPos origin = owner.getBlockPos();
        for (int radius = 3; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 2; dy >= -3; dy--) {
                        BlockPos pos = origin.add(dx, dy, dz);
                        if (isSafeStandPos(bot, pos)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSafeStandPos(ServerPlayerEntity bot, BlockPos pos) {
        World world = bot.getWorld();
        BlockState floor = world.getBlockState(pos.down());
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        if (!floor.isSideSolidFullSquare(world, pos.down(), Direction.UP)) {
            return false;
        }
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        Box box = new Box(pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2, pos.getX() + 0.8, pos.getY() + 1.8, pos.getZ() + 0.8);
        return world.isSpaceEmpty(bot, box);
    }

    private static void command(ServerCommandSource source, ServerPlayerEntity bot, String action) {
        CarpetAIPlayerManager.executeCarpetCommand(source, "player " + bot.getName().getString() + " " + action);
    }

    private static void resetMotionState() {
        moving = false;
        sprinting = false;
        stuckTicks = 0;
        jumpAttempts = 0;
        lastPos = Vec3d.ZERO;
        useCooldown = 0;
        teleportCooldown = 0;
    }
}
