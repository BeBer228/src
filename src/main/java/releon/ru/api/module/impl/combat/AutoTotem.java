package releon.ru.api.module.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.module.impl.movement.InventoryMove;
import releon.ru.api.drag.impl.Notifications;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.Locale;
import java.util.function.Predicate;

public class AutoTotem extends Module {
    private static final long EQUIP_ATTEMPT_COOLDOWN_MS = 250L;
    private static final long SLOWING_DOWN_DELAY_MS = 1L;
    private static final long SWAP_DELAY_MS = 25L;
    private static final long AWAIT_SWITCH_TIMEOUT_MS = 650L;
    private static final long TRANSACTION_TIMEOUT_MS = 1800L;
    private static final long FAILED_RETRY_DELAY_MS = 2000L;
    private static final long SAFE_RETURN_DELAY_MS = 500L;
    private static AutoTotem instance;
    private long transactionStartedMs;
    private long nextAttemptMs;
    private long lastDangerMs;
    private long maceDangerUntilMs;
    private int stoppedTicks;
    private boolean dispatchingSwap;
    private boolean returnTransaction;
    private ItemStack expectedOffhandStack = ItemStack.EMPTY;
    private ItemStack offhandBeforeSwap = ItemStack.EMPTY;
    private Object transactionPlayer;
    private Object transactionLevel;
    private static final long RESTORE_SLOT_DELAY_MS = 25L;
    private static final long SPEEDING_UP_DELAY_MS = 25L;

    private final NumberSetting healthThreshold = register(new NumberSetting("Здоровье", "Порог здоровья.", 4.5, 1.0, 20.0, 0.5));
    private final NumberSetting elytraHealth = register(new NumberSetting("Здоровье элитры", "Порог здоровья для элитры.", 8.5, 1.0, 20.0, 0.5));
    private final MultiModeSetting triggers = register(new MultiModeSetting("Триггеры", "Триггеры для установки тотема.", new String[]{"Кристалл", "Падение", "Элитра"}, "Кристалл", "Падение", "Элитра"));
    private final BooleanSetting returnItem = register(new BooleanSetting("Вернуть предмет", "Вернуть предмет из левой руки после опасности.", true));
    private final NumberSetting crystalDistance = register(new NumberSetting("Дистанция кристалла", "Опасная дистанция до кристалла.", 4.0, 1.0, 6.0, 0.5));
    private final NumberSetting crystalHeight = register(new NumberSetting("Высота кристалла", "Опасная вертикальная дистанция до кристалла.", 2.5, 0.5, 8.0, 0.5));
    private final NumberSetting fallHeight = register(new NumberSetting("Высота падения", "Опасная высота падения.", 15.0, 5.0, 50.0, 1.0));

    private final BooleanSetting maceAbove = register(new BooleanSetting("Булава сверху", "Брать тотем при угрозе от падающего сверху игрока с булавой.", true));
    private final NumberSetting maceDistance = register(new NumberSetting("Дистанция булавы", "Горизонтальная дистанция обнаружения угрозы.", 4.5, 2.0, 8.0, 0.5));
    private final NumberSetting maceHeight = register(new NumberSetting("Высота булавы", "Высота обнаружения игрока над тобой.", 12.0, 3.0, 24.0, 1.0));

    private Object returnPlayer;
    private Object returnLevel;
    private final java.util.Map<Player, Double> observedMaceY = new java.util.IdentityHashMap<>();
    private final java.util.Map<Player, Double> observedMaceFallSpeed = new java.util.IdentityHashMap<>();

    private int totemSlot = -1;
    private int previousOffhandSourceSlot = -1;
    private float fallStartY;
    private boolean wasFalling;
    private boolean keysOverridden;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private boolean wasSprintPressed;
    private ItemStack previousOffhandStack = ItemStack.EMPTY;
    private boolean needsReturn;
    private long actionStartTime;
    private long lastEquipAttemptMs;
    private Phase phase = Phase.READY;

    private enum Phase {
        READY,
        SLOWING_DOWN,
        SWAP_TOTEM,
        AWAIT_SWITCH,
        RESTORE_SLOT,
        RETURN_SLOWING_DOWN,
        RETURN_ITEM,
        AWAIT_RETURN,
        SPEEDING_UP
    }

