package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.target.RaycastAngle;

import java.util.concurrent.ThreadLocalRandom;

public class SpookyTimeTest extends RotateConstructor {
    public static final SpookyTimeTest INSTANCE = new SpookyTimeTest();

    private Entity  lastEntity;
    private int     ticksOnTarget;
    private float   lastYawStep;
    private float   lastPitchStep;
    private double  returnYawMomentum;
    private double  returnPitchMomentum;
    private long    nextMissCheckMs  = 0L;
    private long    missActiveUntilMs = -1L;
    private float   missYaw;
    private float   missPitch;

    private static final float MISS_YAW_RANGE        = 14.0F;
    private static final float MISS_PITCH_RANGE      = 4.0F;
    private static final int   MISS_DURATION_MS      = 140;
    private static final int   MISS_MIN_INTERVAL_MS  = 300;
    private static final int   MISS_MAX_INTERVAL_MS  = 900;
    private static final float MISS_TRIGGER_CHANCE   = 0.22F;

    private static final float CHEST_PITCH_BIAS = 2.0F;
    private static final float MOUSE_GCD        = 0.15F;

    public SpookyTimeTest() {
        super("SpookyTime 1.16");
    }

    private static float gcd(float angle) {
        return Math.round(angle / MOUSE_GCD) * MOUSE_GCD;
    }

    private void onNewTarget(Entity entity) {
        lastEntity    = entity;
        ticksOnTarget = 0;
        lastYawStep   = 0.0F;
        lastPitchStep = 0.0F;
    }

    private void resetTracking() {
        lastEntity          = null;
        ticksOnTarget       = 0;
        lastYawStep         = 0.0F;
        lastPitchStep       = 0.0F;
        returnYawMomentum   = 0.0;
        returnPitchMomentum = 0.0;
        missActiveUntilMs   = -1L;
    }

    private Angle trackTarget(Angle current, Angle target, boolean canAttack, boolean airborne) {
        Angle  delta      = MathAngle.calculateDelta(current, target);
        float  yawD       = delta.getYaw();
        float  pitchD     = delta.getPitch();
        float  totalDelta = (float) Math.hypot(yawD, pitchD);

        if (totalDelta < 0.15F) {
            ticksOnTarget++;
            return current;
        }

        ticksOnTarget++;
        ThreadLocalRandom r = ThreadLocalRandom.current();

        float fraction;
        if (ticksOnTarget <= 2) {
            fraction = 0.32F + r.nextFloat() * 0.10F;
        } else if (ticksOnTarget <= 5) {
            fraction = 0.48F + r.nextFloat() * 0.12F;
        } else {
            fraction = 0.58F + r.nextFloat() * 0.12F;
        }

        if (canAttack) {
            fraction = Math.max(fraction, 0.55F + r.nextFloat() * 0.12F);
        }

        if (totalDelta < 5.0F) {
            fraction = Mth.clamp(fraction * 0.60F, 0.22F, 0.45F);
        }

        if (airborne && !canAttack) {
            fraction *= 0.82F;
        }

        float yawStep   = yawD * fraction;
        float pitchStep = pitchD * fraction;

        float minY = Math.min(Math.abs(yawD),   0.5F) * Math.signum(yawD);
        float minP = Math.min(Math.abs(pitchD), 0.35F) * Math.signum(pitchD);
        if (Math.abs(yawStep)   < Math.abs(minY)) yawStep   = minY;
        if (Math.abs(pitchStep) < Math.abs(minP)) pitchStep = minP;

        if (Math.abs(yawStep)   > Math.abs(yawD))   yawStep   = yawD;
        if (Math.abs(pitchStep) > Math.abs(pitchD)) pitchStep = pitchD;

        float maxY = canAttack ? 44.0F : (airborne ? 30.0F : 34.0F);
        float maxP = canAttack ? 28.0F : (airborne ? 18.0F : 22.0F);
        yawStep   = Mth.clamp(yawStep,   -maxY, maxY);
        pitchStep = Mth.clamp(pitchStep, -maxP, maxP);

        float smooth = 0.55F;
        yawStep   = lastYawStep   + (yawStep   - lastYawStep)   * smooth;
        pitchStep = lastPitchStep + (pitchStep - lastPitchStep) * smooth;

        if (Math.abs(yawStep)   > Math.abs(yawD))   yawStep   = yawD;
        if (Math.abs(pitchStep) > Math.abs(pitchD)) pitchStep = pitchD;

        yawStep   = gcd(yawStep);
        pitchStep = gcd(pitchStep);

        lastYawStep   = yawStep;
        lastPitchStep = pitchStep;

        if (Math.abs(yawStep) < 0.01F && Math.abs(pitchStep) < 0.01F) {
            return current;
        }

        return new Angle(
                current.getYaw()   + yawStep,
                Mth.clamp(current.getPitch() + pitchStep, -69.0F, 89.0F)
        );
    }

