package releon.ru.api.module.impl.visual.esp.prism;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Item name split into styled runs as the game styles it: colour and bold from the component,
 * obfuscated runs re-rolled every frame. Built once per name component, not per frame.
 */
public final class ItemLabel {
    private static final String OBF_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int DEFAULT = 0xFFFFFFFF;

    public final String[] text;
    public final int[] color;
    public final boolean[] bold, obf;
    public final float[] x;
    public final float width;

    private ItemLabel(List<String> t, List<Style> s, int font) {
        int n = t.size();
        text = t.toArray(new String[0]);
        color = new int[n];
        bold = new boolean[n];
        obf = new boolean[n];
        x = new float[n];
        float w = 0f;
        for (int i = 0; i < n; i++) {
            Style st = s.get(i);
            TextColor c = st.getColor();
            color[i] = c == null ? DEFAULT : 0xFF000000 | c.getValue();
            bold[i] = st.isBold();
            obf[i] = st.isObfuscated();
            x[i] = w;
            w += bold[i] ? PrismDraw.interBoldWidth(text[i], font) : PrismDraw.interWidth(text[i], font);
        }
        width = w;
    }

    public static ItemLabel of(Component name, int font) {
        List<String> t = new ArrayList<>();
        List<Style> s = new ArrayList<>();
        name.visit((style, str) -> {
            if (str.isEmpty()) return Optional.empty();
            int last = s.size() - 1;
            if (last >= 0 && same(s.get(last), style)) t.set(last, t.get(last) + str);
            else {
                t.add(str);
                s.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return new ItemLabel(t, s, font);
    }

    private static boolean same(Style a, Style b) {
        return a.isBold() == b.isBold() && a.isObfuscated() == b.isObfuscated() && java.util.Objects.equals(a.getColor(), b.getColor());
    }

    /** Run text for this frame: obfuscated runs get random glyphs like vanilla §k. */
    public String shown(int i) {
        if (!obf[i]) return text[i];
        String src = text[i];
        char[] out = new char[src.length()];
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int k = 0; k < out.length; k++)
            out[k] = src.charAt(k) == ' ' ? ' ' : OBF_CHARS.charAt(r.nextInt(OBF_CHARS.length()));
        return new String(out);
    }
}
