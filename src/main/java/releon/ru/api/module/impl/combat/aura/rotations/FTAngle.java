package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

/**
 * Tick-based tracking and camera-return controller.
 * Experimental client behavior, not a verified Intave bypass.
 * Final sensitivity adjustment belongs to AngleConnection, not this class.
 */
public final class FTAngle extends RotateConstructor {
    public static final FTAngle INSTANCE = new FTAngle();

    // Client tuning values, not measured anticheat tolerances.
    private static final double TICK_SECONDS = 1D / 20D;
    private static final int SUBSTEPS = 4;
    private static final double MAX_YAW_SPEED = 700D;       // degrees / second
    private static final double MAX_PITCH_SPEED = 360D;
    private static final double MAX_YAW_ACCELERATION = 2800D; // degrees / second^2
    private static final double MAX_PITCH_ACCELERATION = 1600D;
    private static final double YAW_RESPONSE = 14D;
    private static final double PITCH_RESPONSE = 12D;
    private static final double STOP_EPSILON = 1E-4D;

    private Object lastPlayer;
    private Object lastLevel;
    private Entity lastEntity;
    private int lastTick;
    private boolean hasTick;
    private boolean previousWasReturn;
    private Angle cachedOutput;
    private double yawVelocity;
    private double pitchVelocity;

    private FTAngle() {
        super("FunTime");
    }

    public void reset() {
        lastPlayer = lastLevel = null;
        lastEntity = null;
        lastTick = 0;
        hasTick = false;
        previousWasReturn = false;
        cachedOutput = null;
        resetVelocity();
    }

    @Override
    public Angle limitAngleChange(Angle current, Angle desired, Vec3 point, Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null
                || !finite(current) || !finite(desired)) {
            reset();
            return current;
        }

        // The two-argument RotateConstructor overload passes null Entity on return.
        // Returning to the camera must continue even after Aura has been disabled.
        boolean cameraReturn = entity == null;
        AuraModule aura = AuraModule.getInstance();
        if (!cameraReturn && (aura == null || !aura.isEnabled())) {
            reset();
            return current;
        }

        int tick = client.player.tickCount;
        if (lastPlayer != client.player || lastLevel != client.level) {
            reset();
            lastPlayer = client.player;
            lastLevel = client.level;
        }

        // Freeze this tick's absolute result, including across target switches.
        // The next tick will process the new target/return mode.
        if (hasTick && tick == lastTick && cachedOutput != null) return cachedOutput;

        boolean discontinuity = !hasTick || (long) tick - lastTick != 1L;
        if (discontinuity || lastEntity != entity || previousWasReturn != cameraReturn) {
            resetVelocity();
        }
        lastEntity = entity;
        previousWasReturn = cameraReturn;

        double yaw = current.getYaw();
        double pitch = clamp(current.getPitch(), -90D, 90D);
        double goalPitch = clamp(desired.getPitch(), -90D, 90D);
        double h = TICK_SECONDS / SUBSTEPS;

        for (int i = 0; i < SUBSTEPS; i++) {
            double yawError = wrap((double) desired.getYaw() - yaw);
            double pitchError = goalPitch - pitch;

            yawVelocity = nextVelocity(yawVelocity, yawError, YAW_RESPONSE,
                    MAX_YAW_SPEED, MAX_YAW_ACCELERATION, h);
            pitchVelocity = nextVelocity(pitchVelocity, pitchError, PITCH_RESPONSE,
                    MAX_PITCH_SPEED, MAX_PITCH_ACCELERATION, h);

            double yawStep = toward(yawVelocity * h, yawError);
            double pitchStep = toward(pitchVelocity * h, pitchError);
            yaw += yawStep;
            pitch = clamp(pitch + pitchStep, -90D, 90D);

            // Safety takes priority over acceleration continuity at reversal/arrival.
            if (yawStep == 0D || Math.abs(yawStep) >= Math.abs(yawError)) yawVelocity = 0D;
            if (pitchStep == 0D || Math.abs(pitchStep) >= Math.abs(pitchError)) pitchVelocity = 0D;
        }

        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            reset();
            return current;
        }
        // Keep yaw unwrapped; only angular ERROR is wrapped to the shortest path.
        // No MouseStepGrid: AngleConnection applies the final sensitivity grid.
        cachedOutput = new Angle((float) yaw, (float) pitch);
        lastTick = tick;
        hasTick = true;
        return cachedOutput;
    }

    private void resetVelocity() {
        yawVelocity = pitchVelocity = 0D;
    }

    private static double nextVelocity(double velocity, double error, double response,
                                       double speedLimit, double accelerationLimit, double h) {
        if (Math.abs(error) < STOP_EPSILON) return 0D;
        double acceleration = response * response * error - 2D * response * velocity;
        acceleration = clamp(acceleration, -accelerationLimit, accelerationLimit);
        return clamp(velocity + acceleration * h, -speedLimit, speedLimit);
    }

    private static double toward(double step, double error) {
        if (Math.abs(error) < STOP_EPSILON || step * error <= 0D) return 0D;
        return Math.copySign(Math.min(Math.abs(step), Math.abs(error)), error);
    }

    private static double wrap(double angle) {
        double result = angle % 360D;
        if (result >= 180D) result -= 360D;
        if (result < -180D) result += 360D;
        return result;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean finite(Angle angle) {
        return angle != null && Float.isFinite(angle.getYaw()) && Float.isFinite(angle.getPitch());
    }

    @Override
    public Vec3 randomValue() {
        // point/desired are supplied by the existing target-point calculation.
        return Vec3.ZERO;
    }
}
