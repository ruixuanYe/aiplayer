package com.aiplayercompanion.bot;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.service.CompanionLog;
import com.aiplayercompanion.service.AIPlayerManager;
import com.aiplayercompanion.util.ModelNameUtil;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.WorldView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AIPlayerBotManager {
    private static final long RESPAWN_RETRY_TICKS = 60L;
    private static final long DEATH_RESPAWN_DELAY_TICKS = 60L;
    private static final Map<UUID, PendingRespawn> PENDING_RESPAWNS = new HashMap<>();

    private AIPlayerBotManager() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(AIPlayerBotManager::tickBots);
    }

    public static Optional<AIPlayerBot> findOwnedBot(ServerPlayerEntity owner) {
        for (ServerPlayerEntity player : owner.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof AIPlayerBot bot && bot.getOwnerUuid().equals(owner.getUuid())) {
                return Optional.of(bot);
            }
        }

        String uuidText = ModConfig.get().botPlayerUuid;
        if (uuidText == null || uuidText.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID botUuid = UUID.fromString(uuidText);
            ServerPlayerEntity player = owner.getServer().getPlayerManager().getPlayer(botUuid);
            if (player instanceof AIPlayerBot bot && bot.getOwnerUuid().equals(owner.getUuid())) {
                return Optional.of(bot);
            }
        } catch (IllegalArgumentException ignored) {
            ModConfig.get().botPlayerUuid = "";
            ModConfig.save();
        }
        return Optional.empty();
    }

    public static AIPlayerBot spawnFor(ServerPlayerEntity owner) {
        PENDING_RESPAWNS.remove(owner.getUuid());
        removeOwnedBots(owner);
        return spawnFor(owner, owner.getPos().add(1.5D, 0.0D, 1.5D));
    }

    private static AIPlayerBot spawnFor(ServerPlayerEntity owner, Vec3d position) {
        MinecraftServer server = owner.getServer();
        ServerWorld world = owner.getWorld();
        AIPlayerManager.findOwnedCompanion(owner).ifPresent(entity -> {
            entity.discard();
            CompanionLog.player(owner, "BOT", "removed legacy companion entity before player bot spawn");
        });
        UUID botUuid = configuredOrNewBotUuid(owner);
        String botName = botPlayerName();
        GameProfile profile = new GameProfile(botUuid, botName);
        AIPlayerBot bot = new AIPlayerBot(server, world, profile, owner.getUuid());
        bot.setBotState(readState());
        reviveVisualState(bot);
        bot.refreshPositionAndAngles(position.x, position.y, position.z, owner.getYaw(), 0.0F);
        ModConfig.get().botPlayerUuid = botUuid.toString();
        ModConfig.rememberOwner(owner.getName().getString(), owner.getUuidAsString());
        ModConfig.save();
        try {
            server.getPlayerManager().onPlayerConnect(new DummyClientConnection(), bot, ConnectedClientData.createDefault(profile, false));
            bot.installFakeNetworkHandler();
        } catch (Throwable throwable) {
            ServerPlayerEntity listed = server.getPlayerManager().getPlayer(botUuid);
            if (!(listed instanceof AIPlayerBot)) {
                ModConfig.get().botPlayerUuid = "";
                ModConfig.save();
                throw throwable;
            }
            AIPlayerCompanionMod.LOGGER.warn("Player bot connect completed with a recoverable sync error: {}", throwable.toString());
            bot.installFakeNetworkHandler();
        }
        reviveVisualState(bot);
        bot.refreshPositionAndAngles(position.x, position.y, position.z, owner.getYaw(), 0.0F);
        CompanionLog.player(owner, "BOT", "spawned player bot " + botName);
        return bot;
    }

    private static void removeOwnedBots(ServerPlayerEntity owner) {
        MinecraftServer server = owner.getServer();
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (player instanceof AIPlayerBot bot && bot.getOwnerUuid().equals(owner.getUuid())) {
                AIPlayerBotController.discardNavigationProxy(bot);
                server.getPlayerManager().remove(bot);
                bot.remove(Entity.RemovalReason.DISCARDED);
            }
        }
    }

    public static void remove(ServerPlayerEntity owner, AIPlayerBot bot) {
        AIPlayerBotController.discardNavigationProxy(bot);
        owner.getServer().getPlayerManager().remove(bot);
        bot.remove(Entity.RemovalReason.DISCARDED);
        ModConfig.get().botPlayerUuid = "";
        ModConfig.save();
        CompanionLog.player(owner, "BOT", "removed player bot");
    }

    public static void setState(ServerPlayerEntity owner, AIPlayerBot.BotState state) {
        findOwnedBot(owner).ifPresent(bot -> {
            bot.setBotState(state);
            ModConfig.get().botState = state.name();
            ModConfig.save();
        });
    }

    public static AIPlayerBot respawnFor(ServerPlayerEntity owner, String reason) {
        findOwnedBot(owner).ifPresent(bot -> {
            owner.getServer().getPlayerManager().remove(bot);
            bot.discard();
        });
        Optional<Vec3d> safe = findSafePositionNearOwner(owner);
        if (safe.isEmpty()) {
            PENDING_RESPAWNS.put(owner.getUuid(), new PendingRespawn(reason, owner.getWorld().getTime() - RESPAWN_RETRY_TICKS + 1L, false));
            owner.sendMessage(Text.literal(ModelNameUtil.companionName() + " 暂时找不到安全复活点，会继续尝试。").formatted(Formatting.YELLOW), false);
            CompanionLog.player(owner, "BOT", "respawn delayed: no safe position");
            return null;
        }
        ModConfig.get().botState = AIPlayerBot.BotState.FOLLOWING.name();
        ModConfig.get().botPlayerUuid = "";
        ModConfig.save();
        AIPlayerBot bot = spawnFor(owner, safe.get());
        reviveVisualState(bot);
        owner.sendMessage(Text.literal(bot.getName().getString() + " 已复活并回到你身边。").formatted(Formatting.GREEN), false);
        CompanionLog.player(owner, "BOT", "respawned player bot: " + reason);
        return bot;
    }

    public static boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        boolean teleported = AIPlayerBotController.teleportNearOwner(owner, bot, feedback);
        CompanionLog.player(owner, "BOT", teleported ? "teleported bot near owner" : "teleport failed: no safe position");
        return teleported;
    }

    private static boolean unusedLegacyTeleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        Optional<Vec3d> safe = findSafePositionNearOwner(owner);
        if (safe.isEmpty()) {
            owner.sendMessage(Text.literal(bot.getName().getString() + ": no safe teleport position near you.").formatted(Formatting.YELLOW), false);
            CompanionLog.player(owner, "BOT", "teleport failed: no safe position");
            return false;
        }
        Vec3d position = safe.get();
        if (bot.getWorld() != owner.getWorld()) {
            bot.teleport(owner.getWorld(), position.x, position.y, position.z, Set.<PositionFlag>of(), owner.getYaw(), owner.getPitch(), true);
        } else {
            bot.requestTeleport(position.x, position.y, position.z);
        }
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityModified = true;
        owner.sendMessage(Text.literal(bot.getName().getString() + (feedback ? "：距离太远了，我传送到你附近。" : "：我过来了。")).formatted(Formatting.GREEN), false);
        CompanionLog.player(owner, "BOT", "teleported bot near owner");
        return true;
    }

    private static void tickBots(MinecraftServer server) {
        tickPendingRespawns(server);
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (!(player instanceof AIPlayerBot bot)) {
                continue;
            }
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(bot.getOwnerUuid());
            if (owner == null) {
                bot.setSneaking(true);
                bot.setSprinting(false);
                continue;
            }
            if (bot.isDead() || !bot.isAlive() || bot.getHealth() <= 0.0F) {
                AIPlayerBotController.discardNavigationProxy(bot);
                owner.getServer().getPlayerManager().remove(bot);
                bot.discard();
                PENDING_RESPAWNS.put(owner.getUuid(), new PendingRespawn("death detected", owner.getWorld().getTime() - RESPAWN_RETRY_TICKS + DEATH_RESPAWN_DELAY_TICKS, false));
                owner.sendMessage(Text.literal(bot.getName().getString() + "：我倒下了，等一下会找安全位置回来。").formatted(Formatting.RED), false);
                CompanionLog.player(owner, "BOT", "bot died, respawn scheduled");
                continue;
            }
            AIPlayerBotController.tick(bot, owner);
        }
    }

    private static void tickPendingRespawns(MinecraftServer server) {
        if (PENDING_RESPAWNS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingRespawn> entry : new ArrayList<>(PENDING_RESPAWNS.entrySet())) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            long now = owner.getWorld().getTime();
            PendingRespawn pending = entry.getValue();
            if (now - pending.lastAttemptTick() < RESPAWN_RETRY_TICKS) {
                continue;
            }
            Optional<Vec3d> safe = findSafePositionNearOwner(owner);
            if (safe.isEmpty()) {
                PENDING_RESPAWNS.put(entry.getKey(), new PendingRespawn(pending.reason(), now, pending.notified()));
                if (!pending.notified()) {
                    owner.sendMessage(Text.literal(ModelNameUtil.companionName() + " 暂时找不到安全复活点，会继续尝试。").formatted(Formatting.YELLOW), false);
                    PENDING_RESPAWNS.put(entry.getKey(), new PendingRespawn(pending.reason(), now, true));
                }
                CompanionLog.player(owner, "BOT", "respawn retry failed: no safe position");
                continue;
            }
            PENDING_RESPAWNS.remove(entry.getKey());
            ModConfig.get().botState = AIPlayerBot.BotState.FOLLOWING.name();
            ModConfig.get().botPlayerUuid = "";
            ModConfig.save();
            AIPlayerBot bot = spawnFor(owner, safe.get());
            reviveVisualState(bot);
            owner.sendMessage(Text.literal(bot.getName().getString() + " 找到安全位置并复活了。").formatted(Formatting.GREEN), false);
            CompanionLog.player(owner, "BOT", "respawn retry succeeded: " + pending.reason());
        }
    }

    private static AIPlayerBot.BotState readState() {
        try {
            return AIPlayerBot.BotState.valueOf(ModConfig.get().botState);
        } catch (IllegalArgumentException e) {
            return AIPlayerBot.BotState.FOLLOWING;
        }
    }

    private static UUID configuredOrNewBotUuid(ServerPlayerEntity owner) {
        String uuidText = ModConfig.get().botPlayerUuid;
        if (uuidText != null && !uuidText.isBlank()) {
            try {
                return UUID.fromString(uuidText);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(("aiplayer:" + owner.getUuidAsString() + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    }

    private static String botPlayerName() {
        return ModelNameUtil.botPlayerName();
    }

    private static Optional<Vec3d> findSafePositionNearOwner(ServerPlayerEntity owner) {
        ServerWorld world = owner.getWorld();
        BlockPos ownerPos = owner.getBlockPos();
        for (int radius = 3; radius <= 7; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    BlockPos safe = findSafeLanding(world, ownerPos.add(x, 0, z));
                    if (safe != null) {
                        return Optional.of(Vec3d.ofBottomCenter(safe));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static BlockPos findSafeLanding(WorldView world, BlockPos near) {
        return findLanding(world, near, false);
    }

    private static BlockPos findWalkableLanding(WorldView world, BlockPos near) {
        return findLanding(world, near, true);
    }

    private static BlockPos findGroundLanding(WorldView world, BlockPos near, int verticalRange) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - verticalRange);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, false)) {
                return feet;
            }
        }
        return null;
    }

    private static BlockPos findLanding(WorldView world, BlockPos near, boolean allowClosedDoors) {
        int minY = world.getBottomY() + 1;
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 5);
        for (int y = maxY; y >= Math.max(minY, near.getY() - 6); y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isWalkablePosition(world, feet, allowClosedDoors)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isSafeStandingPosition(WorldView world, BlockPos feet) {
        return isWalkablePosition(world, feet, false);
    }

    private static boolean isWalkablePosition(WorldView world, BlockPos feet, boolean allowClosedDoors) {
        BlockPos below = feet.down();
        BlockState floor = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (floor.getCollisionShape(world, below).isEmpty() || isDangerous(floor) || isDangerous(feetState) || isDangerous(headState)) {
            return false;
        }
        if (!isPassableForBot(world, feet, feetState, allowClosedDoors)
                || !isPassableForBot(world, feet.up(), headState, allowClosedDoors)) {
            return false;
        }

        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        return allowClosedDoors || ((ServerWorld) world).isSpaceEmpty(null, box);
    }

    private static boolean isPassableForBot(WorldView world, BlockPos pos, BlockState state, boolean allowClosedDoors) {
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return allowClosedDoors && canOpenDoor(state);
    }

    private static boolean canOpenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.isIn(BlockTags.WOODEN_DOORS)
                && state.contains(DoorBlock.OPEN)
                && !state.get(DoorBlock.OPEN);
    }

    private static boolean isClimbable(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE);
    }

    private static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA);
    }

    private static void reviveVisualState(AIPlayerBot bot) {
        bot.changeGameMode(GameMode.SURVIVAL);
        bot.noClip = false;
        bot.setNoGravity(false);
        bot.getAbilities().invulnerable = false;
        bot.getAbilities().creativeMode = false;
        bot.getAbilities().allowFlying = false;
        bot.getAbilities().flying = false;
        bot.sendAbilitiesUpdate();
        bot.deathTime = 0;
        bot.hurtTime = 0;
        bot.maxHurtTime = 0;
        bot.setHealth(bot.getMaxHealth());
        bot.setPose(EntityPose.STANDING);
        bot.setSneaking(false);
        bot.setSprinting(false);
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityModified = true;
    }

    private record PendingRespawn(String reason, long lastAttemptTick, boolean notified) {
    }

    private record PathNode(BlockPos pos, double cost, double score) {
    }
}
