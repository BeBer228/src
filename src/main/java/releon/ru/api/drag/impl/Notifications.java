package releon.ru.api.drag.impl;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.sounds.SoundManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Notifications extends HudPanel {

    private static final long STAY_MS = 4400L;
    private static final float ANIM_S = 0.55F;
    private static final float ROW_HEIGHT = 22.0F;
    private static final float ROW_GAP = 4.0F;
    private static final float RADIUS = HudStyle.RADIUS;
    private static final float TEXT_SIZE = 6.2F;
    private static final float BADGE = 14.0F;
    private static final float TEXT_LEFT = 23.0F;
    private static final float PAD_RIGHT = 9.0F;
    private static final float MIN_WIDTH = 118.0F;

    private static final float SPRING_K = 160.0F;
    private static final float SPRING_D = 10.0F;
    private static final float SPRING_DT = 0.014F;
    private static final int MAX_NOTIFICATIONS = 5;

    private static final List<NotifEntry> NOTIFICATIONS = new ArrayList<>();
    private static final List<Stack> STACKS = new ArrayList<>();

    private final SmoothAnimation previewAnim = new SmoothAnimation();
    private boolean wasPreview = false;

    public Notifications() {
        super("notifications", "Notifications", 10.0F, 330.0F, MIN_WIDTH, ROW_HEIGHT);
    }

    public static void push(String title, String text) {
        push(title, text, STAY_MS);
    }

    public static void push(String title, String text, long stayMs) {
        push(title, text, stayMs, null);
    }

    public static void push(String title, String text, long stayMs, SoundEvent sound) {
        add(new NotifEntry(accentOf(title), text == null ? "" : text, "v", NotifStyle.ICON, false, System.currentTimeMillis() + Math.max(1L, stayMs)), sound);
    }

    public static void icon(String glyph, String accent, String text, long stayMs) {
        add(new NotifEntry(accent, text == null ? "" : text, glyph == null || glyph.isBlank() ? "v" : glyph, NotifStyle.ICON, false, System.currentTimeMillis() + Math.max(1L, stayMs)), null);
    }

    public static void toggle(String moduleName, boolean enabled) {
        toggle(moduleName, enabled, STAY_MS);
    }

    public static void toggle(String moduleName, boolean enabled, long stayMs) {
        String name = moduleName == null || moduleName.isBlank() ? "Module" : moduleName;
        add(new NotifEntry(name, enabled ? " включен" : " выключен", "v", NotifStyle.TOGGLE, enabled, System.currentTimeMillis() + Math.max(1L, stayMs)), null);
    }

    private static void add(NotifEntry entry, SoundEvent sound) {
        add(entry, sound, null);
    }

    private static void add(NotifEntry entry, SoundEvent sound, Component rich) {
        releon.ru.api.module.impl.visual.hudmodern.NewNotices.add(entry.accent, entry.accent + entry.text,
                entry.style == NotifStyle.TOGGLE, entry.toggled, entry.stayMs, rich);
        if (NOTIFICATIONS.size() >= MAX_NOTIFICATIONS) {
            NOTIFICATIONS.subList(0, NOTIFICATIONS.size() - MAX_NOTIFICATIONS + 1).clear();
        }

        float startY = stackHeight();
        entry.currentY = Math.max(0.0F, startY - ROW_GAP);
        entry.targetY = startY;
        entry.velocityY = 0;

        NOTIFICATIONS.add(entry);
        entry.anim.run(1.0, ANIM_S, Easings.EXPO_OUT);
        if (sound != null) {
            SoundManager.playSoundDirect(sound, 0.7F, 1.0F);
        }
    }

    private static String accentOf(String title) {
        return title == null || title.isBlank() ? "" : title;
    }

    public static void addList(String text, long removeTime) {
        push("", text, removeTime);
    }

    public static void addList(Component text, long removeTime) {
        push("", text.getString(), removeTime);
    }

    public static void addList(String text, long removeTime, SoundEvent sound) {
        push("", text, removeTime, sound);
    }

    public static void addList(Component text, long removeTime, SoundEvent sound) {
        add(new NotifEntry("", text.getString(), "v", NotifStyle.ICON, false,
                System.currentTimeMillis() + Math.max(1, removeTime)), sound, text);
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || mc.player == null || mc.level == null) {
            return;
        }

        Object packet = event.getPacket();

        if (packet instanceof ClientboundTakeItemEntityPacket takePacket) {
            Entity entity = mc.level.getEntity(takePacket.getItemId());
            if (entity instanceof ItemEntity itemEntity && takePacket.getPlayerId() == mc.player.getId()) {
                ItemStack stack = itemEntity.getItem();
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);

                if (container == null) {
                    Component itemText = stack.getHoverName();
                    if (!itemText.getString().isEmpty()) {
                        MutableComponent text = Component.empty().append(itemText);
                        if (stack.getCount() > 1) {
                            text.append(" §7[§e" + stack.getCount() + "x§7]");
                        }
                        STACKS.add(new Stack(TypePickUp.INVENTORY, text));
                    }
                } else {
                    container.stream()
                            .filter(s -> !s.getHoverName().getString().isEmpty())
                            .forEach(s -> {
                                MutableComponent text = Component.empty().append(s.getHoverName());
                                if (s.getCount() > 1) {
                                    text.append(" §7[§e" + s.getCount() + "x§7]");
                                }
                                STACKS.add(new Stack(TypePickUp.SHULKER, text));
                            });
                }
            }
        }

        if (packet instanceof ClientboundContainerSetSlotPacket slotPacket) {
            int slotId = slotPacket.getSlot();
            ItemStack updatedStack = slotPacket.getItem();
            ItemContainerContents updatedContainer = updatedStack.get(DataComponents.CONTAINER);

            if (updatedContainer == null || slotId >= mc.player.getInventory().getContainerSize()) {
                return;
            }

            ItemStack currentStack = mc.player.getInventory().getItem(slotId);
            ItemContainerContents currentContainer = currentStack.get(DataComponents.CONTAINER);

            if (currentContainer == null) {
                return;
            }

            updatedContainer.stream()
                    .filter(stack -> currentContainer.stream()
                            .noneMatch(s -> Objects.equals(s.getItem(), stack.getItem()) && s.toString().equals(stack.toString())))
                    .forEach(stack -> {
                        MutableComponent text = Component.empty().append(stack.getHoverName());
                        STACKS.add(new Stack(TypePickUp.SHULKER_INVENTORY, text));
                    });
        }
    }

    @Override
    protected void renderHud() {
        processStacks();
        NotificationsState state = logics();
        if (state == null) {
            return;
        }
        renderNotifications(state);
    }

    private void processStacks() {
        while (!STACKS.isEmpty()) {
            addTextIfNotEmpty(TypePickUp.INVENTORY, "Поднято: ");
            addTextIfNotEmpty(TypePickUp.SHULKER_INVENTORY, "В шалкер: ");
            addTextIfNotEmpty(TypePickUp.SHULKER, "Шалкер с: ");
        }
    }

    private void addTextIfNotEmpty(TypePickUp type, String prefix) {
        MutableComponent text = Component.empty();
        List<Stack> list = STACKS.stream().filter(stack -> stack.type == type).toList();
        int size = list.size();

        for (int i = 0; i < size; i++) {
            Stack stack = list.get(i);
            if (stack.type != type) continue;

            text.append(stack.text);
            STACKS.remove(stack);

            if (text.getString().length() > 150) break;
            if (i + 1 < size) {
                text.append(" , ");
            }
        }

        if (!text.getString().isEmpty()) {
            icon("v", prefix, text.getString(), 8000L);
        }
    }

    private boolean isChatOpen() {
        return mc.screen != null && mc.screen instanceof ChatScreen;
    }

    private NotificationsState logics() {
        long now = System.currentTimeMillis();

        for (NotifEntry e : NOTIFICATIONS) {
            e.anim.update();
            if (!e.exiting && now > e.removeTime) {
                e.exiting = true;
                e.anim.run(0.0, ANIM_S, Easings.EXPO_IN);
            }
        }

        NOTIFICATIONS.removeIf(e -> e.exiting && e.anim.isFinished());

        float offsetY = 0;
        for (NotifEntry e : NOTIFICATIONS) {
            float a = clamp(e.anim.get(), 0.0F, 1.0F);
            e.targetY = offsetY;
            offsetY += (ROW_HEIGHT + ROW_GAP) * a;
        }

        for (NotifEntry e : NOTIFICATIONS) {
            float diff = e.targetY - e.currentY;
            e.velocityY += (diff * SPRING_K - e.velocityY * SPRING_D) * SPRING_DT;
            e.currentY += e.velocityY * SPRING_DT;
            if (Math.abs(diff) < 0.01F && Math.abs(e.velocityY) < 0.01F) {
                e.currentY = e.targetY;
                e.velocityY = 0;
            }
        }

        previewAnim.update();
        boolean preview = NOTIFICATIONS.isEmpty() && (editPreview() || isChatOpen());
        if (preview && !wasPreview) {
            previewAnim.run(1.0, ANIM_S, Easings.EXPO_OUT);
        } else if (!preview && wasPreview) {
            previewAnim.run(0.0, ANIM_S, Easings.EXPO_IN);
        }
        wasPreview = preview;

        boolean visible = !NOTIFICATIONS.isEmpty() || previewAnim.get() > 0.01F;
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = MIN_WIDTH;
        if (!preview) {
            for (NotifEntry e : NOTIFICATIONS) {
                width = Math.max(width, rowWidth(e));
            }
        }
        drag.size((float) Math.ceil(width), ROW_HEIGHT);

        return new NotificationsState(preview, previewAnim.get(), drag.x(), drag.y(), drag.width());
    }

    private float rowWidth(NotifEntry e) {
        float text = Render2D.textWidth(TITLE_FONT, e.accent, TEXT_SIZE) + Render2D.textWidth(TEXT_FONT, e.text, TEXT_SIZE);
        return TEXT_LEFT + text + PAD_RIGHT;
    }

    private void renderNotifications(NotificationsState state) {
        if (state.previewAlpha > 0.01F) {
            NotifEntry sample = new NotifEntry("Уведомления", " появятся тут", "v", NotifStyle.ICON, false, 0L);
            renderRow(sample, state.x + state.width - Math.max(MIN_WIDTH, rowWidth(sample)), state.y, Math.max(MIN_WIDTH, rowWidth(sample)), clamp(state.previewAlpha, 0.0F, 1.0F));
        }

        if (state.preview) {
            return;
        }

        float panelRight = state.x + state.width;

        for (NotifEntry e : NOTIFICATIONS) {
            float anim = clamp(e.anim.get(), 0.0F, 1.0F);
            if (anim < 0.01F) {
                continue;
            }
            float width = rowWidth(e);
            float slide = (1.0F - anim) * (width * 0.45F);
            renderRow(e, panelRight - width + slide, state.y + e.currentY, width, anim);
        }
    }

    private void renderRow(NotifEntry e, float x, float y, float width, float alpha) {
        boolean toggle = e.style == NotifStyle.TOGGLE;
        int tintLeft = toggle
                ? (e.toggled ? HudStyle.good(255.0F, 1.0F) : HudStyle.bad(255.0F, 1.0F))
                : HudStyle.accent(255.0F, 1.0F);
        int tintRight = toggle
                ? HudStyle.mix(tintLeft, HudStyle.white(255.0F, 1.0F), 0.25F)
                : HudStyle.accent2(255.0F, 1.0F);

        long age = System.currentTimeMillis() - e.spawnMs;
        HudStyle.glow(x - 6.0F, y - 4.0F, width + 12.0F, ROW_HEIGHT + 8.0F,
                HudStyle.withAlpha(tintLeft, 46.0F * (1.0F - HudStyle.clamp01(age / 900.0F)), alpha));
        HudStyle.glass(x, y, width, ROW_HEIGHT, RADIUS, alpha);
        // цветная кромка слева
        Render2D.rect(x + 1.2F, y + 5.0F, 1.2F, ROW_HEIGHT - 10.0F, 0.6F,
                HudStyle.withAlpha(tintLeft, 255.0F, alpha), HudStyle.withAlpha(tintLeft, 255.0F, alpha),
                HudStyle.withAlpha(tintRight, 255.0F, alpha), HudStyle.withAlpha(tintRight, 255.0F, alpha));
        // вспышка-блик при появлении
        if (age < 750L) {
            float t = HudStyle.easeInOut(age / 750.0F);
            float half = 18.0F;
            HudStyle.band(x - half + (width + half * 2.0F) * t, half, x + RADIUS, x + width - RADIUS,
                    y + 0.5F, ROW_HEIGHT - 1.0F, 34.0F * (float) Math.sin(t * Math.PI), alpha);
        }

        // чип «выпрыгивает» с пружиной
        float pop = HudStyle.easeOutBack(HudStyle.clamp01(age / 420.0F));
        float badge = BADGE * (0.55F + 0.45F * pop);
        float badgeX = x + 4.0F + (BADGE - badge) * 0.5F;
        float badgeY = y + (ROW_HEIGHT - badge) * 0.5F;

        if (toggle) {
            HudStyle.tintChip(badgeX, badgeY, badge, tintLeft, alpha);
            // переключатель анимированно щёлкает в новое положение
            float sw = HudStyle.easeOutBack(HudStyle.clamp01((age - 120L) / 380.0F));
            float k = e.toggled ? sw : 1.0F - sw;
            float pillW = 10.5F * (badge / BADGE);
            float pillH = 6.0F * (badge / BADGE);
            float pillX = badgeX + (badge - pillW) * 0.5F;
            float pillY = badgeY + (badge - pillH) * 0.5F;
            float knob = pillH - 1.8F;
            float knobX = pillX + 0.9F + (pillW - knob - 1.8F) * HudStyle.clamp01(k);
            Render2D.rect(pillX, pillY, pillW, pillH, pillH * 0.5F,
                    HudStyle.mix(HudStyle.white(40.0F, alpha), HudStyle.withAlpha(tintLeft, 255.0F, alpha), HudStyle.clamp01(k)));
            Render2D.rect(knobX, pillY + 0.9F, knob, knob, knob * 0.5F, HudStyle.white(255.0F, alpha));
        } else {
            HudStyle.iconChip(badgeX, badgeY, badge, e.glyph, 8.0F * (badge / BADGE), alpha);
        }

        float textY = y + (ROW_HEIGHT - TEXT_SIZE) * 0.5F - 0.5F;
        float cursor = x + TEXT_LEFT;
        if (!e.accent.isEmpty()) {
            Render2D.text(TITLE_FONT, e.accent, cursor, textY, TEXT_SIZE, HudStyle.text(alpha));
            cursor += Render2D.textWidth(TITLE_FONT, e.accent, TEXT_SIZE);
        }
        if (!e.text.isEmpty()) {
            int textColor = toggle
                    ? HudStyle.withAlpha(tintLeft, 255.0F, alpha)
                    : HudStyle.rgba(206, 210, 224, 255.0F, alpha);
            Render2D.text(TEXT_FONT, e.text, cursor, textY, TEXT_SIZE, textColor);
        }

        // полоса оставшегося времени
        if (e.stayMs > 0L) {
            float left = (e.removeTime - System.currentTimeMillis()) / (float) e.stayMs;
            float barX = x + RADIUS;
            float barW = width - RADIUS * 2.0F;
            float fill = barW * HudStyle.clamp01(left);
            if (fill > 0.5F) {
                Render2D.rect(barX, y + ROW_HEIGHT - 1.4F, fill, 0.9F, 0.45F,
                        HudStyle.withAlpha(tintLeft, 230.0F, alpha), HudStyle.withAlpha(tintRight, 230.0F, alpha),
                        HudStyle.withAlpha(tintRight, 230.0F, alpha), HudStyle.withAlpha(tintLeft, 230.0F, alpha));
            }
        }
    }

    private static float stackHeight() {
        float height = 0.0F;
        for (NotifEntry e : NOTIFICATIONS) {
            height += (ROW_HEIGHT + ROW_GAP) * clamp(e.anim.get(), 0.0F, 1.0F);
        }
        return height;
    }

    public enum TypePickUp {
        INVENTORY,
        SHULKER,
        SHULKER_INVENTORY
    }

    private enum NotifStyle {
        ICON,
        TOGGLE
    }

    private record Stack(TypePickUp type, Component text) {
    }

    private static final class NotifEntry {
        final String accent;
        final String text;
        final String glyph;
        final NotifStyle style;
        final boolean toggled;
        final long removeTime;
        final long stayMs;
        final long spawnMs = System.currentTimeMillis();
        final SmoothAnimation anim = new SmoothAnimation();
        boolean exiting = false;
        float currentY = 0;
        float targetY = 0;
        float velocityY = 0;

        NotifEntry(String accent, String text, String glyph, NotifStyle style, boolean toggled, long removeTime) {
            this.accent = accent == null ? "" : accent;
            this.text = text == null ? "" : text;
            this.glyph = glyph;
            this.style = style;
            this.toggled = toggled;
            this.removeTime = removeTime;
            this.stayMs = Math.max(0L, removeTime - System.currentTimeMillis());
        }
    }

    private record NotificationsState(boolean preview, float previewAlpha, float x, float y, float width) {
    }
}
