package releon.ru.api.module.impl.visual.hudmodern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;

public final class NewHudDraw {
    private NewHudDraw() {}
    public static void roundedRect(GuiGraphics g, float x, float y, float w, float h, float radius, int color) { PrismDraw.roundRect(g,x,y,w,h,radius,color); }
    public static void bloom(GuiGraphics g, float cx, float cy, float size, int color) { PrismDraw.glow(g, cx, cy, size * .5f, size * .5f, color); }
    public static void blurPanel(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        g.pose().pushMatrix();
        float k=1f/Render2DCoordinateSpace.guiIndependentScale();g.pose().scale(k,k);
        Render2D.blur(x,y,w,h,radius,5,1,color);g.pose().popMatrix();
    }
    public static void imageRegion(GuiGraphics g,float x,float y,float w,float h,Identifier texture,int color,float radius,
                                   float u0,float v0,float u1,float v1,boolean flipX,boolean flipY) {
        g.pose().pushMatrix();float k=1f/Render2DCoordinateSpace.guiIndependentScale();g.pose().scale(k,k);
        Render2D.imageUvNearest(texture.toString(),x,y,w,h,radius,1,flipX?u1:u0,flipY?v1:v0,flipX?u0:u1,flipY?v0:v1,color);
        g.pose().popMatrix();
    }
    public static void mediaIcon(GuiGraphics g,String glyph,float x,float centerY,float size,int color) {
        HudFont font=HudFont.of(Math.round(size),FontType.MEDIA_PLAYER_ICONS);font.drawString(g,glyph,x,centerY-size*.5f,color);
    }
    public static String fmt(float value,int decimals) { return String.format(java.util.Locale.ROOT,"%."+decimals+"f",value); }
}
