package releon.ru.api.drag.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import releon.ru.api.drag.core.ElementComponent;
import releon.ru.api.drag.core.ElementManager;
import releon.ru.api.drag.core.ElementScreen;
import releon.ru.api.drag.core.HudElement;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.List;

public abstract class HudPanel implements HudElement {
    protected static final FontType TITLE_FONT = HudStyle.TITLE;
    protected static final FontType TEXT_FONT = HudStyle.BODY;
    protected static final int TEXT_COLOR = ColorUtil.rgba(HudStyle.TEXT_R, HudStyle.TEXT_G, HudStyle.TEXT_B, 255);
    protected static final int MUTED_COLOR = ColorUtil.rgba(HudStyle.MUTED_R, HudStyle.MUTED_G, HudStyle.MUTED_B, 240);
    protected static final int ACCENT_COLOR = ColorUtil.rgba(HudStyle.ACCENT_R, HudStyle.ACCENT_G, HudStyle.ACCENT_B, 255);
    protected static final int ACCENT_SECONDARY = ColorUtil.rgba(HudStyle.ACCENT2_R, HudStyle.ACCENT2_G, HudStyle.ACCENT2_B, 255);
    protected static final int PANEL_COLOR = ColorUtil.rgba(HudStyle.GLASS_R, HudStyle.GLASS_G, HudStyle.GLASS_B, Math.round(HudStyle.GLASS_A));
    protected static final int PANEL_EDGE = ColorUtil.rgba(255, 255, 255, 20);
    protected static final float PANEL_RADIUS = HudStyle.RADIUS;
    private static final float CONTENT_ANIM = 0.20F;
    private static final String[] ASCII_CHARS = new String[128];

    static {
        for (int i = 0; i < ASCII_CHARS.length; i++) {
            ASCII_CHARS[i] = Character.toString((char) i);
        }
    }

    protected final Minecraft mc = Minecraft.getInstance();
    protected final ElementComponent drag;
    private final String elementName;
    private float animatedWidth;
    private float animatedHeight;
    private long lastFrameMs = System.currentTimeMillis();
    private final SmoothAnimation contentAnimation = new SmoothAnimation();
    private boolean enabled = true;
    private boolean shown = true;

    // ------------------------------------------------------------ drag fx state
    private static final List<HudPanel> PANELS = new ArrayList<>();
    private static final int TRAIL = 7;
    private static final long TRAIL_LIFE_MS = 230L;
    private static final long DROP_MS = 460L;
    private static final long PICK_MS = 260L;
    private static final float GUIDE_SNAP = 1.6F;

    private final float[] trailX = new float[TRAIL];
    private final float[] trailY = new float[TRAIL];
    private final long[] trailT = new long[TRAIL];
    private int trailHead;
    private long lastTrailSample;
    private float lastX = Float.NaN;
    private float lastY;
    private float lastW;
    private float lastH;
    private boolean dragging;
    private long lastMoveMs;
    private long pickMs;
    private long dropMs;
    private float dragAmt;
    private float editAmt;
    private float lagX;
    private float lagY;
    private float tiltX;
    private float tiltY;
    private long fxLastNanos;

    protected HudPanel(String id, String title, float defaultX, float defaultY, float width, float height) {
        this.elementName = title;
        this.drag = ElementManager.getInstance()
                .register("hud." + id, title, defaultX, defaultY)
                .minimumSize(12.0F, 12.0F);
        this.animatedWidth = width;
        this.animatedHeight = height;
        synchronized (PANELS) {
            PANELS.add(this);
        }
    }

    // ================================================================ render pipeline

    /**
     * Общий конвейер для всех HUD-элементов: эффекты перетаскивания под
     * элементом -> сам элемент -> рамка/подсказки поверх.
     * Наследники рисуют себя в {@link #renderHud()}.
     */
    @Override
    public void render() {
        updateDragFx();
        boolean fx = shown && enabled && drag.width() > 2.0F && drag.height() > 2.0F
                && (editAmt > 0.01F || dragAmt > 0.01F || System.currentTimeMillis() - dropMs < DROP_MS);
        if (fx) {
            renderDragUnder();
        }
        renderHud();
        if (fx) {
            renderDragOver();
        }
    }

