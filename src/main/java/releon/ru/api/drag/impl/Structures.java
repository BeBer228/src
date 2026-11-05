package releon.ru.api.drag.impl;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.misc.ServerHelper;
import releon.ru.manager.Manager;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

import java.util.List;

/** Карточки таймеров трапок и пластов, как в Include. */
public final class Structures extends HudPanel {
    private static final float ROW_HEIGHT = 17.0F;
    private static final float ROW_GAP = 2.0F;
    private static final float ICON_SIZE = 10.0F;

    public Structures() {
        super("structures", "Structures", 10.0F, 220.0F, 112.0F, HudStyle.HEADER + 20.0F);
    }

    @Override
    protected void renderHud() {
        StructuresState state = logics();
        if (state == null) {
            return;
        }
        renderStructures(state);
    }

    private StructuresState logics() {
        ServerHelper helper = helper();
        boolean featureOn = helper != null && helper.structureHudEnabled();
        List<ServerHelper.HudStructure> structures = featureOn ? helper.getHudStructures() : List.of();
        boolean preview = featureOn && structures.isEmpty() && editPreview();
        boolean visible = featureOn && (!structures.isEmpty() || preview);
        float alpha = contentAlpha(visible);
        if (alpha <= 0.001F) {
            return null;
        }

        List<ServerHelper.HudStructure> render = preview ? List.of(previewEntry()) : structures;
        int rows = Math.max(1, render.size());
        String badge = preview ? "1" : Integer.toString(render.size());
        float width = Math.max(112.0F, HudStyle.headerWidth("Structures", badge));
        for (ServerHelper.HudStructure structure : render) {
            width = Math.max(width, rowWidth(structure));
        }
        size(width, HudStyle.HEADER + 3.0F + rows * (ROW_HEIGHT + ROW_GAP) + 1.0F);

        return new StructuresState(render, preview, badge, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private float rowWidth(ServerHelper.HudStructure structure) {
        return 6.0F + 3.0F + ICON_SIZE + 5.0F
                + Render2D.textWidth(TEXT_FONT, structure.label(), HudStyle.BODY_SIZE) + 6.0F
                + HudStyle.pillWidth(timerText(structure.remainingSeconds()), HudStyle.NUM, HudStyle.SMALL_SIZE)
                + 9.0F;
    }

    private void renderStructures(StructuresState state) {
        float alpha = state.alpha;
        card(state.x, state.y, state.width, state.height, "h", 7.5F, "Structures", state.badge, alpha);

        float rowX = state.x + 3.0F;
        float rowW = state.width - 6.0F;
        float rowY = state.y + HudStyle.HEADER + 3.0F;

        for (ServerHelper.HudStructure structure : state.structures) {
            renderRow(rowX, rowY, rowW, structure, alpha);
            rowY += ROW_HEIGHT + ROW_GAP;
        }
    }

    private void renderRow(float x, float y, float w, ServerHelper.HudStructure structure, float alpha) {
        HudStyle.surface(x, y, w, ROW_HEIGHT, HudStyle.RADIUS_SM, alpha);

        ItemStack icon = structure.icon() == null ? ItemStack.EMPTY : structure.icon();
        if (!icon.isEmpty()) {
            RenderItem.item(icon, x + 4.0F, y + (ROW_HEIGHT - ICON_SIZE) * 0.5F, ICON_SIZE,
                    RenderItemOptions.noDecorations(alpha));
        }

        String timer = timerText(structure.remainingSeconds());
        float pillH = ROW_HEIGHT - 6.0F;
        float timerW = HudStyle.pillWidth(timer, HudStyle.NUM, HudStyle.SMALL_SIZE);
        float timerX = x + w - timerW - 2.5F;
        HudStyle.pill(timerX, y + 3.0F, pillH, timer, HudStyle.NUM, HudStyle.SMALL_SIZE,
                HudStyle.accent(38.0F, alpha), HudStyle.accent(255.0F, alpha));

        float nameX = x + 5.0F + ICON_SIZE + 4.0F;
        String label = trimToWidth(structure.label(), TEXT_FONT, HudStyle.BODY_SIZE, timerX - nameX - 4.0F);
        Render2D.text(TEXT_FONT, label, nameX, y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F - 0.6F,
                HudStyle.BODY_SIZE, HudStyle.text(alpha));

        float barX = x + 4.0F;
        float barY = y + ROW_HEIGHT - 3.0F;
        float barW = w - 8.0F;
        Render2D.rect(barX, barY, barW, 1.0F, 0.5F, HudStyle.white(20.0F, alpha));
        float fill = barW * clamp(structure.ratio(), 0.0F, 1.0F);
        if (fill > 0.5F) {
            Render2D.rect(barX, barY, fill, 1.0F, 0.5F, HudStyle.accent(220.0F, alpha));
        }
    }

    private ServerHelper.HudStructure previewEntry() {
        return new ServerHelper.HudStructure("Трапка", Items.NETHERITE_SCRAP.getDefaultInstance(), 12.4D, 0.62F);
    }

    private String timerText(double seconds) {
        double safe = Math.max(0.0D, seconds);
        if (safe >= 10.0D) {
            return Math.round(safe) + "с";
        }
        return String.format(java.util.Locale.US, "%.1fс", safe);
    }

    private ServerHelper helper() {
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            return null;
        }
        return manager.getByType(ServerHelper.class).orElse(null);
    }

    private record StructuresState(List<ServerHelper.HudStructure> structures, boolean preview, String badge,
                                   float alpha, float x, float y, float width, float height) {
    }
}
