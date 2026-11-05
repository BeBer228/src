package releon.ru.api.module.impl.visual;

import releon.ru.utils.render.color.ClientTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

public final class CustomHotbar extends Module {
    private static CustomHotbar instance;
    private static final Minecraft mc = Minecraft.getInstance();

    private static final float SLOT_SIZE = 18.0f;
    private static final float SLOT_GAP = 2.0f;
    private static final float CORNER_RADIUS = 6.0f;
    private static final float PADDING_X = 6.0f;
    private static final float PADDING_Y = 5.0f;

    private static final int BG_BLUR_MASK = 0xFFFFFFFF;
    private static final int BG_MAIN = ColorUtil.rgba(18, 18, 24, 235);
    private static final int BG_HIGHLIGHT = ColorUtil.rgba(255, 255, 255, 12);
    // акцент берётся из текущей темы ClickGUI (Themes) каждый кадр
    private static int accentColor() {
        return ClientTheme.primary(255);
    }

    private static int accentGlow() {
        return ClientTheme.primary(90);
    }
    private static final int DIVIDER_COLOR = ColorUtil.rgba(255, 255, 255, 30);
    private static final int TEXT_SHADOW = ColorUtil.rgba(0, 0, 0, 200);

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static float animatedSelectedX;
    private static float lastHotbarX = Float.NaN;
    private static float lastHotbarY = Float.NaN;
    private static long lastAnimationMs;
    private static float offhandProgress;
    private static long lastOffhandAnimationMs;
    private static ItemStack lastOffhandStack = ItemStack.EMPTY;

    public CustomHotbar() {
        super("CustomHotbar", "Renders a beautiful custom hotbar.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static void render(GuiGraphics graphics) {
        if (mc.player == null || mc.getWindow() == null) return;

        Render2D.beginFrame(graphics);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        float hotbarWidth = SLOT_SIZE * 9.0f + SLOT_GAP * 8.0f;
        float baseX = sw / 2.0f - hotbarWidth / 2.0f;
        float y = sh - 24.0f;

        ItemStack offhandStack = mc.player.getOffhandItem();
        updateLastOffhandStack(offhandStack);
        float offhandAlpha = animatedOffhandProgress(!offhandStack.isEmpty());

        int armorCount = armorCount();
        float armorSlot = 15.0f;
        float armorGap = 2.0f;
        float armorIcon = 12.0f;
        float armorWidth = armorCount > 0 ? armorCount * armorSlot + (armorCount - 1) * armorGap : 0.0f;
        float armorGroupGap = armorCount > 0 ? 6.0f : 0.0f;
        float armorX = baseX + hotbarWidth + armorGroupGap;

        float offhandArea = (SLOT_SIZE + SLOT_GAP) * offhandAlpha;
        float fullX = baseX - offhandArea;
        float fullWidth = hotbarWidth + offhandArea + armorGroupGap + armorWidth;

        float bgX = fullX - PADDING_X;
        float bgY = y - PADDING_Y;
        float bgW = fullWidth + PADDING_X * 2.0f;
        float bgH = SLOT_SIZE + PADDING_Y * 2.0f;

        Render2D.blur(bgX, bgY, bgW, bgH, 8.0f, 20.0f, 12.0f, BG_BLUR_MASK);
        Render2D.rect(bgX, bgY, bgW, bgH, CORNER_RADIUS, BG_MAIN);

        Render2D.outline(bgX, bgY, bgW, bgH, CORNER_RADIUS, 1.0f, ColorUtil.rgba(255, 255, 255, 25));
        Render2D.rect(bgX + 1, bgY + 1, bgW - 2, bgH - 2, CORNER_RADIUS - 1, BG_HIGHLIGHT);

        for (int i = 1; i < 9; i++) {
            float sx = baseX + i * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP * 0.5f;
            Render2D.rect(sx, y + 5.0f, 1.0f, SLOT_SIZE - 10.0f, 1.0f, DIVIDER_COLOR);
        }

        if (offhandAlpha > 0.01f) {
            float divX = baseX - SLOT_GAP * 0.5f;
            Render2D.rect(divX, y + 5.0f, 1.0f, SLOT_SIZE - 10.0f, 1.0f, ColorUtil.multAlpha(accentColor(), offhandAlpha * 0.5f));
        }

        if (armorCount > 0) {
            float divX = baseX + hotbarWidth + armorGroupGap * 0.5f;
            Render2D.rect(divX, y + 5.0f, 1.0f, SLOT_SIZE - 10.0f, 1.0f, DIVIDER_COLOR);
        }

        float selX = animatedSelectedX(baseX, y, SLOT_SIZE, SLOT_GAP);
        Render2D.rect(selX - 2, y - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, CORNER_RADIUS + 2, accentGlow());
        Render2D.outline(selX, y, SLOT_SIZE, SLOT_SIZE, CORNER_RADIUS, 1.5f, accentColor());
        Render2D.rect(selX + 1.5f, y + 1.5f, SLOT_SIZE - 3.0f, SLOT_SIZE - 3.0f, CORNER_RADIUS - 2.0f, ClientTheme.primary(25));

        Render2D.flush();

        for (int i = 0; i < 9; i++) {
            float sx = baseX + i * (SLOT_SIZE + SLOT_GAP);
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                renderVanillaItem(graphics, stack, sx + 2.0f, y + 2.0f, 14.0f);
            }
        }

        if (offhandAlpha > 0.01f && !lastOffhandStack.isEmpty()) {
            float ohX = baseX - SLOT_GAP - SLOT_SIZE;
            renderVanillaItem(graphics, lastOffhandStack, ohX + 2.0f, y + 2.0f, 14.0f);
        }

        renderArmorItems(graphics, armorX, y, SLOT_SIZE, armorSlot, armorGap, armorIcon);
        graphics.nextStratum();
        Render2D.beginFrame(graphics);

        for (int i = 0; i < 9; i++) {
            float sx = baseX + i * (SLOT_SIZE + SLOT_GAP);
            ItemStack stack = mc.player.getInventory().getItem(i);

            if (stack.isEmpty()) {
                drawSlotNumber(i, sx, y, SLOT_SIZE);
            } else {
                drawItemDecorations(stack, sx + 2.0f, y + 2.0f, 14.0f, 1.0f);
                drawStackCount(stack, sx, y, SLOT_SIZE);
            }
        }

        if (offhandAlpha > 0.01f && !lastOffhandStack.isEmpty()) {
            float ohX = baseX - SLOT_GAP - SLOT_SIZE;
            drawItemDecorations(lastOffhandStack, ohX + 2.0f, y + 2.0f, 14.0f, offhandAlpha);
            drawStackCount(lastOffhandStack, ohX, y, SLOT_SIZE, offhandAlpha);
        }

        drawArmorDecorations(armorX, y, SLOT_SIZE, armorSlot, armorGap, armorIcon);

        if (offhandAlpha <= 0.01f && offhandStack.isEmpty()) {
            lastOffhandStack = ItemStack.EMPTY;
        }

        Render2D.flush();
        graphics.nextStratum();
    }

