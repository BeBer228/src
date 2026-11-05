package releon.ru.api.module.impl.visual;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.utils.render.IncludeDefaults;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.utils.repository.gps.GpsUtils;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.util.Mth;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;

/** Tick-cached radar targets, antialiased artwork and frame-rate-independent angle interpolation. */
public class Arrows extends Module {
    public final MultiModeSetting targetType=register(multi("Тип","Кого подсвечивать стрелками.",new String[]{"Игроки","Голые","Друзья","Мобы"},true));
    public final ModeSetting style=register(new ModeSetting("Arrow Style","Shape of radar arrows.","Chevron","Chevron","Triangle","Pointer","Double"));
    public final NumberSetting size=register(new NumberSetting("Размер","Размер стрелочки.",12,6,32,1));
    public final NumberSetting offset=register(new NumberSetting("Смещение","Радиус кольца от центра экрана.",75,20,260,1));
    public final NumberSetting verticalRatio=register(new NumberSetting("Vertical Radius","Vertical radius relative to horizontal radius.",1,.4,1.5,.05));
    public final NumberSetting minDistance=register(new NumberSetting("Minimum Distance","Hide targets closer than this distance.",0,0,64,1));
    public final NumberSetting maxDistance=register(new NumberSetting("Maximum Distance","Maximum target distance.",96,16,256,1));
    public final NumberSetting targetLimit=register(new NumberSetting("Arrow Limit","Maximum nearest targets.",16,1,64,1));
    public final NumberSetting animationSpeed=register(new NumberSetting("Arrow Animation","Animation response speed.",12,4,24,1));
    public final ColorSetting color=register(new ColorSetting("Цвет","Цвет стрелочек.",new java.awt.Color(0xFFA7A1FF,true)));
    public final ColorSetting friendColor=register(new ColorSetting("Friend Arrow Color","Radar color for friends.",new java.awt.Color(0xFF8AD4AB,true)));
    public final NumberSetting opacity=register(new NumberSetting("Прозрачность","Прозрачность стрелочек.",1,.1,1,.05));
    public final BooleanSetting offscreenOnly=register(new BooleanSetting("Offscreen Only","Hide arrows for targets already on screen.",true));
    public final BooleanSetting showName=register(new BooleanSetting("Ник","Подписывать ник над стрелкой.",false));
    public final BooleanSetting showDistance=register(new BooleanSetting("Дистанция","Показывать дистанцию под стрелкой.",true));
    public final NumberSetting labelSize=register(new NumberSetting("Arrow Label Size","Size of names and distances.",7,5,12,1));
    public final NumberSetting labelGap=register(new NumberSetting("Arrow Label Gap","Distance from arrow to labels.",4,0,16,1));
    private static final EquipmentSlot[] ARMOR={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
    private static final Identifier[] TEXTURES={id("chevron"),id("triangle"),id("pointer"),id("double")};
    private static Identifier id(String name){return Identifier.fromNamespaceAndPath("releon","textures/hud/arrow_"+name+".png");}
    private static class Target {LivingEntity entity;boolean wanted,friend;float angle,alpha,distance;String name,range="";int rounded=-1;Target(LivingEntity e){entity=e;name=e.getName().getString();angle=angleTo(e.getX(),e.getZ())-(float)Math.PI/2;}}
    private final LinkedHashMap<Integer,Target> targets=new LinkedHashMap<>();
    private Object world;
    public Arrows(){
        super("Arrows","Плавные указатели на игроков, мобов и GPS.",ModuleCategory.VISUAL);
        IncludeDefaults.apply("Arrows",getSettings(),"");
        for(var setting:java.util.List.of(verticalRatio,minDistance,targetLimit,animationSpeed,opacity,labelSize,labelGap,showName,showDistance,offscreenOnly))setting.setVisible(false);
        String[] options={"Ник","Дистанция","Только за экраном"};
        var flags=register(new MultiModeSetting("Показывать","",options,"Дистанция","Только за экраном"));
        flags.addListener((setting,old,value)->{showName.setValue(value.contains("Ник"));showDistance.setValue(value.contains("Дистанция"));offscreenOnly.setValue(value.contains("Только за экраном"));});
    }
    private static MultiModeSetting multi(String n,String d,String[] options,boolean selected){return new MultiModeSetting(n,d,options,selected?options:new String[0]);}
    private long previousFrame;
    private final org.joml.Matrix4f projection=new org.joml.Matrix4f();
    private final org.joml.Vector4f projected=new org.joml.Vector4f();
    private float delta(){long now=System.nanoTime();float dt=previousFrame==0?1f/60:Math.min(.1f,(now-previousFrame)/1e9f);previousFrame=now;return dt;}
    private static int withAlpha(int c,float a){return PrismDraw.setAlpha(c,Math.round((c>>>24)*a));}
    private static float angleTo(double x,double z){var mc=net.minecraft.client.Minecraft.getInstance();return (float)(Math.atan2(z-mc.player.getZ(),x-mc.player.getX())-Math.toRadians(mc.gameRenderer.getMainCamera().yRot())-Math.PI/2);}
    @SubscribeEvent private void draw(DrawEvent event){if(mc.player==null||mc.level==null)return;PrismDraw.begin(event.getGraphics());projection.set(Render3D.lastProjMat).mul(Render3D.lastModMat);onRender2D(event.getGraphics(),mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());PrismDraw.end(event.getGraphics());}

    @Override public void onDisable(){targets.clear();world=null;}
    private final ArrayList<LivingEntity> candidates=new ArrayList<>();
    @Override public void onTick(net.minecraft.client.Minecraft client){
        if(mc.player==null||mc.level==null){onDisable();return;}
        if(world!=mc.level){targets.clear();world=mc.level;}
        for(Target t:targets.values())t.wanted=false;
        candidates.clear();
        double min=minDistance.getValue(),max=Math.max(min,maxDistance.getValue());
        for(var e:mc.level.entitiesForRendering()){
            if(e==mc.player||!(e instanceof LivingEntity living)||!living.isAlive()||living.isSpectator())continue;
            double d=e.distanceToSqr(mc.player);if(d<min*min||d>max*max)continue;
            boolean friend=living instanceof Player p&&FriendUtils.isFriend(p);
            boolean accepted;
            if(living instanceof Player player){boolean naked=true;for(EquipmentSlot slot:ARMOR)if(!player.getItemBySlot(slot).isEmpty()){naked=false;break;}
                accepted=targetType.isSelected(friend?"Друзья":naked?"Голые":"Игроки");
            }else accepted=(living instanceof Animal||living instanceof Monster)&&targetType.isSelected("Мобы");
            if(accepted)candidates.add(living);
        }
        candidates.sort(Comparator.comparingDouble(e->e.distanceToSqr(mc.player)));
        for(int i=0;i<Math.min(targetLimit.getValue().intValue(),candidates.size());i++){
            LivingEntity entity=candidates.get(i);Target t=targets.computeIfAbsent(entity.getId(),key->new Target(entity));t.wanted=true;
            t.friend=entity instanceof Player p&&FriendUtils.isFriend(p);
        }
    }
    public void onRender2D(GuiGraphics g,int sw,int sh){
        if(mc.player==null||mc.level==null)return;
        float dt=delta(),f=1-(float)Math.exp(-animationSpeed.getFloat()*Math.min(.1f,dt));
        float arrowSize=size.getFloat(),rx=Math.min(offset.getFloat(),sw*.45f-arrowSize),ry=Math.min(offset.getFloat()*verticalRatio.getFloat(),sh*.45f-arrowSize);
        int texture=switch(style.getValue()){case "Triangle"->1;case "Pointer"->2;case "Double"->3;default->0;};
        var gps=GpsUtils.get();
        if(gps!=null){
            float a=angleTo(gps.x(),gps.z())-(float)Math.PI/2;
            float gx=sw*.5f+(float)Math.cos(a)*rx,gy=sh*.5f+(float)Math.sin(a)*ry;
            int gc=color.getValue().getRGB();
            g.pose().pushMatrix();g.pose().translate(gx,gy);g.pose().rotate(a);
            PrismDraw.image(g,TEXTURES[texture],-arrowSize*.5f,-arrowSize*.5f,arrowSize,gc);g.pose().popMatrix();
            if(showDistance.getValue()){String range=Math.round(Math.hypot(gps.x()-mc.player.getX(),gps.z()-mc.player.getZ()))+"m";int font=labelSize.getValue().intValue();PrismDraw.drawInter(g,range,gx-PrismDraw.interWidth(range,font)*.5f,gy+arrowSize*.5f+labelGap.getFloat(),font,gc);}
        }
        var it=targets.values().iterator();while(it.hasNext()){
            Target t=it.next();float partial=mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            double ex=Mth.lerp(partial,t.entity.xOld,t.entity.getX()),ez=Mth.lerp(partial,t.entity.zOld,t.entity.getZ());
            double ey=Mth.lerp(partial,t.entity.yOld,t.entity.getY());
            float desired=angleTo(ex,ez)-(float)Math.PI/2;
            t.angle+=(float)Math.atan2(Math.sin(desired-t.angle),Math.cos(desired-t.angle))*f;
            boolean visible=t.wanted&&t.entity.isAlive();
            // Offscreen-only: the arrow disappears the moment the entity itself is inside the
            // viewport - feet or head projected on screen means the player can already see it.
            if(visible&&offscreenOnly.getValue()){
                boolean feetOn=onScreen(ex,ey,ez,sw,sh);
                boolean headOn=feetOn||onScreen(ex,ey+t.entity.getBbHeight(),ez,sw,sh);
                if(feetOn||headOn)visible=false;
            }
            t.alpha+=( (visible?1:0)-t.alpha)*f;
            if(!t.wanted&&t.alpha<.001f){it.remove();continue;}if(t.alpha<.005f)continue;
            float x=sw*.5f+(float)Math.cos(t.angle)*rx,y=sh*.5f+(float)Math.sin(t.angle)*ry;
            int c=withAlpha((t.friend?friendColor:color).getValue().getRGB(),opacity.getFloat()*t.alpha);
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().rotate(t.angle);
            PrismDraw.image(g,TEXTURES[texture],-arrowSize*.5f,-arrowSize*.5f,arrowSize,c);g.pose().popMatrix();
            int font=labelSize.getValue().intValue();float gap=labelGap.getFloat();
            if(showName.getValue())PrismDraw.drawInter(g,t.name,x-PrismDraw.interWidth(t.name,font)*.5f,PrismDraw.interCenteredY(y-arrowSize*.5f-gap-font,font,font),font,c);
            if(showDistance.getValue()){
                t.distance+=(t.entity.distanceTo(mc.player)-t.distance)*f;int rounded=Math.round(t.distance);
                if(t.rounded!=rounded){t.rounded=rounded;t.range=rounded+"m";}
                PrismDraw.drawInter(g,t.range,x-PrismDraw.interWidth(t.range,font)*.5f,PrismDraw.interCenteredY(y+arrowSize*.5f+gap,font,font),font,c);
            }
        }
    }

    private final org.joml.Vector2f screenPos=new org.joml.Vector2f();
    /** True when the world point projects inside the viewport; false behind the camera. */
    private boolean onScreen(double x,double y,double z,int sw,int sh){
        var camera=mc.gameRenderer.getMainCamera().position();
        projected.set((float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z),1);
        projection.transform(projected);
        if(projected.w<=.05f)return false;
        float nx=projected.x/projected.w,ny=projected.y/projected.w;
        return nx>=-1&&nx<=1&&ny>=-1&&ny<=1;
    }
}
