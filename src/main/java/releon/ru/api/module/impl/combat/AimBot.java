package releon.ru.api.module.impl.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AimBot extends Module {
    private static final String PRIORITY_OPTIMAL = "Оптимальный";
    private static final String PRIORITY_DISTANCE = "Дистанция";
    private static final String PRIORITY_HEALTH = "Здоровье";
    private static final String PRIORITY_ANGLE = "Угол поворота";

    private static final double BOW_SPEED_MULTIPLIER = 3.0;
    private static final double BOW_GRAVITY = 0.05;
    private static final double MIN_PULL = 0.1;
    private static final double TRIDENT_SPEED = 2.5;
    private static final double TRIDENT_GRAVITY = 0.05;

    private final NumberSetting distance = register(new NumberSetting("Дистанция", "Максимальная дистанция до цели.", 55.0, 3.0, 140.0, 0.5));
    private final NumberSetting aimSpeed = register(new NumberSetting("Скорость наводки", "Максимальная скорость поворота за обновление.", 360.0, 30.0, 360.0, 5.0));
    private final ModeSetting priority = register(new ModeSetting("Приоритет", "Как выбирать цель.", PRIORITY_OPTIMAL,
            PRIORITY_OPTIMAL, PRIORITY_DISTANCE, PRIORITY_HEALTH, PRIORITY_ANGLE));

    private final BooleanSetting bow = register(new BooleanSetting("Лук", "Наводиться при натяжении лука.", true));
    private final BooleanSetting trident = register(new BooleanSetting("Трезубец", "Наводиться при замахе трезубцем.", true));

    private final BooleanSetting targetNaked = register(new BooleanSetting("Голые", "Наводиться на игроков без брони.", true));
    private final BooleanSetting targetFriends = register(new BooleanSetting("Друзья", "Наводиться на друзей.", false));
    private final BooleanSetting onlyVisible = register(new BooleanSetting("Только видимые", "Наводиться только на видимых игроков.", false));

    private LivingEntity currentTarget;

    private int trackedId = -1;
    private Vec3 lastTargetPos;
    private Vec3 targetVelocity = Vec3.ZERO;

    public AimBot() {
        super("Aim Bot", "Наводится на игроков при натяжении лука/трезубца", ModuleCategory.COMBAT);
    }

    @Override
    protected void onDisable() {
        currentTarget = null;
        resetTracking();
        AngleConnection.INSTANCE.startReturning();
    }

    public LivingEntity getCurrentTarget() {
        return currentTarget;
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        if (!event.isPre() || mc.player == null || mc.level == null) {
            return;
        }

        Projectile projectile = getProjectile();
        if (projectile == null) {
            currentTarget = null;
            resetTracking();
            return;
        }

        if (currentTarget == null || !isValidTarget(currentTarget)) {
            currentTarget = findTarget();
            resetTracking();
        }

        if (currentTarget == null) {
            return;
        }

        trackVelocity(currentTarget);

        Vec3 eye = mc.player.getEyePosition();
        Vec3 aimPoint = getAimPoint(currentTarget);
        Vec3 selfVelocity = mc.player.getDeltaMovement();

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        Vec3 direction = aimPoint.subtract(eye);

        double time = Mth.clamp(eye.distanceTo(aimPoint) / Math.max(projectile.speed(), 0.01), 0.0, 3.0);

        for (int i = 0; i < 4; i++) {
            Vec3 predicted = aimPoint.add(targetVelocity.scale(time));
            Vec3 rel = predicted.subtract(eye).subtract(selfVelocity.scale(time));
            double horizontal = Math.hypot(rel.x, rel.z);
            if (horizontal < 1.0E-4) {
                break;
            }

            double angle = solvePitch(horizontal, rel.y, projectile.speed(), projectile.gravity());
            if (Double.isNaN(angle)) {
                angle = -Math.toDegrees(Math.atan2(rel.y, horizontal));
            }

            direction = rel;
            yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(rel.z, rel.x)) - 90.0);
            pitch = (float) Mth.clamp(angle, -89.0, 89.0);

            double horizontalSpeed = Math.abs(projectile.speed() * Math.cos(Math.toRadians(angle)));
            if (horizontalSpeed < 1.0E-4) {
                break;
            }
            time = Mth.clamp(horizontal / horizontalSpeed, 0.0, 3.0);
        }

        Angle targetAngle = MathAngle.fromVec3d(direction);

        float maxStep = aimSpeed.getFloat();
        if (maxStep < 360.0F) {
            float playerYaw = mc.player.getYRot();
            float playerPitch = mc.player.getXRot();
            float deltaYaw = Mth.clamp(Mth.wrapDegrees(yaw - playerYaw), -maxStep, maxStep);
            float deltaPitch = Mth.clamp(pitch - playerPitch, -maxStep, maxStep);
            yaw = playerYaw + deltaYaw;
            pitch = Mth.clamp(playerPitch + deltaPitch, -89.0F, 89.0F);
        }

        targetAngle.setYaw(yaw);
        targetAngle.setPitch(pitch);

        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(targetAngle, direction),
                currentTarget,
                1,
                new AngleConfig(new LinearConstructor(), true, false),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    // ---------------------------------------------------------------- снаряд

    private Projectile getProjectile() {
        if (mc.player == null || !mc.player.isUsingItem()) {
            return null;
        }

        ItemStack stack = mc.player.getUseItem();
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        Item item = stack.getItem();
        if (item instanceof BowItem) {
            if (!bow.getValue()) {
                return null;
            }
            double pull = Math.max(getPullProgress(mc.player.getTicksUsingItem()), MIN_PULL);
            return new Projectile(BOW_SPEED_MULTIPLIER * pull, BOW_GRAVITY);
        }

        if (item instanceof TridentItem) {
            if (!trident.getValue()) {
                return null;
            }
            return new Projectile(TRIDENT_SPEED, TRIDENT_GRAVITY);
        }

        return null;
    }

    private double getPullProgress(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(f, 1.0);
    }

    /**
     * Баллистика: угол возвышения (в градусах, отрицательный = вверх в Minecraft)
     * для попадания в точку (range, dy) при скорости speed и гравитации gravity.
     */
    private double solvePitch(double range, double dy, double speed, double gravity) {
        if (range <= 0.0 || speed <= 0.0 || gravity <= 0.0) {
            return Double.NaN;
        }

        double v2 = speed * speed;
        double discriminant = v2 * v2 - gravity * (gravity * range * range + 2.0 * dy * v2);
        if (discriminant < 0.0) {
            return Double.NaN;
        }

        double root = Math.sqrt(discriminant);
        double angle = Math.atan((v2 - root) / (gravity * range));
        return -Math.toDegrees(angle);
    }

    private Vec3 getAimPoint(LivingEntity entity) {
        double offset = Mth.clamp(entity.getBbHeight() * 0.7, 0.35, 1.6);
        return entity.position().add(0.0, offset, 0.0);
    }

    // ---------------------------------------------------------------- скорость цели

    private void trackVelocity(LivingEntity target) {
        if (target.getId() != trackedId || lastTargetPos == null) {
            trackedId = target.getId();
            lastTargetPos = target.position();
            targetVelocity = target.getDeltaMovement();
            return;
        }

        Vec3 pos = target.position();
        Vec3 sample = pos.subtract(lastTargetPos);
        if (sample.lengthSqr() > 1.0E-8) {
            targetVelocity = targetVelocity.scale(0.6).add(sample.scale(0.4));
        } else {
            targetVelocity = targetVelocity.scale(0.6);
        }
        lastTargetPos = pos;
    }

    private void resetTracking() {
        trackedId = -1;
        lastTargetPos = null;
        targetVelocity = Vec3.ZERO;
    }

    // ---------------------------------------------------------------- выбор цели

    private LivingEntity findTarget() {
        List<Player> candidates = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (isValidTarget(player)) {
                candidates.add(player);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        switch (priority.getValue()) {
            case PRIORITY_DISTANCE -> candidates.sort(Comparator.comparingDouble(p -> mc.player.distanceTo(p)));
            case PRIORITY_HEALTH -> candidates.sort(Comparator.comparingDouble(this::getEffectiveHealth));
            case PRIORITY_ANGLE -> candidates.sort(Comparator.comparingDouble(this::getAngleDiff));
            default -> candidates.sort(Comparator.comparingDouble(this::getOptimalScore));
        }

        return candidates.get(0);
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (player == mc.player) {
            return false;
        }
        if (!player.isAlive() || player.isInvulnerable()) {
            return false;
        }
        if (mc.player.distanceTo(player) > distance.getFloat()) {
            return false;
        }
        if (onlyVisible.getValue() && !mc.player.hasLineOfSight(player)) {
            return false;
        }
        if (!targetFriends.getValue() && FriendUtils.isFriend(player)) {
            return false;
        }
        return targetNaked.getValue() || player.getArmorValue() > 0;
    }

    private double getEffectiveHealth(LivingEntity entity) {
        double health = entity.getHealth() + entity.getAbsorptionAmount();
        return health * (1.0 + entity.getArmorValue() / 25.0);
    }

    private double getAngleDiff(LivingEntity entity) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 point = entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0);
        Vec3 delta = point.subtract(eye);

        double yaw = Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));

        double diffYaw = Mth.wrapDegrees(yaw - mc.player.getYRot());
        double diffPitch = pitch - mc.player.getXRot();
        return Math.sqrt(diffYaw * diffYaw + diffPitch * diffPitch);
    }

    private double getOptimalScore(LivingEntity entity) {
        double score = 0.0;
        score += mc.player.distanceTo(entity) * 3.0;
        score += getAngleDiff(entity) * 2.0;
        score += getEffectiveHealth(entity) * 1.5;
        score += entity.getArmorValue();

        if (entity instanceof Player player) {
            if (player.isBlocking()) {
                score += 5.0;
            }
            if (player.getArmorValue() == 0) {
                score -= 3.0;
            }
            if (player.getHealth() <= 6.0F) {
                score -= 5.0;
            }
        }

        if (mc.player.hasLineOfSight(entity)) {
            score -= 2.0;
        }

        return score;
    }

    private record Projectile(double speed, double gravity) {
    }
}