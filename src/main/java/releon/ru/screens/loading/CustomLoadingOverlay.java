package releon.ru.screens.loading;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.Optional;
import java.util.function.Consumer;
public class CustomLoadingOverlay extends LoadingOverlay {

    private static final int BG = 0xFF08080A;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT = 0xFFEDEDF0;
    private static final int GRAY = 0xFF8A8A93;
    private static final int DARK = 0xFF55555E;

    private static final long FADE_IN_MS = 300L;
    private static final long FADE_OUT_MS = 550L;
    private static final float RADIUS = 30.0F;
    private static final float THICK = 2.2F;
    private static final int STEPS = 180;

    private final Minecraft minecraft;
    private final ReloadInstance reload;
    private final Consumer<Optional<Throwable>> onFinish;
    private final boolean fadeIn;

    private final long startedAt = System.currentTimeMillis();
    private long fadeOutStart = -1L;
    private long lastFrameNanos;
    private float shown;
    private boolean finished;

    public CustomLoadingOverlay(Minecraft minecraft, ReloadInstance reload,
                                Consumer<Optional<Throwable>> onFinish, boolean fadeIn) {
        super(minecraft, reload, onFinish, fadeIn);
        this.minecraft = minecraft;
        this.reload = reload;
        this.onFinish = onFinish;
        this.fadeIn = fadeIn;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : Math.min(0.1F, (nanos - lastFrameNanos) / 1.0e9F);
        lastFrameNanos = nanos;

        boolean done = reload.isDone();
        float target = done ? 1.0F : clamp(reload.getActualProgress());
        shown = Math.max(shown, shown + (target - shown) * (1.0F - (float) Math.exp(-dt * 5.0F)));
        if (done && shown > 0.995F && fadeOutStart < 0L) {
            shown = 1.0F;
            fadeOutStart = now;
        }

        float t = (now - startedAt) / 1000.0F;
        float in = fadeIn ? easeOut((now - startedAt) / (float) FADE_IN_MS) : 1.0F;
        float out = fadeOutStart < 0L ? 0.0F : clamp((now - fadeOutStart) / (float) FADE_OUT_MS);
        float alpha = in * (1.0F - easeInOut(out));
        float appear = easeOut(t / 0.6F) * alpha;

        float w = Render2D.getFixedScaledWidth();
        float h = Render2D.getFixedScaledHeight();
        float cx = w * 0.5F;
        float cy = h * 0.5F - 10.0F;
        float r = RADIUS * (1.0F + easeOut(out) * 0.08F);

        Render2D.beginFrame(graphics);

        Render2D.rect(0, 0, w, h, 0, withAlpha(BG, alpha));

        for (int i = 0; i < STEPS; i++) {
            float a = i / (float) STEPS * 6.2832F;
            dot(cx + sin(a) * r, cy - cos(a) * r, THICK, withAlpha(WHITE, 0.07F * appear));
        }
        int filled = (int) (STEPS * shown);
        for (int i = 0; i <= filled; i++) {
            float k = Math.min(shown, i / (float) STEPS);
            float a = k * 6.2832F;
            dot(cx + sin(a) * r, cy - cos(a) * r, THICK, withAlpha(WHITE, (0.55F + 0.45F * (k / Math.max(shown, 0.001F))) * appear));
        }
        if (shown > 0.002F && shown < 0.999F) {
            float a = shown * 6.2832F;
            float pulse = 0.5F + 0.5F * sin(t * 4.0F);
            float hx = cx + sin(a) * r, hy = cy - cos(a) * r;
            dot(hx, hy, THICK + 4.0F + pulse * 1.5F, withAlpha(WHITE, 0.08F * appear));
            dot(hx, hy, THICK + 1.4F, withAlpha(WHITE, appear));
        }
        String pct = Integer.toString(Math.round(shown * 100.0F));
        float ps = 14.0F, ss = 6.0F;
        float pw = Render2D.textWidth(FontType.BOLD, pct, ps);
        float sw = Render2D.textWidth(FontType.SEMIBOLD, "%", ss);
        float total = pw + 1.0F + sw;
        float px = cx - total * 0.5F;
        float py = cy - ps * 0.5F - 0.5F;
        Render2D.text(FontType.BOLD, pct, px, py, ps, withAlpha(TEXT, appear));
        Render2D.text(FontType.SEMIBOLD, "%", px + pw + 1.0F, py + 2.5F, ss, withAlpha(GRAY, appear));
        float ty = cy + r + 20.0F;
        String title = done ? "Клиент готов" : (fadeIn ? "Перезагрузка ресурсов" : "Подготовка клиента");
        float ts = 7.0F;
        float tw = Render2D.textWidth(FontType.SEMIBOLD, title, ts);
        Render2D.text(FontType.SEMIBOLD, title, cx - tw * 0.5F, ty, ts, withAlpha(TEXT, appear));

        String stage = stage(done) + dots(t, done);
        float st = 5.5F;
        float stw = Render2D.textWidth(FontType.SEMIBOLD, stage(done) + "...", st);
        Render2D.text(FontType.SEMIBOLD, stage, cx - stw * 0.5F, ty + 11.0F, st, withAlpha(GRAY, appear));

        String hint = "Пожалуйста, подождите";
        float hs = 5.0F;
        float hw = Render2D.textWidth(FontType.SEMIBOLD, hint, hs);
        Render2D.text(FontType.SEMIBOLD, hint, cx - hw * 0.5F, h - 22.0F, hs, withAlpha(DARK, appear));

        String brand = "TITANIUM";
        float bs = 5.0F, track = 2.5F;
        float bw = 0;
        for (int i = 0; i < brand.length(); i++) bw += Render2D.textWidth(FontType.BOLD, String.valueOf(brand.charAt(i)), bs) + track;
        bw -= track;
        float bx = cx - bw * 0.5F;
        for (int i = 0; i < brand.length(); i++) {
            String ch = String.valueOf(brand.charAt(i));
            Render2D.text(FontType.BOLD, ch, bx, 18.0F, bs, withAlpha(DARK, appear));
            bx += Render2D.textWidth(FontType.BOLD, ch, bs) + track;
        }

        Render2D.flush();

        if (fadeOutStart >= 0L && out >= 1.0F && !finished) {
            finished = true;
            minecraft.setOverlay(null);
            try {
                reload.checkExceptions();
                onFinish.accept(Optional.empty());
            } catch (Throwable throwable) {
                onFinish.accept(Optional.of(throwable));
            }
        }
    }

