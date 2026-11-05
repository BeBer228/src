package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.RotationRecorder;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;

/** Engineering revision of the supplied controller, not a confirmed bypass.
 * Original coefficients retained, NOT retrained. Independent random residuals
 * and constant pitch bias removed. Quantization occurs only once.
 */
public class MLSAC extends RotateConstructor {
    public static final MLSAC INSTANCE = new MLSAC();
    private static final float YAW_GAIN_ADD=.15F, PITCH_GAIN_ADD=.03F;
    private static final float PITCH_MOMENTUM=.88F, FAR_ERROR=30F;
    private static final float[][] YAW={
            {.2406F,.6213F,.8390F,-.2963F},
            {.2064F,.8297F,.9068F,-.5808F},
            {-.0803F,.4338F,1.2899F,-1.0480F},
            {-.0173F,.4876F,.7926F,-.5543F},
            {-.0464F,.3066F,.8985F,-.4445F},
            {.0126F,.4018F,.7509F,-.2008F},
            {.1151F,.1289F,.7725F,-.0853F},
            {.1494F,.3996F,.6245F,-.1461F},
            {.1106F,.2091F,.7336F,-.2098F},
            {.1820F,.3457F,.6649F,-.2004F},
            {.1866F,.3050F,.4342F,-.0816F},
            {.1870F,.0756F,.5093F,-.0896F},
            {.1604F,.3829F,.6341F,-.2186F}
    };
    // The original fifth term was a constant bias, deliberately omitted.
    private static final float[][] PITCH={
            {-.0113F,.0532F,.9779F,-.4869F},
            {.0467F,.0230F,.9960F,-.6696F},
            {.0398F,-.0230F,.9182F,-.5016F},
            {.0290F,-.0170F,.9293F,-.3755F},
            {.0094F,-.0071F,.8916F,-.3260F},
            {.0454F,.0993F,.9296F,-.3311F},
            {.0718F,.1417F,.9766F,-.3405F},
            {.1002F,.2395F,.8586F,-.3440F},
            {.1296F,.3765F,.7104F,-.2555F},
            {.0889F,.2224F,.8267F,-.4048F},
            {.0776F,.2127F,.8276F,-.4126F},
            {.0273F,.1583F,1.0098F,-.3889F},
            {-.0088F,.1288F,1.0452F,-.4858F}
    };
    private static final float[] FAR_YAW={.0186F,.0543F,1.1897F,-.4174F};
    private static final float[] FAR_PITCH={.0683F,.0592F,.8149F,-.3144F};

    private Entity lastTarget;
    private Object lastPlayer, lastLevel;
    private int lastTick=Integer.MIN_VALUE;
    private Angle lastResult;
    private boolean hasPrevT;
    private float prevTY,prevTP,previousInputYaw,previousInputPitch;
    private float dYaw,pdYaw,dPitch,pdPitch,residY,residP;

    public MLSAC() { super("MLSAC AI"); }

    public void reset() {
        lastTarget=null; lastPlayer=lastLevel=null;
        lastTick=Integer.MIN_VALUE; lastResult=null; hasPrevT=false;
        dYaw=pdYaw=dPitch=pdPitch=residY=residP=0;
    }
    private static float mouseGcd(Minecraft mc) {
        float f=(float)(mc.options.sensitivity().get()*.6+.2);
        return Math.max(1E-4F,f*f*f*8F*.15F);
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        Minecraft mc=Minecraft.getInstance();
        AuraModule aura=AuraModule.getInstance();
        if (mc.player==null || mc.level==null || entity==null || !entity.isAlive()
                || aura==null || !aura.isEnabled() || mc.screen!=null) {
            reset(); return currentAngle;
        }
        if (lastPlayer!=mc.player || lastLevel!=mc.level) {
            reset(); lastPlayer=mc.player; lastLevel=mc.level;
        }
        int now=mc.player.tickCount;
        if (now==lastTick && lastResult!=null)
            return entity==lastTarget ? lastResult : currentAngle;
        if (entity!=lastTarget || (lastTick!=Integer.MIN_VALUE && now!=lastTick+1)) {
            reset(); lastTarget=entity; lastPlayer=mc.player; lastLevel=mc.level;
        }
        float yaw=currentAngle.getYaw(),pitch=currentAngle.getPitch();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) { reset(); return currentAngle; }
        float[] t=RotationRecorder.targetAngle(mc.player.getEyePosition(),entity.getBoundingBox());
        if (!Float.isFinite(t[0]) || !Float.isFinite(t[1])) { reset(); return currentAngle; }
        float eY=Mth.wrapDegrees(t[0]-yaw),eP=t[1]-pitch;
        float rY=hasPrevT ? Mth.wrapDegrees(t[0]-prevTY) : 0;
        float rP=hasPrevT ? t[1]-prevTP : 0;
        if (hasPrevT) {
            // History reflects the angles actually supplied by the caller.
            pdYaw=dYaw; pdPitch=dPitch;
            dYaw=Mth.wrapDegrees(yaw-previousInputYaw);
            dPitch=pitch-previousInputPitch;
        }
        previousInputYaw=yaw; previousInputPitch=pitch;
        prevTY=t[0]; prevTP=t[1]; hasPrevT=true;

        int tsa=12;
        StrikeManager handler=aura.getAttackHandlerRaw();
        if (handler!=null) {
            StopWatch timer=handler.getAttackTimer();
            if (timer!=null && handler.getCount()>0)
                tsa=(int)Math.max(0L,Math.min(12L,timer.elapsedTime()/50L));
        }
        boolean far=Math.abs(eY)>=FAR_ERROR;
        float[] cy=far ? FAR_YAW : YAW[tsa];
        float[] cp=far ? FAR_PITCH : PITCH[tsa];
        float nextY=(cy[0]+YAW_GAIN_ADD)*eY+cy[1]*rY+cy[2]*dYaw+cy[3]*pdYaw;
        float nextP=(cp[0]+PITCH_GAIN_ADD)*eP+cp[1]*rP+cp[2]*PITCH_MOMENTUM*dPitch+cp[3]*pdPitch;
        float gcd=mouseGcd(mc);
        // Engineering stop tolerance: half the smallest representable mouse step.
        // Not inferred from a dataset or an anti-cheat threshold.
        if (Math.abs(eY)<=gcd*.5F && Math.abs(rY)<=gcd*.5F) {
            nextY=0; dYaw=pdYaw=residY=0;
        }
        if (Math.abs(eP)<=gcd*.5F && Math.abs(rP)<=gcd*.5F) {
            nextP=0; dPitch=pdPitch=residP=0;
        }
        nextY=Mth.clamp(nextY,-112F,112F);
        nextP=Mth.clamp(nextP,-21F,21F);
        float wy=nextY+residY,wp=nextP+residP;
        float outY=Math.round(wy/gcd)*gcd,outP=Math.round(wp/gcd)*gcd;
        residY=Mth.clamp(wy-outY,-gcd,gcd);
        residP=Mth.clamp(wp-outP,-gcd,gcd);
        float finalPitch=Mth.clamp(pitch+outP,-90F,90F);
        if (finalPitch!=pitch+outP) residP=0;
        lastTick=now;
        lastResult=new Angle(yaw+outY,finalPitch);
        return lastResult;
    }
    @Override public Vec3 randomValue() { return Vec3.ZERO; }
}
