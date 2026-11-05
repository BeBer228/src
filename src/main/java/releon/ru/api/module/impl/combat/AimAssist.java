package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.repository.friend.FriendUtils;
import java.util.concurrent.ThreadLocalRandom;

public class AimAssist extends Module {
    private static AimAssist instance;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final MultiModeSetting targetType = register(new MultiModeSetting("Цели", "Фильтр типов целей.",
            new String[]{"Игроки", "Голые игроки", "Мобы", "Животные", "Друзья", "Стойки брони"}, "Игроки", "Мобы", "Животные"));
    private final NumberSetting distanceSetting = register(new NumberSetting("Дистанция", "Максимальная дистанция до цели.", 6.0, 1.0, 6.0, 0.1));
    private final NumberSetting fovSetting = register(new NumberSetting("Обзор", "Угол обзора для захвата цели.", 360.0, 20.0, 360.0, 1.0));
    private final BooleanSetting drawFovRadiusSetting = register(new BooleanSetting("Рисовать обзор", "Рисует круг угла обзора аим ассиста.", false));
    private final NumberSetting speedSetting = register(new NumberSetting("Скорость", "Скорость наведения; не зависит от числа кадров.", 4.0, 0.5, 10.0, 0.1));
    private final NumberSetting smoothnessSetting = register(new NumberSetting("Плавность", "Плавность разгона и торможения.", 6.0, 1.0, 10.0, 0.1));
    private final NumberSetting aimHeightSetting = register(new NumberSetting("Высота прицеливания", "Высота точки внутри хитбокса.", 0.85, 0.0, 1.0, 0.01));
    private final ModeSetting prioritySetting = register(new ModeSetting("Приоритет", "Приоритет новой цели.", "Дистанция", "Дистанция", "Здоровье"));
    private final BooleanSetting onlyWithWeaponSetting = register(new BooleanSetting("Только с оружием", "Наводиться только с оружием в руке.", false));
    private final BooleanSetting onlyPlayersSetting = register(new BooleanSetting("Только игроки", "Наводиться только на игроков.", false));
    private final BooleanSetting noAimInInventorySetting = register(new BooleanSetting("Не наводиться в инвентаре", "Приостанавливать наведение при открытом GUI.", true));
    private final BooleanSetting hitInvisibleSetting = register(new BooleanSetting("Невидимки", "Наводиться на невидимых существ.", false));
    private final BooleanSetting onlyArmoredSetting = register(new BooleanSetting("Только в броне", "Наводиться только на игроков в броне.", false));
    private final BooleanSetting ignoreNakedSetting = register(new BooleanSetting("Игнорировать голых", "Игнорировать игроков без брони.", false));
    private final BooleanSetting onlyYawSetting = register(new BooleanSetting("Только поворот", "Менять только yaw, не трогать pitch.", false));
    private final BooleanSetting multipointSetting = register(new BooleanSetting("Мульти-точка", "Медленно менять точку внутри безопасной части хитбокса.", true));
    private final BooleanSetting wallCheckSetting = register(new BooleanSetting("Проверка стен", "Не целиться в точку за коллизией блока.", false));
    private final BooleanSetting disableOnWorldChangeSetting = register(new BooleanSetting("Отключать при смене мира", "Отключать модуль после смены мира.", false));
    private final BooleanSetting microMovementsSetting = register(new BooleanSetting("Микро-движения", "Небольшое медленное изменение точки, без шума в готовом угле.", true));

    private Entity target;
    private Object world, player;
    private long lastFrameNs, lastRenderAimNs, acquiredNs, nextPointNs;
    private double yawVelocity, pitchVelocity;
    private double pointX, pointY, pointZ, nextX, nextY, nextZ;

