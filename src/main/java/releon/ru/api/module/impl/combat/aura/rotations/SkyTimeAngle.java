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

/** Experimental visual rotation; not a verified anticheat bypass. */
public final class SkyTimeAngle extends RotateConstructor {
    public static final SkyTimeAngle INSTANCE = new SkyTimeAngle();

    // Animation choices, NOT measured anticheat limits.
    private static final int LIFT_TICKS = 2;
    private static final float LIFT_DEGREES = 22.0F;
    private Object lastPlayer, lastLevel;
    private Entity lastTarget;
    private int lastAttackCount;
    private int liftStarted;
    private boolean lifting;
    private float liftPitch;
    private int lastStepTick = Integer.MIN_VALUE;
    private Angle lastStep;

    private SkyTimeAngle() {
        super("SkyTime");
    }

    public void reset() {
        lastPlayer = lastLevel = null;
        lastTarget = null;
        lastAttackCount = 0;
        lifting = false;
        liftStarted = 0;
        lastStepTick = Integer.MIN_VALUE;
        lastStep = null;
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle,
                                  Vec3 point, Entity entity) {
        Minecraft client = Minecraft.getInstance();
        AuraModule aura = AuraModule.getInstance();
        if (currentAngle == null || targetAngle == null || entity == null
                || client.player == null || client.level == null
                || aura == null || !aura.isEnabled()) {
            reset();
            return currentAngle;
        }
        StrikeManager handler = aura.getAttackHandlerRaw();
        if (handler == null) {
            reset();
            return currentAngle;
        }
        int tick = client.player.tickCount;
        int attacks = handler.getCount();
        if (lastPlayer != client.player || lastLevel != client.level
                || lastTarget != entity || attacks < lastAttackCount
                || (lastStepTick != Integer.MIN_VALUE && tick < lastStepTick)) {
            reset();
            lastPlayer = client.player;
            lastLevel = client.level;
            lastTarget = entity;
            lastAttackCount = attacks;
        }
        // Count is a LOCAL attack dispatch, not a server-confirmed hit or crit.
        // Begin the lift after dispatch, then return to aiming for the next attack.
        if (attacks > lastAttackCount) {
            lifting = true;
            liftStarted = tick;
            // Negative pitch points upward in Minecraft.
            liftPitch = Mth.clamp(currentAngle.getPitch() - LIFT_DEGREES, -89F, 89F);
            lastAttackCount = attacks;
            lastStepTick = Integer.MIN_VALUE;
        }
        // Controller may query the same singleton several times in one tick.
        if (lastStepTick == tick && lastStep != null) return lastStep;
        if (lifting && tick - liftStarted >= LIFT_TICKS) lifting = false;
        Angle desired = lifting
                ? new Angle(targetAngle.getYaw(), liftPitch) : targetAngle;
        lastStep = step(currentAngle, desired, lifting ? 8F : 35F,
                lifting ? 12F : 18F);
        lastStepTick = tick;
        return lastStep;
    }

    private Angle step(Angle current, Angle desired, float yawSpeed, float pitchSpeed) {
        Angle delta = MathAngle.calculateDelta(current, desired);
        return new Angle(
                current.getYaw() + Mth.clamp(delta.getYaw(), -yawSpeed, yawSpeed),
                Mth.clamp(current.getPitch()
                        + Mth.clamp(desired.getPitch() - current.getPitch(),
                                    -pitchSpeed, pitchSpeed), -89F, 89F));
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
