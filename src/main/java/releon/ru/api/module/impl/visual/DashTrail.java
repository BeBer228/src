package releon.ru.api.module.impl.visual;

import releon.ru.utils.render.color.ClientTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.WorldVertex;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;
import releon.ru.utils.repository.friend.FriendUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Original dash textures/settings plus bounded neon impulse rings and optional pulse.
 * No new texture assets. Project compilation and in-game rendering have not been tested here.
 */
public class DashTrail extends Module {
    private static final String ROOT="textures/features/dashtrail/";
    private static final Identifier BLOOM_TEXTURE=texture("dashbloomsample.png");
    private static final Identifier DOT_TEXTURE=texture("dashbloom.png");
    private static final float BASE_PIXEL_SCALE=.0033F;
    private static final double MIN_DASH_SPEED=.08D;
    private static final int MAX_CUBICS=420,LIGHT_MAX_CUBICS=240,MAX_SPARKS=12;
    private static final int MAX_RINGS=8,LIGHT_MAX_RINGS=4,RING_SEGMENTS=32,RING_COOLDOWN_TICKS=10;
    private static final float[] RING_COS=new float[RING_SEGMENTS+1],RING_SIN=new float[RING_SEGMENTS+1];
    static {
        for (int i=0;i<=RING_SEGMENTS;i++) {
            double a=i*Math.PI*2/RING_SEGMENTS;
            RING_COS[i]=(float)Math.cos(a);RING_SIN[i]=(float)Math.sin(a);
        }
    }
    private static final TextureInfo[] STATIC_TEXTURES={
            cubic(1,48,48),cubic(2,48,48),cubic(3,48,48),cubic(4,96,48),cubic(5,96,48),
            cubic(6,96,48),cubic(7,96,48),cubic(8,96,48),cubic(9,96,48),cubic(10,96,48),
            cubic(11,96,48),cubic(12,96,48),cubic(13,96,96),cubic(14,96,96),cubic(15,96,96),
            cubic(16,96,96),cubic(17,44,48),cubic(18,44,48),cubic(19,44,48),cubic(20,44,48),cubic(21,44,48)
    };
    private static final TextureInfo[][] ANIMATED_TEXTURES={
            group(1,11,144,48),group(2,23,48,48),group(3,32,96,48),group(4,16,48,48),group(5,32,48,48)
    };
    private final Random random=new Random(1234567891L);
    private final List<DashCubic> dashCubics=new ArrayList<>();
    private final List<DashRing> rings=new ArrayList<>();
    private final Map<Integer,PlayerTrack> tracks=new HashMap<>();
    private final Set<Integer> seenPlayerIds=new HashSet<>();
    private final Set<RenderType> usedRenderTypes=new HashSet<>();
    // Lazy creation on the render thread, not during module/static initialization.
    private final Map<Identifier,RenderType> ghostTypes=new HashMap<>();
    private RenderType bloomType,dotType;
    private Object trackedLevel;

    private final BooleanSetting self=register(new BooleanSetting("Себя","Рисовать след рывка для вас.",true));
    private final BooleanSetting players=register(new BooleanSetting("Игроки","Рисовать след рывка для других игроков.",false));
    private final BooleanSetting friends=register(new BooleanSetting("Друзья","Рисовать след рывка для друзей.",true));
    private final ModeSetting colorMode=register(new ModeSetting("Режим цвета","Режим цвета следа рывка.","Random Palette","Random Palette","Custom","Client","Rainbow"));
    private final ColorSetting pickColor=register(new ColorSetting("Выбрать цвет","Пользовательский цвет следа рывка.",new Color(170,40,255,255)));
    private final NumberSetting maxDistance=register(new NumberSetting("Макс. дистанция","Максимальная дистанция отрисовки других игроков.",25,15,100,1));
    private final BooleanSetting motionSmoothing=register(new BooleanSetting("Сглаживание движения","Сглаживает частицы рывка по цепочке.",false));
    private final BooleanSetting dashSegments=register(new BooleanSetting("Сегменты рывка","Добавляет маленькие светящиеся линии.",false));
    private final BooleanSetting dashDots=register(new BooleanSetting("Точки рывка","Добавляет искры вокруг частиц рывка.",true));
    private final BooleanSetting lighting=register(new BooleanSetting("Освещение","Добавляет свечение (bloom) вокруг частиц рывка.",true));
    private final NumberSetting dashLength=register(new NumberSetting("Длина рывка","Множитель времени жизни частицы рывка.",.75,.5,1.5,.05));
    private final BooleanSetting impulseRings=register(new BooleanSetting("Импульсные кольца","Неоновые кольца при резком разгоне и приземлении.",true));
    private final BooleanSetting pulse=register(new BooleanSetting("Пульсация","Лёгкая пульсация размера и свечения частиц.",true));
    private final BooleanSetting lightweight=register(new BooleanSetting("Лёгкий режим","Меньше частиц, искр и деталей колец. Дальние эффекты упрощаются.",false));