    public AimAssist() {
        super("AimAssist", "Плавная помощь в наведении на цель.", ModuleCategory.COMBAT);
        ignoreNakedSetting.visibleWhen(() -> !onlyArmoredSetting.getValue());
        instance = this;
    }
    public static AimAssist getInstance() { return instance; }
    @Override protected void onEnable() { resetState(); }
    @Override protected void onDisable() { resetState(); }
    @Override public void onTick(Minecraft client) {
        if (!isEnabled()) return;
        long now = System.nanoTime();
        if (lastRenderAimNs == 0L || now - lastRenderAimNs > 75_000_000L) updateAim(client);
    }
    @SubscribeEvent private void onDraw(DrawEvent event) {
        if (!isEnabled() || event.getLayer() != DrawEvent.Layer.GAME) return;
        lastRenderAimNs = System.nanoTime();
        updateAim(Minecraft.getInstance());
        drawFovRadius();
    }
    private void updateAim(Minecraft c) {
        if (c == null || c.player == null || c.level == null) { resetState(); return; }
        if (world != c.level || player != c.player) {
            boolean changed = world != null && world != c.level;
            resetMotion();
            world = c.level;
            player = c.player;
            if (changed && disableOnWorldChangeSetting.getValue()) { setEnabled(false); return; }
        }
        if (!c.player.isAlive() || (onlyWithWeaponSetting.getValue() && !isHoldingWeapon(c))
                || (noAimInInventorySetting.getValue() && c.screen != null)) {
            resetMotion();
            return;
        }
        long now = System.nanoTime();
        double dt = lastFrameNs == 0L ? 0D : Math.min(0.05D, Math.max(0D, (now - lastFrameNs) * 1E-9D));
        lastFrameNs = now;
        Entity selected = findTarget(c);
        if (selected == null) {
            target = null;
            yawVelocity = pitchVelocity = 0D;
            return;
        }
        if (selected != target) {
            target = selected;
            acquiredNs = now;
            yawVelocity = pitchVelocity = 0D;
            pointX = pointY = pointZ = nextX = nextY = nextZ = 0D;
            nextPointNs = now;
        }
        if (dt <= 0D) return;
        updatePoint(dt, now);
        Vec3 point = preferredPoint(selected);
        if (wallCheckSetting.getValue() && blocked(c, c.player.getEyePosition(), point)) {
            Vec3 center = selected.getBoundingBox().getCenter();
            if (blocked(c, c.player.getEyePosition(), center)) {
                yawVelocity = pitchVelocity = 0D;
                return;
            }
            point = center;
        }
        Vec3 delta = point.subtract(c.player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double desiredYaw = horizontal < 1E-7D ? c.player.getYRot() : Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double desiredPitch = -Math.toDegrees(Math.atan2(delta.y, horizontal));
        double yaw = c.player.getYRot(), pitch = c.player.getXRot();
        boolean yawOnly = onlyYawSetting.getValue();
        double smooth = (smoothnessSetting.getValue() - 1D) / 9D;
        double speed = speedSetting.getValue();
        double warmup = Mth.clamp((now - acquiredNs) / 160_000_000D, 0D, 1D);
        double omega = (8D + speed * 1.8D) * (1D - smooth * 0.35D) * (0.6D + 0.4D * warmup);
        double maxYaw = 30D + speed * 32D;
        double maxPitch = 22D + speed * 23D;
        int steps = Math.max(1, (int) Math.ceil(dt * 240D));
        double h = dt / steps;
        for (int i = 0; i < steps; i++) {
            double ey = Mth.wrapDegrees(desiredYaw - yaw);
            double ep = desiredPitch - pitch;
            double ay = omega * omega * ey - 2D * omega * yawVelocity;
            yawVelocity = Mth.clamp(yawVelocity + ay * h, -maxYaw, maxYaw);
            double sy = limitStep(yawVelocity * h, ey);
            if (sy == 0D || Math.abs(sy) >= Math.abs(ey)) yawVelocity = 0D;
            yaw += sy;
            if (yawOnly) {
                pitchVelocity = 0D;
            } else {
                double ap = omega * omega * ep - 2D * omega * pitchVelocity;
                pitchVelocity = Mth.clamp(pitchVelocity + ap * h, -maxPitch, maxPitch);
                double sp = limitStep(pitchVelocity * h, ep);
                if (sp == 0D || Math.abs(sp) >= Math.abs(ep)) pitchVelocity = 0D;
                pitch = Mth.clamp(pitch + sp, -90D, 90D);
            }
        }
        if (Double.isFinite(yaw) && Double.isFinite(pitch)) {
            c.player.setYRot((float) yaw);
            if (!yawOnly) c.player.setXRot((float) pitch);
        } else {
            resetMotion();
        }
    }
    private void updatePoint(double dt, long now) {
        boolean multi = multipointSetting.getValue();
        boolean micro = microMovementsSetting.getValue();
        if (!multi && !micro) { pointX = pointY = pointZ = 0D; return; }
        if (now >= nextPointNs) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            nextX = multi ? r.nextDouble(-0.16D, 0.16D) : 0D;
            nextZ = multi ? r.nextDouble(-0.16D, 0.16D) : 0D;
            nextY = (multi ? r.nextDouble(-0.035D, 0.035D) : 0D)
                    + (micro ? r.nextDouble(-0.008D, 0.008D) : 0D);
            nextPointNs = now + r.nextLong(800_000_000L, 1_800_000_000L);
        }
        double alpha = -Math.expm1(-3D * dt);
        pointX += (nextX - pointX) * alpha;
        pointY += (nextY - pointY) * alpha;
        pointZ += (nextZ - pointZ) * alpha;
    }
    private Vec3 preferredPoint(Entity e) {
        AABB b = e.getBoundingBox();
        double fx = Mth.clamp(0.5D + pointX, 0.15D, 0.85D);
        double fy = Mth.clamp(aimHeightSetting.getValue() + pointY, 0.12D, 0.88D);
        double fz = Mth.clamp(0.5D + pointZ, 0.15D, 0.85D);
        return new Vec3(b.minX + (b.maxX - b.minX) * fx,
                b.minY + (b.maxY - b.minY) * fy, b.minZ + (b.maxZ - b.minZ) * fz);
    }
    private Vec3 basePoint(Entity e) {
        AABB b = e.getBoundingBox();
        return new Vec3((b.minX + b.maxX) * 0.5D,
                b.minY + (b.maxY - b.minY) * Mth.clamp(aimHeightSetting.getValue(), 0.12D, 0.88D),
                (b.minZ + b.maxZ) * 0.5D);
    }
    private Entity findTarget(Minecraft c) {
        if (target instanceof LivingEntity living && validCandidate(c, target, living)) return target;
        Entity best = null;
        double score = Double.POSITIVE_INFINITY;
        boolean health = prioritySetting.isSelected("Здоровье");
        for (Entity e : c.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !validCandidate(c, e, living)) continue;
            double distance = c.player.distanceToSqr(e);
            double value = health ? living.getHealth() + Math.min(distance, 36D) * 0.0001D : distance;
            if (value < score) { score = value; best = e; }
        }
        return best;
    }
    private boolean validCandidate(Minecraft c, Entity e, LivingEntity living) {
        if (!isValidTarget(c, e, living)) return false;
        if (c.player.distanceToSqr(e) > distanceSetting.getValue() * distanceSetting.getValue()) return false;
        Vec3 eye = c.player.getEyePosition(), point = basePoint(e);
        Vec3 d = point.subtract(eye);
        double len = d.length();
        if (len > 1E-7D) {
            double dot = c.player.getLookAngle().dot(d.scale(1D / len));
            if (Math.toDegrees(Math.acos(Mth.clamp(dot, -1D, 1D))) > fovSetting.getValue() * 0.5D) return false;
        }
        return !wallCheckSetting.getValue() || !blocked(c, eye, point)
                || !blocked(c, eye, e.getBoundingBox().getCenter());
    }
    private boolean isValidTarget(Minecraft c, Entity e, LivingEntity living) {
        if (e == c.player || !e.isAlive() || e.isSpectator() || living.getHealth() <= 0F) return false;
        if (onlyPlayersSetting.getValue() && !(e instanceof Player)) return false;
        if (!hitInvisibleSetting.getValue() && living.isInvisible()) return false;
        if (e instanceof Player p) {
            if (AntiBot.shouldIgnore(p)) return false;
            boolean armor = hasArmor(living);
            if ((onlyArmoredSetting.getValue() || ignoreNakedSetting.getValue()) && !armor) return false;
            if (FriendUtils.isFriend(p)) return targetType.isSelected("Друзья");
            return targetType.isSelected("Игроки") || (!armor && targetType.isSelected("Голые игроки"));
        }
        if (e instanceof Animal) return targetType.isSelected("Животные");
        if (e instanceof Mob) return targetType.isSelected("Мобы");
        if (e instanceof ArmorStand) return targetType.isSelected("Стойки брони");
        return false;
    }
    private boolean blocked(Minecraft c, Vec3 from, Vec3 to) {
        return c.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, c.player)).getType() != HitResult.Type.MISS;
    }
    private boolean hasArmor(LivingEntity e) {
        for (EquipmentSlot slot : ARMOR) if (!e.getItemBySlot(slot).isEmpty()) return true;
        return false;
    }
    private boolean isHoldingWeapon(Minecraft c) {
        Item item = c.player.getMainHandItem().getItem();
        return item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD
                || item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD
                || item instanceof AxeItem || item instanceof TridentItem || item instanceof MaceItem;
    }
    private static double limitStep(double step, double remaining) {
        if (Math.abs(remaining) < 0.002D || step * remaining <= 0D) return 0D;
        return Math.copySign(Math.min(Math.abs(step), Math.abs(remaining)), remaining);
    }
    private void drawFovRadius() {
        if (!drawFovRadiusSetting.getValue() || mc.player == null || mc.level == null || mc.screen != null || mc.getWindow() == null) return;
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        float radius = calculateFovRadius(width, height);
        if (radius < 1F) return;
        int color = target != null ? ColorUtil.rgba(127, 242, 255, 165) : ColorUtil.rgba(255, 255, 255, 95);
        Render2D.outline360(width * 0.5F - radius, height * 0.5F - radius, radius * 2F, radius * 2F,
                radius, 1.2F, color, Render2D.outline360Range(0F, 360F, color));
    }
    private float calculateFovRadius(int width, int height) {
        float max = Math.max(0F, Math.min(width, height) * 0.5F - 2F);
        double game = Mth.clamp(mc.options.fov().get(), 30D, 170D), aim = fovSetting.getValue();
        if (aim >= game) return max;
        double radius = Math.tan(Math.toRadians(Mth.clamp(aim * 0.5D, 0.1D, 89.9D)))
                / Math.tan(Math.toRadians(game * 0.5D)) * height * 0.5D;
        return (float) Mth.clamp(radius, 0D, (double) max);
    }
    private void resetMotion() {
        target = null;
        lastFrameNs = acquiredNs = nextPointNs = 0L;
        yawVelocity = pitchVelocity = pointX = pointY = pointZ = nextX = nextY = nextZ = 0D;
    }
    private void resetState() {
        world = player = null;
        lastRenderAimNs = 0L;
        resetMotion();
    }
}
