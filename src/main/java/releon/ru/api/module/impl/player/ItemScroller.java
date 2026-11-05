package releon.ru.api.module.impl.player;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.ClickSlotEvent;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.ArrayList;
import java.util.List;

public final class ItemScroller extends Module {
    private final NumberSetting scrollDelay = register(new NumberSetting(
            "Scroll Delay", "Delay between item scroll clicks.", 50.0, 0.0, 200.0, 1.0));
    private boolean processingMassMove;
    private AbstractContainerMenu hoverMenu;
    private long nextHoverClickNs;
    private long nextMassMoveNs;

    public ItemScroller() {
        super("ItemScroller", "Item Scroller", ModuleCategory.PLAYER);
    }

    @Override
    protected void onDisable() {
        processingMassMove = false;
        resetHover();
        nextMassMoveNs = 0L;
    }

    private void resetHover() {
        hoverMenu = null;
        nextHoverClickNs = 0L;
    }

    private static boolean isDown(KeyMapping mapping) {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return false;
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(mapping.saveString());
        } catch (Exception ignored) {
            return false;
        }
        int code = key.getValue();
        if (code == InputConstants.UNKNOWN.getValue()) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return code >= 0 && code <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
                    && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
        }
        // Scancode bindings are not GLFW key-symbol IDs.
        return mapping.isDown();
    }

    private static boolean isShiftDown(Minecraft client) {
        if (isDown(client.options.keyShift)) return true;
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
    }

    /** Resolve the click ID from the active menu, never from an inventory index/helper. */
    private static int findMenuSlotId(AbstractContainerMenu menu, Slot slot) {
        if (menu == null || slot == null) return -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) == slot) return i;
        }
        // A slot from an old/other screen must not address the current menu.
        return -1;
    }

    @SubscribeEvent
    private void onHandledScreen(HandledScreenEvent event) {
        if (processingMassMove) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            resetHover();
            return;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        Slot slot = event.getSlotHover();
        ClickType action = isDown(client.options.keyDrop) ? ClickType.THROW
                : isDown(client.options.keyAttack) ? ClickType.QUICK_MOVE : null;
        if (!isShiftDown(client) || isDown(client.options.keySprint) || action == null
                || slot == null || !menu.getCarried().isEmpty()) {
            resetHover();
            return;
        }
        if (hoverMenu != menu) {
            hoverMenu = menu;
            nextHoverClickNs = 0L;
        }
        int slotId = findMenuSlotId(menu, slot);
        if (slotId < 0 || !slot.hasItem() || !slot.mayPickup(client.player)) return;
        long now = System.nanoTime();
        if (nextHoverClickNs != 0L && now < nextHoverClickNs) return;
        double delayMs = scrollDelay.getValue();
        if (!Double.isFinite(delayMs)) delayMs = 50.0;
        delayMs = Math.max(0.0, Math.min(200.0, delayMs));
        nextHoverClickNs = now + (long) (delayMs * 1000000.0);
        client.gameMode.handleInventoryMouseClick(menu.containerId, slotId,
                action == ClickType.THROW ? 1 : 0, action, client.player);
    }

    /** Player inventory QUICK_MOVE stays in its original source region, avoiding bounce-back. */
    private static int playerInventoryRegion(int menuSlotId) {
        if (menuSlotId >= 9 && menuSlotId <= 35) return 0;  // main inventory
        if (menuSlotId >= 36 && menuSlotId <= 44) return 1; // hotbar
        if (menuSlotId >= 5 && menuSlotId <= 8) return 2;   // armour
        if (menuSlotId == 45) return 3;                    // offhand
        if (menuSlotId >= 1 && menuSlotId <= 4) return 4;   // crafting input
        return -1;                                       // result/unknown: no mass action
    }

    @SubscribeEvent
    private void onClickSlot(ClickSlotEvent event) {
        if (processingMassMove) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int clickedId = event.getSlotId();
        if (clickedId < 0 || clickedId >= menu.slots.size() || !menu.getCarried().isEmpty()) return;
        ClickType action = event.getActionType();
        if (action != ClickType.QUICK_MOVE && action != ClickType.THROW) return;
        if (!isShiftDown(client) || !isDown(client.options.keySprint)) return;
        Slot clicked = menu.getSlot(clickedId);
        if (!clicked.hasItem() || !clicked.mayPickup(client.player)) return;
        boolean playerMenu = menu == client.player.inventoryMenu;
        int sourceRegion = playerMenu ? playerInventoryRegion(clickedId) : -1;
        if (playerMenu && sourceRegion < 0) return;
        long now = System.nanoTime();
        if (nextMassMoveNs != 0L && now < nextMassMoveNs) return;
        nextMassMoveNs = now + 50000000L;
        Item item = clicked.getItem().getItem();
        // Snapshot source slots before QUICK_MOVE changes destination stacks.
        List<Integer> sources = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot candidate = menu.slots.get(i);
            if (i == clickedId || candidate.container != clicked.container
                    || !candidate.hasItem() || !candidate.getItem().is(item)
                    || !candidate.mayPickup(client.player)) continue;
            if (playerMenu && playerInventoryRegion(i) != sourceRegion) continue;
            sources.add(i);
        }
        processingMassMove = true;
        try {
            for (int sourceId : sources) {
                if (client.player == null || client.gameMode == null
                        || client.player.containerMenu != menu || !menu.getCarried().isEmpty()) break;
                Slot source = menu.getSlot(sourceId);
                if (!source.hasItem() || !source.getItem().is(item) || !source.mayPickup(client.player)) continue;
                client.gameMode.handleInventoryMouseClick(menu.containerId, sourceId,
                        action == ClickType.THROW ? 1 : 0, action, client.player);
            }
        } finally {
            processingMassMove = false;
        }
    }
}
