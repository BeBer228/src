package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.player.BaritoneMovementHelper;
import java.util.HashSet;
import java.util.Set;

public class TriggerBot extends Module {
    private static TriggerBot instance;
    private final StrikerConstructor attackPerpetrator = new StrikerConstructor();
    public LivingEntity target;
    private int lastTick = Integer.MIN_VALUE;
    private Object lastPlayer, lastLevel;
    private long activatedAt;

    private final NumberSetting attackRange = register(new NumberSetting("Дальность атаки", "Дистанция луча атаки; сервер может ограничивать её сильнее.", 3.0D, 1.0D, 6.0D, 0.1D));
    private final NumberSetting attackDelay = register(new NumberSetting("Задержка атаки", "Дополнительная задержка сверх готовности оружия.", 0.0D, 0.0D, 1000.0D, 10.0D));
    private final MultiModeSetting targetType = register(new MultiModeSetting("Цели", "Фильтр типов целей.", new String[]{"Players", "Friends", "Mobs", "Animals", "Invisible", "Armor Stands"}, "Players", "Mobs", "Animals"));
    private final MultiModeSetting attackSetting = register(new MultiModeSetting("Опции", "Опции атаки.", new String[]{"Только криты", "Ломать щит", "Опустить щит", "Пауза при использовании", "Игнорировать стены", "Шанс попадания"}, "Только криты", "Ломать щит"));
    private final NumberSetting hitChance = register(new NumberSetting("Шанс попадания", "Шанс отправить допустимую атаку.", 100.0D, 1.0D, 100.0D, 1.0D));
    private final BooleanSetting smartCrits = register(new BooleanSetting("Умные криты", "Обычные удары без намерения прыгать.", false));
    private final BooleanSetting tpsSync = register(new BooleanSetting("Синхронизация TPS", "Учитывать оценку TPS в интервале обычных атак.", true));
    private final ModeSetting sprintReset = register(new ModeSetting("Сброс спринта", "Режим сброса спринта перед атакой.", "Легитный", "Легитный", "ReallyWorld"));

    public TriggerBot() {
        super("TriggerBot", "Автоматическая атака по цели под прицелом.", ModuleCategory.COMBAT);
        instance = this;
        hitChance.visibleWhen(() -> attackSetting.isSelected("Шанс попадания"));
        smartCrits.visibleWhen(() -> attackSetting.isSelected("Только криты"));
    }
    public static TriggerBot getInstance() { return instance; }
    @Override protected void onEnable() {
        activatedAt = System.currentTimeMillis();
        clearState();
    }
    @Override protected void onDisable() { clearState(); }
    private void clearState() {
        target = null;
        lastTick = Integer.MIN_VALUE;
        lastPlayer = lastLevel = null;
        attackPerpetrator.getAttackHandler().resetPendingState();
    }
    @SubscribeEvent private void onPreTick(TickEvent.Pre event) {
        Minecraft c = event.getClient();
        if (!isEnabled() || c == null || c.player == null || c.level == null) {
            target = null;
            lastTick = Integer.MIN_VALUE;
            return;
        }
        if (lastPlayer != c.player || lastLevel != c.level) {
            clearState();
            lastPlayer = c.player;
            lastLevel = c.level;
        }
        if (lastTick == c.player.tickCount) return;
        lastTick = c.player.tickCount;
        attackPerpetrator.tick();
        if (c.screen != null || !c.player.isAlive() || BaritoneMovementHelper.isBaritoneActive(c.player)) {
            target = null;
            attackPerpetrator.getAttackHandler().resetPendingState();
            return;
        }
        target = findRayTarget(c);
        if (target != null) attackPerpetrator.performTriggerAttack(getConfig(c), this);
    }
    @SubscribeEvent private void onPacket(PacketEvent event) {
        if (isEnabled()) attackPerpetrator.onPacket(event);
    }
    private LivingEntity findRayTarget(Minecraft c) {
        Vec3 eye = c.player.getEyePosition();
        double range = attackRange.getValue();
        Vec3 end = eye.add(c.player.getLookAngle().scale(range));
        double best = range * range;
        if (!isAttackSettingSelected("Игнорировать стены")) {
            HitResult block = c.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c.player));
            if (block.getType() != HitResult.Type.MISS) best = Math.min(best, eye.distanceToSqr(block.getLocation()));
        }
        TargetFinder.EntityFilter filter = new TargetFinder.EntityFilter(targetType.getValue());
        LivingEntity selected = null;
        for (Entity e : c.level.entitiesForRendering()) {
            if (e == c.player || !e.isAlive() || e.isSpectator() || !e.isPickable()) continue;
            Vec3 hit = e.getBoundingBox().contains(eye) ? eye : e.getBoundingBox().clip(eye, end).orElse(null);
            if (hit == null) continue;
            double d = eye.distanceToSqr(hit);
            if (d < best) {
                best = d;
                selected = e instanceof LivingEntity living && living.getHealth() > 0F
                        && filter.isValid(living) ? living : null;
            }
        }
        return selected;
    }
    public boolean isLookingAtTarget() {
        Minecraft c = Minecraft.getInstance();
        return target != null && c.player != null && c.level != null && c.screen == null
                && findRayTarget(c) == target;
    }
    public StrikerConstructor.AttackPerpetratorConfigurable getConfig(Minecraft c) {
        if (c == null || c.player == null || target == null) return null;
        Set<String> options = new HashSet<>();
        if (isAttackSettingSelected("Только криты")) options.add("Только криты");
        if (isAttackSettingSelected("Ломать щит")) options.add("Break Shield");
        if (isAttackSettingSelected("Опустить щит")) {
            options.add("Снимать свой щит");
            options.add("Ломать щит");
        }
        if (isAttackSettingSelected("Пауза при использовании")) options.add("Не бить при использовании");
        if (isAttackSettingSelected("Игнорировать стены")) options.add("Бить через стены");
        Angle camera = new Angle(c.player.getYRot(), c.player.getXRot());
        return new StrikerConstructor.AttackPerpetratorConfigurable(target, camera, attackRange.getFloat(),
                options, null, target.getBoundingBox(), isOnlyCrits(), tpsSync.getValue(), false);
    }
    public RotateConstructor getSmoothMode() { return LinearConstructor.INSTANCE; }
    private boolean isAttackSettingSelected(String... names) {
        for (String name : names) if (attackSetting.isSelected(name)) return true;
        return false;
    }
    public boolean shouldPreventSprinting() {
        Minecraft c = Minecraft.getInstance();
        return isEnabled() && target != null && c.player != null && c.screen == null
                && attackPerpetrator.getAttackHandler().shouldPrepareTriggerSprint(getConfig(c), this);
    }
    public boolean isResetSprintLegit() { return sprintReset.is("Легитный"); }
    public boolean isResetSprintPacket() { return false; }
    public boolean isRandomizeCrit() { return false; }
    public boolean isSmartCritsEnabled() { return isOnlyCrits() && smartCrits.getValue(); }
    public boolean isOnlyCrits() { return isAttackSettingSelected("Только криты"); }
    public int getAttackDelayMs() { return Math.max(0, attackDelay.getValue().intValue()); }
    public boolean shouldPassHitChance() {
        return !isAttackSettingSelected("Шанс попадания")
                || releon.ru.api.module.impl.combat.aura.util.MathUtils.getRandom(0F, 100F) < hitChance.getFloat();
    }
    public ModeSetting getSprintReset() { return sprintReset; }
    public long getActivationTimeMs() { return activatedAt; }
}
