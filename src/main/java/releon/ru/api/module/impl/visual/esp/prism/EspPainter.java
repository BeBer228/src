package releon.ru.api.module.impl.visual.esp.prism;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * "Prism" ESP renderer. The frame is drawn in layered passes inside one {@link GuiBatch} scope:
 * every shape of every entity shares the batch atlas, so all boxes/bars/tag backgrounds become one
 * render element; text is drawn per font after that (one element per glyph page), then
 * faces/items. Overlapping tags fall back to per-tag drawing (see {@link #layout}).
 */
public final class EspPainter {
    public enum Box { CORNERS, FRAME, GLASS, NONE }

    public static final class Style {
        public Box box = Box.CORNERS;
        public float corner = .25f, thick = 1f, fillA = .18f, glowA = .45f, spread = 6f, barW = 2f;
        public boolean shadow = true, glow = true, pulse = true;
        public boolean bar = true, barRight, trail = true, absorption = true;
        public boolean tag = true, face = true, hpValue = true, distance = true, ping = true, strip = true, use = true;
        public boolean bold = true, equip = true, enchants = true, durability = true;
        public int font = 9, tagBg = 0xC70E1016, faceBg = 0xFF1A1D26, friendColor = 0xFF4ADE80, targetColor = 0xFFFF4D6D;
        public int equipmentBg = 0xDC0E1016, dropBg = 0xDC0E1016;
        public boolean dropIcon = true;
        public boolean mendingIndicator, krushIndicator, swordIndicator;
        public int mendingColor, krushColor, swordColor;
        public float boxScale = 1f, barScale = 1f, nameScale = 1f, hpScale = 1f, faceScale = 1f;
        public float distanceScale = 1f, pingScale = 1f, equipmentScale = 1f, itemScale = 1f;
        public float scale = 1f, minScale = .6f, fullAt = 10f;
    }

    /** One merged ground-item label: full styled name and count on a small plate. */
    public static final class Drop {
        public ItemStack stack;
        public ItemLabel label;
        /** name component (renamed) or item (plain) the label was built from */
        public Object nameKey;
        /** {@code type}: index into ESP item types; {@code override}: custom name colour, 0 = item's own */
        public int count, shownCount, countW, nameRarity = -1, type, override;
        public String countStr;
        public boolean valuable, hidden;
        public float sx, sy, dist, scale, w, h;
    }

    private static final int TEXT = 0xFFF2F4F8, MUTED = 0xFF9AA3B5, ENCH = 0xFFC9A8FF, GOLD = 0xFFFFD34D;
    private static final float PAD = 5f, CHIP = 14f, CHIP_GAP = 2f, DROP_H = 11f, DROP_PAD = 4f, DROP_ICON = 8f, DROP_GAP = 3f;
    /** Plates pushed above this many neighbours are dropped instead of building tall towers. */
    private static final int DROP_STACK = 6;
    private static final int EQ_FONT = 6;
    public static final int DROP_FONT = 7;
    private static final int[] ENCH_W = new int[100];

    static {
        java.util.Arrays.fill(ENCH_W, -1);
    }

    public final Style style = new Style();
    /** Screen-space tag rects {x, y, w, h, radius} of the last frame, for the frosted blur pass. */
    public float[] blur = new float[5 * 32];
    public int blurCount;

    private int infoFont() {
        return Math.max(6, style.font - 2);
    }

    private float faceSize() {
        return (style.font + 2f) * style.faceScale;
    }

    private float tagH(EspEntry e) {
        float h = style.font * style.nameScale;
        int info = infoFont();
        if (style.hpValue) h = Math.max(h, info * style.hpScale);
        if (style.distance) h = Math.max(h, info * style.distanceScale);
        if (style.ping && e.pingStr != null) h = Math.max(h, info * style.pingScale);
        if (hasFace(e)) h = Math.max(h, faceSize() - 2f);
        return h + 8f;
    }

    private float nameW(EspEntry e) { return e.nameW * style.nameScale; }
    private float hpW(EspEntry e) { return e.hpW * style.hpScale; }
    private float distW(EspEntry e) { return e.distW * style.distanceScale; }
    private float pingW(EspEntry e) { return e.pingW * style.pingScale; }


    private float gap() {
        return 2f + (style.strip ? 3f : 0f);
    }

    // ================================================================== layout

    /**
     * Measures tags and computes the distance scale. Tags that overlap another tag are marked
     * {@code solo}: they are drawn one by one far→near after the batched passes, so the layered
     * order never shows a far tag's text over a near tag.
     */
    public void layout(List<EspEntry> entries) {
        Style st = style;
        blurCount = 0;
        int n = entries.size();
        for (int i = 0; i < n; i++) {
            EspEntry e = entries.get(i);
            e.scale = st.scale * Mth.clamp(st.fullAt / Math.max(1f, e.dist), st.minScale, 1f);
            e.solo = false;
            if (!st.tag) continue;
            float h = tagH(e);
            e.lead = hasFace(e) ? PAD + faceSize() + 4f : PAD;
            float w = e.lead + nameW(e) + PAD;
            if (st.hpValue) w += 5f + hpW(e);
            if (st.distance) w += 5f + distW(e);
            if (st.ping && e.pingStr != null) w += 5f + pingW(e);
            e.tagW = w;
            e.tagH = h;
            int eq = st.equip ? e.eqCount : 0;
            e.eqW = eq == 0 ? 0f : (eq * CHIP + (eq - 1) * CHIP_GAP) * st.equipmentScale;
            float half = Math.max(w, e.eqW) * .5f * e.scale, cx = cx(e);
            e.rl = cx - half;
            e.rr = cx + half;
            e.rb = anchorY(e);
            e.rt = e.rb + (-gap() - h - (e.eqW > 0 ? CHIP * st.equipmentScale + 2f : 0f)) * e.scale;
        }
        if (!st.tag) return;
        for (int i = 1; i < n; i++) {
            EspEntry a = entries.get(i);
            for (int j = 0; j < i; j++) {
                EspEntry b = entries.get(j);
                if (a.rb <= b.rt || a.rt >= b.rb || a.rr <= b.rl || a.rl >= b.rr) continue;
                a.solo = b.solo = true;
            }
        }
        for (int i = 0; i < n && blurCount < 32; i++) {
            EspEntry e = entries.get(i);
            if (e.alpha < .5f) continue;
            float s = e.scale;
            int o = blurCount++ * 5;
            blur[o] = cx(e) - e.tagW * .5f * s;
            blur[o + 1] = anchorY(e) + (-gap() - e.tagH) * s;
            blur[o + 2] = e.tagW * s;
            blur[o + 3] = e.tagH * s;
            blur[o + 4] = 5f * s;
        }
    }

    private static float cx(EspEntry e) {
        return (e.x0 + e.x1) * .5f;
    }

    private float anchorY(EspEntry e) {
        return e.y0 - 1f - style.thick;
    }

    // ================================================================== render

    public void render(GuiGraphics g, List<EspEntry> entries, Drop[] drops, int dropCount) {
        PrismDraw.begin(g);
        try {
            int n = entries.size();
            float time = PrismDraw.time();
            if (style.glow) for (int i = n - 1; i >= 0; i--) glow(g, entries.get(i), time);
            for (int i = n - 1; i >= 0; i--) {
                EspEntry e = entries.get(i);
                if (style.box != Box.NONE) {
                    enterBox(g, e);
                    box(g, e);
                    g.pose().popMatrix();
                }
                if (style.bar) bar(g, e);
            }
            for (int i = 0; i < dropCount; i++) if (!drops[i].hidden) dropShape(g, drops[i]);
            if (style.tag) for (int i = n - 1; i >= 0; i--) if (!entries.get(i).solo) tagShape(g, entries.get(i));
            // pending batch quads must reach the render state before faces/items, or they cover them
            PrismDraw.flush(g);
            if (style.dropIcon) for (int i = 0; i < dropCount; i++) if (!drops[i].hidden) dropIcon(g, drops[i]);
            if (style.tag) for (int i = n - 1; i >= 0; i--) if (!entries.get(i).solo) tagIcons(g, entries.get(i));
            if (style.tag) {
                for (int pass = 0; pass < 4; pass++)
                    for (int i = n - 1; i >= 0; i--) if (!entries.get(i).solo) tagText(g, entries.get(i), pass);
            }
            for (int pass = 0; pass < 2; pass++)
                for (int i = 0; i < dropCount; i++) if (!drops[i].hidden) dropText(g, drops[i], pass);
            if (style.tag) {
                for (int i = n - 1; i >= 0; i--) {
                    EspEntry e = entries.get(i);
                    if (!e.solo) continue;
                    tagShape(g, e);
                    PrismDraw.flush(g);
                    tagIcons(g, e);
                    for (int pass = 0; pass < 4; pass++) tagText(g, e, pass);
                }
            }
        } finally {
            PrismDraw.end(g);
        }
    }

    // ================================================================== box

    private void enterBox(GuiGraphics g, EspEntry e) {
        var pose = g.pose();
        float x = cx(e), y = (e.y0 + e.y1) * .5f;
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(style.boxScale, style.boxScale);
        pose.translate(-x, -y);
    }

    private void glow(GuiGraphics g, EspEntry e, float time) {
        enterBox(g, e);
        float a = style.glowA * e.alpha;
        if (e.target && style.pulse) a *= .7f + .3f * Mth.sin(time * 6f);
        float w = e.x1 - e.x0, h = e.y1 - e.y0, sp = style.spread + Math.min(w, h) * .15f;
        PrismDraw.glow(g, cx(e), (e.y0 + e.y1) * .5f, w * .5f + sp, h * .5f + sp, mulA(e.c1, a));
        g.pose().popMatrix();
    }

    private void box(GuiGraphics g, EspEntry e) {
        float la = e.alpha, t = style.thick, w = e.x1 - e.x0, h = e.y1 - e.y0;
        if (style.box == Box.GLASS)
            PrismDraw.vgrad(g, e.x0, e.y0, e.x1, e.y1, mulA(e.c1, style.fillA * la), mulA(e.c2, style.fillA * .3f * la));
        boolean full = style.box != Box.CORNERS;
        float lx = full ? w : Math.max(t * 2f, w * style.corner), ly = full ? h : Math.max(t * 2f, h * style.corner);
        if (style.shadow) {
            int d = mulA(0x99000000, la);
            frame(g, e.x0 - .5f, e.y0 - .5f, e.x1 + .5f, e.y1 + .5f, lx + 1f, ly + 1f, t + 1f, d, d, full);
        }
        frame(g, e.x0, e.y0, e.x1, e.y1, lx, ly, t, mulA(e.c1, la), mulA(e.c2, la), full);
    }

    /** Brackets ({@code full == false}) or a full frame without overlapping segments, coloured top→bottom. */
    private static void frame(GuiGraphics g, float x0, float y0, float x1, float y1, float lx, float ly, float t,
                              int top, int bot, boolean full) {
        if (full) {
            seg(g, x0, y0, x1, y0 + t, y0, y1, top, bot);
            seg(g, x0, y1 - t, x1, y1, y0, y1, top, bot);
            seg(g, x0, y0 + t, x0 + t, y1 - t, y0, y1, top, bot);
            seg(g, x1 - t, y0 + t, x1, y1 - t, y0, y1, top, bot);
            return;
        }
        seg(g, x0, y0, x0 + lx, y0 + t, y0, y1, top, bot);
        seg(g, x0, y0 + t, x0 + t, y0 + ly, y0, y1, top, bot);
        seg(g, x1 - lx, y0, x1, y0 + t, y0, y1, top, bot);
        seg(g, x1 - t, y0 + t, x1, y0 + ly, y0, y1, top, bot);
        seg(g, x0, y1 - t, x0 + lx, y1, y0, y1, top, bot);
        seg(g, x0, y1 - ly, x0 + t, y1 - t, y0, y1, top, bot);
        seg(g, x1 - lx, y1 - t, x1, y1, y0, y1, top, bot);
        seg(g, x1 - t, y1 - ly, x1, y1 - t, y0, y1, top, bot);
    }

    private static void seg(GuiGraphics g, float xa, float ya, float xb, float yb, float y0, float y1, int top, int bot) {
        if (top == bot) {
            PrismDraw.rect(g, xa, ya, xb, yb, top);
            return;
        }
        float inv = 1f / Math.max(1f, y1 - y0);
        PrismDraw.vgrad(g, xa, ya, xb, yb, lerp(top, bot, (ya - y0) * inv), lerp(top, bot, (yb - y0) * inv));
    }

    private void bar(GuiGraphics g, EspEntry e) {
        float la = e.alpha, bw = style.barW * style.barScale, gap = 2f + style.thick;
        float bx = style.barRight ? e.x1 + gap : e.x0 - gap - bw, top = (e.y0 + e.y1) * .5f - (e.y1 - e.y0) * .5f * style.barScale,
                bot = top + (e.y1 - e.y0) * style.barScale, h = bot - top;
        PrismDraw.rect(g, bx - .5f, top - .5f, bx + bw + .5f, bot + .5f, mulA(0xB0000000, la));
        float total = e.total();
        float hr = Mth.clamp(e.shownHp / total, 0f, 1f);
        if (style.trail && e.trailHp > e.shownHp)
            PrismDraw.rect(g, bx, bot - h * Mth.clamp(e.trailHp / total, 0f, 1f), bx + bw, bot - h * hr, mulA(0xD0FFFFFF, la));
        int c = healthColor(e.shownHp / e.maxHp);
        PrismDraw.vgrad(g, bx, bot - h * hr, bx + bw, bot, mulA(c, la), mulA(lerp(c, 0xFF000000, .3f), la));
        if (style.absorption && e.abs > 0f)
            PrismDraw.rect(g, bx, bot - h * Mth.clamp(hr + e.abs / total, 0f, 1f), bx + bw, bot - h * hr, mulA(GOLD, la));
    }

    // ================================================================== tag

    /** Pushes the tag's local space: origin at the box top centre (stacked), scaled by distance. */
    private void enter(GuiGraphics g, EspEntry e) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx(e), anchorY(e));
        pose.scale(e.scale, e.scale);
    }

    private int special(EspEntry e) {
        return e.target ? style.targetColor : e.friend ? style.friendColor : 0;
    }

    private boolean hasFace(EspEntry e) {
        return style.face && e.entity instanceof AbstractClientPlayer;
    }

    private void tagShape(GuiGraphics g, EspEntry e) {
        float la = e.alpha, w = e.tagW, h = e.tagH, tx = -w * .5f, ty = -gap() - h;
        enter(g, e);
        int sp = special(e);
        if (sp != 0) PrismDraw.glow(g, 0f, ty + h * .5f, w * .5f + 10f, h * .5f + 8f, mulA(sp, .35f * la));
        PrismDraw.roundRect(g, tx, ty, w, h, 5f, mulA(style.tagBg, la));
        if (hasFace(e)) PrismDraw.roundRect(g, tx + PAD - 1f, ty + (h - faceSize()) * .5f - 1f,
                faceSize() + 2f, faceSize() + 2f, 4f, mulA(style.faceBg, la));
        if (style.strip) {
            float sx0 = tx + 4f, sx1 = tx + w - 4f, sy = ty + h + 1.5f;
            boolean using = style.use && e.use > 0f;
            float r = using ? e.use : Mth.clamp(e.shownHp / e.maxHp, 0f, 1f);
            int c = using ? e.c1 : sp != 0 ? sp : healthColor(r);
            PrismDraw.roundRect(g, sx0, sy, sx1 - sx0, 1.5f, .75f, mulA(0x90000000, la));
            PrismDraw.roundRect(g, sx0, sy, (sx1 - sx0) * r, 1.5f, .75f, mulA(c, la));
        }
        if (e.eqW > 0f) {
            enterEquipment(g, e, ty);
            float x = -e.eqW / style.equipmentScale * .5f, y = 0f;
            for (int i = 0; i < e.eqCount; i++, x += CHIP + CHIP_GAP) {
                PrismDraw.roundRect(g, x, y, CHIP, CHIP, 3f, mulA(style.equipmentBg, la));
                int highlight = equipmentHighlight(e.equipmentFlags[i]);
                if (highlight != 0) {
                    PrismDraw.glow(g, x + CHIP * .5f, y + CHIP * .5f, CHIP * .65f, CHIP * .65f, mulA(highlight, .22f * la));
                    PrismDraw.roundRect(g, x, y, CHIP, CHIP, 3f, mulA(highlight, .18f * la));
                }
                ItemStack s = e.eq[i];
                if (style.durability && s.isDamageableItem() && s.getMaxDamage() > 0) {
                    float left = Mth.clamp(1f - s.getDamageValue() / (float) s.getMaxDamage(), 0f, 1f);
                    PrismDraw.rect(g, x + 2f, y + CHIP - 2f, x + CHIP - 2f, y + CHIP - 1f, mulA(0xC0000000, la));
                    PrismDraw.rect(g, x + 2f, y + CHIP - 2f, x + 2f + (CHIP - 4f) * left, y + CHIP - 1f, mulA(healthColor(left), la));
                }
            }
            g.pose().popMatrix();
        }
        g.pose().popMatrix();
    }

    private int equipmentHighlight(int flags) {
        if (style.swordIndicator && (flags & EquipmentHighlights.SWORD) != 0) return style.swordColor;
        if (style.krushIndicator && (flags & EquipmentHighlights.KRUSH) != 0) return style.krushColor;
        return style.mendingIndicator && (flags & EquipmentHighlights.MENDING) != 0 ? style.mendingColor : 0;
    }

    private void enterEquipment(GuiGraphics g, EspEntry e, float tagTop) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(0f, tagTop - 2f - CHIP * style.equipmentScale);
        pose.scale(style.equipmentScale, style.equipmentScale);
    }

    private void tagIcons(GuiGraphics g, EspEntry e) {
        if (e.alpha < .05f) return;
        boolean face = hasFace(e);
        if (!face && e.eqW <= 0f) return;
        float w = e.tagW, h = e.tagH, ty = -gap() - h;
        enter(g, e);
        if (face) {
            var skin = ((AbstractClientPlayer)e.entity).getSkin();
            var texture = skin.body().texturePath();
            float size = faceSize(), x = -w * .5f + PAD, y = ty + (h - size) * .5f;
            float radius = Math.min(2f, size * .18f);
            int color = mulA(0xFFFFFFFF, e.alpha);
            PrismDraw.imageRegionRaw(g, x, y, size, texture,
                    color, radius, .125f, .125f, .25f, .25f);
            PrismDraw.imageRegionRaw(g, x, y, size, texture,
                    color, radius, .625f, .125f, .75f, .25f);
        }
        if (e.eqW > 0f) {
            Matrix3x2fStack pose = g.pose();
            enterEquipment(g, e, ty);
            float x = -e.eqW / style.equipmentScale * .5f, y = 0f, k = (CHIP - 2f) / 16f;
            for (int i = 0; i < e.eqCount; i++, x += CHIP + CHIP_GAP) {
                pose.pushMatrix();
                pose.translate(x + 1f, y + 1f);
                pose.scale(k, k);
                g.renderItem(e.eq[i], 0, 0);
                pose.popMatrix();
            }
            pose.popMatrix();
        }
        g.pose().popMatrix();
    }

    /** pass 0: name, 1: hp (bold info), 2: distance/ping (regular info), 3: equipment numbers. */
    private void tagText(GuiGraphics g, EspEntry e, int pass) {
        float la = e.alpha;
        if (la < .05f || (pass == 3 && e.eqW <= 0f)) return;
        float w = e.tagW, h = e.tagH, ty = -gap() - h, x = -w * .5f + e.lead;
        int info = infoFont();
        enter(g, e);
        switch (pass) {
            case 0 -> drawTagText(g, e.name, x, ty, h, style.font, style.nameScale,
                    mulA(e.friend ? style.friendColor : TEXT, la), style.bold);
            case 1 -> {
                if (style.hpValue) drawTagText(g, e.hpStr, x + nameW(e) + 5f, ty, h, info,
                        style.hpScale, mulA(e.abs > 0f ? GOLD : healthColor(e.shownHp / e.maxHp), la), true);
            }
            case 2 -> {
                float tx = x + nameW(e) + (style.hpValue ? 5f + hpW(e) : 0f) + 5f;
                if (style.distance) {
                    drawTagText(g, e.distStr, tx, ty, h, info, style.distanceScale, mulA(MUTED, la), false);
                    tx += distW(e) + 5f;
                }
                if (style.ping && e.pingStr != null)
                    drawTagText(g, e.pingStr, tx, ty, h, info, style.pingScale, mulA(pingColor(e.ping), la), false);
            }
            default -> {
                enterEquipment(g, e, ty);
                float cx = -e.eqW / style.equipmentScale * .5f, y = 0f;
                for (int i = 0; i < e.eqCount; i++, cx += CHIP + CHIP_GAP) {
                    int lvl = e.ench[i];
                    if (style.enchants && lvl > 0) {
                        lvl = Math.min(lvl, 99);
                        if (ENCH_W[lvl] < 0) ENCH_W[lvl] = PrismDraw.interBoldWidth(EspEntry.num(lvl), EQ_FONT);
                        shadowText(g, EspEntry.num(lvl), cx + CHIP - .5f - ENCH_W[lvl], y - 1f, ENCH, la);
                    }
                    int cnt = e.eq[i].getCount();
                    if (cnt > 1) shadowText(g, EspEntry.num(cnt), cx + 1f, y + CHIP - 7f, TEXT, la);
                }
                g.pose().popMatrix();
            }
        }
        g.pose().popMatrix();
    }

    private static void drawTagText(GuiGraphics g, String text, float x, float top,
                                    float height, int font, float scale, int color, boolean bold) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, top);
        pose.scale(scale, scale);
        if (bold) PrismDraw.drawInterBold(g, text, 0f, PrismDraw.interBoldCenteredY(0f, height / scale, font), font, color);
        else PrismDraw.drawInter(g, text, 0f, PrismDraw.interCenteredY(0f, height / scale, font), font, color);
        pose.popMatrix();
    }

    private static void shadowText(GuiGraphics g, String s, float x, float y, int color, float la) {
        PrismDraw.drawInterBold(g, s, x + .5f, y + .5f, EQ_FONT, mulA(0xFF000000, la));
        PrismDraw.drawInterBold(g, s, x, y, EQ_FONT, mulA(color, la));
    }

    // ================================================================== ground items

    /**
     * Distance-scales plates and declutters them: drops come nearest first, so a plate that overlaps
     * an already placed one is lifted above it; after {@link #DROP_STACK} lifts it is hidden.
     */
    public void layoutDrops(Drop[] drops, int count) {
        Style st = style;
        for (int i = 0; i < count; i++) {
            Drop d = drops[i];
            d.scale = st.itemScale * Mth.clamp(st.fullAt / Math.max(1f, d.dist), st.minScale, 1f);
            d.w = (DROP_PAD * 2f + dropIconW() + d.label.width + (d.count > 1 ? 3f + d.countW : 0f)) * d.scale;
            d.h = DROP_H * d.scale;
            d.hidden = false;
            int lifts = 0;
            for (int j = 0; j < i; j++) {
                Drop o = drops[j];
                if (o.hidden || Math.abs(d.sx - o.sx) * 2f >= d.w + o.w || Math.abs(d.sy - o.sy) * 2f >= d.h + o.h) continue;
                if (++lifts > DROP_STACK) {
                    d.hidden = true;
                    break;
                }
                d.sy = o.sy - (o.h + d.h) * .5f - DROP_GAP * d.scale;
                j = -1; // re-check against every placed plate at the new height
            }
        }
    }

    private static void enterDrop(GuiGraphics g, Drop d) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(d.sx, d.sy);
        pose.scale(d.scale, d.scale);
    }

    private void dropShape(GuiGraphics g, Drop d) {
        float w = d.w / d.scale;
        enterDrop(g, d);
        PrismDraw.roundRect(g, -w * .5f, -DROP_H * .5f, w, DROP_H, 2.5f, style.dropBg);
        g.pose().popMatrix();
    }

    private float dropIconW() {
        return style.dropIcon ? DROP_ICON + 2f : 0f;
    }

    private void dropIcon(GuiGraphics g, Drop d) {
        enterDrop(g, d);
        Matrix3x2fStack pose = g.pose();
        float k = DROP_ICON / 16f;
        pose.translate(-d.w / d.scale * .5f + DROP_PAD, -DROP_ICON * .5f);
        pose.scale(k, k);
        g.renderItem(d.stack, 0, 0);
        pose.popMatrix();
    }

    /** pass 0: regular name runs, 1: bold name runs and the count. */
    private void dropText(GuiGraphics g, Drop d, int pass) {
        ItemLabel l = d.label;
        float x = -d.w / d.scale * .5f + DROP_PAD + dropIconW(), top = -DROP_H * .5f;
        boolean bold = pass == 1;
        float y = bold ? PrismDraw.interBoldCenteredY(top, DROP_H, DROP_FONT) : PrismDraw.interCenteredY(top, DROP_H, DROP_FONT);
        enterDrop(g, d);
        for (int i = 0; i < l.text.length; i++) {
            if (l.bold[i] != bold) continue;
            int c = d.override != 0 ? d.override : l.color[i];
            if (bold) PrismDraw.drawInterBold(g, l.shown(i), x + l.x[i], y, DROP_FONT, c);
            else PrismDraw.drawInter(g, l.shown(i), x + l.x[i], y, DROP_FONT, c);
        }
        if (bold && d.count > 1) PrismDraw.drawInterBold(g, d.countStr, x + l.width + 3f, y, DROP_FONT, MUTED);
        g.pose().popMatrix();
    }

    // ================================================================== colours

    public static int healthColor(float r) {
        r = Mth.clamp(r, 0f, 1f);
        return r < .5f ? lerp(0xFFFF4D4D, 0xFFFFC94D, r * 2f) : lerp(0xFFFFC94D, 0xFF4DFF88, (r - .5f) * 2f);
    }

    private static int pingColor(int ms) {
        return ms < 80 ? 0xFF7CE8A0 : ms < 160 ? 0xFFFFD27A : 0xFFFF7A7A;
    }

    public static int lerp(int a, int b, float t) {
        return PrismDraw.lerpColor(a, b, Mth.clamp(t, 0f, 1f));
    }

    public static int mulA(int c, float f) {
        int a = Mth.clamp(Math.round((c >>> 24) * f), 0, 255);
        return a << 24 | (c & 0xFFFFFF);
    }
}