    private Angle snapReturnToPlayer(Angle current, Angle playerView) {
        Angle  delta    = MathAngle.calculateDelta(current, playerView);
        double yawD     = delta.getYaw();
        double pitchD   = delta.getPitch();
        double dist     = Math.hypot(yawD, pitchD);

        if (dist < 0.1) return current;

        double fraction = Mth.clamp(0.55 + dist / 80.0, 0.55, 0.85);

        double yawStep   = yawD * fraction;
        double pitchStep = pitchD * fraction;

        yawStep   = returnYawMomentum   + (yawStep   - returnYawMomentum)   * 0.65;
        pitchStep = returnPitchMomentum + (pitchStep - returnPitchMomentum) * 0.65;

        if (Math.abs(yawStep)   > Math.abs(yawD))   yawStep   = yawD;
        if (Math.abs(pitchStep) > Math.abs(pitchD)) pitchStep = pitchD;

        returnYawMomentum   = yawStep;
        returnPitchMomentum = pitchStep;

        float fy = gcd((float) yawStep);
        float fp = gcd((float) pitchStep);

        if (Math.abs(fy) < 0.01F && Math.abs(fp) < 0.01F) return current;

        return new Angle(
                current.getYaw()   + fy,
                Mth.clamp(current.getPitch() + fp, -89.0F, 89.0F)
        );
    }

    private Angle applyMiss(Angle angle) {
        long now = System.currentTimeMillis();

        if (missActiveUntilMs < 0 && now >= nextMissCheckMs) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (r.nextFloat() < MISS_TRIGGER_CHANCE) {
                missActiveUntilMs = now + MISS_DURATION_MS + r.nextInt(80);
                missYaw   = (float) ((r.nextDouble() * 2.0 - 1.0) * MISS_YAW_RANGE);
                missPitch = (float) ((r.nextDouble() * 2.0 - 1.0) * MISS_PITCH_RANGE);
            }
            nextMissCheckMs = now + MISS_MIN_INTERVAL_MS
                    + r.nextInt(MISS_MAX_INTERVAL_MS - MISS_MIN_INTERVAL_MS);
        }

        if (missActiveUntilMs > 0) {
            long remaining = missActiveUntilMs - now;
            if (remaining <= 0) {
                missActiveUntilMs = -1L;
                return angle;
            }
            float progress = 1.0F - Mth.clamp(remaining / (float) MISS_DURATION_MS, 0.0F, 1.0F);
            float ease = progress < 0.5F ? progress * 2.0F : (1.0F - progress) * 2.0F;
            return new Angle(
                    angle.getYaw()   + missYaw   * ease,
                    Mth.clamp(angle.getPitch() + missPitch * ease, -89.0F, 89.0F)
            );
        }

        return angle;
    }

    @Override
    public Angle limitAngleChange(Angle current, Angle target, Vec3 vec3d, Entity entity) {
        AuraModule aura   = AuraModule.getInstance();
        Minecraft  client = Minecraft.getInstance();
        if (aura == null || client.player == null) return current;

        boolean hasTarget = aura.isEnabled() && AuraModule.target != null && entity != null;

        if (!hasTarget) {
            if (lastEntity != null) {
                resetTracking();
            }
            Angle playerView = new Angle(client.player.getYRot(), client.player.getXRot());
            return snapReturnToPlayer(current, playerView);
        }

        if (entity != lastEntity) {
            onNewTarget(entity);
        }

        StrikeManager        attackHandler = aura.getAttackHandlerRaw();
        boolean canAttack = attackHandler != null && attackHandler.canAttack(aura.getConfig(), 0);
        boolean airborne  = !client.player.onGround();

        Angle biased = new Angle(
                target.getYaw(),
                Mth.clamp(target.getPitch() + CHEST_PITCH_BIAS, -89.0F, 89.0F)
        );

        Angle result = trackTarget(current, biased, canAttack, airborne);
        result = applyMiss(result);
        return result;
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}