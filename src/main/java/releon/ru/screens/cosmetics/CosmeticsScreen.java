package releon.ru.screens.cosmetics;

import releon.ru.manager.Manager;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import releon.ru.api.module.impl.misc.Cosmetics;
import releon.ru.api.module.impl.misc.Cosmetics.Audience;
import releon.ru.api.module.impl.misc.Cosmetics.Entry;
import releon.ru.api.module.impl.misc.Cosmetics.Group;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.media.MediaSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

public final class CosmeticsScreen extends Screen {
    private static final String[] GROUP_NAMES = {"Булавы", "Копья", "Голова", "Крылья", "Одежда", "Питомцы", "Талисманы", "Звуки"};
    private static final String[] AUDIENCE_NAMES = {"Себе", "Друзьям", "Остальным"};
    private static final int PANEL = 0xF0111420;
    private static final int BORDER = 0xFF2A3146;
    private static final int TEXT = 0xFFF2F4FF;
    private static final int MUTED = 0xFF9AA6C2;
    private static final int ACCENT = 0xFFA9BAFF;
    private final Cosmetics cosmetics;
    private final Screen parent;
    private Group group = Group.Weapons;
    private Audience audience = Audience.Self;
    private RemotePlayer model;
    private net.minecraft.client.resources.sounds.SimpleSoundInstance soundPreview;
    private List<Entry> entries = List.of();
    private ItemStack[] icons = {};
    private Button apply, remove, play, previous, next;
    private final Button[] audienceButtons = new Button[Audience.values().length];
    private int selected, center, left, top, panelWidth, panelHeight, step, stageLeft, stageWidth;
    private AbstractSliderButton volumeSlider, speedSlider;
    private Checkbox pauseMusic;
    private boolean audioSettingsChanged;
    private float carousel;
    private long frameTime;

