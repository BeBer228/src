package releon.ru.api.module.impl.player;

import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Locale;

public class AutoArmor extends Module {

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // A replacement must beat the equipped piece by this much: equal pieces never swap back and forth
    private static final double HYSTERESIS = 0.5;

    private long lastClickTime = 0L, rolledDelay = -1L;
    private boolean sessionClicked;
    // The last click and how often the same move came back (server rejected it); repeated ones cool the slot down
    private long lastSignature = Long.MIN_VALUE;
    private int repeats;
    private final long[] slotCooldownUntil = new long[SLOTS.length];

    public AutoArmor() {
        super("AutoArmor", "Automatically equips the strongest armor pieces from your inventory", ModuleCategory.PLAYER);
    }

    @Override
    public void onDisable() {
        endSession();
    }

    @SubscribeEvent
    public void onGameUpdate(TickEvent.Post event) {
        if (mc.player == null || mc.gameMode == null || mc.player.inventoryMenu == null) return;
        boolean inventoryOpen = mc.gui != null && mc.screen instanceof InventoryScreen;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()
                || mc.gui != null && mc.screen != null && !inventoryOpen) {
            endSession();
            return;
        }

        long now = System.currentTimeMillis();
        int[] move = nextMove(now);
        if (move == null) {
            endSession();
            return;
        }
        // Called every tick while the set is incomplete, so the whole set goes on within one GuiMove stop
        if (!InventoryFlowManager.script.isFinished() || !InventoryFlowManager.postScript.isFinished()) return;
        if (rolledDelay < 0) rolledDelay = 150L;
        if (now - lastClickTime < rolledDelay) return;

        long signature = ((long) move[0] << 32) | ((long) move[1] << 16) | move[2];
        if (signature == lastSignature) {
            if (++repeats >= 3) {
                slotCooldownUntil[move[3]] = now + 5000L;
                repeats = 0;
                lastSignature = Long.MIN_VALUE;
                return;
            }
        } else {
            lastSignature = signature;
            repeats = 0;
        }
        // One confirmed-by-state click per call; the next one is planned from the inventory as the server left it
        InventoryFlowManager.addTask(() -> {
            if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu
                    || !mc.player.containerMenu.getCarried().isEmpty()) return;
            int[] fresh = nextMove(System.currentTimeMillis());
            if (fresh == null || fresh[0] != move[0] || fresh[1] != move[1] || fresh[2] != move[2]) return;
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, fresh[0], fresh[1],
                    fresh[2] == 1 ? ClickType.SWAP : ClickType.QUICK_MOVE, mc.player);
        });
        sessionClicked = true;
        lastClickTime = now;
        rolledDelay = -1L;
    }

    private void endSession() {
        // Like a manual open-click-close: the player inventory is closed once, after the last click of the stop
        if (sessionClicked && mc.player != null && mc.screen == null && mc.getConnection() != null)
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
        sessionClicked = false;
        lastSignature = Long.MIN_VALUE;
        repeats = 0;
    }

    /** {container slot, button, 1 = SWAP / 0 = QUICK_MOVE, armor index} of the next click towards the best set, or null. */
    private int[] nextMove(long now) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (now < slotCooldownUntil[i]) continue;
            EquipmentSlot eqSlot = SLOTS[i];
            int armorSlot = 5 + i;
            ItemStack current = mc.player.getItemBySlot(eqSlot);
            if (!current.isEmpty() && (isBound(current) && !mc.player.isCreative() || eqSlot == EquipmentSlot.CHEST && current.has(DataComponents.GLIDER))) continue;

            double currentScore = current.isEmpty() || !usable(current) ? Double.NEGATIVE_INFINITY : score(current, eqSlot) + HYSTERESIS;
            int bestSlot = -1;
            double bestScore = currentScore;
            for (int s = 9; s <= 44; s++) {
                ItemStack stack = mc.player.inventoryMenu.getSlot(s).getItem();
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (stack.isEmpty() || equippable == null || equippable.slot() != eqSlot || !usable(stack)) continue;
                if (isBound(stack)) continue;
                // Elytra only goes into an empty chest slot
                if (stack.has(DataComponents.GLIDER) && !current.isEmpty()) continue;
                double score = score(stack, eqSlot);
                // strict >: equal candidates keep the lowest slot, deterministic
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = s;
                }
            }
            if (bestSlot == -1) continue;
            if (bestSlot >= 36) return new int[]{armorSlot, bestSlot - 36, 1, i}; // hotbar: one swap replaces the piece
            if (current.isEmpty()) return new int[]{bestSlot, 0, 0, i};
            if (mc.player.getInventory().getFreeSlot() != -1) return new int[]{armorSlot, 0, 0, i}; // take off, next click puts on
        }
        return null;
    }

    private boolean usable(ItemStack stack) {
        if (!stack.isDamageableItem()) return true;
        return (stack.getMaxDamage() - stack.getDamageValue()) * 100.0 / stack.getMaxDamage() >= 5.0;
    }

    private static boolean isBound(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private double score(ItemStack stack, EquipmentSlot eqSlot) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double armor = modifiers.compute(Attributes.ARMOR, 0.0, eqSlot);
        double toughness = modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0, eqSlot);
        double durability = stack.isDamageableItem() ? 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage() : 1.0;
        return 10.0 / 10.0 * armor
                + 6.0 / 10.0 * toughness
                + 5.0 / 10.0 * enchantScore(stack)
                + 2.0 / 10.0 * materialRank(stack)
                + 2.0 / 10.0 * durability * 4.0;
    }

    private double enchantScore(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0.0;
        double score = 0.0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            Holder<Enchantment> e = entry.getKey();
            int level = entry.getValue().intValue();
            if (e.is(Enchantments.PROTECTION)) score += level;
            else if (e.is(Enchantments.BLAST_PROTECTION)) score += level * special("Blast");
            else if (e.is(Enchantments.PROJECTILE_PROTECTION)) score += level * special("Projectile");
            else if (e.is(Enchantments.FIRE_PROTECTION)) score += level * special("Fire");
            else if (e.is(Enchantments.UNBREAKING)) score += level * 0.25;
            else if (e.is(Enchantments.MENDING)) score += 0.5;
        }
        return score;
    }

    private double special(String type) {
        return 0.5;
    }

    private static double materialRank(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (id.contains("netherite")) return 5.0;
        if (id.contains("diamond")) return 4.0;
        if (id.contains("iron") || id.contains("turtle")) return 3.0;
        if (id.contains("chainmail")) return 2.0;
        if (id.contains("golden")) return 1.5;
        if (id.contains("leather")) return 1.0;
        return 0.0;
    }
}
