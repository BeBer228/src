package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Map;
public final class NewHudIcons {
    private NewHudIcons() {}
    private static final Map<String,Character> GLYPHS=Map.ofEntries(
            Map.entry("logo", '\ue000'),
            Map.entry("clock", '\ue001'),
            Map.entry("flask", '\ue002'),
            Map.entry("keyboard", '\ue003'),
            Map.entry("shield", '\ue004'),
            Map.entry("fire", '\ue005'),
            Map.entry("heart", '\ue006'),
            Map.entry("speed", '\ue007'),
            Map.entry("sword", '\ue008'),
            Map.entry("eye", '\ue009'),
            Map.entry("fps", '\ue00a'),
            Map.entry("signal", '\ue00b'),
            Map.entry("pin", '\ue00c'),
            Map.entry("users", '\ue00d'),
            Map.entry("bell", '\ue00e'),
            Map.entry("power", '\ue00f'),
            Map.entry("power-off", '\ue010'),
            Map.entry("bubbles", '\ue011'),
            Map.entry("cycle", '\ue012'),
            Map.entry("switches", '\ue013'),
            Map.entry("bag", '\ue014'),
            Map.entry("spark", '\ue015'),
            Map.entry("hourglass", '\ue016'),
            Map.entry("command", '\ue017'),
            Map.entry("grid", '\ue018'),
            Map.entry("mouse", '\ue019'),
            Map.entry("discord", '\ue01a'),
            Map.entry("heart-pulse", '\ue01b'));
    private static final String[] TEXT=new String[0x20];
    public static void draw(GuiGraphics g,String name,float x,float y,float size,int color){
        glow(g,x,y,size,color);
        char glyph=GLYPHS.getOrDefault(name,GLYPHS.get("spark"));
        String text=TEXT[glyph-0xe000];if(text==null)TEXT[glyph-0xe000]=text=String.valueOf(glyph);
        // Rasterised at double size and drawn at half scale: crisper, evenly antialiased pictograms.
        var font=HudFont.of(Math.max(8,Math.round(size*2)),FontType.NEW_HUD_ICONS);
        g.pose().pushMatrix();
        try{g.pose().translate(x,y);g.pose().scale(.5f,.5f);font.drawString(g,text,(size*2-font.getStringWidth(text))/2,font.centeredY(0,size*2),HudPaint.tint(color));}
        finally{g.pose().popMatrix();}
    }
    /** Additive two-lobe bloom; alpha follows the icon so it fades with the panel. */
    public static void glow(GuiGraphics g,float x,float y,float size,int color){
        if((color>>>24)<5)return;
        NewHudDraw.bloom(g,x+size/2,y+size/2,size*1.3f,
                PrismDraw.withAlpha(color,.42f));
    }
}