    /** Отрисовка конкретного элемента. */
    protected void renderHud() {
    }

    /** true, пока пользователь тащит этот элемент. */
    protected boolean isDragging() {
        return dragging;
    }

    /** 0..1 — плавная «степень» перетаскивания (для своих эффектов в элементах). */
    protected float dragProgress() {
        return dragAmt;
    }

    private void updateDragFx() {
        long nanos = System.nanoTime();
        float dt = fxLastNanos == 0L ? 0.016F : Math.min(0.1F, (nanos - fxLastNanos) / 1.0e9F);
        fxLastNanos = nanos;
        long now = System.currentTimeMillis();

        float x = drag.x();
        float y = drag.y();
        float w = drag.width();
        float h = drag.height();
        boolean edit = editPreview();
        boolean mouseDown = mouseDown();

        if (!Float.isNaN(lastX)) {
            float dx = x - lastX;
            float dy = y - lastY;
            boolean resized = Math.abs(w - lastW) > 0.01F || Math.abs(h - lastH) > 0.01F;
            boolean moved = Math.abs(dx) + Math.abs(dy) > 0.01F;
            if (edit && mouseDown && moved && !resized) {
                if (!dragging) {
                    dragging = true;
                    pickMs = now;
                    lagX = x;
                    lagY = y;
                    for (int i = 0; i < TRAIL; i++) {
                        trailT[i] = 0L;
                    }
                }
                lastMoveMs = now;
                float inv = 1.0F / Math.max(0.004F, dt);
                tiltX = smooth(tiltX, clamp(dx * inv / 900.0F, -1.0F, 1.0F), dt, 10.0F);
                tiltY = smooth(tiltY, clamp(dy * inv / 900.0F, -1.0F, 1.0F), dt, 10.0F);
            }
        }
        if (dragging && (!mouseDown || !edit)) {
            dragging = false;
            dropMs = now;
        }
        if (dragging && now - lastMoveMs > 60L) {
            tiltX = smooth(tiltX, 0.0F, dt, 6.0F);
            tiltY = smooth(tiltY, 0.0F, dt, 6.0F);
        }
        if (!dragging) {
            tiltX = smooth(tiltX, 0.0F, dt, 8.0F);
            tiltY = smooth(tiltY, 0.0F, dt, 8.0F);
        }

        if (dragging && now - lastTrailSample >= 24L) {
            lastTrailSample = now;
            trailX[trailHead] = x;
            trailY[trailHead] = y;
            trailT[trailHead] = now;
            trailHead = (trailHead + 1) % TRAIL;
        }

        dragAmt = smooth(dragAmt, dragging ? 1.0F : 0.0F, dt, dragging ? 9.0F : 5.0F);
        editAmt = smooth(editAmt, edit ? 1.0F : 0.0F, dt, 6.0F);
        if (dragAmt < 0.002F) {
            dragAmt = 0.0F;
        }
        if (editAmt < 0.002F) {
            editAmt = 0.0F;
        }
        lagX = dragAmt > 0.0F ? smooth(lagX, x, dt, 5.0F) : x;
        lagY = dragAmt > 0.0F ? smooth(lagY, y, dt, 5.0F) : y;

        lastX = x;
        lastY = y;
        lastW = w;
        lastH = h;
    }

