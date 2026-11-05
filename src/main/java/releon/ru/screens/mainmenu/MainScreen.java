package releon.ru.screens.mainmenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import releon.ru.utils.PlaytimeTracker;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
public class MainScreen extends Screen {

    private static final String TELEGRAM_URL = "https://t.me/Titaniumdls";
    private static final String DISCORD_URL = "https://discord.gg/fZgMjDda4G";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final String[] GREETINGS = {
            "Привет", "Hello", "Hola", "Bonjour", "Ciao", "Hallo", "Olá", "Hej",
            "Удачи", "Good luck", "Have fun", "Приятной игры", "Welcome back"
    };
    private static final float GREET_PERIOD = 3.2F;

    private static final float M = 26.0F;
    private static final float BTN_W = 150.0F;
    private static final float BTN_H = 26.0F;
    private static final float BTN_GAP = 6.0F;
    private static final float QC_W = 140.0F;
    private static final float QC_H = 20.0F;
    private static final float QC_GAP = 4.0F;
    private static final long OUTRO_MS = 320L;

    private static final String[][] PRESET_SERVERS = {
            {"ReallyWorld", "ReallyWorld.ru"},
            {"SpookyTime", "play.DILDOtime.su"},
            {"FunTime", "FunTime.su"},
            {"Close", "svhost:112.11.2.0"}
    };

    private static String customServerIp = "";
    private static boolean introPlayed;

    private final Btn[] buttons = {
            new Btn("I", "Одиночная игра", () -> minecraft.setScreen(new SelectWorldScreen(new MainScreen()))),
            new Btn("U", "Сетевая игра", () -> minecraft.setScreen(new JoinMultiplayerScreen(new MainScreen()))),
            new Btn("T", "Настройки", () -> minecraft.setScreen(new OptionsScreen(new MainScreen(), minecraft.options))),
            new Btn("R", "Выход", () -> minecraft.stop())
    };
    private final Pill[] pills = {new Pill("Telegram", TELEGRAM_URL), new Pill("Discord", DISCORD_URL)};
    private final Slot[] slots = new Slot[PRESET_SERVERS.length + 1];

    private long openedAt = System.currentTimeMillis();
    private long lastNanos;
    private float speed = 1.0F;
    private long outroStart = -1L;
    private Runnable outroAction;
    private float parX, parY;

    private float btnX, btnY, qcX, qcY;
    private boolean editing;
    private String buffer = "";
    private long editAt;

    public MainScreen() {
        super(Component.literal("Titanium Client"));
        for (int i = 0; i < PRESET_SERVERS.length; i++) {
            slots[i] = new Slot(PRESET_SERVERS[i][0], PRESET_SERVERS[i][1], false, i);
        }
        slots[PRESET_SERVERS.length] = new Slot("Свой сервер", null, true, PRESET_SERVERS.length);
    }

    @Override
    protected void init() {
        // Экран мог вернуться назад после неудачного захода/кика (кнопка "Назад" в DisconnectedScreen).
        // Без сброса он оставался в состоянии "ухода": всё прозрачное, клики игнорируются -> серый экран.
        outroStart = -1L;
        outroAction = null;
        openedAt = System.currentTimeMillis();
        lastNanos = 0L;
        editing = false;
        speed = introPlayed ? 1.8F : 1.0F;
        introPlayed = true;
        layout();
    }

    @Override
    protected void repositionElements() {
        layout();
    }

