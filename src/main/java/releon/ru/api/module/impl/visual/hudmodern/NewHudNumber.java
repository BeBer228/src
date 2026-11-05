package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;
import net.minecraft.client.gui.GuiGraphics;
public final class NewHudNumber {
    private String current="",previous="";
    private float progress=1;
    public void reset(){current=previous="";progress=1;}
    public void draw(GuiGraphics g,String value,float x,float y,HudFont font,int color,float dt){
        if(current.isEmpty())current=value;
        if(!current.equals(value)&&progress>=1){previous=current;current=value;progress=0;}
        progress=Math.min(1,progress+dt*9);float t=progress*progress*(3-2*progress);
        if(t<1&&!previous.isEmpty())font.drawString(g,previous,x,y-t*3,HudPaint.tint(PrismDraw.withAlpha(color,1-t)));
        font.drawString(g,current,x,y+(1-t)*3,HudPaint.tint(PrismDraw.withAlpha(color,t)));
    }
}
