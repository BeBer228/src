package releon.ru.api.module.impl.visual.hudmodern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.Setting;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import java.util.EnumMap;

/** Owns the NEW suite and its normalized, independently persisted drag positions. */
public final class NewHudRenderer extends Module {
    private static NewHudRenderer instance;
    public final NewHudSettings settings;
    private final EnumMap<NewHudSettings.Kind,NewHudElement> elements = new EnumMap<>(NewHudSettings.Kind.class);
    private final float[] opacity = new float[NewHudSettings.Kind.values().length];
    private NewHudElement dragging;
    private NewHudSettings.Kind dragKind;
    private float dragX, dragY;
    private long frame;
    private LivingEntity manualTarget;
    private long manualUntil;
    private Object world;
    public NewHudRenderer() {
        super("HUD New", "Titanium NEW HUD renderer.", ModuleCategory.VISUAL);
        instance = this;
        settings = new NewHudSettings(this);
        for (var kind:NewHudSettings.Kind.values()) elements.put(kind,new NewHudElement(kind,settings.style(kind)));
    }
    public static NewHudRenderer getInstance() { return instance; }
    public <S extends Setting<?>> S setting(S setting) { return register(setting); }
    @Override public void onTick(Minecraft client) {
        if (world != mc.level) { manualTarget = null; world = mc.level; release(); }
        if (mc.player == null || mc.level == null) return;
        for (var entry:elements.entrySet()) if (settings.components.isSelected(entry.getKey().id)) entry.getValue().tick();
    }
    public void attacked(LivingEntity target) { manualTarget = target; manualUntil = System.currentTimeMillis() + 1500; }
    public LivingEntity target() {
        var aura = AuraModule.getInstance();
        if (aura != null && aura.isEnabled() && AuraModule.target != null) return AuraModule.target;
        var trigger = TriggerBot.getInstance();
        if (trigger != null && trigger.isEnabled() && trigger.target != null) return trigger.target;
        return manualTarget != null && manualTarget.isAlive() && System.currentTimeMillis() < manualUntil ? manualTarget : null;
    }
    private float unit() { return settings.scale.getFloat(); }
    public void render(GuiGraphics g) {
        long now=System.nanoTime();float dt=frame==0?1f/60:Math.min(.1f,Math.max(.001f,(now-frame)/1e9f));frame=now;
        boolean interactive=mc.screen instanceof ChatScreen;
        if(!interactive||GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)!=GLFW.GLFW_PRESS)release();
        PrismDraw.begin(g);
        float unit=unit();
        for(var entry:elements.entrySet()){
            var kind=entry.getKey();var element=entry.getValue();float scale=unit*settings.style(kind).scale.getFloat();
            element.setRenderScale(scale);
            element.setViewport(mc.getWindow().getGuiScaledWidth()/scale,mc.getWindow().getGuiScaledHeight()/scale);
            if(dragging==element){
                float mx=(float)mc.mouseHandler.getScaledXPos(mc.getWindow())/scale,my=(float)mc.mouseHandler.getScaledYPos(mc.getWindow())/scale;
                element.move(mx-dragX,my-dragY);
            }
            element.frame(dt);
            int index=kind.ordinal();float wanted=settings.components.isSelected(kind.id)?1:0;
            opacity[index]+=(wanted-opacity[index])*(1-(float)Math.exp(-12*dt));
            if(opacity[index]<.001f)continue;
            HudPaint.beginElement(opacity[index]);g.pose().pushMatrix();g.pose().scale(scale,scale);
            try{
                element.render(g,dt);
                if(interactive&&element.width>0&&element.height>0){
                    float mx=(float)mc.mouseHandler.getScaledXPos(mc.getWindow())/scale,my=(float)mc.mouseHandler.getScaledYPos(mc.getWindow())/scale;
                    if(dragging==element||element.hitTest(mx,my))PrismDraw.outline(g,element.x,element.y,element.width,element.height,5,0x809E94E8);
                }
            }finally{g.pose().popMatrix();HudPaint.beginElement(1);}
        }
        PrismDraw.end(g);
    }
    public boolean mouseClicked(double guiX,double guiY) {
        if(!(mc.screen instanceof ChatScreen))return false;
        var kinds=NewHudSettings.Kind.values();
        for(int i=kinds.length-1;i>=0;i--){
            var kind=kinds[i];if(!settings.components.isSelected(kind.id))continue;
            var element=elements.get(kind);float scale=unit()*settings.style(kind).scale.getFloat(),mx=(float)guiX/scale,my=(float)guiY/scale;
            if(!element.hitTest(mx,my))continue;
            if(element.onClick(mx,my))return true;
            dragging=element;dragKind=kind;dragX=mx-element.x;dragY=my-element.y;return true;
        }
        return false;
    }
    public void release() { dragging=null;dragKind=null; }
    @Override public void onDisable() { release();frame=0;manualTarget=null; }
    public static int ping() {
        var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return 0;
        var info=mc.getConnection().getPlayerInfo(mc.player.getUUID());return info==null?0:info.getLatency();
    }
}