    public CosmeticsScreen(Cosmetics cosmetics, Screen parent) {
        super(Component.literal("Cosmetics"));
        this.cosmetics = cosmetics;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 12, 570);
        panelHeight = Math.min(height - 12, 344);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int sidebar = Math.min(118, panelWidth / 4);
        stageLeft = left + sidebar + 13;
        stageWidth = panelWidth - sidebar - 25;
        center = stageLeft + stageWidth / 2;
        step = Math.min(78, stageWidth / 4);
        if (minecraft.level != null && minecraft.player != null && model == null) {
            model = new RemotePlayer(minecraft.level, minecraft.player.getGameProfile());
            model.setId(minecraft.player.getId());
        }
        int rowHeight = Math.min(26, (panelHeight - 59) / GROUP_NAMES.length);
        for (Group value : Group.values()) {
            button(GROUP_NAMES[value.ordinal()], left + 9, top + 49 + value.ordinal() * rowHeight,
                    sidebar - 9, rowHeight - 3, () -> {
                        group = value;
                        updateCatalog();
                    }, () -> group == value);
        }
        for (Audience value : Audience.values()) {
            int w = Math.min(85, stageWidth / 3);
            audienceButtons[value.ordinal()] = button(AUDIENCE_NAMES[value.ordinal()],
                    center - w * 3 / 2 + value.ordinal() * w, top + 45, w - 3, 19, () -> {
                        audience = value;
                        updateCatalog();
                    }, () -> group != Group.Sounds && audience == value);
        }
        int bottom = top + panelHeight;
        previous = button("‹", stageLeft + 4, bottom - 76, 24, 24, () -> move(-1), () -> false);
        next = button("›", stageLeft + stageWidth - 28, bottom - 76, 24, 24, () -> move(1), () -> false);
        int w = Math.min(102, stageWidth / 3);
        int x = center - w * 3 / 2;
        apply = button("Применить", x, bottom - 31, w - 3, 22, () -> save(current()), () -> true);
        remove = button("Убрать", x + w, bottom - 31, w - 3, 22, () -> save(null), () -> false);
        play = button("Слушать", x + w * 2, bottom - 31, w - 3, 22, this::listen, () -> false);
        button("×", left + panelWidth - 33, top + 9, 23, 21, this::onClose, () -> false);
        int sliderWidth = Math.min(124, (stageWidth - 20) / 2);
        volumeSlider = audioSlider(center - sliderWidth - 5, bottom - 117, sliderWidth, "Громкость", cosmetics.maceVolume);
        speedSlider = audioSlider(center + 5, bottom - 117, sliderWidth, "Скорость", cosmetics.maceSpeed);
        pauseMusic = addRenderableWidget(Checkbox.builder(Component.literal("Пауза музыки Windows"), font)
                .pos(stageLeft + 10, top + 44)
                .maxWidth(stageWidth - 20)
                .selected(cosmetics.pauseMusic.getValue())
                .onValueChange((checkbox, checked) -> {
                    cosmetics.pauseMusic.setValue(checked);
                    audioSettingsChanged = true;
                    if (checked) MediaSession.prepare();
                }).build());
        if (cosmetics.pauseMusic.getValue()) MediaSession.prepare();
        updateCatalog();
    }

    private AbstractSliderButton audioSlider(int x, int y, int width, String label, NumberSetting setting) {
        double range = setting.getMax() - setting.getMin();
        return addRenderableWidget(new AbstractSliderButton(x, y, width, 21,
                Component.literal(label + " " + setting.getValue().intValue() + "%"),
                (setting.getValue() - setting.getMin()) / range) {
            @Override protected void updateMessage() {
                setMessage(Component.literal(label + " " + setting.getValue().intValue() + "%"));
            }

            @Override protected void applyValue() {
                setting.setValue(setting.getMin() + value * range);
                audioSettingsChanged = true;
                stopPreview();
            }

            @Override public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                int x = getX(), y = getY(), track = getWidth() - 12;
                PrismDraw.roundRect(g, x + 6, y + 14, track, 4, 2, BORDER);
                int knob = (int) Math.round(value * track);
                if (knob > 0) PrismDraw.roundRect(g, x + 6, y + 14, knob, 4, 2, ACCENT);
                PrismDraw.roundRect(g, x + knob + 3, y + 12, 6, 8, 3, TEXT);
                PrismDraw.drawInterCentered(g, getMessage().getString(), x + getWidth() / 2f, y - 4, 9, TEXT);
            }
        });
    }

    private Button button(String text, int x, int y, int width, int height, Runnable action,
                          BooleanSupplier accent) {
        return addRenderableWidget(new Button(x, y, width, height, Component.literal(text),
                ignored -> action.run(), narration -> narration.get()) {
            @Override protected void renderContents(GuiGraphics g, int mx, int my, float delta) {
                boolean selected = accent.getAsBoolean();
                int fill = selected ? (active && isHoveredOrFocused() ? 0xFFCDDAFF : ACCENT)
                        : active && isHoveredOrFocused() ? 0xFF35415E : 0xFF20283B;
                PrismDraw.roundRect(g, getX(), getY(), getWidth(), getHeight(), 6, fill);
                PrismDraw.drawInterBoldCentered(g, text, getX() + getWidth() / 2f,
                        PrismDraw.interBoldCenteredY(getY(), getHeight(), 9), 9,
                        selected ? 0xFF17203C : active ? TEXT : MUTED);
            }
        });
    }

    private Audience target() {
        return group == Group.Sounds ? Audience.Self : audience;
    }

    private void updateCatalog() {
        stopPreview();
        entries = cosmetics.entries(group);
        icons = group == Group.Sounds ? new ItemStack[0] : entries.stream().map(cosmetics::displayStack).toArray(ItemStack[]::new);
        selected = Math.max(0, entries.indexOf(cosmetics.selected(target(), group)));
        carousel = selected;
        updateSelection();
    }

    private void move(int direction) {
        selected = Math.clamp(selected + direction, 0, Math.max(0, entries.size() - 1));
        updateSelection();
    }

    private Entry current() {
        return entries.isEmpty() ? null : entries.get(selected);
    }

    public Entry shown(Group slot) {
        if (slot == group) return current();
        return cosmetics.selected(audience, slot);
    }

    public boolean isModel(Avatar player) {
        return model != null && model == player;
    }

    private void updateSelection() {
        if (model != null) {
            Entry weapon = switch (group) {
                case Spears, Talismans -> shown(group);
                default -> shown(Group.Weapons);
            };
            model.setItemSlot(EquipmentSlot.MAINHAND, weapon == null ? ItemStack.EMPTY : cosmetics.displayStack(weapon));
        }
        previous.active = selected > 0;
        next.active = selected + 1 < entries.size();
        apply.active = current() != null && current() != cosmetics.selected(target(), group);
        remove.active = cosmetics.selected(target(), group) != null;
        play.visible = group == Group.Sounds;
        play.active = play.visible && current() != null;
        volumeSlider.visible = group == Group.Sounds;
        speedSlider.visible = volumeSlider.visible;
        pauseMusic.visible = volumeSlider.visible;
        for (Audience value : Audience.values()) {
            audienceButtons[value.ordinal()].visible = group != Group.Sounds;
            audienceButtons[value.ordinal()].active = value != audience;
        }
    }

    private void listen() {
        stopPreview();
        soundPreview = cosmetics.play(current());
    }

    private void stopPreview() {
        if (soundPreview == null) return;
        minecraft.getSoundManager().stop(soundPreview);
        soundPreview = null;
    }

    private void save(Entry entry) {
        cosmetics.select(target(), group, entry);
        Manager.getInstance().getConfigManager().saveAll();
        updateSelection();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mx, int my, float delta) {
        renderBlurredBackground(graphics);
        graphics.fill(0, 0, width, height, 0x60080B15);
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float delta) {
        PrismDraw.begin(graphics);
        int bottom = top + panelHeight;
        PrismDraw.roundRect(graphics, left, top, panelWidth, panelHeight, 14, PANEL);
        PrismDraw.outline(graphics, left, top, panelWidth, panelHeight, 14, BORDER);
        PrismDraw.drawInterBold(graphics, "COSMETICS", left + 14, top + 11, 13, TEXT);
        PrismDraw.drawInter(graphics, "КОЛЛЕКЦИЯ", left + 14, top + 27, 8, MUTED);
        PrismDraw.drawInterBold(graphics, GROUP_NAMES[group.ordinal()], stageLeft + 10, top + 11, 16, TEXT);
        PrismDraw.drawInter(graphics, group == Group.Sounds ? "Звук удара булавой" : "Предпросмотр · " + AUDIENCE_NAMES[audience.ordinal()],
                stageLeft + 10, top + 30, 9, MUTED);
        int viewTop = top + 70;
        int viewBottom = bottom - 90;
        if (viewBottom - viewTop > 30) {
            PrismDraw.roundRect(graphics, stageLeft, viewTop, stageWidth, viewBottom - viewTop, 10, 0xFF191F30);
            if (group == Group.Sounds) {
                Entry entry = current();
                PrismDraw.drawInterBoldCentered(graphics, "♪", center, viewTop + 4, 20, ACCENT);
                PrismDraw.drawInterCentered(graphics, entry == null ? "Нет звуков" : entry.name(), center,
                        viewTop + 29, 11, TEXT);
            } else if (model != null) {
                PrismDraw.circle(graphics, center, (viewTop + viewBottom) / 2f, Math.min(57, (viewBottom - viewTop) / 2f),
                        0xFF242D45);
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, stageLeft + 8, viewTop + 3,
                        stageLeft + stageWidth - 8, viewBottom - 3, Math.min(69, (viewBottom - viewTop - 10) / 2),
                        0.06f, mx, my, model);
            }
        }
        drawCarousel(graphics, bottom);
        super.render(graphics, mx, my, delta);
        PrismDraw.end(graphics);
    }

    private void drawCarousel(GuiGraphics graphics, int bottom) {
        long now = System.nanoTime();
        double elapsed = frameTime == 0 ? 0 : Math.min(0.05, (now - frameTime) * 1e-9);
        frameTime = now;
        carousel += (selected - carousel) * (float) (1 - Math.exp(-16 * elapsed));
        int y = bottom - 81;
        graphics.enableScissor(stageLeft + 32, y, stageLeft + stageWidth - 32, bottom - 33);
        for (int i = Math.max(0, (int) carousel - 3); i < Math.min(entries.size(), (int) carousel + 4); i++) {
            float distance = Math.abs(i - carousel);
            int x = center + Math.round((i - carousel) * step);
            PrismDraw.roundRect(graphics, x - step / 2 + 3, y, step - 6, 34, 6,
                    i == selected ? 0xFF344367 : 0xFF20283B);
            if (i == selected) {
                PrismDraw.outline(graphics, x - step / 2 + 3, y, step - 6, 34, 6, ACCENT);
            }
            if (i < icons.length) {
                float scale = Math.max(1, 1.8f - distance * 0.32f);
                graphics.pose().pushMatrix();
                graphics.pose().translate(x - 8 * scale, y + 17 - 8 * scale);
                graphics.pose().scale(scale, scale);
                graphics.renderFakeItem(icons[i], 0, 0);
                graphics.pose().popMatrix();
            } else {
                graphics.drawCenteredString(font, "♫", x, y + 12, 0xFFAEB9FF);
            }
        }
        Entry entry = current();
        String label = entry == null ? "Нет вариантов" : entry.name() + "   ·   " + (selected + 1) + "/" + entries.size();
        PrismDraw.drawInterCentered(graphics, label, center, bottom - 45, 9, TEXT);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int cardY = top + panelHeight - 81;
        if ((event.button()==0) && event.y() >= cardY && event.y() < cardY + 34
                && event.x() >= stageLeft + 32 && event.x() < stageLeft + stageWidth - 32) {
            int index = Math.round(carousel + (float) (event.x() - center) / step);
            if (index >= 0 && index < entries.size()
                    && Math.abs(event.x() - (center + Math.round((index - carousel) * step))) <= step / 2f - 3) {
                move(index - selected);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= stageLeft && mx <= stageLeft + stageWidth && vertical != 0) {
            move(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public void tick() {
        if (model != null) model.tickCount++;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        stopPreview();
        if (audioSettingsChanged) Manager.getInstance().getConfigManager().saveAll();
        cosmetics.closePreview(this);
        super.removed();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
