package releon.ru.api.drag.impl;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

public final class Inventory extends HudPanel {
    private static final ItemStack[] PREVIEW = {
            Items.ENDER_PEARL.getDefaultInstance(),
            Items.GOLDEN_APPLE.getDefaultInstance(),
            Items.TOTEM_OF_UNDYING.getDefaultInstance(),
            Items.SUGAR.getDefaultInstance(),
            Items.DIAMOND_SWORD.getDefaultInstance(),
            Items.BOW.getDefaultInstance()
    };

    private static final float HEADER_HEIGHT = HudStyle.HEADER;
    private static final float CELL_SIZE = 12.5F;
    private static final float CELL_GAP = 1.0F;
    private static final int ITEMS_PER_ROW = 9;
    private static final int ROWS = 3;

    private final ItemStack[] inventoryStacks = new ItemStack[27];
    private int totalItemCount = 0;

    public Inventory() {
        super("inventory", "Inventory", 10.0F, 260.0F, 126.0F, 62.0F);
        for (int i = 0; i < inventoryStacks.length; i++) {
            inventoryStacks[i] = ItemStack.EMPTY;
        }
    }

    @Override
    protected void renderHud() {
        InventoryState state = logics();
        if (state == null) {
            return;
        }
        renderInventory(state);
    }

    private InventoryState logics() {
        boolean hasItems = inventoryItems();
        boolean preview = !hasItems && editPreview();
        boolean visible = hasItems || preview;
        float alpha = contentAlpha(visible);
        if (alpha <= 0.0F) {
            return null;
        }

        float width = ITEMS_PER_ROW * CELL_SIZE + (ITEMS_PER_ROW - 1) * CELL_GAP + 9.0F;
        float height = HEADER_HEIGHT + ROWS * CELL_SIZE + (ROWS - 1) * CELL_GAP + 8.0F;
        size(width, height);

        return new InventoryState(
                preview ? PREVIEW : inventoryStacks,
                preview ? PREVIEW.length : inventoryStacks.length,
                totalItemCount,
                alpha,
                drag.x(),
                drag.y(),
                drag.width(),
                drag.height()
        );
    }

    private void renderInventory(InventoryState state) {
        float x = state.x;
        float y = state.y;
        float w = state.width;
        float alpha = state.alpha;

        card(x, y, w, state.height, "B", 7.5F, "Inventory", Integer.toString(state.itemCount), alpha);

        float gridW = ITEMS_PER_ROW * CELL_SIZE + (ITEMS_PER_ROW - 1) * CELL_GAP;
        float gridX = x + (w - gridW) * 0.5F;
        float gridY = y + HEADER_HEIGHT + 3.0F;

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < ITEMS_PER_ROW; col++) {
                float cellX = gridX + col * (CELL_SIZE + CELL_GAP);
                float cellY = gridY + row * (CELL_SIZE + CELL_GAP);
                int index = row * ITEMS_PER_ROW + col;
                boolean filled = index < state.count && state.items[index] != null && !state.items[index].isEmpty();
                Render2D.rect(cellX, cellY, CELL_SIZE, CELL_SIZE, 2.5F, HudStyle.white(filled ? 12.0F : 6.0F, alpha));
            }
        }

        for (int i = 0; i < state.count; i++) {
            ItemStack stack = state.items[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int col = i % ITEMS_PER_ROW;
            int row = i / ITEMS_PER_ROW;
            float cellX = gridX + col * (CELL_SIZE + CELL_GAP);
            float cellY = gridY + row * (CELL_SIZE + CELL_GAP);
            RenderItem.item(stack, cellX + 1.0F, cellY + 1.0F, CELL_SIZE - 2.0F, RenderItemOptions.countNoDurability(alpha));
        }
    }

    private boolean inventoryItems() {
        if (mc.player == null) {
            clearInventoryCache();
            totalItemCount = 0;
            return false;
        }

        boolean hasItems = false;
        int count = 0;

        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            ItemStack safeStack = stack == null ? ItemStack.EMPTY : stack;
            inventoryStacks[slot - 9] = safeStack;

            if (!safeStack.isEmpty()) {
                hasItems = true;
                count += safeStack.getCount();
            }
        }

        totalItemCount = count;
        return hasItems;
    }

    private void clearInventoryCache() {
        for (int i = 0; i < inventoryStacks.length; i++) {
            inventoryStacks[i] = ItemStack.EMPTY;
        }
        totalItemCount = 0;
    }

    private record InventoryState(
            ItemStack[] items,
            int count,
            int itemCount,
            float alpha,
            float x,
            float y,
            float width,
            float height
    ) {
    }
}
