package com.aiplayercompanion.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AIPlayerBot extends ServerPlayerEntity {
    public static final double DEFAULT_STOP_DISTANCE = 4.5D;
    public static final double DEFAULT_FOLLOW_DISTANCE = 6.5D;
    public static final double DEFAULT_SIDE_DISTANCE = 2.5D;

    private final UUID ownerUuid;
    private BotState state = BotState.FOLLOWING;
    private long lastDamageFeedbackTick;
    private final List<BlockPos> path = new ArrayList<>();
    private int pathIndex;
    private long lastPathComputeTick;
    private BlockPos lastPathTarget;
    private double stopDistance = DEFAULT_STOP_DISTANCE;
    private double followDistance = DEFAULT_FOLLOW_DISTANCE;
    private double sideDistance = DEFAULT_SIDE_DISTANCE;
    private double speedScale = 1.0D;
    private boolean lookAtOwnerWhenIdle;
    private long lookAroundUntilTick;
    private float lookAroundYaw;
    private long lastAttackTick;

    public AIPlayerBot(MinecraftServer server, ServerWorld world, GameProfile profile, UUID ownerUuid) {
        super(server, world, profile, SyncedClientOptions.createDefault());
        this.ownerUuid = ownerUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public BotState getBotState() {
        return state;
    }

    public void setBotState(BotState state) {
        this.state = state;
        setSneaking(state == BotState.WAITING);
        setSprinting(false);
        if (state == BotState.WAITING) {
            clearPath();
        }
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public int getPathIndex() {
        return pathIndex;
    }

    public void advancePath() {
        pathIndex++;
    }

    public void setPath(List<BlockPos> path, BlockPos target, long tick) {
        this.path.clear();
        this.path.addAll(path);
        this.pathIndex = 0;
        this.lastPathTarget = target;
        this.lastPathComputeTick = tick;
    }

    public void clearPath() {
        path.clear();
        pathIndex = 0;
        lastPathTarget = null;
    }

    public long getLastPathComputeTick() {
        return lastPathComputeTick;
    }

    public BlockPos getLastPathTarget() {
        return lastPathTarget;
    }

    public double getStopDistance() {
        return stopDistance;
    }

    public double getFollowDistance() {
        return followDistance;
    }

    public double getSideDistance() {
        return sideDistance;
    }

    public double getSpeedScale() {
        return speedScale;
    }

    public boolean shouldLookAtOwnerWhenIdle() {
        return lookAtOwnerWhenIdle;
    }

    public boolean isLookingAround(long tick) {
        return tick < lookAroundUntilTick;
    }

    public float getLookAroundYaw() {
        return lookAroundYaw;
    }

    public void startLookingAround(long tick, int durationTicks) {
        lookAroundUntilTick = tick + durationTicks;
        lookAroundYaw = getYaw() + 90.0F;
    }

    public void applyControlPreset(ControlPreset preset) {
        switch (preset) {
            case CLOSE -> {
                stopDistance = 3.0D;
                followDistance = 4.2D;
                sideDistance = 1.5D;
                speedScale = 1.05D;
                lookAtOwnerWhenIdle = true;
            }
            case FAR -> {
                stopDistance = 7.0D;
                followDistance = 9.5D;
                sideDistance = 4.0D;
                speedScale = 0.85D;
                lookAtOwnerWhenIdle = false;
            }
            case NATURAL -> {
                stopDistance = DEFAULT_STOP_DISTANCE;
                followDistance = DEFAULT_FOLLOW_DISTANCE;
                sideDistance = DEFAULT_SIDE_DISTANCE;
                speedScale = 1.0D;
                lookAtOwnerWhenIdle = false;
            }
        }
        clearPath();
    }

    public boolean canAttackAt(long tick) {
        return tick - lastAttackTick >= 20L;
    }

    public void markAttacked(long tick) {
        lastAttackTick = tick;
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> getInventory().getStack(getInventory().getSelectedSlot());
            case OFFHAND -> getInventory().getStack(40);
            case FEET, LEGS, CHEST, HEAD -> getInventory().getStack(slot.getOffsetEntitySlotId(36));
            default -> super.getEquippedStack(slot);
        };
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
        switch (slot) {
            case MAINHAND -> getInventory().setStack(getInventory().getSelectedSlot(), stack);
            case OFFHAND -> getInventory().setStack(40, stack);
            case FEET, LEGS, CHEST, HEAD -> getInventory().setStack(slot.getOffsetEntitySlotId(36), stack);
            default -> super.equipStack(slot, stack);
        }
        getInventory().markDirty();
        syncEquipment();
    }

    public void syncEquipment() {
        if (currentScreenHandler != null) {
            currentScreenHandler.sendContentUpdates();
        }
        if (getWorld() instanceof ServerWorld serverWorld) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(
                    Pair.of(EquipmentSlot.MAINHAND, getEquippedStack(EquipmentSlot.MAINHAND).copy()),
                    Pair.of(EquipmentSlot.OFFHAND, getEquippedStack(EquipmentSlot.OFFHAND).copy()),
                    Pair.of(EquipmentSlot.HEAD, getEquippedStack(EquipmentSlot.HEAD).copy()),
                    Pair.of(EquipmentSlot.CHEST, getEquippedStack(EquipmentSlot.CHEST).copy()),
                    Pair.of(EquipmentSlot.LEGS, getEquippedStack(EquipmentSlot.LEGS).copy()),
                    Pair.of(EquipmentSlot.FEET, getEquippedStack(EquipmentSlot.FEET).copy())
            );
            serverWorld.getChunkManager().sendToNearbyPlayers(this, new EntityEquipmentUpdateS2CPacket(getId(), equipment));
        }
    }

    @Override
    public void setStackInHand(Hand hand, ItemStack stack) {
        if (hand == Hand.MAIN_HAND) {
            equipStack(EquipmentSlot.MAINHAND, stack);
        } else {
            equipStack(EquipmentSlot.OFFHAND, stack);
        }
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean damaged = super.damage(world, source, amount);
        if (damaged) {
            ServerPlayerEntity owner = getServer() == null ? null : getServer().getPlayerManager().getPlayer(ownerUuid);
            if (owner != null && world.getTime() - lastDamageFeedbackTick > 40L && getHealth() > 0.0F) {
                lastDamageFeedbackTick = world.getTime();
                owner.sendMessage(Text.literal(getName().getString() + "：我被攻击了，正在撑住。").formatted(Formatting.YELLOW), false);
            }
        }
        return damaged;
    }

    public enum BotState {
        FOLLOWING,
        WAITING
    }

    public enum ControlPreset {
        NATURAL,
        CLOSE,
        FAR
    }
}
