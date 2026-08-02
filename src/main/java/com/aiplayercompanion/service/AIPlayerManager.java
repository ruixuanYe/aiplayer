package com.aiplayercompanion.service;

import com.aiplayercompanion.AIPlayerCompanionMod;
import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.AIPlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public class AIPlayerManager {
    private static final double SEARCH_RANGE = 256.0D;

    public static void initialize() {
        // Reserved for future lifecycle hooks.
    }

    public static Optional<AIPlayerEntity> findOwnedCompanion(ServerPlayerEntity owner) {
        UUID ownerUuid = owner.getUuid();
        return owner.getServer().getWorlds().iterator().hasNext()
                ? findOwnedCompanionInAllWorlds(owner, ownerUuid)
                : Optional.empty();
    }

    public static Optional<AIPlayerEntity> findNearbyOwnedCompanion(ServerPlayerEntity owner) {
        Box box = owner.getBoundingBox().expand(SEARCH_RANGE);
        return owner.getWorld()
                .getEntitiesByType(AIPlayerCompanionMod.AI_PLAYER, box, entity -> entity.getOwnerUuid().filter(owner.getUuid()::equals).isPresent())
                .stream()
                .min(Comparator.comparingDouble(owner::squaredDistanceTo));
    }

    public static AIPlayerEntity spawnFor(ServerPlayerEntity owner) {
        AIPlayerEntity entity = new AIPlayerEntity(AIPlayerCompanionMod.AI_PLAYER, owner.getWorld());
        entity.setOwner(owner);
        entity.setCompanionState(AIPlayerEntity.CompanionState.FOLLOWING);
        entity.refreshPositionAndAngles(owner.getX() + 1.5D, owner.getY(), owner.getZ() + 1.5D, owner.getYaw(), 0.0F);
        owner.getWorld().spawnEntity(entity);
        ModConfig.rememberOwner(owner.getName().getString(), owner.getUuidAsString());
        return entity;
    }

    private static Optional<AIPlayerEntity> findOwnedCompanionInAllWorlds(ServerPlayerEntity owner, UUID ownerUuid) {
        AIPlayerEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ServerWorld world : owner.getServer().getWorlds()) {
            Box searchBox = owner.getWorld() == world
                    ? owner.getBoundingBox().expand(SEARCH_RANGE)
                    : new Box(-30000000, world.getBottomY(), -30000000, 30000000, world.getTopYInclusive(), 30000000);
            for (AIPlayerEntity entity : world.getEntitiesByType(AIPlayerCompanionMod.AI_PLAYER, searchBox, ai -> ai.getOwnerUuid().filter(ownerUuid::equals).isPresent())) {
                double distance = owner.getWorld() == world ? owner.squaredDistanceTo(entity) : Double.MAX_VALUE / 2.0D;
                if (distance < nearestDistance) {
                    nearest = entity;
                    nearestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }
}