    public AutoTotem() {
        super("Auto Totem", "Автоматически берёт в руки тотем", ModuleCategory.COMBAT);
        instance = this;
        maceDistance.visibleWhen(maceAbove::getValue);
        maceHeight.visibleWhen(maceAbove::getValue);
        crystalDistance.visibleWhen(() -> triggers.isSelected("Кристалл"));
        crystalHeight.visibleWhen(() -> triggers.isSelected("Кристалл"));
        fallHeight.visibleWhen(() -> triggers.isSelected("Падение"));
        elytraHealth.visibleWhen(() -> triggers.isSelected("Элитра"));
    }

    public static boolean isInventoryPauseActive() {
        AutoTotem value = instance;
        return value != null && value.isEnabled() && value.phase != Phase.READY;
    }

    public static boolean isDispatchingOffhandSwap() {
        return isInventoryPauseActive() && instance.dispatchingSwap;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (isInventoryPauseActive()) {
            event.inputNone();
            event.setSprinting(false);
            stopPlayerInput(Minecraft.getInstance());
        }
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.gameMode == null || client.level == null) {
            resetState();
            clearReturnState();
            wasFalling = false;
            observedMaceY.clear();
            observedMaceFallSpeed.clear();
            lastDangerMs = maceDangerUntilMs = nextAttemptMs = 0L;
            return;
        }
        if (transactionPlayer != null && (transactionPlayer != client.player || transactionLevel != client.level)) {
            resetState();
            clearReturnState();
            wasFalling = false;
            observedMaceY.clear();
            observedMaceFallSpeed.clear();
            lastDangerMs = maceDangerUntilMs = nextAttemptMs = 0L;
        }
        if (needsReturn && (returnPlayer != client.player || returnLevel != client.level)) clearReturnState();
        updateFallTracking(client);
        updateMaceMotion(client);
        long now = System.currentTimeMillis();
        boolean danger = shouldEquipTotem(client);
        if (danger) lastDangerMs = now;
        if (phase != Phase.READY) {
            execute(client);
            return;
        }
        if (danger) {
            if (now >= nextAttemptMs && now - lastEquipAttemptMs >= EQUIP_ATTEMPT_COOLDOWN_MS) {
                tryEquipTotem(client);
            }
            return;
        }
        if (needsReturn && returnItem.getValue() && !previousOffhandStack.isEmpty()
                && now >= nextAttemptMs && now - lastDangerMs >= SAFE_RETURN_DELAY_MS) {
            if (isTotemInOffhand(client)) startLegitReturn(client);
            else clearReturnState();
        }
    }

    @Override
    protected void onDisable() {
        resetState();
        clearReturnState();
        nextAttemptMs = lastDangerMs = maceDangerUntilMs = 0L;
        wasFalling = false;
        observedMaceY.clear();
        observedMaceFallSpeed.clear();
    }

    private boolean canAccessInventory(Minecraft client) {
        return client.player != null && client.gameMode != null
                && (client.screen == null || client.screen instanceof InventoryScreen)
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.inventoryMenu.getCarried().isEmpty();
    }

    private void tryEquipTotem(Minecraft client) {
        if (phase != Phase.READY || !canAccessInventory(client) || isPreferredTotemAlreadyInOffhand(client)) return;
        InventoryMove movement = InventoryMove.getInstance();
        if (movement != null && movement.isEnabled() && movement.isBulkInventoryActionRunning()) return;
        lastEquipAttemptMs = System.currentTimeMillis();
        Slot slot = findPreferredTotemSlot(client);
        if (slot == null) return;
        totemSlot = getMenuSlotId(client, slot);
        if (totemSlot < 0) return;
        startTransaction(client, false);
    }

    private void startLegitReturn(Minecraft client) {
        if (!canAccessInventory(client)) return;
        InventoryMove movement = InventoryMove.getInstance();
        if (movement != null && movement.isEnabled() && movement.isBulkInventoryActionRunning()) return;
        startTransaction(client, true);
    }

    private void startTransaction(Minecraft client, boolean returning) {
        captureKeyStates(client);
        returnTransaction = returning;
        transactionPlayer = client.player;
        transactionLevel = client.level;
        phase = returning ? Phase.RETURN_SLOWING_DOWN : Phase.SLOWING_DOWN;
        transactionStartedMs = actionStartTime = System.currentTimeMillis();
        stoppedTicks = 0;
        stopPlayerInput(client);
    }

    private boolean movementQueueReady() {
        InventoryMove movement = InventoryMove.getInstance();
        return movement == null || !movement.isEnabled() || movement.isReadyForAutoTotemSwap();
    }

    private void transition(Phase next) {
        phase = next;
        actionStartTime = System.currentTimeMillis();
    }

    private void failTransaction(Minecraft client, String reason) {
        nextAttemptMs = System.currentTimeMillis() + FAILED_RETRY_DELAY_MS;
        Notifications.push("AutoTotem", reason, 3000L);
        resetState();
    }

    private void execute(Minecraft client) {
        if (!canAccessInventory(client)) {
            failTransaction(client, "Свап остановлен: изменился экран или занят курсор");
            return;
        }
        stopPlayerInput(client);
        long now = System.currentTimeMillis();
        if (now - transactionStartedMs > TRANSACTION_TIMEOUT_MS) {
            failTransaction(client, "Не завершился свап: проверь инвентарь/соединение");
            return;
        }
        if (returnTransaction && phase != Phase.AWAIT_RETURN && phase != Phase.SPEEDING_UP
                && shouldEquipTotem(client)) {
            resetState();
            return;
        }
        long elapsed = now - actionStartTime;
        switch (phase) {
            case SLOWING_DOWN, RETURN_SLOWING_DOWN -> {
                stoppedTicks++;
                if (stoppedTicks >= 2 && elapsed >= SLOWING_DOWN_DELAY_MS && movementQueueReady()) {
                    transition(returnTransaction ? Phase.RETURN_ITEM : Phase.SWAP_TOTEM);
                }
            }
            case SWAP_TOTEM -> {
                if (isPreferredTotemAlreadyInOffhand(client)) { transition(Phase.RESTORE_SLOT); return; }
                if (elapsed < SWAP_DELAY_MS || !movementQueueReady()) return;
                Slot source = findPreferredTotemSlot(client);
                if (source == null) { failTransaction(client, "Тотем не найден перед свапом"); return; }
                totemSlot = getMenuSlotId(client, source);
                expectedOffhandStack = source.getItem().copy();
                offhandBeforeSwap = client.player.getOffhandItem().copy();
                if (!clickOffhandSwap(client, totemSlot)) { failTransaction(client, "Свап тотема недоступен"); return; }
                transition(Phase.AWAIT_SWITCH);
            }
            case AWAIT_SWITCH -> {
                if (isTotemInOffhand(client)
                        && ItemStack.isSameItemSameComponents(client.player.getOffhandItem(), expectedOffhandStack)) {
                    if (!needsReturn) {
                        previousOffhandStack = offhandBeforeSwap.copy();
                        previousOffhandSourceSlot = totemSlot;
                        needsReturn = !previousOffhandStack.isEmpty();
                        returnPlayer = client.player;
                        returnLevel = client.level;
                    }
                    transition(Phase.RESTORE_SLOT);
                } else if (elapsed >= AWAIT_SWITCH_TIMEOUT_MS) {
                    failTransaction(client, "Тотем не появился в левой руке — попытка остановлена");
                }
            }
            case RESTORE_SLOT -> {
                if (elapsed >= RESTORE_SLOT_DELAY_MS) {
                    transition(Phase.SPEEDING_UP);
                }
            }
            case RETURN_ITEM -> {
                if (elapsed < SWAP_DELAY_MS || !movementQueueReady()) return;
                expectedOffhandStack = previousOffhandStack.copy();
                if (attemptReturnPreviousItem(client)) transition(Phase.AWAIT_RETURN);
                else { clearReturnState(); failTransaction(client, "Исходный предмет для возврата не найден"); }
            }
            case AWAIT_RETURN -> {
                if (ItemStack.isSameItemSameComponents(client.player.getOffhandItem(), expectedOffhandStack)) {
                    clearReturnState();
                    transition(Phase.SPEEDING_UP);
                } else if (elapsed >= AWAIT_SWITCH_TIMEOUT_MS) {
                    clearReturnState();
                    failTransaction(client, "Возврат предмета не подтверждён локальным инвентарём");
                }
            }
            case SPEEDING_UP -> {
                if (elapsed >= SPEEDING_UP_DELAY_MS) resetState();
            }
            case READY -> { }
        }
    }

    private void updateFallTracking(Minecraft client) {
        boolean falling = client.player.getDeltaMovement().y < -0.1 && !client.player.onGround() && !client.player.onClimbable() && !client.player.isInWater();
        if (falling && !wasFalling) {
            fallStartY = (float) client.player.getY();
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.onClimbable()) {
            fallStartY = (float) client.player.getY();
        }
        wasFalling = falling;
    }

    private boolean shouldEquipTotem(Minecraft client) {
        if (maceAbove.getValue() && checkMaceDanger(client)) return true;
        if (client.player.getHealth() <= healthThreshold.getFloat()) {
            return true;
        }
        if (triggers.isSelected("Элитра") && client.player.isFallFlying() && client.player.getHealth() <= elytraHealth.getFloat()) {
            return true;
        }
        if (triggers.isSelected("Падение") && fallStartY - (float) client.player.getY() >= fallHeight.getFloat() && client.player.getDeltaMovement().y < -0.1) {
            return true;
        }
        return triggers.isSelected("Кристалл") && checkCrystalDanger(client);
    }

    private void updateMaceMotion(Minecraft client) {
        java.util.Map<Player, Double> current = new java.util.IdentityHashMap<>();
        observedMaceFallSpeed.clear();
        for (Player other : client.level.players()) {
            Double previous = observedMaceY.get(other);
            if (previous != null) observedMaceFallSpeed.put(other, other.getY() - previous);
            current.put(other, other.getY());
        }
        observedMaceY.clear();
        observedMaceY.putAll(current);
    }

    private boolean checkMaceDanger(Minecraft client) {
        long now = System.currentTimeMillis();
        double radius = maceDistance.getValue();
        double height = maceHeight.getValue();
        for (Player other : client.level.players()) {
            if (other == client.player || !other.isAlive() || other.isSpectator() || other.isCreative()
                    || !other.getMainHandItem().is(Items.MACE) || other.onGround()) continue;
            double dy = other.getY() - client.player.getY();
            double vy = Math.min(other.getDeltaMovement().y,
                    observedMaceFallSpeed.getOrDefault(other, other.getDeltaMovement().y));
            if (dy < 0.5 || dy > height || vy >= -0.08) continue;
            double dx = other.getX() - client.player.getX();
            double dz = other.getZ() - client.player.getZ();
            double horizontal = Math.hypot(dx, dz);
            double ticks = Math.min(10.0, dy / Math.max(0.1, -vy));
            double predictedX = dx + (other.getDeltaMovement().x - client.player.getDeltaMovement().x) * ticks;
            double predictedZ = dz + (other.getDeltaMovement().z - client.player.getDeltaMovement().z) * ticks;
            if (horizontal <= radius || (horizontal <= radius + 3.0 && Math.hypot(predictedX, predictedZ) <= radius)) {
                maceDangerUntilMs = now + 450L;
                return true;
            }
        }
        return now < maceDangerUntilMs;
    }

    private boolean checkCrystalDanger(Minecraft client) {
        double maxHorizontal = crystalDistance.getValue();
        double maxVertical = crystalHeight.getValue();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal)) {
                continue;
            }
            double vertical = Math.abs(entity.getY() - client.player.getY());
            if (vertical > maxVertical) {
                continue;
            }
            double horizontal = Math.hypot(entity.getX() - client.player.getX(), entity.getZ() - client.player.getZ());
            if (horizontal <= maxHorizontal) {
                return true;
            }
        }
        return false;
    }

    private Slot findPreferredTotemSlot(Minecraft client) {
        Slot plain = findSlot(client, this::isPlainTotem);
        if (plain != null) {
            return plain;
        }

        Slot nonSphere = findSlot(client, this::isTotemButNotSphere);
        if (nonSphere != null) {
            return nonSphere;
        }

        return findSlot(client, stack -> stack.getItem() == Items.TOTEM_OF_UNDYING);
    }

    private Slot findSlot(Minecraft client, Predicate<ItemStack> predicate) {
        for (int i = 0; i < client.player.inventoryMenu.slots.size(); i++) {
            Slot slot = client.player.inventoryMenu.getSlot(i);
            if (!isValidInventorySlot(client, i, slot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return slot;
            }
        }
        return null;
    }

    private boolean isValidInventorySlot(Minecraft client, int slotId, Slot slot) {
        return slot != null
                && slot.container == client.player.getInventory()
                && slotId >= 9 && slotId <= 44;
    }

    private boolean isPlainTotem(ItemStack stack) {
        return isTotemButNotSphere(stack) && !stack.isEnchanted();
    }

    private boolean isTotemButNotSphere(ItemStack stack) {
        return stack.getItem() == Items.TOTEM_OF_UNDYING && !isSphereTotem(stack);
    }

    private boolean isSphereTotem(ItemStack stack) {
        if (stack.getItem() != Items.TOTEM_OF_UNDYING) {
            return false;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("sphere");
    }

    private boolean isTotemInOffhand(Minecraft client) {
        return client.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean isPreferredTotemAlreadyInOffhand(Minecraft client) {
        if (!isTotemInOffhand(client)) {
            return false;
        }
        ItemStack offhand = client.player.getOffhandItem();
        return isPlainTotem(offhand) || findSlot(client, this::isPlainTotem) == null;
    }

    private boolean attemptReturnPreviousItem(Minecraft client) {
        if (!returnItem.getValue() || previousOffhandStack.isEmpty() || !isTotemInOffhand(client)) {
            return false;
        }

        if (isValidMenuSlot(client, previousOffhandSourceSlot)) {
            Slot restoreSlot = client.player.inventoryMenu.getSlot(previousOffhandSourceSlot);
            if (stackMatchesForReturn(restoreSlot.getItem())) {
                return clickOffhandSwap(client, previousOffhandSourceSlot);
            }
        }

        Slot exactSlot = findSlot(client, this::stackMatchesForReturn);
        if (exactSlot != null) {
            return clickOffhandSwap(client, getMenuSlotId(client, exactSlot));
        }

        return false;
    }

    private boolean stackMatchesForReturn(ItemStack stack) {
        if (stack == null || stack.isEmpty() || previousOffhandStack.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(stack, previousOffhandStack);
    }

    private boolean clickOffhandSwap(Minecraft client, int slotId) {
        if (!canAccessInventory(client) || !isValidMenuSlot(client, slotId)
                || slotId < 9 || slotId > 44 || !movementQueueReady()) return false;
        dispatchingSwap = true;
        try {
            client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slotId, 40, ClickType.SWAP, client.player);
            return true;
        } finally {
            dispatchingSwap = false;
        }
    }

    private boolean isValidMenuSlot(Minecraft client, int slotId) {
        return slotId >= 0 && slotId < client.player.inventoryMenu.slots.size();
    }

    private int getMenuSlotId(Minecraft client, Slot slot) {
        if (slot == null) {
            return -1;
        }
        for (int i = 0; i < client.player.inventoryMenu.slots.size(); i++) {
            if (client.player.inventoryMenu.getSlot(i) == slot) {
                return i;
            }
        }
        return -1;
    }

    private void captureKeyStates(Minecraft client) {
        wasForwardPressed = isCurrentlyPressed(client, client.options.keyUp);
        wasBackPressed = isCurrentlyPressed(client, client.options.keyDown);
        wasLeftPressed = isCurrentlyPressed(client, client.options.keyLeft);
        wasRightPressed = isCurrentlyPressed(client, client.options.keyRight);
        wasJumpPressed = isCurrentlyPressed(client, client.options.keyJump);
        wasSprintPressed = isCurrentlyPressed(client, client.options.keySprint);
    }

    private void stopPlayerInput(Minecraft client) {
        if (client.player == null) return;
        client.player.setSprinting(false);
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        keysOverridden = true;
    }

    private void restoreKeyStates(Minecraft client) {
        if (!keysOverridden || client.options == null) {
            return;
        }
        client.options.keyUp.setDown(wasForwardPressed && isCurrentlyPressed(client, client.options.keyUp));
        client.options.keyDown.setDown(wasBackPressed && isCurrentlyPressed(client, client.options.keyDown));
        client.options.keyLeft.setDown(wasLeftPressed && isCurrentlyPressed(client, client.options.keyLeft));
        client.options.keyRight.setDown(wasRightPressed && isCurrentlyPressed(client, client.options.keyRight));
        client.options.keyJump.setDown(wasJumpPressed && isCurrentlyPressed(client, client.options.keyJump));
        client.options.keySprint.setDown(wasSprintPressed && isCurrentlyPressed(client, client.options.keySprint));
        keysOverridden = false;
    }

    private boolean isCurrentlyPressed(Minecraft client, KeyMapping key) {
        if (client.getWindow() == null || key == null) {
            return false;
        }
        InputConstants.Key input = InputConstants.getKey(key.saveString());
        return switch (input.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), input.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(client.getWindow().handle(), input.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    private void clearReturnState() {
        returnPlayer = returnLevel = null;
        previousOffhandStack = ItemStack.EMPTY;
        previousOffhandSourceSlot = -1;
        needsReturn = false;
    }

    private void resetState() {
        Minecraft client = Minecraft.getInstance();
        phase = Phase.READY;
        if (client.player != null && client.screen == null) {
            restoreKeyStates(client);
        }
        transactionPlayer = transactionLevel = null;
        dispatchingSwap = returnTransaction = false;
        expectedOffhandStack = offhandBeforeSwap = ItemStack.EMPTY;
        transactionStartedMs = 0L;
        stoppedTicks = 0;
        totemSlot = -1;
        actionStartTime = 0L;
        phase = Phase.READY;
        keysOverridden = false;
    }
}
