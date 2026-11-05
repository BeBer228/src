package releon.ru.api.module.impl.visual;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.utils.render.*;
import releon.ru.utils.render.color.ClientTheme;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;

public class Trails extends Module {
    public final ModeSetting shape=register(new ModeSetting("Shape","Форма объёмного шлейфа","Full Height","Feet","Full Height","Flag","Tube"));
    public final ModeSetting colorMode=register(new ModeSetting("Color Mode","Цвет следа","Client","Client","Custom","Rainbow"));
    public final ColorSetting customColor=register(new ColorSetting("Color","Цвет шлейфа",new java.awt.Color(0xFFA7A1FF,true)));
    public final NumberSetting lifetime=register(new NumberSetting("Lifetime (s)","Время жизни следа",1.5,.3,4,.1));
    public final NumberSetting thickness=register(new NumberSetting("Thickness","Ширина у ног / диаметр трубы",.35,.05,1.5,.05));
    public final NumberSetting height=register(new NumberSetting("Height","Высота ленты",1.8,.1,3,.05));
    public final NumberSetting opacity=register(new NumberSetting("Opacity","Непрозрачность",.65,.05,1,.05));
    public final NumberSetting wave=register(new NumberSetting("Wave","Амплитуда волн флага",.12,0,.6,.01));
    public final NumberSetting speed=register(new NumberSetting("Animation Speed","Скорость шейдера",1,.1,4,.1));
    public final NumberSetting glow=register(new NumberSetting("Glow","Сила свечения",.8,0,2,.1));
    public final NumberSetting samples=register(new NumberSetting("Max Segments","Лимит сегментов",96,16,160,8));
    public final BooleanSetting firstPerson=register(new BooleanSetting("First Person","Показывать от первого лица",false));
    public final BooleanSetting depth=register(new BooleanSetting("Depth Test","Скрывать за стенами и моделями игроков",true));
    private record Point(Vec3 pos,long time){}
    private final ArrayDeque<Point> points=new ArrayDeque<>();
    private final WorldBatch batch=new WorldBatch();
    private net.minecraft.client.multiplayer.ClientLevel world;
    public Trails(){super("Trails","Объёмный шейдерный след: лента, флаг и труба.",ModuleCategory.VISUAL);IncludeDefaults.apply("Trails",getSettings(),"");customColor.setVisible(()->colorMode.is("Custom"));wave.setVisible(()->shape.is("Flag"));}
    @Override public void onDisable(){points.clear();world=null;batch.close();}
    @Override public void onTick(net.minecraft.client.Minecraft client){
        if(mc.player==null||mc.level==null){onDisable();return;}
        if(world!=mc.level){points.clear();world=mc.level;}
        Vec3 pos=mc.player.getPosition(0.0f);long now=System.currentTimeMillis();
        if(!points.isEmpty()&&points.getLast().pos.distanceToSqr(pos)>64)points.clear();
        if(points.isEmpty()||points.getLast().pos.distanceToSqr(pos)>.0025)points.addLast(new Point(pos,now));
        long life=(long)(lifetime.getValue()*1000);
        while(!points.isEmpty()&&(now-points.getFirst().time>life||points.size()>samples.getValue().intValue()))points.removeFirst();
    }
    private int[] colors=new int[0];
    private static final double[] TUBE_COS=new double[9],TUBE_SIN=new double[9];
    static{for(int i=0;i<=8;i++){TUBE_COS[i]=Math.cos(i*Math.PI/4);TUBE_SIN[i]=Math.sin(i*Math.PI/4);}}
    // Per-frame settings hoisted out of the segment loop (string compares / getValue per segment were hot).
    private int shapeId;
    private double thick,waveAmp,anim;
    private float heightF;
    @SubscribeEvent
    public void render(WorldRenderEvent e){
        if(mc.player==null||points.size()<2)return;
        if(!firstPerson.getValue()&&mc.options.getCameraType().isFirstPerson())return;
        batch.begin(mc.gameRenderer.getMainCamera());long now=System.currentTimeMillis();float time=(now%360000L)/1000f;
        shapeId=shape.is("Feet")?0:shape.is("Tube")?1:shape.is("Flag")?2:3;
        thick=thickness.getValue();waveAmp=wave.getValue();anim=speed.getValue()*3;heightF=height.getFloat();
        // Each point's color is computed once and shared by both adjacent segments.
        int n=points.size();if(colors.length<n+1)colors=new int[n+8];
        int k=0;for(Point p:points)colors[k]=color(p,k++,now);
        Point previous=null;int index=0;
        for(Point p:points){if(previous!=null){segment(previous,p,index,colors[index],colors[index+1],time);index++;}previous=p;}
        Vec3 head=mc.player.getPosition(e.getPartialTicks());
        if(previous!=null&&previous.pos.distanceToSqr(head)<4) {
            Vec3 delta=head.subtract(previous.pos);
            double distance=delta.length();
            Vec3 end=distance>.22 ? head.subtract(delta.scale(Math.min(.22,distance)/distance)) : previous.pos;
            if(end.distanceToSqr(previous.pos)>.0001){
                Point tail=new Point(end,now);
                segment(previous,tail,index,color(previous,index,now),color(tail,index+1,now),time);
            }
        }
        batch.end(depth.getValue(),false,1,time,speed.getFloat(),glow.getFloat());
    }
    private int color(Point p,int i,long now){
        int c=colorMode.is("Custom")?customColor.getValue().getRGB():colorMode.is("Rainbow")?java.awt.Color.HSBtoRGB(((now+i*30L)%3000)/3000f,.75f,1):ClientTheme.wave(500, i*.12f, 255);
        float fade=Math.max(0,1-(now-p.time)/(float)(lifetime.getValue()*1000));
        return setAlpha(c,Math.round(fade*fade*opacity.getFloat()*255));
    }
    private void segment(Point a,Point b,int i,int ca,int cb,float time){
        Vec3 p=a.pos,q=b.pos;double dx=q.x-p.x,dz=q.z-p.z,len=Math.sqrt(dx*dx+dz*dz);
        if(len<.0001)return;
        float ua=i*.2f,ub=ua+.2f;
        if(shapeId==0){
            double nx=-dz/len*thick*.5,nz=dx/len*thick*.5;
            batch.quad(p.x-nx,p.y+.03,p.z-nz,q.x-nx,q.y+.03,q.z-nz,q.x+nx,q.y+.03,q.z+nz,p.x+nx,p.y+.03,p.z+nz,ua,ub,ca,cb);
        }else if(shapeId==1){
            double r=thick*.5, nx=-dz/len*r, nz=dx/len*r;
            for(int s=0;s<8;s++){
                double c0=TUBE_COS[s],c1=TUBE_COS[s+1],s0=TUBE_SIN[s]*r,s1=TUBE_SIN[s+1]*r;
                batch.quad(p.x+nx*c0,p.y+.8+s0,p.z+nz*c0,q.x+nx*c0,q.y+.8+s0,q.z+nz*c0,
                        q.x+nx*c1,q.y+.8+s1,q.z+nz*c1,p.x+nx*c1,p.y+.8+s1,p.z+nz*c1,ua,ub,ca,cb);
            }
        }else{
            int rows=shapeId==2?6:1;
            double wa=rows>1?Math.sin(i*.55-time*anim)*waveAmp:0;
            double wb=rows>1?Math.sin((i+1)*.55-time*anim)*waveAmp:0;
            double nx=-dz/len,nz=dx/len;
            for(int row=0;row<rows;row++){
                double lo=heightF*row/rows,hi=heightF*(row+1)/rows,fl=(double)row/rows,fh=(double)(row+1)/rows;
                batch.quad(p.x+nx*wa*fl,p.y+lo,p.z+nz*wa*fl,q.x+nx*wb*fl,q.y+lo,q.z+nz*wb*fl,
                        q.x+nx*wb*fh,q.y+hi,q.z+nz*wb*fh,p.x+nx*wa*fh,p.y+hi,p.z+nz*wa*fh,ua,ub,ca,cb);
            }
        }
    }
    private static int setAlpha(int c,int a){return (Math.max(0,Math.min(255,a))<<24)|(c&0xFFFFFF);}
}
