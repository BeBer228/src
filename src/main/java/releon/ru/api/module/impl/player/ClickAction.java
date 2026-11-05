package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.types.EventPriority;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Быстрые действия с предметами: заряд ветра (прыжок), зелья опыта и заряженный арбалет.
 * Бросок вниз идёт обычным пакетом использования с явным углом, поэтому камера не крутится.
 */
public final class ClickAction extends Module {
    private static final int OFFHAND_SLOT = 40;
    private static final float DOWN_PITCH = 90.0F;
    private static final float WALL_PITCH = 60.0F;

    private final BindSetting expKey = register(new BindSetting("Experience Bottle",
            "Удерживать для быстрого броска зелья опыта.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));
    private final BooleanSetting expLookDown = register(new BooleanSetting("Look Down",
            "Бросать опыт под себя: голова опускается, камера остаётся свободной.", true));
    private final NumberSetting expDelay = register(new NumberSetting("Exp Delay",
            "Тиков между бросками опыта (2 — быстрое нажатие, 4 — обычное удержание).", 2.0, 2.0, 6.0, 1.0));
    private final BindSetting windKey = register(new BindSetting("Wind Charge",
            "Заряд ветра с автоматическим прыжком.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));
    private final ModeSetting windAirMode = register(new ModeSetting("Wind In Air",
            "Нажатие в воздухе. Base — бросить вниз у земли. Landing — ждать приземления и прыгать с земли.",
            "Base", "Base", "Landing"));
    private final BindSetting crossbowKey = register(new BindSetting("Crossbow",
            "Выстрелить заряженным арбалетом, даже из инвентаря.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));

    private boolean lastWindDown;
    private boolean lastCrossbowDown;

    // ------------------------------------------------------------ опыт
    private boolean expActive;
    private boolean expLooking;
    private int expSlot = -1;
    private int expOldSlot = -1;
    private int expInvSlot = -1;
    private int expNextThrow;

    // ------------------------------------------------------------ арбалет
    private int crossbowInvSlot = -1;
    private int crossbowSlot = -1;
    private int crossbowUseAt;
    private int crossbowReturnAt;
    private boolean crossbowUsed;

    // ------------------------------------------------------------ заряд ветра
    private int windStage;
    private int windSlot = -1;
    private int windInvSlot = -1;
    private int windTicks;
    private int windHoldSpace;
    private long windReturnAt;
    private boolean windBlockJump;

    public ClickAction() {
        super("ClickAction", "Быстрый бросок опыта, заряда ветра и выстрел из арбалета.", ModuleCategory.PLAYER);
        expLookDown.visibleWhen(() -> expKey.getValue().isBound());
        expDelay.visibleWhen(() -> expKey.getValue().isBound());
        windAirMode.visibleWhen(() -> windKey.getValue().isBound());
    }

    @Override
    protected void onDisable() {
        stopExp();
        resetWind();
        finishCrossbow(true);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }

        tickExp(client);
        tickCrossbow(client);
        tickWind(client);

        if (client.getWindow() == null) {
            lastWindDown = false;
            lastCrossbowDown = false;
            return;
        }

        long handle = client.getWindow().handle();
        boolean windDown = windKey.getValue().isDown(handle);
        boolean crossbowDown = crossbowKey.getValue().isDown(handle);

        if (windDown && !lastWindDown && windStage == 0) {
            startWind(client);
        }
        if (crossbowDown && !lastCrossbowDown) {
            startCrossbow(client);
        }

        lastWindDown = windDown;
        lastCrossbowDown = crossbowDown;
    }

    // ============================================================ опыт

    private void tickExp(Minecraft client) {
        boolean down = client.getWindow() != null && expKey.getValue().isDown(client.getWindow().handle());
        if (!down) {
            if (expActive) {
                stopExp();
            }
            return;
        }

        if (!expActive) {
            startExp();
            return;
        }
        if (expSlot == -1) {
            stopExp();
            return;
        }
        if (stackAt(expSlot).isEmpty()) {
            // Стопка кончилась: вернуть свап и на следующем тике взять следующую.
            stopExp();
            return;
        }
        if (client.player.tickCount >= expNextThrow) {
            useSlot(expSlot, expLooking ? DOWN_PITCH : null);
            expNextThrow = client.player.tickCount + Math.max(1, expDelay.getValue().intValue());
        }
    }

    private void startExp() {
        int found = findItem(stack -> stack.is(Items.EXPERIENCE_BOTTLE));
        if (found == -1 || cooldownTicks(stackAt(found)) > 0) {
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        expInvSlot = -1;
        if (found >= 9) {
            int spare = spareHotbarSlot();
            if (spare == -1) {
                return;
            }
            InventoryUtils.click(found, spare, ClickType.SWAP);
            expInvSlot = found;
            found = spare;
        }

        expActive = true;
        expSlot = found;
        expOldSlot = -1;
        expLooking = expLookDown.getValue();
        if (found != OFFHAND_SLOT && found != selected) {
            expOldSlot = selected;
            InventoryUtils.syncSelectedHotbarSlot(found);
        }
        // Первый бросок — на следующем тике, чтобы сервер увидел смену слота.
        expNextThrow = mc.player.tickCount + 1;
    }

    private void stopExp() {
        if (expSlot != -1 && expInvSlot != -1) {
            InventoryUtils.click(expInvSlot, expSlot, ClickType.SWAP);
        }
        if (expOldSlot != -1) {
            InventoryUtils.syncSelectedHotbarSlot(expOldSlot);
        }
        expActive = false;
        expLooking = false;
        expSlot = -1;
        expOldSlot = -1;
        expInvSlot = -1;
    }

    // ============================================================ арбалет

    private void startCrossbow(Minecraft client) {
        if (crossbowInvSlot != -1) {
            return;
        }
        int slot = findItem(ClickAction::isLoadedCrossbow);
        if (slot == -1) {
            Notifications.push("ClickAction", "Нет заряженного арбалета", 2500L);
            return;
        }
        if (slot < 9 || slot == OFFHAND_SLOT) {
            useSlot(slot, null);
            return;
        }
        int selected = client.player.getInventory().getSelectedSlot();
        InventoryUtils.click(slot, selected, ClickType.SWAP);
        crossbowInvSlot = slot;
        crossbowSlot = selected;
        crossbowUseAt = client.player.tickCount + 1;
        crossbowReturnAt = Integer.MAX_VALUE;
        crossbowUsed = false;
    }

    private void tickCrossbow(Minecraft client) {
        if (crossbowInvSlot == -1) {
            return;
        }
        if (!crossbowUsed) {
            if (client.player.tickCount >= crossbowUseAt) {
                useSlot(crossbowSlot, null);
                crossbowUsed = true;
                crossbowReturnAt = client.player.tickCount + 2;
            }
            return;
        }
        if (client.player.tickCount >= crossbowReturnAt) {
            finishCrossbow(false);
        }
    }

    private void finishCrossbow(boolean force) {
        if (crossbowInvSlot != -1) {
            InventoryUtils.click(crossbowInvSlot, crossbowSlot, ClickType.SWAP);
        }
        crossbowInvSlot = -1;
        crossbowSlot = -1;
        crossbowUsed = false;
        crossbowReturnAt = Integer.MAX_VALUE;
    }

    private static boolean isLoadedCrossbow(ItemStack stack) {
        return stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
    }

    // ============================================================ заряд ветра

    private void startWind(Minecraft client) {
        int slot = findItem(stack -> stack.is(Items.WIND_CHARGE));
        if (slot == -1) {
            Notifications.push("ClickAction", "Заряд ветра не найден", 2500L);
            return;
        }
        if (cooldownTicks(stackAt(slot)) > 0) {
            return;
        }
        windTicks = 0;
        windHoldSpace = 0;
        windBlockJump = false;
        if (slot >= 9 && slot != OFFHAND_SLOT) {
            int spare = spareHotbarSlot();
            int target = spare != -1 ? spare : client.player.getInventory().getSelectedSlot();
            windInvSlot = slot;
            windSlot = target;
            windStage = 1;
            return;
        }
        windInvSlot = -1;
        windSlot = slot;
        windStage = client.player.onGround() ? 2 : 4;
    }

    private void tickWind(Minecraft client) {
        windTicks++;
        switch (windStage) {
            case 1 -> {
                if (windTicks > 20) {
                    resetWind();
                    return;
                }
                InventoryUtils.click(windInvSlot, windSlot, ClickType.SWAP);
                windStage = client.player.onGround() ? 2 : 4;
                windTicks = 0;
            }
            case 2 -> {
                if (windTicks < 1) {
                    return;
                }
                useSlot(windSlot, windPitch());
                windHoldSpace = 10;
                windBlockJump = false;
                windReturnAt = System.currentTimeMillis() + returnDelayMs();
                windTicks = 0;
                windStage = 3;
            }
            case 4 -> {
                boolean landing = windAirMode.is("Landing");
                windBlockJump = landing;
                if (client.player.onGround() || !landing && groundDistance() <= 2.5) {
                    windTicks = 0;
                    windStage = 2;
                } else if (windTicks > 100) {
                    windBlockJump = false;
                    windReturnAt = 0L;
                    windStage = 3;
                }
            }
            case 3 -> {
                if (windInvSlot == -1) {
                    windStage = 0;
                } else if (System.currentTimeMillis() >= windReturnAt) {
                    InventoryUtils.click(windInvSlot, windSlot, ClickType.SWAP);
                    windInvSlot = -1;
                    windStage = 0;
                }
            }
            default -> {
            }
        }
        if (windHoldSpace > 0) {
            windHoldSpace--;
        }
    }

    private void resetWind() {
        if (windInvSlot != -1 && windSlot != -1) {
            InventoryUtils.click(windInvSlot, windSlot, ClickType.SWAP);
        }
        windStage = 0;
        windSlot = -1;
        windInvSlot = -1;
        windTicks = 0;
        windHoldSpace = 0;
        windBlockJump = false;
        windReturnAt = 0L;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    private void onInput(InputEvent event) {
        // Прыжок сразу после броска сохраняет импульс; в режиме Landing прыжок блокируется до земли.
        if (windHoldSpace > 0) {
            event.setJumping(true);
        } else if (windBlockJump) {
            event.setJumping(false);
        }
    }

    /** Взгляд вниз; у стены наклон меньше, чтобы заряд упал под ноги, а не в стену. */
    private float windPitch() {
        if (mc.player == null || mc.level == null) {
            return DOWN_PITCH;
        }
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw) * 0.45D;
        double fz = Math.cos(yaw) * 0.45D;
        AABB slice = new AABB(
                mc.player.getX() - 0.2D, mc.player.getEyeY() - 0.3D, mc.player.getZ() - 0.2D,
                mc.player.getX() + 0.2D, mc.player.getEyeY() + 0.1D, mc.player.getZ() + 0.2D);
        return mc.level.noCollision(mc.player, slice.move(fx, 0.0D, fz)) ? DOWN_PITCH : WALL_PITCH;
    }

    private double groundDistance() {
        if (mc.player == null || mc.level == null) {
            return Double.MAX_VALUE;
        }
        for (double d = 0.0D; d <= 3.0D; d += 0.25D) {
            if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0.0D, -d, 0.0D))) {
                return d;
            }
        }
        return Double.MAX_VALUE;
    }

    // ============================================================ утилиты

    private ItemStack stackAt(int slot) {
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        return slot == OFFHAND_SLOT ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    private int findItem(Predicate<ItemStack> match) {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            if (match.test(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        if (match.test(mc.player.getOffhandItem())) {
            return OFFHAND_SLOT;
        }
        for (int i = 9; i < 36; i++) {
            if (match.test(mc.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private int spareHotbarSlot() {
        int selected = mc.player.getInventory().getSelectedSlot();
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            if (i == selected) {
                continue;
            }
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                return i;
            }
            if (fallback == -1 && !isKeptInHotbar(stack)) {
                fallback = i;
            }
        }
        return fallback;
    }

    private boolean isKeptInHotbar(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.getItem() instanceof MaceItem
                || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.ENDER_PEARL) || stack.is(Items.WIND_CHARGE)
                || stack.is(Items.FIREWORK_ROCKET) || stack.is(Items.EXPERIENCE_BOTTLE) || stack.is(Items.CROSSBOW)
                || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.POTION)
                || stack.has(DataComponents.FOOD) || stack.getItem() instanceof BlockItem;
    }

    private void useSlot(int slot, Float pitch) {
        if (slot == -1 || mc.player == null || mc.gameMode == null) {
            return;
        }
        InteractionHand hand = slot == OFFHAND_SLOT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int current = mc.player.getInventory().getSelectedSlot();
        if (hand == InteractionHand.MAIN_HAND) {
            InventoryUtils.syncSelectedHotbarSlot(slot);
        }
        if (pitch == null) {
            mc.gameMode.useItem(mc.player, hand);
        } else {
            InventoryUtils.sendUsePacket(hand, mc.player.getYRot(), pitch);
        }
        mc.player.swing(hand);
        if (hand == InteractionHand.MAIN_HAND && slot != current) {
            InventoryUtils.syncSelectedHotbarSlot(current);
        }
    }

    private int cooldownTicks(ItemStack stack) {
        if (mc.player == null || stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            ItemCooldownsAccessor cooldowns = (ItemCooldownsAccessor) mc.player.getCooldowns();
            Identifier group = cooldowns.releon$getCooldownGroup(stack);
            var active = cooldowns.releon$getCooldowns();
            Object instance = active.get(group);
            if (instance instanceof ItemCooldownInstanceAccessor accessor) {
                return Math.max(0, accessor.releon$getEndTime() - cooldowns.releon$getTickCount());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static long returnDelayMs() {
        float r = ThreadLocalRandom.current().nextFloat();
        return (r < 0.6F ? 1 : r < 0.9F ? 2 : 3) * 50L - 15L;
    }
}
