package com.aiplayercompanion.bot;

import com.aiplayercompanion.bot.navigation.BotNavigationController;
import com.aiplayercompanion.bot.navigation.BotPathingUtil;
import com.aiplayercompanion.config.ModConfig;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class AIPlayerBotController {
    private static final double DEFEND_SCAN_RANGE = 12.0D;
    private static final double ATTACK_REACH = 2.8D;
    private static final double PICKUP_SCAN_RANGE = 7.0D;
    private static final double PICKUP_REACH = 1.8D;

    private AIPlayerBotController() {
    }

    static void tick(AIPlayerBot bot, ServerPlayerEntity owner) {
        long now = owner.getWorld().getTime();
        BotNavigationController.enforceSurvivalBody(bot);
        handleInventory(bot);

        if (bot.getBotState() == AIPlayerBot.BotState.WAITING) {
            BotNavigationController.stop(bot);
            bot.setSneaking(true);
            BotNavigationController.idleLook(bot, owner, now);
            return;
        }

        if (bot.getWorld() != owner.getWorld()) {
            teleportNearOwner(owner, bot, true);
            return;
        }

        double ownerDistanceSq = bot.squaredDistanceTo(owner);
        double teleportDistance = ModConfig.get().teleportDistance;
        if (ownerDistanceSq > teleportDistance * teleportDistance) {
            teleportNearOwner(owner, bot, true);
            return;
        }

        if (ModConfig.get().botAutoPickup) {
            Optional<ItemEntity> pickupTarget = findPickupTarget(bot);
            if (pickupTarget.isPresent() && moveToPickup(bot, owner, pickupTarget.get(), now)) {
                return;
            }
        }

        if (ModConfig.get().botAutoCombat || ModConfig.get().botProtectOwner) {
            Optional<LivingEntity> threat = findThreat(owner, bot);
            if (threat.isPresent() && engageThreat(bot, owner, threat.get(), now)) {
                return;
            }
        }

        double stopDistance = Math.max(ModConfig.get().stopFollowDistance, bot.getStopDistance());
        if (ownerDistanceSq <= stopDistance * stopDistance) {
            BotNavigationController.stop(bot);
            bot.setSneaking(false);
            BotNavigationController.idleLook(bot, owner, now);
            return;
        }

        Optional<BlockPos> target = BotNavigationController.chooseFollowTarget(owner, bot);
        if (target.isEmpty()) {
            BotNavigationController.stop(bot);
            BotNavigationController.lookTowardYaw(bot, owner.getYaw());
            return;
        }

        BotNavigationController.moveTo(bot, owner, target.get(), stopDistance, ownerDistanceSq, now);
    }

    private static Optional<LivingEntity> findThreat(ServerPlayerEntity owner, AIPlayerBot bot) {
        Box scanBox = owner.getBoundingBox().expand(DEFEND_SCAN_RANGE);
        List<MobEntity> hostiles = owner.getWorld().getEntitiesByClass(MobEntity.class, scanBox, mob ->
                mob.isAlive()
                        && !mob.isRemoved()
                        && mob.squaredDistanceTo(owner) <= DEFEND_SCAN_RANGE * DEFEND_SCAN_RANGE
                        && hasLineOrClose(owner, bot, mob)
                        && shouldEngageMob(owner, mob));
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (MobEntity mob : hostiles) {
            double ownerDistance = mob.squaredDistanceTo(owner);
            double botDistance = mob.squaredDistanceTo(bot);
            LivingEntity target = mob.getTarget();
            double score = ownerDistance + botDistance * 0.35D;
            if (target != null && target.getUuid().equals(owner.getUuid())) {
                score -= 80.0D;
            }
            if (score < bestScore) {
                best = mob;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean shouldEngageMob(ServerPlayerEntity owner, MobEntity mob) {
        LivingEntity target = mob.getTarget();
        boolean targetsOwner = target != null && target.getUuid().equals(owner.getUuid());
        if (targetsOwner) {
            return true;
        }
        return ModConfig.get().botAutoCombat && mob instanceof HostileEntity;
    }

    private static boolean hasLineOrClose(ServerPlayerEntity owner, AIPlayerBot bot, MobEntity mob) {
        return mob.squaredDistanceTo(owner) < 36.0D
                || mob.squaredDistanceTo(bot) < 36.0D
                || mob.canSee(owner);
    }

    private static boolean engageThreat(AIPlayerBot bot, ServerPlayerEntity owner, LivingEntity hostile, long now) {
        bot.setSneaking(false);
        double distanceSq = bot.squaredDistanceTo(hostile);
        if (distanceSq <= ATTACK_REACH * ATTACK_REACH) {
            BotNavigationController.stop(bot);
            BotNavigationController.lookAtEntity(bot, hostile, bot.getYaw());
            if (bot.canAttackAt(now)) {
                selectBestWeapon(bot);
                bot.attack(hostile);
                bot.swingHand(Hand.MAIN_HAND);
                bot.markAttacked(now);
            }
            return true;
        }

        Optional<BlockPos> target = BotPathingUtil.findGroundLanding(owner.getWorld(), hostile.getBlockPos(), 8);
        if (target.isEmpty()) {
            return false;
        }
        return BotNavigationController.moveTo(bot, owner, target.get(), 2.2D, distanceSq, now);
    }

    private static void handleInventory(AIPlayerBot bot) {
        if (ModConfig.get().botAutoPickup) {
            pickupNearbyItems(bot);
        }
        if (ModConfig.get().botAutoEquip) {
            equipBestArmor(bot);
        }
        if (ModConfig.get().botAutoWeapon) {
            selectBestWeapon(bot);
        }
    }

    private static void pickupNearbyItems(AIPlayerBot bot) {
        Box box = bot.getBoundingBox().expand(PICKUP_REACH);
        List<ItemEntity> items = bot.getWorld().getEntitiesByClass(ItemEntity.class, box, item ->
                item.isAlive() && !item.isRemoved() && !item.getStack().isEmpty());
        for (ItemEntity item : items) {
            ItemStack stack = item.getStack();
            ItemStack before = stack.copy();
            boolean inserted = bot.getInventory().insertStack(stack);
            if (!inserted && stack.getCount() == before.getCount()) {
                continue;
            }
            bot.sendPickup(item, before.getCount() - stack.getCount());
            if (stack.isEmpty()) {
                item.discard();
            } else {
                item.setStack(stack);
            }
        }
        bot.getInventory().markDirty();
    }

    private static Optional<ItemEntity> findPickupTarget(AIPlayerBot bot) {
        Box box = bot.getBoundingBox().expand(PICKUP_SCAN_RANGE);
        List<ItemEntity> items = bot.getWorld().getEntitiesByClass(ItemEntity.class, box, item ->
                item.isAlive() && !item.isRemoved() && !item.getStack().isEmpty());
        return items.stream().min(Comparator.comparingDouble(item -> pickupPriority(bot, item)));
    }

    private static double pickupPriority(AIPlayerBot bot, ItemEntity item) {
        double distance = item.squaredDistanceTo(bot);
        return isUsefulPickup(item.getStack()) ? distance * 0.25D : distance;
    }

    private static boolean moveToPickup(AIPlayerBot bot, ServerPlayerEntity owner, ItemEntity item, long now) {
        double distanceSq = bot.squaredDistanceTo(item);
        if (distanceSq <= PICKUP_REACH * PICKUP_REACH) {
            BotNavigationController.stop(bot);
            pickupNearbyItems(bot);
            handleInventory(bot);
            return true;
        }

        Optional<BlockPos> target = BotPathingUtil.findGroundLanding(owner.getWorld(), item.getBlockPos(), 8);
        return target.isPresent() && BotNavigationController.moveTo(bot, owner, target.get(), 1.4D, distanceSq, now);
    }

    private static boolean isUsefulPickup(ItemStack stack) {
        return weaponScore(stack) > 0
                || armorScore(stack, EquipmentSlot.HEAD) > 0
                || armorScore(stack, EquipmentSlot.CHEST) > 0
                || armorScore(stack, EquipmentSlot.LEGS) > 0
                || armorScore(stack, EquipmentSlot.FEET) > 0;
    }

    private static void equipBestArmor(AIPlayerBot bot) {
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            int armorSlot = armorInventorySlot(slot);
            int bestSlot = -1;
            int bestScore = armorScore(bot.getEquippedStack(slot), slot);
            for (int i = 0; i < 36; i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                int score = armorScore(stack, slot);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
            if (bestSlot >= 0) {
                ItemStack current = bot.getEquippedStack(slot).copy();
                ItemStack replacement = bot.getInventory().removeStack(bestSlot, 1);
                bot.getInventory().setStack(armorSlot, replacement);
                bot.equipStack(slot, replacement);
                if (!current.isEmpty()) {
                    bot.getInventory().insertStack(current);
                }
                bot.getInventory().markDirty();
                bot.currentScreenHandler.sendContentUpdates();
                bot.syncEquipment();
            }
        }
    }

    private static int armorInventorySlot(EquipmentSlot slot) {
        return slot.getOffsetEntitySlotId(36);
    }

    private static int armorScore(ItemStack stack, EquipmentSlot slot) {
        if (slot == EquipmentSlot.HEAD) {
            if (stack.isOf(Items.NETHERITE_HELMET)) return 600;
            if (stack.isOf(Items.DIAMOND_HELMET)) return 500;
            if (stack.isOf(Items.IRON_HELMET)) return 400;
            if (stack.isOf(Items.CHAINMAIL_HELMET)) return 350;
            if (stack.isOf(Items.GOLDEN_HELMET)) return 300;
            if (stack.isOf(Items.LEATHER_HELMET)) return 200;
        } else if (slot == EquipmentSlot.CHEST) {
            if (stack.isOf(Items.NETHERITE_CHESTPLATE)) return 600;
            if (stack.isOf(Items.DIAMOND_CHESTPLATE)) return 500;
            if (stack.isOf(Items.IRON_CHESTPLATE)) return 400;
            if (stack.isOf(Items.CHAINMAIL_CHESTPLATE)) return 350;
            if (stack.isOf(Items.GOLDEN_CHESTPLATE)) return 300;
            if (stack.isOf(Items.LEATHER_CHESTPLATE)) return 200;
        } else if (slot == EquipmentSlot.LEGS) {
            if (stack.isOf(Items.NETHERITE_LEGGINGS)) return 600;
            if (stack.isOf(Items.DIAMOND_LEGGINGS)) return 500;
            if (stack.isOf(Items.IRON_LEGGINGS)) return 400;
            if (stack.isOf(Items.CHAINMAIL_LEGGINGS)) return 350;
            if (stack.isOf(Items.GOLDEN_LEGGINGS)) return 300;
            if (stack.isOf(Items.LEATHER_LEGGINGS)) return 200;
        } else if (slot == EquipmentSlot.FEET) {
            if (stack.isOf(Items.NETHERITE_BOOTS)) return 600;
            if (stack.isOf(Items.DIAMOND_BOOTS)) return 500;
            if (stack.isOf(Items.IRON_BOOTS)) return 400;
            if (stack.isOf(Items.CHAINMAIL_BOOTS)) return 350;
            if (stack.isOf(Items.GOLDEN_BOOTS)) return 300;
            if (stack.isOf(Items.LEATHER_BOOTS)) return 200;
        }
        return -1;
    }

    private static void selectBestWeapon(AIPlayerBot bot) {
        int selectedSlot = bot.getInventory().getSelectedSlot();
        int bestSlot = selectedSlot;
        int bestScore = weaponScore(bot.getInventory().getStack(selectedSlot));
        for (int i = 0; i < 36; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            int score = weaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestScore <= 0) {
            return;
        }
        if (bestSlot >= 9) {
            ItemStack currentHand = bot.getInventory().getStack(selectedSlot);
            ItemStack bestWeapon = bot.getInventory().getStack(bestSlot);
            bot.getInventory().setStack(bestSlot, currentHand);
            bot.getInventory().setStack(selectedSlot, bestWeapon);
        } else {
            bot.getInventory().setSelectedSlot(bestSlot);
        }
        bot.equipStack(EquipmentSlot.MAINHAND, bot.getInventory().getStack(bot.getInventory().getSelectedSlot()));
        bot.getInventory().markDirty();
        bot.currentScreenHandler.sendContentUpdates();
        bot.syncEquipment();
    }

    private static int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.isOf(Items.NETHERITE_SWORD)) return 1000;
        if (stack.isOf(Items.DIAMOND_SWORD)) return 900;
        if (stack.isOf(Items.IRON_SWORD)) return 800;
        if (stack.isOf(Items.STONE_SWORD)) return 700;
        if (stack.isOf(Items.GOLDEN_SWORD)) return 650;
        if (stack.isOf(Items.WOODEN_SWORD)) return 600;
        if (stack.isOf(Items.NETHERITE_AXE)) return 580;
        if (stack.isOf(Items.DIAMOND_AXE)) return 560;
        if (stack.isOf(Items.IRON_AXE)) return 540;
        if (stack.isOf(Items.STONE_AXE)) return 520;
        if (stack.isOf(Items.GOLDEN_AXE)) return 500;
        if (stack.isOf(Items.WOODEN_AXE)) return 480;
        if (stack.getItem() instanceof AxeItem) return 400;
        return 0;
    }

    static boolean teleportNearOwner(ServerPlayerEntity owner, AIPlayerBot bot, boolean feedback) {
        return BotNavigationController.teleportNearOwner(owner, bot, feedback);
    }

    static void discardNavigationProxy(AIPlayerBot bot) {
        BotNavigationController.stop(bot);
    }
}