    private static boolean mouseDown() {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** Под элементом: «подъём» (тень + цветное свечение с инерцией) и шлейф. */
    private void renderDragUnder() {
        float x = drag.x();
        float y = drag.y();
        float w = drag.width();
        float h = drag.height();
        long now = System.currentTimeMillis();
        float d = HudStyle.easeOut(dragAmt);

        // шлейф: призрачные копии рамки в прошлых позициях
        if (dragAmt > 0.01F) {
            for (int i = 0; i < TRAIL; i++) {
                long age = now - trailT[i];
                if (trailT[i] == 0L || age > TRAIL_LIFE_MS) {
                    continue;
                }
                float k = 1.0F - age / (float) TRAIL_LIFE_MS;
                if (Math.abs(trailX[i] - x) + Math.abs(trailY[i] - y) < 1.0F) {
                    continue;
                }
                float a = k * k * d;
                Render2D.rect(trailX[i], trailY[i], w, h, PANEL_RADIUS, HudStyle.accent(16.0F * a, 1.0F));
                Render2D.outline(trailX[i], trailY[i], w, h, PANEL_RADIUS, 0.6F, HudStyle.accent(70.0F * a, 1.0F));
            }
        }

        if (d > 0.01F) {
            // тень «поднятой» карточки
            float lift = 4.0F * d;
            HudStyle.glow(x - 14.0F, y - 8.0F + lift, w + 28.0F, h + 24.0F, HudStyle.black(150.0F * d, 1.0F));
            // свечение акцента, отстающее от элемента -> ощущение инерции
            float gx = lagX + (x - lagX) * 0.35F;
            float gy = lagY + (y - lagY) * 0.35F;
            HudStyle.glow(gx - 18.0F, gy - 14.0F, w + 36.0F, h + 28.0F, HudStyle.accent(85.0F * d, 1.0F));
            HudStyle.glow(x - 10.0F - tiltX * 8.0F, y - 8.0F - tiltY * 8.0F, w + 20.0F, h + 16.0F, HudStyle.accent2(55.0F * d, 1.0F));
        }

        // импульс при «поднятии»
        long sincePick = now - pickMs;
        if (dragging && sincePick < PICK_MS) {
            float t = sincePick / (float) PICK_MS;
            float grow = (1.0F - HudStyle.easeOut(t)) * 6.0F;
            Render2D.outline(x - grow, y - grow, w + grow * 2.0F, h + grow * 2.0F, PANEL_RADIUS + grow, 0.8F,
                    HudStyle.accent(200.0F * (1.0F - t), 1.0F));
        }
    }

    /** Поверх элемента: рамка, уголки, смарт-направляющие, координаты, волна при отпускании. */
    private void renderDragOver() {
        float x = drag.x();
        float y = drag.y();
        float w = drag.width();
        float h = drag.height();
        long now = System.currentTimeMillis();
        float d = HudStyle.easeOut(dragAmt);

        // уголки: в режиме редактирования тонкие белые, при перетаскивании — акцентные и раздвигаются
        float idle = editAmt * (1.0F - d);
        if (idle > 0.01F) {
            float pulse = 0.65F + 0.35F * HudStyle.pulse(2400L);
            HudStyle.corners(x - 1.5F, y - 1.5F, w + 3.0F, h + 3.0F, 4.5F, 0.7F, HudStyle.white(90.0F * pulse * idle, 1.0F));
        }
        if (d > 0.01F) {
            float spread = 1.5F + 2.0F * d;
            Render2D.outline(x, y, w, h, PANEL_RADIUS, 0.7F, HudStyle.accent(150.0F * d, 1.0F));
            HudStyle.corners(x - spread, y - spread, w + spread * 2.0F, h + spread * 2.0F, 6.0F, 1.0F, HudStyle.accent(255.0F * d, 1.0F));
            renderGuides(x, y, w, h, d);
            renderDragLabel(x, y, w, h, d);
        }

        // волна при отпускании + короткая вспышка
        long sinceDrop = now - dropMs;
        if (!dragging && sinceDrop < DROP_MS) {
            float t = sinceDrop / (float) DROP_MS;
            float e = HudStyle.easeOut(t);
            float grow = e * 10.0F;
            float fade = (1.0F - t) * (1.0F - t);
            Render2D.outline(x - grow, y - grow, w + grow * 2.0F, h + grow * 2.0F, PANEL_RADIUS + grow, 1.0F,
                    HudStyle.accent(220.0F * fade, 1.0F));
            float grow2 = HudStyle.easeOut(Math.min(1.0F, t * 1.6F)) * 5.0F;
            Render2D.outline(x - grow2, y - grow2, w + grow2 * 2.0F, h + grow2 * 2.0F, PANEL_RADIUS + grow2, 0.6F,
                    HudStyle.accent2(160.0F * fade, 1.0F));
            if (t < 0.4F) {
                float f = 1.0F - t / 0.4F;
                Render2D.rect(x, y, w, h, PANEL_RADIUS, HudStyle.white(26.0F * f, 1.0F));
            }
        }
    }

    /** Направляющие как в Figma: выравнивание по краям/центрам других элементов и центру экрана. */
    private void renderGuides(float x, float y, float w, float h, float d) {
        float[] ax = {x, x + w * 0.5F, x + w};
        float[] ay = {y, y + h * 0.5F, y + h};
        int color = HudStyle.accent(200.0F * d, 1.0F);

        float sw = screenWidth();
        float sh = screenHeight();
        if (sw > 0.0F && Math.abs(ax[1] - sw * 0.5F) < GUIDE_SNAP) {
            HudStyle.dashedV(sw * 0.5F, 0.0F, sh, color);
        }
        if (sh > 0.0F && Math.abs(ay[1] - sh * 0.5F) < GUIDE_SNAP) {
            HudStyle.dashedH(0.0F, sh * 0.5F, sw, color);
        }

        List<HudPanel> panels;
        synchronized (PANELS) {
            panels = new ArrayList<>(PANELS);
        }
        for (HudPanel other : panels) {
            if (other == this || !other.enabled || !other.shown || other.drag.width() <= 2.0F) {
                continue;
            }
            float ox = other.drag.x();
            float oy = other.drag.y();
            float ow = other.drag.width();
            float oh = other.drag.height();
            float[] bx = {ox, ox + ow * 0.5F, ox + ow};
            float[] by = {oy, oy + oh * 0.5F, oy + oh};
            for (float a : ax) {
                for (float b : bx) {
                    if (Math.abs(a - b) < GUIDE_SNAP) {
                        float top = Math.min(y, oy) - 6.0F;
                        float bottom = Math.max(y + h, oy + oh) + 6.0F;
                        HudStyle.dashedV(b, top, bottom - top, color);
                        HudStyle.dot(b, y < oy ? y + h : y, 2.0F, color);
                    }
                }
            }
            for (float a : ay) {
                for (float b : by) {
                    if (Math.abs(a - b) < GUIDE_SNAP) {
                        float left = Math.min(x, ox) - 6.0F;
                        float right = Math.max(x + w, ox + ow) + 6.0F;
                        HudStyle.dashedH(left, b, right - left, color);
                    }
                }
            }
        }
    }

    /** Плашка с названием и координатами над элементом. */
    private void renderDragLabel(float x, float y, float w, float h, float d) {
        String name = elementName;
        String pos = Math.round(x) + ", " + Math.round(y);
        float nameW = Render2D.textWidth(HudStyle.TITLE, name, HudStyle.TINY_SIZE);
        float posW = Render2D.textWidth(HudStyle.NUM, pos, HudStyle.TINY_SIZE);
        float lw = nameW + posW + 13.0F;
        float lh = 9.5F;
        float lx = x + (w - lw) * 0.5F;
        float rise = (1.0F - d) * 4.0F;
        float ly = y - lh - 4.0F + rise;
        if (ly < 2.0F) {
            ly = y + h + 4.0F - rise;
        }
        Render2D.rect(lx, ly, lw, lh, lh * 0.5F, HudStyle.glassColor(235.0F, d));
        Render2D.outline(lx, ly, lw, lh, lh * 0.5F, 0.5F, HudStyle.accent(140.0F, d));
        HudStyle.dot(lx + 4.5F, ly + lh * 0.5F, 2.2F, HudStyle.accent(255.0F, d));
        float ty = ly + (lh - HudStyle.TINY_SIZE) * 0.5F;
        Render2D.text(HudStyle.TITLE, name, lx + 8.0F, ty, HudStyle.TINY_SIZE, HudStyle.text(d));
        Render2D.text(HudStyle.NUM, pos, lx + 8.0F + nameW + 3.0F, ty, HudStyle.TINY_SIZE, HudStyle.muted(d));
    }

    private float screenWidth() {
        if (mc.getWindow() == null) {
            return 0.0F;
        }
        return mc.getWindow().getGuiScaledWidth() / Math.max(0.0001F, Render2DCoordinateSpace.guiIndependentScale());
    }

    private float screenHeight() {
        if (mc.getWindow() == null) {
            return 0.0F;
        }
        return mc.getWindow().getGuiScaledHeight() / Math.max(0.0001F, Render2DCoordinateSpace.guiIndependentScale());
    }

    public String elementName() {
        return elementName;
    }

    public void setHudVisible(boolean visible) {
        enabled = visible;
        shown = visible;
        drag.visible(visible);
    }

    protected boolean selected() {
        return enabled;
    }

    protected void contentVisible(boolean visible) {
        shown = visible;
        drag.visible(enabled && visible);
    }

    protected float contentAlpha(boolean targetVisible) {
        contentAnimation.update();
        contentAnimation.run(targetVisible ? 1.0 : 0.0, CONTENT_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        float alpha = contentAnimation.get();
        contentVisible(targetVisible || alpha > 0.01F || contentAnimation.isAlive());
        return alpha;
    }

    protected boolean editPreview() {
        return mc.screen instanceof ChatScreen;
    }

    protected void panelBackground(float x, float y, float width, float height, float alpha) {
        HudStyle.glass(x, y, width, height, PANEL_RADIUS, alpha);
    }

    /** Стеклянная карточка со встроенной шапкой (иконка, заголовок, счётчик). */
    protected void card(float x, float y, float width, float height, String glyph, float glyphSize,
                        String title, String badge, float alpha) {
        HudStyle.glass(x, y, width, height, PANEL_RADIUS, alpha);
        HudStyle.header(x, y, width, HudStyle.HEADER, glyph, glyphSize, title, badge, alpha);
    }

    protected int themed(int red, int green, int blue, float alpha) {
        return ColorUtil.rgba(red, green, blue, Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F));
    }

    protected void size(float targetWidth, float targetHeight) {
        float delta = deltaSeconds();
        animatedWidth = smooth(animatedWidth, targetWidth, delta, 8.0F);
        animatedHeight = smooth(animatedHeight, targetHeight, delta, 8.0F);
        if (Math.abs(animatedWidth - targetWidth) < 0.2F) {
            animatedWidth = targetWidth;
        }
        if (Math.abs(animatedHeight - targetHeight) < 0.2F) {
            animatedHeight = targetHeight;
        }
        drag.size((float) Math.ceil(animatedWidth), (float) Math.ceil(animatedHeight));
        drag.clamp(ElementScreen.current());
    }

    protected String trimToWidth(String text, FontType font, float size, float width) {
        if (text == null) {
            return "";
        }
        if (Render2D.textWidth(font, text, size) <= width) {
            return text;
        }
        String suffix = "..";
        if (Render2D.textWidth(font, suffix, size) > width) {
            return suffix;
        }

        int low = 0;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = text.substring(0, mid) + suffix;
            if (Render2D.textWidth(font, candidate, size) <= width) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best <= 0 ? suffix : text.substring(0, best) + suffix;
    }

    protected String formatDurationTicks(int ticks) {
        if (ticks < 0) {
            return "inf";
        }
        int totalSeconds = Math.max(0, ticks / 20);
        return totalSeconds / 60 + ":" + twoDigits(totalSeconds % 60);
    }

    protected static String twoDigits(int value) {
        int safe = Math.max(0, Math.min(99, value));
        return ASCII_CHARS['0' + safe / 10] + ASCII_CHARS['0' + safe % 10];
    }

    protected static String timerText(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }
        int totalSeconds = Math.max(0, ticks / 20);
        int minutes = Math.min(99, totalSeconds / 60);
        int seconds = totalSeconds % 60;
        return twoDigits(minutes) + ":" + twoDigits(seconds);
    }

    protected static String charText(char c) {
        return c < ASCII_CHARS.length ? ASCII_CHARS[c] : Character.toString(c);
    }

    protected String shortBind(KeyBind bind) {
        if (bind == null || !bind.isBound()) {
            return "None";
        }
        return bind.getDisplayName()
                .replace("MOUSE ", "M")
                .replace("L SHIFT", "LSH")
                .replace("R SHIFT", "RSH")
                .replace("L CTRL", "LCT")
                .replace("R CTRL", "RCT")
                .replace("SPACE", "SPC");
    }

    protected static float smooth(float current, float target, float deltaSeconds, float speed) {
        float factor = (float) (1.0D - Math.pow(0.001D, Math.max(0.0F, deltaSeconds) * speed));
        return current + (target - current) * factor;
    }

    protected static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float deltaSeconds() {
        long now = System.currentTimeMillis();
        float delta = Math.min(0.1F, Math.max(0.0F, (now - lastFrameMs) / 1000.0F));
        lastFrameMs = now;
        return delta;
    }
}
