package com.aiplayercompanion.entity;

import com.aiplayercompanion.config.ModConfig;
import com.aiplayercompanion.entity.goal.FollowOwnerGoal;
import com.aiplayercompanion.service.CompanionLog;
import com.aiplayercompanion.util.ModelNameUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

public class AIPlayerEntity extends PathAwareEntity {
    private static final String OWNER_UUID_KEY = "OwnerUuid";
    private static final String STATE_KEY = "CompanionState";

    private UUID ownerUuid;
    private CompanionState companionState = CompanionState.FOLLOWING;
    private LivingEntity fleeTarget;
    private long fleeUntilTick;
    private long lastFeedbackTick;
    private long lastTeleportAttemptTick;
    private long lastChunkTicketTick;

    public AIPlayerEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        setCustomName(Text.literal(ModelNameUtil.companionName()));
        setCustomNameVisible(true);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder createAIPlayerAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 20.0D)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.28D)
                .add(EntityAttributes.FOLLOW_RANGE, 48.0D)
                .add(EntityAttributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new LongDoorInteractGoal(this, true));
        this.goalSelector.add(2, new FollowOwnerGoal(this, 1.15D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        MobNavigation navigation = new MobNavigation(this, world);
        navigation.setCanOpenDoors(true);
        return navigation;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    protected void writeCustomData(WriteView view) {
        super.writeCustomData(view);
        if (ownerUuid != null) {
            view.putString(OWNER_UUID_KEY, ownerUuid.toString());
        }
        view.putString(STATE_KEY, companionState.name());
    }

    @Override
    protected void readCustomData(ReadView view) {
        super.readCustomData(view);
        view.getOptionalString(OWNER_UUID_KEY).ifPresent(uuid -> {
            try {
                ownerUuid = UUID.fromString(uuid);
            } catch (IllegalArgumentException ignored) {
                ownerUuid = null;
            }
        });
        try {
            companionState = CompanionState.valueOf(view.getString(STATE_KEY, CompanionState.FOLLOWING.name()));
        } catch (IllegalArgumentException ignored) {
            companionState = CompanionState.FOLLOWING;
        }
        if (!hasCustomName()) {
            setCustomName(Text.literal(ModelNameUtil.companionName()));
            setCustomNameVisible(true);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient()) {
            return;
        }

        if (companionState == CompanionState.WAITING) {
            setSneaking(true);
            setSprinting(false);
        } else if (!isFleeing()) {
            setSneaking(false);
        }

        if (isFleeing()) {
            fleeFromThreat();
        } else {
            keepNearOnlineOwner();
        }
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean damaged = super.damage(world, source, amount);
        if (damaged && source.getAttacker() instanceof LivingEntity attacker && !isOwner(attacker)) {
            startFleeing(attacker);
        }
        return damaged;
    }

    public Optional<UUID> getOwnerUuid() {
        return Optional.ofNullable(ownerUuid);
    }

    public void setOwner(ServerPlayerEntity owner) {
        this.ownerUuid = owner.getUuid();
        setPersistent();
    }

    public ServerPlayerEntity getOwnerPlayer() {
        if (ownerUuid == null || getServer() == null) {
            return null;
        }
        return getServer().getPlayerManager().getPlayer(ownerUuid);
    }

    public boolean isOwner(LivingEntity entity) {
        return entity != null && ownerUuid != null && ownerUuid.equals(entity.getUuid());
    }

    public CompanionState getCompanionState() {
        return companionState;
    }

    public void setCompanionState(CompanionState companionState) {
        this.companionState = companionState;
        if (companionState == CompanionState.WAITING) {
            getNavigation().stop();
            setSneaking(true);
            setSprinting(false);
        } else {
            setSneaking(false);
        }
    }

    public boolean isFleeing() {
        return fleeTarget != null && fleeTarget.isAlive() && getWorld().getTime() < fleeUntilTick;
    }

    public void refreshDisplayNameFromConfig() {
        setCustomName(Text.literal(ModelNameUtil.companionName()));
        setCustomNameVisible(true);
    }

    private void startFleeing(LivingEntity attacker) {
        fleeTarget = attacker;
        fleeUntilTick = getWorld().getTime() + ModConfig.get().fleeSeconds * 20L;
        setSneaking(false);
        setSprinting(true);
        sendOwnerFeedback("我被攻击了，先躲一下。");
        CompanionLog.player(getOwnerPlayer(), "FLEE", "companion fleeing from " + attacker.getType().toString());
    }

    private void fleeFromThreat() {
        if (fleeTarget == null || fleeTarget.getWorld() != getWorld()) {
            return;
        }
        Vec3d away = getPos().subtract(fleeTarget.getPos());
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        Vec3d target = getPos().add(away.normalize().multiply(8.0D));
        getNavigation().startMovingTo(target.x, target.y, target.z, 1.35D);
    }

    private void keepNearOnlineOwner() {
        if (companionState != CompanionState.FOLLOWING || !(getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner == null || owner.getWorld() != getWorld()) {
            getNavigation().stop();
            return;
        }

        keepCurrentChunkLoaded(serverWorld);

        double teleportDistance = ModConfig.get().teleportDistance;
        if (squaredDistanceTo(owner) <= teleportDistance * teleportDistance) {
            return;
        }

        long now = getWorld().getTime();
        if (now - lastTeleportAttemptTick < 20L) {
            return;
        }
        lastTeleportAttemptTick = now;
        if (tryTeleportNear(serverWorld, owner)) {
            sendOwnerFeedback("太远了，我过来了。");
        }
    }

    private void keepCurrentChunkLoaded(ServerWorld world) {
        long now = getWorld().getTime();
        if (now - lastChunkTicketTick < 100L) {
            return;
        }
        lastChunkTicketTick = now;
        world.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(getBlockPos()), 3);
    }

    private boolean tryTeleportNear(ServerWorld world, ServerPlayerEntity owner) {
        BlockPos ownerPos = owner.getBlockPos();
        for (int radius = 2; radius <= 5; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    BlockPos safe = findSafeLanding(world, ownerPos.add(x, 0, z));
                    if (safe != null) {
                        requestTeleport(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
                        getNavigation().stop();
                        setSprinting(false);
                        CompanionLog.player(owner, "TELEPORT", "companion teleported near owner to " + safe.toShortString());
                        return true;
                    }
                }
            }
        }
        CompanionLog.player(owner, "TELEPORT", "failed to find safe landing position near owner");
        return false;
    }

    private BlockPos findSafeLanding(WorldView world, BlockPos near) {
        int minY = world.getBottomY() + 1;
        int maxY = Math.min(world.getTopYInclusive() - 2, near.getY() + 4);
        for (int y = maxY; y >= Math.max(minY, near.getY() - 5); y--) {
            BlockPos feet = new BlockPos(near.getX(), y, near.getZ());
            if (isSafeStandingPosition(world, feet)) {
                return feet;
            }
        }
        return null;
    }

    private boolean isSafeStandingPosition(WorldView world, BlockPos feet) {
        BlockPos below = feet.down();
        BlockState floor = world.getBlockState(below);
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (!floor.isSolidBlock(world, below) || isDangerous(floor) || isDangerous(feetState) || isDangerous(headState)) {
            return false;
        }
        if (!feetState.getCollisionShape(world, feet).isEmpty() || !headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }

        Box box = Box.of(Vec3d.ofBottomCenter(feet), getWidth(), getHeight(), getWidth());
        return getWorld().isSpaceEmpty(this, box);
    }

    private boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA);
    }

    public void sendOwnerFeedback(String message) {
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner == null) {
            return;
        }
        long now = getWorld().getTime();
        if (now - lastFeedbackTick < 40L) {
            return;
        }
        lastFeedbackTick = now;
        owner.sendMessage(Text.literal(ModelNameUtil.companionName() + "：" + message), false);
    }

    public enum CompanionState {
        FOLLOWING,
        WAITING
    }
}