    private String stage(boolean done) {
        if (done) return "Готово";
        if (shown < 0.12F) return "Инициализация";
        if (shown < 0.35F) return "Загрузка текстур";
        if (shown < 0.60F) return "Загрузка моделей";
        if (shown < 0.80F) return "Загрузка звуков";
        if (shown < 0.95F) return "Подготовка модулей";
        return "Почти готово";
    }

    private static String dots(float t, boolean done) {
        if (done) return "";
        int n = (int) (t * 2.5F) % 4;
        return n == 0 ? "" : (n == 1 ? "." : (n == 2 ? ".." : "..."));
    }

    private static void dot(float x, float y, float d, int color) {
        if ((color >>> 24) == 0) return;
        Render2D.rect(x - d * 0.5F, y - d * 0.5F, d, d, d * 0.5F, color);
    }

    private static float sin(float v) { return (float) Math.sin(v); }
    private static float cos(float v) { return (float) Math.cos(v); }

    private static int withAlpha(int argb, float a) {
        return (Math.round((argb >>> 24) * clamp(a)) << 24) | (argb & 0xFFFFFF);
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static float easeOut(float t) {
        t = clamp(t);
        return 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
    }

    private static float easeInOut(float t) {
        t = clamp(t);
        return t < 0.5F ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }
}
