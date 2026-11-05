package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import java.util.concurrent.ThreadLocalRandom;

public class SpookyTimeNewAngle extends RotateConstructor {
    public static final SpookyTimeNewAngle INSTANCE=new SpookyTimeNewAngle();
    private Entity lastEntity;
    private Object lastPlayer,lastLevel;
    private int ticksOnTarget,lastTick=Integer.MIN_VALUE;
    private Angle lastResult;
    private float lastYawStep,lastPitchStep,residY,residP;
    private double returnYawMomentum,returnPitchMomentum;
    private long nextMissCheckMs,missStartedMs,missActiveUntilMs=-1L;
    private float missYaw,missPitch;
    private boolean previousAttackWindow;
    private static final float MISS_YAW_RANGE=14F,MISS_PITCH_RANGE=4F;
    private static final int MISS_DURATION_MS=140,MISS_MIN_INTERVAL_MS=300,MISS_MAX_INTERVAL_MS=900;
    private static final float MISS_TRIGGER_CHANCE=.22F;
    private static final float MOUSE_GCD=.15F; // Original fixed step restored.
    private static final double ZONE_TOP=.92,ZONE_BOTTOM=.06,AIM_POINT=.45,MOVE_SPEED=.06;

    public SpookyTimeNewAngle() { super("SpookyTime 1.21"); }
    public void reset() {
        lastEntity=null;lastPlayer=lastLevel=null;
        ticksOnTarget=0;lastTick=Integer.MIN_VALUE;lastResult=null;
        lastYawStep=lastPitchStep=residY=residP=0;
        returnYawMomentum=returnPitchMomentum=0;
        nextMissCheckMs=missStartedMs=0;missActiveUntilMs=-1L;missYaw=missPitch=0;
        previousAttackWindow=false;
    }
    private void onNewTarget(Entity entity) {
        lastEntity=entity;ticksOnTarget=0;
        lastYawStep=lastPitchStep=residY=residP=0;
        returnYawMomentum=returnPitchMomentum=0;
        missActiveUntilMs=-1L;missYaw=missPitch=0;previousAttackWindow=false;
        nextMissCheckMs=System.currentTimeMillis()+MISS_MIN_INTERVAL_MS;
    }
    private static float mouseGcd(Minecraft mc) {
        return MOUSE_GCD;
    }
    private static float pitchTo(LocalPlayer p,Entity entity,double fraction) {
        AABB b=entity.getBoundingBox();
        double dx=(b.minX+b.maxX)*.5-p.getX(),dz=(b.minZ+b.maxZ)*.5-p.getZ();
        double dy=b.minY+(b.maxY-b.minY)*fraction-p.getEyeY();
        return (float)-Math.toDegrees(Math.atan2(dy,Math.max(1E-4,Math.hypot(dx,dz))));
    }
    private static boolean pitchInZone(LocalPlayer p,Entity e,float pitch) {
        float a=pitchTo(p,e,ZONE_TOP),b=pitchTo(p,e,ZONE_BOTTOM);
        return pitch>=Math.min(a,b) && pitch<=Math.max(a,b);
    }
    private static boolean moving(Entity e) {
        double dx=e.getX()-e.xo,dz=e.getZ()-e.zo;
        return dx*dx+dz*dz>MOVE_SPEED*MOVE_SPEED;
    }
    private static float toward(float value,float error) {
        if (value*error<=0) return 0;
        return Math.signum(error)*Math.min(Math.abs(value),Math.abs(error));
    }
    private static float[] quantize(float value,float error,float gcd,float residue) {
        int max=(int)Math.floor(Math.abs(error)/(double)gcd);
        if (max<=0) return new float[]{0,0};
        float wanted=value+residue;
        int units=Math.round(wanted/gcd);
        if (error>0) units=Math.max(0,Math.min(max,units));
        else units=Math.min(0,Math.max(-max,units));
        float out=units*gcd;
        if (Math.abs(out)>Math.abs(error)) { units-=Integer.signum(units);out=units*gcd; }
        return new float[]{out,Mth.clamp(wanted-out,-gcd,gcd)};
    }
    private Angle track(Angle current,Angle desired,boolean attackWindow,boolean ready,boolean airborne,float gcd) {
        float ey=Mth.wrapDegrees(desired.getYaw()-current.getYaw());
        float ep=desired.getPitch()-current.getPitch();
        ticksOnTarget++;
        float total=(float)Math.hypot(ey,ep);
        if (total<gcd) { lastYawStep=lastPitchStep=residY=residP=0;return current; }
        ThreadLocalRandom r=ThreadLocalRandom.current();
        float fraction=ticksOnTarget<=2 ? .32F+r.nextFloat()*.10F
            : ticksOnTarget<=5 ? .48F+r.nextFloat()*.12F : .58F+r.nextFloat()*.12F;
        if (ready) fraction=Math.max(fraction,.55F+r.nextFloat()*.12F);
        if (total<5F) fraction=Mth.clamp(fraction*.60F,.22F,.45F);
        if (airborne && !ready) fraction*=.82F;
        float sy=ey*fraction,sp=ep*fraction;
        if (Math.abs(sy)<Math.min(Math.abs(ey),.5F)) sy=Math.signum(ey)*Math.min(Math.abs(ey),.5F);
        if (Math.abs(sp)<Math.min(Math.abs(ep),.35F)) sp=Math.signum(ep)*Math.min(Math.abs(ep),.35F);
        float maxY=attackWindow ? 44F : airborne ? 30F : 34F;
        float maxP=attackWindow ? 28F : airborne ? 18F : 22F;
        sy=Mth.clamp(sy,-maxY,maxY);sp=Mth.clamp(sp,-maxP,maxP);
        if (lastYawStep*ey<=0) lastYawStep=0;
        if (lastPitchStep*ep<=0) lastPitchStep=0;
        sy=lastYawStep+(sy-lastYawStep)*.55F;
        sp=lastPitchStep+(sp-lastPitchStep)*.55F;
        sy=toward(sy,ey);sp=toward(sp,ep);
        if (residY*ey<0) residY=0;
        if (residP*ep<0) residP=0;
        float[] y=quantize(sy,ey,gcd,residY),p=quantize(sp,ep,gcd,residP);
        residY=y[1];residP=p[1];lastYawStep=y[0];lastPitchStep=p[0];
        float pitch=Mth.clamp(current.getPitch()+p[0],-90F,90F);
        if (pitch!=current.getPitch()+p[0]) residP=0;
        return new Angle(current.getYaw()+y[0],pitch);
    }
    private Angle returnToPlayer(Angle current,Angle view,float gcd) {
        float ey=Mth.wrapDegrees(view.getYaw()-current.getYaw()),ep=view.getPitch()-current.getPitch();
        double dist=Math.hypot(ey,ep);
        if (dist<gcd) { returnYawMomentum=returnPitchMomentum=0;return current; }
        double fraction=Mth.clamp(.55+dist/80D,.55,.85);
        if (returnYawMomentum*ey<=0) returnYawMomentum=0;
        if (returnPitchMomentum*ep<=0) returnPitchMomentum=0;
        float sy=toward((float)(returnYawMomentum+(ey*fraction-returnYawMomentum)*.65),ey);
        float sp=toward((float)(returnPitchMomentum+(ep*fraction-returnPitchMomentum)*.65),ep);
        float[] y=quantize(sy,ey,gcd,0),p=quantize(sp,ep,gcd,0);
        returnYawMomentum=y[0];returnPitchMomentum=p[0];
        return new Angle(current.getYaw()+y[0],Mth.clamp(current.getPitch()+p[0],-90F,90F));
    }
    private void cancelMiss() { missActiveUntilMs=-1L;missYaw=missPitch=0; }
    private Angle miss(Angle angle) {
        long now=System.currentTimeMillis();ThreadLocalRandom r=ThreadLocalRandom.current();
        if (missActiveUntilMs<0 && now>=nextMissCheckMs) {
            if (r.nextFloat()<MISS_TRIGGER_CHANCE) {
                missStartedMs=now;missActiveUntilMs=now+MISS_DURATION_MS+r.nextInt(80);
                missYaw=(float)((r.nextDouble()*2-1)*MISS_YAW_RANGE);
                missPitch=(float)((r.nextDouble()*2-1)*MISS_PITCH_RANGE);
            }
            nextMissCheckMs=now+MISS_MIN_INTERVAL_MS+r.nextInt(MISS_MAX_INTERVAL_MS-MISS_MIN_INTERVAL_MS);
        }
        if (missActiveUntilMs<0) return angle;
        if (now>=missActiveUntilMs) { cancelMiss();return angle; }
        float progress=Mth.clamp((now-missStartedMs)/(float)(missActiveUntilMs-missStartedMs),0F,1F);
        float ease=progress<.5F ? progress*2F : (1F-progress)*2F;
        return new Angle(angle.getYaw()+missYaw*ease,Mth.clamp(angle.getPitch()+missPitch*ease,-90F,90F));
    }
    @Override
    public Angle limitAngleChange(Angle current,Angle target,Vec3 vec3d,Entity entity) {
        Minecraft mc=Minecraft.getInstance();AuraModule aura=AuraModule.getInstance();
        if (current==null || mc.player==null || mc.level==null || aura==null) { reset();return current; }
        if (lastPlayer!=mc.player || lastLevel!=mc.level) { reset();lastPlayer=mc.player;lastLevel=mc.level; }
        if (mc.screen!=null) { AuraModule.target=null;reset();return current; }
        if (!Float.isFinite(current.getYaw()) || !Float.isFinite(current.getPitch())) { reset();return current; }
        int tick=mc.player.tickCount;
        if (tick==lastTick && lastResult!=null) return entity==lastEntity ? lastResult : current;
        if (lastTick!=Integer.MIN_VALUE && tick!=lastTick+1) {
            reset();lastPlayer=mc.player;lastLevel=mc.level;
        }
        float gcd=mouseGcd(mc);
        boolean hasTarget=aura.isEnabled() && entity!=null && entity.isAlive() && target!=null
            && Float.isFinite(target.getYaw()) && Float.isFinite(target.getPitch());
        if (!hasTarget) {
            if (lastEntity!=null) onNewTarget(null);
            lastTick=tick;
            lastResult=returnToPlayer(current,new Angle(mc.player.getYRot(),mc.player.getXRot()),gcd);
            return lastResult;
        }
        if (entity!=lastEntity) onNewTarget(entity);
        StrikeManager handler=aura.getAttackHandlerRaw();
        StrikerConstructor.AttackPerpetratorConfigurable config=aura.getConfig();
        boolean matching=handler!=null && config!=null && config.getTarget()==entity;
        boolean ready=matching && handler.canAttack(config,0);
        boolean preparing=matching && handler.canAttack(config,1);
        boolean fallingCrit=matching && config.isOnlyCritical() && !mc.player.onGround()
            && mc.player.fallDistance>0 && mc.player.getDeltaMovement().y<0;
        boolean attackWindow=ready || preparing || fallingCrit;
        if (attackWindow) {
            cancelMiss();nextMissCheckMs=System.currentTimeMillis()+MISS_MIN_INTERVAL_MS;
            if (!previousAttackWindow) lastYawStep=lastPitchStep=residY=residP=0;
        }
        previousAttackWindow=attackWindow;
        boolean frozen=!moving(entity) && pitchInZone(mc.player,entity,current.getPitch());
        float pitch=frozen ? current.getPitch()
            : attackWindow ? Mth.clamp(target.getPitch(),-90F,90F)
            : Mth.clamp(pitchTo(mc.player,entity,AIM_POINT),-90F,90F);
        Angle desired=new Angle(target.getYaw(),pitch);
        Angle result=track(current,desired,attackWindow,ready,!mc.player.onGround(),gcd);
        if (!attackWindow) result=miss(result);
        if (frozen && !pitchInZone(mc.player,entity,result.getPitch()))
            result=new Angle(result.getYaw(),current.getPitch());
        lastTick=tick;lastResult=result;return result;
    }
    @Override public Vec3 randomValue() { return Vec3.ZERO; }
}
