package releon.ru.api.module.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public class MaceHelper extends Module {
    private static MaceHelper instance;
    private static final Minecraft MC = Minecraft.getInstance();

    private static final double FALL_TARGET_RANGE = 6.0;
    private static final long MOVEMENT_TIMEOUT_MS = 400L;
    private static final long MOVEMENT_SETTLE_MS = 100L;
    private static final long WIND_RESTORE_RETRY_MS = 60L;
    private static final int WIND_RESTORE_MAX_TRIES = 10;
    private static final float THROW_PITCH = 90.0F;
    private static final float MACE_SMASH_FALL = 1.5F;

    private final BindSetting windKey = register(new BindSetting("Клавиша заряда ветра", "Запускает бросок заряда ветра вниз и взятие булавы.", KeyBind.NONE));
    private final BooleanSetting returnSlot = register(new BooleanSetting("Возвращать слот", "Возвращать прежний слот после цикла.", true));
    private final BooleanSetting takeMace = register(new BooleanSetting("Брать булаву", "Брать булаву в руку после броска заряда.", true));
    private final BooleanSetting autoMace = register(new BooleanSetting("Авто булава", "Брать булаву, когда ты выше врага больше порога.", true));
    private final NumberSetting fallThreshold = register(new NumberSetting("Порог падения", "Разница высот с врагом для авто-булавы.", 3.0, 0.5, 10.0, 0.5));

    private int step = 0;
    private long timerStart = 0L;
    private int windSlot = -1;
    private int maceSlot = -1;
    private int oldSlot = -1;
    private int usedMaceSwapSlot = -1;
    private int windSwapSlot = -1;
    private boolean usedWindSwap = false;
    private boolean usedMaceSwap = false;
    private boolean windSlotSwitched = false;
    private ItemStack windSwapHotbarBefore = ItemStack.EMPTY;
    private boolean windRestorePending = false;
    private int windRestoreTries = 0;
    private long windRestoreNextTry = 0L;
    private boolean attackDetected = false;
    private float lastAttackScale = 1.0F;
    private boolean wasAboveEnemy = false;
    private long movementStopTime = 0L;
    private boolean lastKeyDown = false;
    private boolean step5Airborne = false;
    private int autoOldSlot = -1;
    private int autoSwapInvSlot = -1;
    private boolean autoAirborne = false;
    private float autoLastScale = 1.0F;
    private boolean smashedThisFall = false;

    public MaceHelper() {
        super("Mace Helper", "Булава + заряд ветра.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceHelper getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        returnAutoMace();
        reset();
        if (usedWindSwap && MC.player != null) {
            Notifications.push("MaceHelper", "Слот заряда не вернулся — верни предмет вручную", 3000L);
        }
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (MC.player == null || MC.level == null) {
            clearWindSlotState();
            windSlotSwitched = false;
            clearSequenceState();
            clearAutoState();
            lastKeyDown = false;
            return;
        }
        if (!isEnabled()) {
            return;
        }

        if (MC.player.onGround()) {
            smashedThisFall = false;
        }

        handleWindKey();

        if (windRestorePending && System.currentTimeMillis() >= windRestoreNextTry) {
            restoreWindSlot();
        }

        if (step == 0) {
            handleFallThreshold();
            handleAutoReturn();
            return;
        }

        if (step <= 2) {
            stopMovement();
        }

        switch (step) {
            case 1 -> {
                if (canUseWind() && timePassed(35L)) {
                    selectWindSlot(windSlot);
                    resetTimer();
                    step = 2;
                }
            }
            case 2 -> {
                if (timePassed(60L)) {
                    sendWindUse();
                    restoreWindSlot();
                    restoreMovement();
                    resetTimer();
                    step = 3;
                }
            }
            case 3 -> {
                if (timePassed(80L)) {
                    MC.options.keyJump.setDown(true);
                    resetTimer();
                    step = 4;
                }
            }
            case 4 -> {
                MC.options.keyJump.setDown(false);
                if (timePassed(40L)) {
                    if (takeMace.getValue() && maceSlot != -1) {
                        selectMaceSlot(maceSlot);
                        lastAttackScale = MC.player.getAttackStrengthScale(0.0F);
                        attackDetected = false;
                        step5Airborne = false;
                        resetTimer();
                        step = 5;
                    } else {
                        if (returnSlot.getValue()) {
                            restoreOriginalSlot();
                        }
                        resetTimer();
                        step = 6;
                    }
                }
            }
            case 5 -> {
                // Удар делает только килаура. Мы лишь замечаем, что он произошёл (упал кулдаун атаки).
                float scale = MC.player.getAttackStrengthScale(0.0F);
                if (!attackDetected && (smashedThisFall || scale < lastAttackScale - 0.3F)) {
                    attackDetected = true;
                    resetTimer();
                }
                lastAttackScale = scale;

                if (!MC.player.onGround()) {
                    step5Airborne = true;
                }
                boolean landed = step5Airborne && MC.player.onGround() && timePassed(150L);
                boolean timeout = timePassed(3000L);
                if ((attackDetected && timePassed(80L)) || landed || timeout) {
                    if (returnSlot.getValue()) {
                        restoreOriginalSlot();
                    }
                    resetTimer();
                    step = 6;
                }
            }
            case 6 -> {
                if (timePassed(80L)) {
                    reset();
                }
            }
            default -> {
            }
        }
    }

    private void handleWindKey() {
        if (MC.screen != null || MC.getWindow() == null || !windKey.getValue().isBound()) {
            lastKeyDown = false;
            return;
        }
        boolean down = windKey.getValue().isDown(MC.getWindow().handle());
        if (down && !lastKeyDown) {
            start();
        }
        lastKeyDown = down;
    }

    private void start() {
        if (step != 0) {
            return;
        }
        returnAutoMace();

        if (usedWindSwap || windRestorePending || windSlotSwitched) {
            restoreWindSlot();
            if (usedWindSwap || windRestorePending || windSlotSwitched) {
                return;
            }
        }

        windSlot = findSlot(Items.WIND_CHARGE);
        maceSlot = findSlot(Items.MACE);
        if (windSlot == -1) {
            Notifications.push("MaceHelper", "Заряд ветра не найден!", 2000L);
        } else if (isWindCoolingDown()) {
            Notifications.push("MaceHelper", "Заряд ветра на КД", 2000L);
        } else {
            stopMovement();
            movementStopTime = System.currentTimeMillis();
            oldSlot = MC.player.getInventory().getSelectedSlot();
            usedMaceSwap = false;
            usedMaceSwapSlot = -1;
            attackDetected = false;
            resetTimer();
            step = 1;
        }
    }

    private void reset() {
        if (MC.player != null) {
            restoreMovement();
            MC.options.keyJump.setDown(false);
            restoreWindSlot();
            if (usedMaceSwap) {
                MC.getConnection().send(new ServerboundContainerClosePacket(MC.player.inventoryMenu.containerId));
            }
        }
        clearSequenceState();
    }

    private void clearSequenceState() {
        step = 0;
        windSlot = -1;
        maceSlot = -1;
        usedMaceSwapSlot = -1;
        usedMaceSwap = false;
        attackDetected = false;
        step5Airborne = false;
        wasAboveEnemy = false;
        movementStopTime = 0L;
        if (!usedWindSwap && !windSlotSwitched && !windRestorePending) {
            oldSlot = -1;
        }
    }

    private void clearWindSlotState() {
        usedWindSwap = false;
        windSwapSlot = -1;
        windSwapHotbarBefore = ItemStack.EMPTY;
        windRestorePending = false;
        windRestoreTries = 0;
        windRestoreNextTry = 0L;
    }

    private void clearAutoState() {
        autoOldSlot = -1;
        autoSwapInvSlot = -1;
        autoAirborne = false;
        autoLastScale = 1.0F;
    }

    private void handleFallThreshold() {
        if (!autoMace.getValue()) {
            wasAboveEnemy = false;
            return;
        }
        boolean above = isAboveEnemy(fallThreshold.getFloat());
        boolean justAbove = above && !wasAboveEnemy;
        wasAboveEnemy = above;
        if (justAbove) {
            selectAutoMace();
        }
    }

    private boolean isAboveEnemy(float threshold) {
        double y = MC.player.getY();
        for (Player other : MC.level.players()) {
            if (other != MC.player
                    && other.isAlive()
                    && !other.isCreative()
                    && !isFriend(other)
                    && MC.player.distanceTo(other) <= FALL_TARGET_RANGE
                    && y - other.getY() >= threshold) {
                return true;
            }
        }
        return false;
    }

    private void selectAutoMace() {
        if (MC.player.getMainHandItem().is(Items.MACE)) {
            return;
        }
        int hotbar = findMaceInHotbar();
        int selected = MC.player.getInventory().getSelectedSlot();
        if (hotbar != -1) {
            autoOldSlot = selected;
            autoSwapInvSlot = -1;
            autoAirborne = false;
            selectHotbar(hotbar);
            autoLastScale = MC.player.getAttackStrengthScale(0.0F);
            return;
        }
        int inv = findMaceInInventory();
        if (inv == -1) {
            return;
        }
        autoOldSlot = selected;
        autoSwapInvSlot = inv;
        autoAirborne = false;
        InventoryUtils.swap(inv, selected);
        autoLastScale = MC.player.getAttackStrengthScale(0.0F);
    }

    /**
     * Возвращает слот после авто-булавы:
     *  - когда килаура ударила (упал кулдаун атаки в воздухе),
     *  - или когда игрок приземлился.
     */
    private void handleAutoReturn() {
        if (autoOldSlot == -1) {
            return;
        }
        float scale = MC.player.getAttackStrengthScale(0.0F);
        boolean attacked = !MC.player.onGround() && scale < autoLastScale - 0.3F;
        autoLastScale = scale;

        if (!MC.player.onGround()) {
            autoAirborne = true;
        } else if (autoAirborne) {
            returnAutoMace();
            return;
        }
        if (attacked || smashedThisFall) {
            returnAutoMace();
        }
    }

    private void returnAutoMace() {
        if (autoOldSlot == -1 || MC.player == null) {
            clearAutoState();
            return;
        }
        if (returnSlot.getValue()) {
            if (autoSwapInvSlot != -1) {
                InventoryUtils.swap(autoSwapInvSlot, autoOldSlot);
            }
            selectHotbar(autoOldSlot);
        }
        clearAutoState();
    }

    private boolean isFriend(Player player) {
        return false;
    }

    private void selectWindSlot(int slot) {
        if (MC.player == null || slot < 0) {
            return;
        }
        if (MC.player.getMainHandItem().is(Items.WIND_CHARGE)) {
            return;
        }
        if (slot == oldSlot) {
            windSwapSlot = -1;
        } else if (slot < 9) {
            windSlotSwitched = true;
            windSwapSlot = -1;
            selectHotbar(slot);
        } else {
            windSwapHotbarBefore = MC.player.getInventory().getItem(oldSlot).copy();
            if (!clickSwap(slot, oldSlot)) {
                windSwapHotbarBefore = ItemStack.EMPTY;
            } else {
                windSwapSlot = slot;
                usedWindSwap = true;
                windRestorePending = false;
                windRestoreTries = 0;
            }
        }
    }

    private void restoreWindSlot() {
        if (MC.player == null) {
            clearWindSlotState();
            windSlotSwitched = false;
            return;
        }
        if (windSlotSwitched) {
            windSlotSwitched = false;
            selectHotbar(oldSlot);
        }
        if (usedWindSwap && windSwapSlot != -1) {
            clickSwap(windSwapSlot, oldSlot);
            if (isWindSlotRestored()) {
                clearWindSlotState();
            } else if (++windRestoreTries >= WIND_RESTORE_MAX_TRIES) {
                clearWindSlotState();
                Notifications.push("MaceHelper", "Не удалось вернуть слот заряда — проверь инвентарь", 3000L);
            } else {
                windRestorePending = true;
                windRestoreNextTry = System.currentTimeMillis() + WIND_RESTORE_RETRY_MS;
            }
        }
    }

    private boolean isWindSlotRestored() {
        if (MC.player == null) {
            return true;
        }
        ItemStack stack = MC.player.getInventory().getItem(oldSlot);
        if (stack.is(Items.WIND_CHARGE)) {
            return false;
        }
        return windSwapHotbarBefore.isEmpty()
                ? stack.isEmpty()
                : stack.is(windSwapHotbarBefore.getItem()) && stack.getCount() == windSwapHotbarBefore.getCount();
    }

    private void selectMaceSlot(int slot) {
        int hotbar = findMaceInHotbar();
        if (hotbar != -1) {
            usedMaceSwap = false;
            usedMaceSwapSlot = -1;
            maceSlot = hotbar;
            selectHotbar(hotbar);
        } else if (slot >= 9) {
            usedMaceSwap = true;
            usedMaceSwapSlot = oldSlot;
            InventoryUtils.swap(slot, oldSlot);
        }
    }

    private void restoreOriginalSlot() {
        if (MC.player == null) {
            return;
        }
        if (usedMaceSwap && usedMaceSwapSlot != -1) {
            InventoryUtils.swap(maceSlot, usedMaceSwapSlot);
        }
        restoreWindSlot();
        if (oldSlot >= 0 && oldSlot < 9) {
            selectHotbar(oldSlot);
        }
    }

    private void selectHotbar(int slot) {
        MC.player.getInventory().setSelectedSlot(slot);
        MC.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    private boolean clickSwap(int inventorySlot, int hotbarSlot) {
        if (MC.player == null || MC.gameMode == null) {
            return false;
        }
        if (MC.player.containerMenu != MC.player.inventoryMenu) {
            return false;
        }
        InventoryUtils.swap(inventorySlot, hotbarSlot);
        return true;
    }

    private int findSlot(Item item) {
        for (int i = 0; i < 36; i++) {
            if (MC.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int findMaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (MC.player.getInventory().getItem(i).is(Items.MACE)) {
                return i;
            }
        }
        return -1;
    }

    private int findMaceInInventory() {
        for (int i = 9; i < 36; i++) {
            if (MC.player.getInventory().getItem(i).is(Items.MACE)) {
                return i;
            }
        }
        return -1;
    }

    private void sendWindUse() {
        float oldPitch = MC.player.getXRot();
        MC.player.setXRot(THROW_PITCH);
        try {
            MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
        } finally {
            MC.player.setXRot(oldPitch);
        }
        MC.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean isWindCoolingDown() {
        return MC.player != null && MC.player.getCooldowns().isOnCooldown(new ItemStack(Items.WIND_CHARGE));
    }

    private boolean canUseWind() {
        long elapsed = System.currentTimeMillis() - movementStopTime;
        return elapsed >= MOVEMENT_TIMEOUT_MS || (elapsed >= MOVEMENT_SETTLE_MS && isMovementStopped());
    }

    private boolean isMovementStopped() {
        return MC.player == null
                || (!MC.options.keyUp.isDown()
                && !MC.options.keyDown.isDown()
                && !MC.options.keyLeft.isDown()
                && !MC.options.keyRight.isDown()
                && !MC.player.isSprinting());
    }

    private KeyMapping[] movementKeys() {
        return new KeyMapping[]{MC.options.keyUp, MC.options.keyDown, MC.options.keyLeft, MC.options.keyRight, MC.options.keyJump};
    }

    private void stopMovement() {
        if (MC.player == null) {
            return;
        }
        for (KeyMapping key : movementKeys()) {
            key.setDown(false);
        }
        MC.options.keySprint.setDown(false);
        if (MC.player.isSprinting()) {
            MC.player.setSprinting(false);
        }
    }

    private void restoreMovement() {
        if (MC.player == null || MC.getWindow() == null) {
            return;
        }
        long handle = MC.getWindow().handle();
        for (KeyMapping key : movementKeys()) {
            key.setDown(isPhysicallyDown(key, handle));
        }
        MC.options.keySprint.setDown(isPhysicallyDown(MC.options.keySprint, handle));
    }

    private static boolean isPhysicallyDown(KeyMapping mapping, long windowHandle) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        return key.getType() == InputConstants.Type.KEYSYM && GLFW.glfwGetKey(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    private void resetTimer() {
        timerStart = System.currentTimeMillis();
    }

    private boolean timePassed(long ms) {
        return System.currentTimeMillis() - timerStart >= ms;
    }

    /**
     * Окно "разового" удара булавой за падение: пока true, килаура может ударить
     * БЕЗ полной зарядки атаки. После одного удара (onAuraAttack) окно закрывается
     * до следующего приземления, поэтому заклика нет.
     */
    public boolean allowUnchargedSmash() {
        return isEnabled()
                && !smashedThisFall
                && MC.player != null
                && !MC.player.onGround()
                && MC.player.getDeltaMovement().y < 0.0
                && MC.player.fallDistance >= MACE_SMASH_FALL
                && MC.player.getMainHandItem().is(Items.MACE);
    }

    /** Килаура сообщает, что удар прошёл. Считаем его единственным ударом за это падение. */
    public void onAuraAttack() {
        if (MC.player != null
                && !MC.player.onGround()
                && MC.player.getMainHandItem().is(Items.MACE)) {
            smashedThisFall = true;
        }
    }

    /** После удара за это падение килаура больше не бьёт булавой в воздухе. */
    public boolean blocksAuraAttack() {
        return isEnabled()
                && smashedThisFall
                && MC.player != null
                && !MC.player.onGround()
                && MC.player.getMainHandItem().is(Items.MACE);
    }

    public boolean shouldHelpAura() {
        return isEnabled();
    }

    public boolean isHoldingMace() {
        return MC.player != null && MC.player.getMainHandItem().is(Items.MACE);
    }
}