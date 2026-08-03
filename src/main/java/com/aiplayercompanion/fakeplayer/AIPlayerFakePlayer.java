package com.aiplayercompanion.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.impl.event.interaction.FakePlayerNetworkHandler;
import net.minecraft.entity.EntityPose;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.UUID;

public final class AIPlayerFakePlayer extends ServerPlayerEntity {
    private final UUID ownerUuid;

    public AIPlayerFakePlayer(MinecraftServer server, ServerWorld world, GameProfile profile, UUID ownerUuid) {
        super(server, world, profile, SyncedClientOptions.createDefault());
        this.ownerUuid = ownerUuid;
        this.networkHandler = new FakePlayerNetworkHandler(this);
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    @Override
    public void tick() {
        enforceSurvivalPlayerState();
        super.tick();
    }

    public void prepareAt(Vec3d position, float yaw, float pitch) {
        enforceSurvivalPlayerState();
        refreshPositionAndAngles(position.x, position.y, position.z, yaw, pitch);
        setVelocity(Vec3d.ZERO);
        velocityModified = true;
        setHealth(getMaxHealth());
        deathTime = 0;
        hurtTime = 0;
        maxHurtTime = 0;
        setPose(EntityPose.STANDING);
    }

    public void enforceSurvivalPlayerState() {
        noClip = false;
        setNoGravity(false);
        getAbilities().invulnerable = false;
        getAbilities().creativeMode = false;
        getAbilities().allowFlying = false;
        getAbilities().flying = false;
        changeGameMode(GameMode.SURVIVAL);
    }
}