    private void layout() {
        float w = Render2D.getFixedScaledWidth();
        float h = Render2D.getFixedScaledHeight();
        float total = buttons.length * BTN_H + (buttons.length - 1) * BTN_GAP;
        btnX = M + 8.0F;
        btnY = h * 0.5F - total * 0.5F + 10.0F;
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].set(btnX, btnY + i * (BTN_H + BTN_GAP), i);
        }
        float px = btnX;
        for (Pill p : pills) {
            p.set(px, h - M - 18.0F);
            px += p.width() + 6.0F;
        }
        qcX = w - M - QC_W;
        qcY = h - M - slots.length * (QC_H + QC_GAP) + QC_GAP;
        for (int i = 0; i < slots.length; i++) {
            slots[i].set(qcX, qcY + i * (QC_H + QC_GAP));
        }
    }
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderPanorama(graphics, partialTick);

        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0.016F : Math.min(0.1F, (nanos - lastNanos) / 1.0e9F);
        lastNanos = nanos;
        long now = System.currentTimeMillis();
        float t = (now - openedAt) / 1000.0F;
        float it = t * speed;

        float w = Render2D.getFixedScaledWidth();
        float h = Render2D.getFixedScaledHeight();
        float mx = Render2D.guiToFixed((float) mouseX);
        float my = Render2D.guiToFixed((float) mouseY);
        float kp = 1.0F - (float) Math.exp(-dt * 4.0F);
        parX += ((mx / w - 0.5F) - parX) * kp;
        parY += ((my / h - 0.5F) - parY) * kp;

        float out = outroStart < 0L ? 0.0F : clamp((now - outroStart) / (float) OUTRO_MS);
        float ui = 1.0F - easeInOut(out);
        boolean active = outroStart < 0L;

        Render2D.beginFrame(graphics);

        float shade = easeOut(it / 0.9F);
        Render2D.rect(0, 0, w, h, 0, rgba(0, 0, 0, (int) (255 * (1.0F - shade))));
        Render2D.gradientRect(0, 0, w * 0.55F, h, 0,
                rgba(0, 0, 0, 120), rgba(0, 0, 0, 0), rgba(0, 0, 0, 0), rgba(0, 0, 0, 120));
        Render2D.gradientRect(0, h * 0.6F, w, h * 0.4F, 0,
                rgba(0, 0, 0, 0), rgba(0, 0, 0, 0), rgba(0, 0, 0, 110), rgba(0, 0, 0, 110));
        Render2D.rect(0, 0, w, h, 0, rgba(0, 0, 0, (int) (90 * out)));

        float l1x = -parX * 8.0F - out * 18.0F, l1y = -parY * 5.0F;
        float l2x = -parX * 4.0F + out * 18.0F, l2y = -parY * 3.0F;

        drawLogo(it, ui, l1x, l1y);
        for (Btn b : buttons) b.render(t, it, ui, l1x, l1y, mx, my, dt, active);
        for (Pill p : pills) p.render(it, ui, l1x, l1y, mx, my, dt, active);

        drawClock(w, it, ui, l2x, l2y);
        drawGreeting(w, h, t, it, ui, l2x, l2y);
        drawQuickHeader(it, ui, l2x, l2y);
        for (Slot s : slots) s.render(it, ui, l2x, l2y, mx, my, dt, active);

        Render2D.flush();

        if (outroStart >= 0L && out >= 1.0F && outroAction != null) {
            Runnable r = outroAction;
            outroAction = null;
            r.run();
        }
    }

    private void drawLogo(float it, float ui, float ox, float oy) {
        float a = easeOut((it - 0.15F) / 0.6F) * ui;
        if (a <= 0.01F) return;
        float x = btnX + ox - (1.0F - a) * 12.0F;
        float y = btnY - 46.0F + oy;
        Render2D.text(FontType.BOLD, "Titanium", x, y, 18.0F, rgba(255, 255, 255, (int) (245 * a)));
        float tw = Render2D.textWidth(FontType.BOLD, "Titanium", 18.0F);
        Render2D.text(FontType.SEMIBOLD, "client", x + tw + 4.0F, y + 9.0F, 7.0F, rgba(255, 255, 255, (int) (120 * a)));
        float lw = 26.0F * easeOut((it - 0.45F) / 0.5F);
        Render2D.rect(x + 1.0F, y + 24.0F, lw, 1.5F, 0.75F, rgba(255, 255, 255, (int) (200 * a)));
    }

    private void drawClock(float w, float it, float ui, float ox, float oy) {
        float a = easeOut((it - 0.35F) / 0.6F) * ui;
        if (a <= 0.01F) return;
        String time = LocalTime.now().format(CLOCK);
        String play = "в игре " + PlaytimeTracker.getFormatted();
        float ts = 7.5F, ps = 5.5F;
        float tw = Render2D.textWidth(FontType.BOLD, time, ts);
        float pw = Render2D.textWidth(FontType.SEMIBOLD, play, ps);
        float cw = tw + pw + 30.0F, ch = 20.0F;
        float x = w - M - cw + ox + (1.0F - a) * 12.0F;
        float y = M + oy;
        glass(x, y, cw, ch, ch * 0.5F, a, 0.0F);
        boolean blink = (System.currentTimeMillis() / 600L) % 2L == 0L;
        Render2D.rect(x + 8.0F, y + ch * 0.5F - 2.0F, 4.0F, 4.0F, 2.0F,
                rgba(120, 220, 150, (int) ((blink ? 230 : 110) * a)));
        Render2D.text(FontType.BOLD, time, x + 17.0F, y + (ch - ts) * 0.5F, ts, rgba(255, 255, 255, (int) (240 * a)));
        Render2D.text(FontType.SEMIBOLD, play, x + 17.0F + tw + 7.0F, y + (ch - ps) * 0.5F + 0.5F, ps,
                rgba(255, 255, 255, (int) (140 * a)));
    }

    private void drawGreeting(float w, float h, float t, float it, float ui, float ox, float oy) {
        float a = easeOut((it - 0.4F) / 0.8F) * ui;
        if (a <= 0.01F) return;
        int idx = (int) (t / GREET_PERIOD) % GREETINGS.length;
        float local = t % GREET_PERIOD;
        String word = GREETINGS[idx];
        float size = 34.0F;
        float right = w - M - 8.0F + ox;
        float y = h * 0.40F + oy;

        float ww = Render2D.textWidth(FontType.BOLD, word, size);
        float x = right - ww;
        int n = word.length();
        for (int i = 0; i < n; i++) {
            String ch = String.valueOf(word.charAt(i));
            float inP = easeOut((local - i * 0.035F) / 0.45F);
            float outP = easeInOut((local - (GREET_PERIOD - 0.45F) - i * 0.025F) / 0.35F);
            float la = inP * (1.0F - outP) * a;
            float dy = (1.0F - inP) * 10.0F - outP * 10.0F;
            if (la > 0.01F) {
                Render2D.text(FontType.BOLD, ch, x, y + dy, size, rgba(255, 255, 255, (int) (245 * la)));
            }
            x += Render2D.textWidth(FontType.BOLD, ch, size);
        }

        float bw = 46.0F;
        float p = clamp(local / GREET_PERIOD);
        Render2D.rect(right - bw, y + size + 8.0F, bw, 1.5F, 0.75F, rgba(255, 255, 255, (int) (40 * a)));
        Render2D.rect(right - bw * p, y + size + 8.0F, bw * p, 1.5F, 0.75F, rgba(255, 255, 255, (int) (190 * a)));

        String name = minecraft != null && minecraft.getUser() != null ? minecraft.getUser().getName() : "Player";
        float ns = 7.0F;
        float nw = Render2D.textWidth(FontType.SEMIBOLD, name, ns);
        Render2D.text(FontType.SEMIBOLD, name, right - nw, y + size + 15.0F, ns, rgba(255, 255, 255, (int) (150 * a)));
    }

    private void drawQuickHeader(float it, float ui, float ox, float oy) {
        float a = easeOut((it - 0.5F) / 0.6F) * ui;
        if (a <= 0.01F) return;
        String s = "Быстрое подключение";
        float size = 6.0F;
        float sw = Render2D.textWidth(FontType.SEMIBOLD, s, size);
        Render2D.text(FontType.SEMIBOLD, s, qcX + QC_W - sw + ox, qcY - size - 6.0F + oy, size,
                rgba(255, 255, 255, (int) (150 * a)));
    }

    private static void glass(float x, float y, float w, float h, float r, float a, float hover) {
        Render2D.rect(x, y + 2.0F, w, h, r, rgba(0, 0, 0, (int) ((40 + 20 * hover) * a)));
        Render2D.glass(x, y, w, h, r,
                rgba(255, 255, 255, (int) ((40 + 30 * hover) * a)), 0.62F, 40.0F,
                rgba(255, 255, 255, (int) ((100 + 50 * hover) * a)), 0.15F,
                true, 0.2F, 0.003F, 1.0F, 0.0F);
        Render2D.glassOutline(x, y, w, h, r, 0.9F,
                rgba(255, 255, 255, (int) ((70 + 90 * hover) * a)), 1.0F, 30.0F,
                rgba(168, 168, 174, (int) (80 * a)), 0.4F,
                false, 0.42F, 0.0015F, 1.0F, 0.0F);
    }

    private void leaveTo(Runnable action) {
        if (outroStart >= 0L) return;
        outroAction = action;
        outroStart = System.currentTimeMillis();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (outroStart >= 0L) return true;
        float fx = Render2D.guiToFixed((float) event.x());
        float fy = Render2D.guiToFixed((float) event.y());
        for (Btn b : buttons) if (b.click(event, fx, fy)) return true;
        for (Pill p : pills) if (p.click(event, fx, fy)) return true;
        for (Slot s : slots) if (s.click(event, fx, fy)) return true;
        editing = false;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!editing) return super.keyPressed(event);
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            String ip = buffer.trim();
            editing = false;
            if (!ip.isEmpty()) {
                customServerIp = ip;
                connectTo("Custom", ip);
            }
        } else if (key == GLFW.GLFW_KEY_ESCAPE) {
            editing = false;
        } else if (key == GLFW.GLFW_KEY_BACKSPACE && !buffer.isEmpty()) {
            buffer = buffer.substring(0, buffer.length() - 1);
        } else if (key == GLFW.GLFW_KEY_V && (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            String clip = minecraft.keyboardHandler.getClipboard();
            if (clip != null) {
                String joined = buffer + clip.replaceAll("\\s+", "");
                buffer = joined.length() > 64 ? joined.substring(0, 64) : joined;
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing) {
            char c = (char) event.codepoint();
            if (buffer.length() < 64 && c > 32) buffer += c;
            return true;
        }
        return super.charTyped(event);
    }

    private void connectTo(String name, String ip) {
        if (ip == null || ip.isBlank()) return;
        leaveTo(() -> {
            try {
                ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(ip),
                        new ServerData(name, ip, ServerData.Type.OTHER), false, null);
            } catch (Throwable ignored) {
                outroStart = -1L;
                outroAction = null;
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private final class Btn {
        final String icon, label;
        final Runnable action;
        float x, y, hover, press, dx, dy;
        int index;

        Btn(String icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }

        void set(float x, float y, int index) {
            this.x = x;
            this.y = y;
            this.index = index;
        }

        void render(float t, float it, float ui, float ox, float oy, float mx, float my, float dt, boolean active) {
            float a = easeOut((it - 0.25F - index * 0.07F) / 0.5F) * ui;
            if (a <= 0.01F) return;
            float bx = x + ox - (1.0F - a) * 22.0F;
            float by = y + oy;
            dx = bx;
            dy = by;
            boolean over = active && inside(mx, my, bx, by, BTN_W, BTN_H);
            hover = approach(hover, over ? 1 : 0, dt, 12.0F);
            press = approach(press, 0, dt, 9.0F);

            float slide = easeOut(hover) * 5.0F;
            float s = press * 1.5F;
            float gx = bx + slide + s, gy = by + s * 0.5F, gw = BTN_W - s * 2.0F, gh = BTN_H - s;
            glass(gx, gy, gw, gh, 7.0F, a, hover);

            float ih = 4.0F + 10.0F * easeOut(hover);
            Render2D.rect(gx + 3.0F, gy + (gh - ih) * 0.5F, 1.8F, ih, 0.9F, rgba(255, 255, 255, (int) (230 * hover * a)));

            float is = 10.0F;
            float ix = gx + 11.0F;
            Render2D.text(FontType.GUI_ICONS, icon, ix, gy + (gh - is) * 0.5F, is,
                    rgba(255, 255, 255, (int) ((180 + 70 * hover) * a)));
            float ts = 7.5F;
            Render2D.text(FontType.SEMIBOLD, label, ix + is + 8.0F + hover * 2.0F, gy + (gh - ts) * 0.5F, ts,
                    rgba(255, 255, 255, (int) ((205 + 45 * hover) * a)));

            float aa = hover * a;
            if (aa > 0.01F) {
                float ax = gx + gw - 14.0F - (1.0F - hover) * 5.0F;
                float ay = gy + gh * 0.5F;
                Render2D.text(FontType.SEMIBOLD, "›", ax, ay - 5.0F, 10.0F, rgba(255, 255, 255, (int) (220 * aa)));
            }
        }

        boolean click(MouseButtonEvent e, float fx, float fy) {
            if (e.button() != 0 || !inside(fx, fy, dx, dy, BTN_W, BTN_H)) return false;
            press = 1.0F;
            leaveTo(action);
            return true;
        }
    }

    private final class Pill {
        final String label, url;
        float x, y, hover, dx, dy;

        Pill(String label, String url) {
            this.label = label;
            this.url = url;
        }

        float width() {
            return Render2D.textWidth(FontType.SEMIBOLD, label, 6.0F) + 22.0F;
        }

        void set(float x, float y) {
            this.x = x;
            this.y = y;
        }

        void render(float it, float ui, float ox, float oy, float mx, float my, float dt, boolean active) {
            float a = easeOut((it - 0.6F) / 0.5F) * ui;
            if (a <= 0.01F) return;
            float w = width(), h = 18.0F;
            float px = x + ox, py = y + oy + (1.0F - a) * 8.0F - easeOut(hover) * 2.0F;
            dx = px;
            dy = py;
            boolean over = active && inside(mx, my, px, py, w, h);
            hover = approach(hover, over ? 1 : 0, dt, 12.0F);
            glass(px, py, w, h, h * 0.5F, a, hover);
            float tw = Render2D.textWidth(FontType.SEMIBOLD, label, 6.0F);
            Render2D.text(FontType.SEMIBOLD, label, px + (w - tw) * 0.5F, py + (h - 6.0F) * 0.5F, 6.0F,
                    rgba(255, 255, 255, (int) ((190 + 60 * hover) * a)));
        }

        boolean click(MouseButtonEvent e, float fx, float fy) {
            if (e.button() != 0 || !inside(fx, fy, dx, dy, width(), 18.0F)) return false;
            try {
                Util.getPlatform().openUri(URI.create(url));
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    private final class Slot {
        final String name, ip;
        final boolean custom;
        final int index;
        float x, y, hover, dx, dy;

        Slot(String name, String ip, boolean custom, int index) {
            this.name = name;
            this.ip = ip;
            this.custom = custom;
            this.index = index;
        }

        void set(float x, float y) {
            this.x = x;
            this.y = y;
        }

        String ip() {
            return custom ? customServerIp : ip;
        }

        void render(float it, float ui, float ox, float oy, float mx, float my, float dt, boolean active) {
            float a = easeOut((it - 0.55F - index * 0.06F) / 0.5F) * ui;
            if (a <= 0.01F) return;
            float sx = x + ox + (1.0F - a) * 18.0F - easeOut(hover) * 4.0F;
            float sy = y + oy;
            dx = sx;
            dy = sy;
            boolean over = active && inside(mx, my, sx, sy, QC_W, QC_H);
            boolean thisEditing = custom && editing;
            hover = approach(hover, over || thisEditing ? 1 : 0, dt, 12.0F);
            glass(sx, sy, QC_W, QC_H, 6.0F, a, hover);

            float ts = 6.0F;
            float ty = sy + (QC_H - ts) * 0.5F;
            if (thisEditing) {
                String txt = buffer.isEmpty() ? "введи IP..." : buffer;
                Render2D.text(FontType.SEMIBOLD, txt, sx + 9.0F, ty, ts,
                        rgba(255, 255, 255, (int) ((buffer.isEmpty() ? 120 : 235) * a)));
                if (((System.currentTimeMillis() - editAt) / 500L) % 2L == 0L) {
                    float cw = buffer.isEmpty() ? 0 : Render2D.textWidth(FontType.SEMIBOLD, buffer, ts);
                    Render2D.rect(sx + 9.5F + cw, sy + 5.0F, 0.8F, QC_H - 10.0F, 0, rgba(255, 255, 255, (int) (220 * a)));
                }
                return;
            }
            String cur = ip();
            boolean empty = cur == null || cur.isEmpty();
            Render2D.rect(sx + 8.0F, sy + QC_H * 0.5F - 2.0F, 4.0F, 4.0F, 2.0F,
                    empty ? rgba(150, 150, 156, (int) (170 * a)) : rgba(120, 220, 150, (int) (220 * a)));
            String title = custom && empty ? "Добавить сервер" : name;
            Render2D.text(FontType.SEMIBOLD, title, sx + 17.0F, ty, ts, rgba(255, 255, 255, (int) ((210 + 40 * hover) * a)));
            String right = empty ? "+" : (hover > 0.5F ? "Играть" : cur);
            if (right.length() > 16) right = right.substring(0, 15) + "…";
            float rs = 5.0F;
            float rw = Render2D.textWidth(FontType.SEMIBOLD, right, rs);
            Render2D.text(FontType.SEMIBOLD, right, sx + QC_W - rw - 8.0F, sy + (QC_H - rs) * 0.5F, rs,
                    rgba(255, 255, 255, (int) ((120 + 110 * hover) * a)));
        }

        boolean click(MouseButtonEvent e, float fx, float fy) {
            if (!inside(fx, fy, dx, dy, QC_W, QC_H)) return false;
            if (custom && (e.button() == 1 || customServerIp.isEmpty())) {
                editing = true;
                buffer = customServerIp;
                editAt = System.currentTimeMillis();
                return true;
            }
            if (e.button() == 0) {
                connectTo(name, ip());
                return true;
            }
            return false;
        }
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int rgba(int r, int g, int b, int a) {
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float approach(float cur, float target, float dt, float speed) {
        return cur + (target - cur) * (1.0F - (float) Math.exp(-dt * speed));
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static float easeOut(float t) {
        t = clamp(t);
        return 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
    }

    private static float easeInOut(float t) {
        t = clamp(t);
        return t < 0.5F ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }
}
