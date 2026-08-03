package com.aiplayercompanion.fakeplayer;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.config.AIPlayerCleanConfig;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

public final class AIPlayerFakePlayerManager {
    private static final int DEATH_REMOVE_TICKS = 100;

    private AIPlayerFakePlayerManager() {
    }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(AIPlayerFakePlayerManager::tick);
    }

    public static Optional<AIPlayerFakePlayer> findOwned(ServerPlayerEntity owner) {
        MinecraftServer server = owner.getServer();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof AIPlayerFakePlayer fake && fake.ownerUuid().equals(owner.getUuid())) {
                return Optional.of(fake);
            }
        }
        return Optional.empty();
    }

    public static AIPlayerFakePlayer spawn(ServerPlayerEntity owner) {
        removeOwned(owner);

        MinecraftServer server = owner.getServer();
        ServerWorld world = owner.getWorld();
        String name = uniqueBotName(server);
        UUID uuid = UUID.nameUUIDFromBytes(("aiplayer-clean:" + owner.getUuidAsString() + ":" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        AIPlayerFakePlayer fake = new AIPlayerFakePlayer(server, world, profile, owner.getUuid());
        Vec3d position = findSafeSpawnPosition(owner).orElse(owner.getPos().add(1.5D, 0.0D, 1.5D));
        fake.prepareAt(position, owner.getYaw(), 0.0F);

        server.getPlayerManager().onPlayerConnect(new AIPlayerClientConnection(), fake, ConnectedClientData.createDefault(profile, false));
        fake.networkHandler = new net.fabricmc.fabric.impl.event.interaction.FakePlayerNetworkHandler(fake);
        fake.changeGameMode(GameMode.SURVIVAL);
        fake.prepareAt(position, owner.getYaw(), 0.0F);
        owner.sendMessage(Text.literal("AIPlayer 假玩家已生成：" + fake.getName().getString()).formatted(Formatting.GREEN), false);
        AIPlayerCompanionMod.LOGGER.info("Spawned clean fake player {} for {}", fake.getName().getString(), owner.getName().getString());
        return fake;
    }

    public static int removeOwned(ServerPlayerEntity owner) {
        int removed = 0;
        MinecraftServer server = owner.getServer();
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (player instanceof AIPlayerFakePlayer fake && fake.ownerUuid().equals(owner.getUuid())) {
                remove(server, fake);
                removed++;
            }
        }
        return removed;
    }

    public static void remove(MinecraftServer server, AIPlayerFakePlayer fake) {
        server.getPlayerManager().remove(fake);
        fake.remove(Entity.RemovalReason.DISCARDED);
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            if (!(player instanceof AIPlayerFakePlayer fake)) {
                continue;
            }
            if (fake.deathTime > DEATH_REMOVE_TICKS) {
                remove(server, fake);
            }
        }
    }

    private static Optional<Vec3d> findSafeSpawnPosition(ServerPlayerEntity owner) {
        ServerWorld world = owner.getWorld();
        BlockPos center = owner.getBlockPos();
        for (int radius = 2; radius <= 5; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    Optional<BlockPos> safe = findStandable(world, center.add(x, 0, z));
                    if (safe.isPresent()) {
                        return safe.map(Vec3d::ofBottomCenter);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findStandable(ServerWorld world, BlockPos near) {
        int minY = Math.max(world.getBottomY() + 1, near.getY() - 4);
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 3);
        for (int y = maxY; y >= minY; y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (canStandAt(world, feet)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    private static boolean canStandAt(ServerWorld world, BlockPos feet) {
        BlockPos floorPos = feet.down();
        BlockState floor = world.getBlockState(floorPos);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (floor.getCollisionShape(world, floorPos).isEmpty()) {
            return false;
        }
        if (floor.isOf(Blocks.LAVA) || feetState.isOf(Blocks.LAVA) || headState.isOf(Blocks.LAVA)) {
            return false;
        }
        if (!feetState.getCollisionShape(world, feet).isEmpty() || !headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        Box box = new Box(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D);
        return world.isSpaceEmpty(null, box);
    }

    private static String uniqueBotName(MinecraftServer server) {
        String base = AIPlayerCleanConfig.DEFAULT_BOT_NAME;
        if (server.getPlayerManager().getPlayer(base) == null) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            String name = base + i;
            if (server.getPlayerManager().getPlayer(name) == null) {
                return name;
            }
        }
        return base + System.currentTimeMillis() % 10000L;
    }
}