    private static void renderVanillaItem(GuiGraphics graphics, ItemStack stack, float x, float y, float size) {
        if (graphics == null || stack.isEmpty() || size <= 0.0f) return;

        float scale = size / 16.0f;
        graphics.pose().pushMatrix();
        try {
            Render2DCoordinateSpace.applyGuiScaleIndependence(graphics.pose());
            graphics.pose().translate(x, y);
            graphics.pose().scale(scale);
            graphics.renderItem(stack, 0, 0);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static int armorCount() {
        int count = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!mc.player.getItemBySlot(slot).isEmpty()) count++;
        }
        return count;
    }

    private static void renderArmorItems(GuiGraphics graphics, float x, float y, float slotSize, float armorSlot, float gap, float iconSize) {
        int index = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;

            float sx = x + index * (armorSlot + gap);
            renderVanillaItem(graphics, stack, sx + (armorSlot - iconSize) * 0.5f, y + (slotSize - iconSize) * 0.5f, iconSize);
            index++;
        }
    }

    private static void drawArmorDecorations(float x, float y, float slotSize, float armorSlot, float gap, float iconSize) {
        int index = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;

            float sx = x + index * (armorSlot + gap);
            drawItemDecorations(stack, sx + (armorSlot - iconSize) * 0.5f, y + (slotSize - iconSize) * 0.5f, iconSize, 1.0f);
            index++;
        }
    }

    private static void updateLastOffhandStack(ItemStack offhandStack) {
        if (offhandStack.isEmpty()) return;
        if (shouldRefreshCachedStack(lastOffhandStack, offhandStack)) {
            lastOffhandStack = offhandStack.copy();
        }
    }

    private static boolean shouldRefreshCachedStack(ItemStack cached, ItemStack current) {
        return cached.isEmpty() || cached.getItem() != current.getItem() || cached.getCount() != current.getCount()
                || cached.getDamageValue() != current.getDamageValue() || cached.isBarVisible() != current.isBarVisible()
                || cached.getBarWidth() != current.getBarWidth() || cached.getBarColor() != current.getBarColor();
    }

    private static void drawItemDecorations(ItemStack stack, float x, float y, float size, float alpha) {
        if (stack.isEmpty() || alpha <= 0.01f) return;
        drawCooldownOverlay(stack, x, y, size, alpha);
        drawDurabilityBar(stack, x, y, size, alpha);
    }

    private static void drawDurabilityBar(ItemStack stack, float x, float y, float size, float alpha) {
        if (!stack.isBarVisible()) return;

        float barWidth = Math.max(8.0f, size * 0.75f);
        float barHeight = 2.0f;
        float barX = x + (size - barWidth) * 0.5f;
        float barY = y + size - barHeight - 1.5f;
        float fill = Math.max(0.0f, Math.min(1.0f, stack.getBarWidth() / 13.0f));
        int barColor = stack.getBarColor();

        Render2D.rect(barX, barY, barWidth, barHeight, barHeight * 0.5f, ColorUtil.rgba(0, 0, 0, Math.round(180.0f * alpha)));

        float currentWidth = Math.max(1.5f, barWidth * fill);
        Render2D.rect(barX, barY, currentWidth, barHeight, barHeight * 0.5f,
                ColorUtil.rgba((barColor >>> 16) & 0xFF, (barColor >>> 8) & 0xFF, barColor & 0xFF, Math.round(255.0f * alpha)));
    }

    private static void drawCooldownOverlay(ItemStack stack, float x, float y, float size, float alpha) {
        float cooldown = mc.player.getCooldowns().getCooldownPercent(stack, 0.0f);
        if (cooldown <= 0.0f) return;

        float overlayHeight = size * Math.max(0.0f, Math.min(1.0f, cooldown));
        Render2D.rect(x, y + size - overlayHeight, size, overlayHeight, 2.0f, ColorUtil.rgba(0, 0, 0, Math.round(160.0f * alpha)));
    }

    private static float animatedSelectedX(float x, float y, float slot, float gap) {
        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        float targetX = x + selectedSlot * (slot + gap);
        long now = System.currentTimeMillis();

        boolean layoutChanged = Float.isNaN(lastHotbarX) || Math.abs(lastHotbarX - x) > 1.0f || Math.abs(lastHotbarY - y) > 1.0f;
        if (layoutChanged || lastAnimationMs == 0L) {
            animatedSelectedX = targetX;
            lastAnimationMs = now;
            lastHotbarX = x;
            lastHotbarY = y;
            return animatedSelectedX;
        }

        float delta = Math.min((now - lastAnimationMs) / 1000.0f, 0.05f);
        lastAnimationMs = now;
        lastHotbarX = x;
        lastHotbarY = y;

        float speed = 22.0f;
        float factor = 1.0f - (float) Math.exp(-speed * delta);
        animatedSelectedX += (targetX - animatedSelectedX) * factor;

        if (Math.abs(targetX - animatedSelectedX) < 0.05f) {
            animatedSelectedX = targetX;
        }
        return animatedSelectedX;
    }

    private static float animatedOffhandProgress(boolean visible) {
        float target = visible ? 1.0f : 0.0f;
        long now = System.currentTimeMillis();
        if (lastOffhandAnimationMs == 0L) lastOffhandAnimationMs = now;

        float delta = Math.min((now - lastOffhandAnimationMs) / 1000.0f, 0.05f);
        lastOffhandAnimationMs = now;

        float speed = 16.0f;
        float factor = 1.0f - (float) Math.exp(-speed * delta);
        offhandProgress += (target - offhandProgress) * factor;

        if (Math.abs(target - offhandProgress) < 0.01f) offhandProgress = target;
        return offhandProgress;
    }

    private static void drawSlotNumber(int index, float sx, float y, float slot) {
        String number = String.valueOf(index + 1);
        float textSize = 6.5f;
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, number, textSize);

        Render2D.text(FontType.SEMIBOLD, number, sx + (slot - textWidth) * 0.5f + 0.5f, y + (slot - textSize) * 0.45f + 0.5f, textSize, TEXT_SHADOW);
        Render2D.text(FontType.SEMIBOLD, number, sx + (slot - textWidth) * 0.5f, y + (slot - textSize) * 0.45f, textSize, ColorUtil.rgba(255, 255, 255, 120));
    }

    private static void drawStackCount(ItemStack stack, float sx, float y, float slot) {
        drawStackCount(stack, sx, y, slot, 1.0f);
    }

    private static void drawStackCount(ItemStack stack, float sx, float y, float slot, float alpha) {
        if (stack.isEmpty() || stack.getCount() <= 1) return;

        String count = stack.getCount() > 99 ? "99+" : String.valueOf(stack.getCount());
        float textSize = 6.0f;
        float textWidth = Render2D.textWidth(FontType.BOLD, count, textSize);
        float textX = sx + slot - textWidth - 1.0f;
        float textY = y + slot - textSize - 1.0f;

        int textColor = ColorUtil.multAlpha(0xFFFFFFFF, alpha);
        int shadowColor = ColorUtil.multAlpha(TEXT_SHADOW, alpha);

        Render2D.text(FontType.BOLD, count, textX + 0.8f, textY + 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX - 0.8f, textY - 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX + 0.8f, textY - 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX - 0.8f, textY + 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX, textY + 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX, textY - 0.8f, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX + 0.8f, textY, textSize, shadowColor);
        Render2D.text(FontType.BOLD, count, textX - 0.8f, textY, textSize, shadowColor);

        Render2D.text(FontType.BOLD, count, textX, textY, textSize, textColor);
    }
}