    public DashTrail() {
        super("Dash Trail","Рисует частицы следа рывка в стиле Vega с неоновыми импульсами.",ModuleCategory.VISUAL);
        colorMode.visibleWhen(this::hasAnyTargetEnabled);
        pickColor.visibleWhen(()->colorMode.is("Custom") && hasAnyTargetEnabled());
        maxDistance.visibleWhen(()->players.getValue() || friends.getValue());
        motionSmoothing.visibleWhen(this::hasAnyTargetEnabled);dashSegments.visibleWhen(this::hasAnyTargetEnabled);
        dashDots.visibleWhen(this::hasAnyTargetEnabled);lighting.visibleWhen(this::hasAnyTargetEnabled);
        dashLength.visibleWhen(this::hasAnyTargetEnabled);impulseRings.visibleWhen(this::hasAnyTargetEnabled);
        pulse.visibleWhen(this::hasAnyTargetEnabled);lightweight.visibleWhen(this::hasAnyTargetEnabled);
    }
    @Override protected void onDisable() {
        clearScene();ghostTypes.clear();bloomType=dotType=null;usedRenderTypes.clear();
    }
    private void clearScene() {
        dashCubics.clear();rings.clear();tracks.clear();seenPlayerIds.clear();trackedLevel=null;
    }
    @Override public void onTick(Minecraft client) {
        if (client==null || client.level==null || client.player==null) { clearScene();return; }
        if (trackedLevel!=client.level) { clearScene();trackedLevel=client.level; }
        long now=System.currentTimeMillis();boolean light=lightweight.getValue();
        if (!impulseRings.getValue()) rings.clear();
        if (hasAnyTargetEnabled()) {
            seenPlayerIds.clear();
            for (Player player:client.level.players()) {
                if (!isTarget(player,client)) continue;
                seenPlayerIds.add(player.getId());trackPlayer(player,now,light);
            }
            tracks.keySet().removeIf(id->!seenPlayerIds.contains(id));
        } else tracks.clear();
        tickCubics(now,light);
        rings.removeIf(ring->ring.dead(now));
        trimRings(light);
    }
    private void trackPlayer(Player player,long now,boolean light) {
        Vec3 current=player.position();PlayerTrack track=tracks.get(player.getId());
        if (track==null) { tracks.put(player.getId(),new PlayerTrack(current,player.onGround()));return; }
        Vec3 delta=current.subtract(track.pos);
        double speed=Math.hypot(delta.x,delta.z);
        if (delta.lengthSqr()>36D) {
            track.pos=current;track.speed=0;track.vertical=0;track.ground=player.onGround();
            track.ringCooldown=RING_COOLDOWN_TICKS;return;
        }
        if (track.ringCooldown>0) track.ringCooldown--;
        if (impulseRings.getValue() && track.ringCooldown==0) {
            boolean landing=!track.ground && player.onGround() && (track.vertical<-.12D || speed>=MIN_DASH_SPEED);
            boolean accelerating=speed>=MIN_DASH_SPEED*2 && speed-track.speed>MIN_DASH_SPEED*.9;
            if (landing || (player.onGround() && accelerating)) {
                float strength=(float)Mth.clamp(landing ? .7+Math.abs(track.vertical)*.6 : .55+speed*1.6,.55,1.5);
                spawnRing(current,strength,landing,now,light);
                track.ringCooldown=RING_COOLDOWN_TICKS;
            }
        }
        track.pos=current;track.speed=speed;track.vertical=delta.y;track.ground=player.onGround();
        if (speed<MIN_DASH_SPEED) return;
        int count=Mth.clamp((int)(delta.length()/MIN_DASH_SPEED),1,light ? 8 : 16);
        boolean sparks=dashSegments.getValue() || dashDots.getValue();
        for (int i=0;i<count;i++) {
            float offset=count==1 ? 0F : i/(float)count;
            spawnCubic(player,current,delta,offset,sparks,now);
        }
        trimCubics(light);
    }
    private void spawnRing(Vec3 pos,float strength,boolean landing,long now,boolean light) {
        int lifetime=landing ? 650 : 480;
        rings.add(new DashRing(pos.add(0,.035,0),now,lifetime,nextDashColor(now),strength,landing));
        trimRings(light);
    }
    private void spawnCubic(Player player,Vec3 current,Vec3 delta,float offset,boolean sparks,long now) {
        double h=player.getBbHeight();
        Vec3 pos=new Vec3(current.x-delta.x*offset+randomRange(.175)-.0875,
                current.y-delta.y*offset+h/3+h/4*random.nextDouble()*.7,
                current.z-delta.z*offset+randomRange(.175)-.0875);
        int lifetime=getRandomTimeAnimation();
        DashTexture tex=DashTexture.random(random,lifetime,now);
        int color=nextDashColor(now);float scale=.86F+random.nextFloat()*.34F;
        dashCubics.add(new DashCubic(player.getId(),pos,delta.scale(.04),tex,lifetime,color,scale,sparks,random,now));
    }
    private void tickCubics(long now,boolean light) {
        boolean segments=dashSegments.getValue(),dots=dashDots.getValue();
        for (int i=0;i<dashCubics.size();i++) {
            DashCubic current=dashCubics.get(i);
            DashCubic next=motionSmoothing.getValue() && i+1<dashCubics.size()
                    && dashCubics.get(i+1).entityId==current.entityId ? dashCubics.get(i+1) : null;
            current.tick(next,random,segments,dots,now,light ? 6 : MAX_SPARKS);
        }
        // Once cubic alpha is zero its sparks are also invisible, so don't retain dead owners.
        dashCubics.removeIf(cubic->cubic.isDead(now));trimCubics(light);
    }
    @SubscribeEvent private void onWorldRender(WorldRenderEvent event) {
        if (mc.level==null || mc.player==null || trackedLevel!=mc.level || mc.gameRenderer==null
                || (dashCubics.isEmpty() && rings.isEmpty())) return;
        long now=System.currentTimeMillis();PoseStack stack=event.getStack();
        MultiBufferSource.BufferSource provider=mc.renderBuffers().bufferSource();
        Vec3 camera=mc.gameRenderer.getMainCamera().position();
        Quaternionf rotation=mc.gameRenderer.getMainCamera().rotation();
        float partial=event.getPartialTicks();boolean light=lightweight.getValue();
        boolean dots=dashDots.getValue(),segments=dashSegments.getValue(),pulsing=pulse.getValue();
        usedRenderTypes.clear();
        try {
            for (DashCubic cubic:dashCubics) {
                float progress=cubic.progress(now),alpha=dashAlpha(progress);
                if (alpha<=.02F) continue;
                Vec3 pos=cubic.renderPos(partial);
                boolean far=light && pos.distanceToSqr(camera)>144D;
                TextureInfo texture=cubic.texture.get(now);
                float wave=pulsing ? .94F+.06F*Mth.sin(progress*16F+(cubic.entityId&31)*.37F) : 1F;
                float size=easeOutQuad(alpha)*wave;
                float w=texture.width()*BASE_PIXEL_SCALE*cubic.scale*size;
                float h=texture.height()*BASE_PIXEL_SCALE*cubic.scale*size;
                int main=ColorUtil.multAlpha(ColorUtil.lerpColor(cubic.color,ColorUtil.WHITE,.34F),alpha*.92F);
                renderBillboard(stack,provider,ghostType(texture.id()),pos,camera,rotation,w,h,cubic.rotation,main);
                if (!far) renderBloom(stack,provider,pos,camera,rotation,w,h,cubic.color,alpha,progress,light);
                if (!far && (dots || segments))
                    renderSparks(stack,provider,cubic,pos,camera,rotation,partial,alpha,dots,segments,now,light);
            }
            if (impulseRings.getValue()) {
                for (DashRing ring:rings) {
                    if (ring.dead(now)) continue;
                    renderRing(stack,provider,ring,camera,now,light);
                }
            }
        } finally {
            // Flush only the types touched by this module; no global endBatch().
            for (RenderType type:usedRenderTypes) provider.endBatch(type);
            usedRenderTypes.clear();
        }
    }
    private RenderType ghostType(Identifier id) {
        RenderType type=ghostTypes.get(id);
        if (type==null) { type=ClientPipelines.GHOSTS_ESP.apply(id);ghostTypes.put(id,type); }
        return type;
    }
    private RenderType bloomType() {
        if (bloomType==null) bloomType=ClientPipelines.BLOOM_ESP.apply(BLOOM_TEXTURE);
        return bloomType;
    }
    private RenderType dotType() {
        if (dotType==null) dotType=ClientPipelines.WORLD_PARTICLES_GLOW.apply(DOT_TEXTURE);
        return dotType;
    }
    private void renderBloom(PoseStack stack,MultiBufferSource.BufferSource provider,Vec3 pos,Vec3 camera,
                             Quaternionf rotation,float w,float h,int color,float alpha,float progress,boolean light) {
        float diagonal=(float)Math.sqrt(w*w+h*h);
        int soft=ColorUtil.multAlpha(ColorUtil.lerpColor(color,ColorUtil.WHITE,.18F),alpha*.13F);
        renderBillboard(stack,provider,bloomType(),pos,camera,rotation,diagonal*.58F,diagonal*.58F,0,soft);
        if (!lighting.getValue() || light) return;
        float scale=1.05F+1.85F*(1-progress)*alpha;
        int glow=ColorUtil.multAlpha(ColorUtil.lerpColor(color,ColorUtil.WHITE,.10F),alpha*.18F);
        renderBillboard(stack,provider,bloomType(),pos,camera,rotation,diagonal*scale,diagonal*scale,0,glow);
    }
    private void renderSparks(PoseStack stack,MultiBufferSource.BufferSource provider,DashCubic cubic,
                              Vec3 center,Vec3 camera,Quaternionf rotation,float partial,float alpha,
                              boolean dots,boolean segments,long now,boolean light) {
        for (int i=0;i<cubic.sparks.size();i++) {
            if (light && (i&1)!=0) continue;
            DashSpark spark=cubic.sparks.get(i);
            float a=easeInOutWave((float)spark.alpha(now)*alpha);
            if (a<=.01F) continue;
            Vec3 offset=spark.renderPos(partial),first=center.add(offset),second=center.subtract(offset);
            int color=ColorUtil.multAlpha(ColorUtil.lerpColor(cubic.color,ColorUtil.WHITE,.45F),a*.72F);
            if (dots) {
                float size=.052F+.07F*a;
                renderBillboard(stack,provider,dotType(),first,camera,rotation,size,size,0,color);
                renderBillboard(stack,provider,dotType(),second,camera,rotation,size,size,0,color);
            }
            if (segments) Render3D.drawLineGradient(first,second,color,ColorUtil.withAlpha(color,0),2F,false);
        }
    }
    private void renderRing(PoseStack stack,MultiBufferSource.BufferSource provider,DashRing ring,Vec3 camera,long now,boolean light) {
        float t=ring.progress(now),out=1F-(1F-t)*(1F-t)*(1F-t);
        float fade=(1F-t)*(1F-t);
        if (fade<=.01F) return;
        float radius=.12F+out*(ring.landing ? 1.25F : .85F)*ring.strength;
        RenderType type=bloomType();usedRenderTypes.add(type);
        VertexConsumer consumer=provider.getBuffer(type);
        stack.pushPose();
        try {
            stack.translate(ring.pos.x-camera.x,ring.pos.y-camera.y,ring.pos.z-camera.z);
            PoseStack.Pose pose=stack.last();
            int color=ColorUtil.multAlpha(ColorUtil.lerpColor(ring.color,ColorUtil.WHITE,.25F),fade*.78F);
            ringBand(consumer,pose,radius,.035F+.025F*fade,color,light ? 2 : 1);
            if (lighting.getValue() && !light) {
                int halo=ColorUtil.multAlpha(ring.color,fade*.15F);
                ringBand(consumer,pose,radius,.14F,halo,1);
            }
        } finally { stack.popPose(); }
    }
    private static void ringBand(VertexConsumer consumer,PoseStack.Pose pose,float radius,float width,int color,int step) {
        float inner=Math.max(.001F,radius-width),outer=radius+width;
        for (int i=0;i<RING_SEGMENTS;i+=step) {
            int j=i+step;
            // Counter-clockwise seen from above, textured glow strip in world X/Z plane.
            WorldVertex.textured(consumer,pose,RING_COS[i]*inner,0,RING_SIN[i]*inner,0,0,color);
            WorldVertex.textured(consumer,pose,RING_COS[j]*inner,0,RING_SIN[j]*inner,1,0,color);
            WorldVertex.textured(consumer,pose,RING_COS[j]*outer,0,RING_SIN[j]*outer,1,1,color);
            WorldVertex.textured(consumer,pose,RING_COS[i]*outer,0,RING_SIN[i]*outer,0,1,color);
        }
    }
    private void renderBillboard(PoseStack stack,MultiBufferSource.BufferSource provider,RenderType type,
                                 Vec3 pos,Vec3 camera,Quaternionf rotation,float w,float h,float angle,int color) {
        if (w<=.001F || h<=.001F || ColorUtil.getAlpha(color)<=0) return;
        usedRenderTypes.add(type);VertexConsumer consumer=provider.getBuffer(type);
        stack.pushPose();
        try {
            stack.translate(pos.x-camera.x,pos.y-camera.y,pos.z-camera.z);
            stack.mulPose(rotation);stack.mulPose(Axis.ZP.rotationDegrees(angle));
            PoseStack.Pose pose=stack.last();float hw=w/2,hh=h/2;
            WorldVertex.textured(consumer,pose,-hw,-hh,0,0,0,color);
            WorldVertex.textured(consumer,pose,hw,-hh,0,1,0,color);
            WorldVertex.textured(consumer,pose,hw,hh,0,1,1,color);
            WorldVertex.textured(consumer,pose,-hw,hh,0,0,1,color);
        } finally { stack.popPose(); }
    }
    private boolean isTarget(Player p,Minecraft client) {
        if (p==null || !p.isAlive()) return false;
        if (p==client.player) return self.getValue();
        double distance=maxDistance.getValue();
        if (client.player.distanceToSqr(p)>distance*distance) return false;
        return FriendUtils.isFriend(p) ? friends.getValue() : players.getValue();
    }
    private boolean hasAnyTargetEnabled() { return self.getValue() || players.getValue() || friends.getValue(); }
    private int getRandomTimeAnimation() { return Math.round((550+random.nextInt(300))*dashLength.getFloat()); }
    private int nextDashColor(long now) {
        if (colorMode.is("Random Palette")) return Color.getHSBColor(random.nextFloat(),.95F,1F).getRGB();
        if (colorMode.is("Custom")) return pickColor.getValue().getRGB();
        if (colorMode.is("Client")) return ClientTheme.wave(520L,255);
        return Color.getHSBColor((now%1600L)/1600F,.82F,1F).getRGB();
    }
    private void trimCubics(boolean light) {
        int overflow=dashCubics.size()-(light ? LIGHT_MAX_CUBICS : MAX_CUBICS);
        if (overflow>0) dashCubics.subList(0,overflow).clear();
    }
    private void trimRings(boolean light) {
        int overflow=rings.size()-(light ? LIGHT_MAX_RINGS : MAX_RINGS);
        if (overflow>0) rings.subList(0,overflow).clear();
    }
    private double randomRange(double range) { return random.nextDouble()*range; }
    private static float dashAlpha(float t) {
        t=Mth.clamp(t,0F,1F);
        if (t<.08F) return t/.08F;
        if (t>.72F) return 1F-(t-.72F)/.28F;
        return 1F;
    }
    private static float easeOutQuad(float t) { t=Mth.clamp(t,0F,1F);return 1F-(1F-t)*(1F-t); }
    private static float easeInOutWave(float t) {
        t=Mth.clamp(t,0F,1F);return t<=.5F ? 2F*t*t : 1F-(float)Math.pow(-2F*t+2F,2F)/2F;
    }
    private static Identifier texture(String path) { return Identifier.fromNamespaceAndPath("releon",ROOT+path); }
    private static TextureInfo cubic(int n,int w,int h) { return new TextureInfo(texture("dashcubics/dashcubic"+n+".png"),w,h); }
    private static TextureInfo[] group(int group,int count,int w,int h) {
        TextureInfo[] textures=new TextureInfo[count];
        for (int i=0;i<count;i++) textures[i]=new TextureInfo(texture("dashcubics/group_dashs/group"+group+"/dashcubic"+(i+1)+".png"),w,h);
        return textures;
    }
    private record TextureInfo(Identifier id,int width,int height) { }
    private static final class PlayerTrack {
        Vec3 pos;double speed,vertical;boolean ground;int ringCooldown;
        PlayerTrack(Vec3 pos,boolean ground) { this.pos=pos;this.ground=ground; }
    }
    private static final class DashRing {
        final Vec3 pos;final long born;final int lifetime,color;final float strength;final boolean landing;
        DashRing(Vec3 pos,long born,int lifetime,int color,float strength,boolean landing) {
            this.pos=pos;this.born=born;this.lifetime=lifetime;this.color=color;this.strength=strength;this.landing=landing;
        }
        float progress(long now) { return Mth.clamp((now-born)/(float)lifetime,0F,1F); }
        boolean dead(long now) { return now-born>=lifetime; }
    }
    private static final class DashTexture {
        final TextureInfo[] textures;final long startTime,animationTime;
        DashTexture(TextureInfo[] textures,long start,long animation) {
            this.textures=textures;startTime=start;animationTime=Math.max(1,animation);
        }
        static DashTexture random(Random r,long animation,long now) {
            if (r.nextInt(100)>40) return new DashTexture(ANIMATED_TEXTURES[r.nextInt(ANIMATED_TEXTURES.length)],now,animation);
            return new DashTexture(new TextureInfo[]{STATIC_TEXTURES[r.nextInt(STATIC_TEXTURES.length)]},now,animation);
        }
        TextureInfo get(long now) {
            if (textures.length==1) return textures[0];
            long elapsed=Math.floorMod(now-startTime,animationTime);
            int index=Mth.clamp((int)((elapsed/(float)animationTime)*textures.length),0,textures.length-1);
            return textures[index];
        }
    }
    private static final class DashCubic {
        final int entityId,lifetime,color;final DashTexture texture;final long startTime;
        final float scale;final boolean addSparks;final List<DashSpark> sparks=new ArrayList<>();
        Vec3 pos,prevPos,motion;float rotation;
        DashCubic(int id,Vec3 pos,Vec3 motion,DashTexture texture,int lifetime,int color,float scale,
                  boolean addSparks,Random random,long now) {
            entityId=id;this.pos=prevPos=pos;this.motion=motion;this.texture=texture;
            this.lifetime=Math.max(1,lifetime);this.color=color;this.scale=scale;this.addSparks=addSparks;
            startTime=now;updateRotation(random);
        }
        void tick(DashCubic next,Random random,boolean segments,boolean dots,long now,int sparkCap) {
            prevPos=pos;motion=(next!=null ? next.motion : motion).scale(1D/1.05D);
            double divisor=motion.y<0 ? 1D : 3.5D;
            pos=pos.add(motion.x*5,motion.y*5/divisor,motion.z*5);updateRotation(random);
            if (addSparks && (segments || dots)) {
                for (DashSpark spark:sparks) spark.tick();
                sparks.removeIf(spark->spark.isDead(now));
                if (progress(now)<.3F && sparks.size()<sparkCap && random.nextInt(12)>5) {
                    int amount=Math.min(segments ? 1 : 2,sparkCap-sparks.size());
                    for (int i=0;i<amount;i++) sparks.add(new DashSpark(random,now));
                }
            } else sparks.clear();
        }
        Vec3 renderPos(float partial) { return prevPos.lerp(pos,Mth.clamp(partial,0F,1F)); }
        float progress(long now) { return Mth.clamp((now-startTime)/(float)lifetime,0F,1F); }
        boolean isDead(long now) { return now-startTime>=lifetime; }
        void updateRotation(Random r) {
            double horizontal=Math.hypot(motion.x,motion.z);
            if (horizontal<5e-4) { rotation=r.nextFloat()*360;return; }
            float yaw=(float)Math.toDegrees(Math.atan2(motion.z,motion.x)-Math.PI/2);
            if (yaw<0) yaw+=360;rotation=yaw-60;
        }
    }
    private static final class DashSpark {
        final double stepX,stepY,stepZ;final long startTime;
        Vec3 pos=Vec3.ZERO,prevPos=Vec3.ZERO;
        DashSpark(Random r,long now) {
            double speed=r.nextDouble()/50,yaw=Math.toRadians(r.nextDouble()*360);
            double pitch=Math.toRadians(-180+r.nextDouble()*180);
            stepX=Math.sin(yaw)*speed;stepY=Math.cos(pitch)*speed;stepZ=Math.cos(yaw)*speed;
            startTime=now;
        }
        void tick() { prevPos=pos;pos=pos.add(stepX,stepY,stepZ); }
        Vec3 renderPos(float partial) { return prevPos.lerp(pos,Mth.clamp(partial,0F,1F)); }
        double alpha(long now) { return 1D-Mth.clamp((now-startTime)/1000D,0D,1D); }
        boolean isDead(long now) { return now-startTime>=1000; }
    }
}
