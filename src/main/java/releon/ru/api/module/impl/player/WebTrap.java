package releon.ru.api.module.impl.player;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.chat.ChatMessage;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebTrap extends Module {

    private final MultiModeSetting targetType = register(new MultiModeSetting("Цели", "Фильтр типов целей.",
            new String[]{"Players", "Friends", "Mobs", "Animals", "Invisible"}, "Players", "Mobs", "Animals"));

    private final NumberSetting range = register(new NumberSetting("Дистанция", "Максимальная дистанция установки.", 4.5, 2.0, 6.0, 0.1));
    private final NumberSetting delay = register(new NumberSetting("Задержка", "Задержка между установками (мс).", 150.0, 0.0, 1000.0, 10.0));
    private final NumberSetting predictTicks = register(new NumberSetting("Предсказание", "На сколько тиков вперёд считать движение цели.", 3.0, 0.0, 10.0, 1.0));
    private final NumberSetting predictDistance = register(new NumberSetting("Дальность предикта", "Максимальное расстояние предсказания вперёд (блоков).", 1.5, 0.0, 3.0, 0.1));
    private final BooleanSetting pingPredict = register(new BooleanSetting("Учитывать пинг", "Добавлять к предсказанию ваш пинг.", true));
    private final BooleanSetting rotate = register(new BooleanSetting("Ротация", "Поворачиваться к цели/месту установки.", true));
    private final BooleanSetting ignoreWalls = register(new BooleanSetting("Бить через стены", "Искать цель без учёта стен.", false));
    private final BooleanSetting fromInventory = register(new BooleanSetting("Из инвентаря", "Брать паутину из инвентаря, если нет в хотбаре.", false));
    private final BooleanSetting onlyMoving = register(new BooleanSetting("Только бегущих", "Ставить только если цель двигается.", false));
    private final BooleanSetting debug = register(new BooleanSetting("Debug", "Писать в чат, что сейчас делает модуль.", true));

    private static final double MOVE_EPS = 0.03;

    private final TargetFinder targetFinder = new TargetFinder();
    private LivingEntity target;
    private Vec3 targetVelocity = Vec3.ZERO;
    private int trackedId = -1;
    private Vec3 lastTargetPos;
    private boolean aiming;

    private Placement desiredPlacement;
    private long lastPlace;
    private final Map<BlockPos, Long> recent = new HashMap<>();

    private String lastDebugState = "";
    private long lastDebugAt;

    public WebTrap() {
        super("WebTrap", "Ставит паутину туда, куда побежит цель", ModuleCategory.COMBAT);
    }

    @Override
    protected void onDisable() {
        target = null;
        desiredPlacement = null;
        targetVelocity = Vec3.ZERO;
        trackedId = -1;
        lastTargetPos = null;
        aiming = false;
        recent.clear();
        targetFinder.releaseTarget();
        AngleConnection.INSTANCE.startReturning();
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            target = null;
            desiredPlacement = null;
            targetFinder.releaseTarget();
            return;
        }

        if (event.getPhase() == EventPhase.PRE) {
            handlePre();
            return;
        }
        if (event.getPhase() == EventPhase.POST) {
            handlePost();
        }
    }

    private void handlePre() {
        long now = System.currentTimeMillis();
        recent.values().removeIf(t -> now - t > 600L);

        LivingEntity previousTarget = target;
        target = updateTarget();

        if (target == null) {
            desiredPlacement = null;
            trackedId = -1;
            lastTargetPos = null;
            if (previousTarget != null && aiming) {
                releaseAim();
            }
            debug("нет подходящей цели рядом");
            return;
        }

        updateVelocity(target);

        boolean canAct = true;
        String blockReason = null;
        if (!hasWebs()) {
            canAct = false;
            blockReason = "нет паутины (оффхенд/хотбар" + (fromInventory.getValue() ? "/инвентарь" : "") + ")";
        } else if (now - lastPlace < delay.getFloat()) {
            canAct = false;
            blockReason = "кулдаун задержки";
        } else {
            boolean moving = targetVelocity.horizontalDistanceSqr() > MOVE_EPS * MOVE_EPS;
            if (onlyMoving.getValue() && !moving) {
                canAct = false;
                blockReason = "цель стоит (включено \"Только бегущих\")";
            }
        }
        Placement placement = canAct ? findPlacement(target) : null;
        desiredPlacement = placement;

        if (placement != null) {
            if (rotate.getValue()) {
                rotateTowards(placement.hit);
            }
            aiming = true;
        } else {
            debug(blockReason != null ? blockReason : "цель найдена, но не нашёл блок под паутину (нет опоры рядом)");
            if (aiming) {
                releaseAim();
            }
        }
    }

    private void releaseAim() {
        aiming = false;
        if (mc.player == null) return;
        AngleConnection controller = AngleConnection.INSTANCE;
        controller.applyPlayerRotation(mc);
        controller.restoreVanillaLook();
    }

    private void updateVelocity(LivingEntity t) {
        if (t.getId() != trackedId || lastTargetPos == null) {
            trackedId = t.getId();
            lastTargetPos = t.position();
            targetVelocity = t.getDeltaMovement();
            return;
        }
        Vec3 pos = t.position();
        Vec3 sample = pos.subtract(lastTargetPos);
        lastTargetPos = pos;
        if (sample.lengthSqr() > 4.0) {
            // телепорт/респавн - не считаем это скоростью движения
            targetVelocity = t.getDeltaMovement();
            return;
        }
        targetVelocity = sample;
    }

    private void handlePost() {
        if (target == null || desiredPlacement == null) {
            return;
        }
        Placement placement = desiredPlacement;

        if (mc.screen != null) {
            debug("открыт экран/инвентарь, не ставлю");
            return;
        }
        if (!canPlaceAt(placement.pos)) {
            debug("место установки заблокировано (недавно ставил/занято)");
            return;
        }
        if (mc.player.getEyePosition().distanceTo(placement.hit) > range.getFloat()) {
            debug("точка установки дальше дистанции");
            return;
        }

        if (place(placement)) {
            lastPlace = System.currentTimeMillis();
            recent.put(placement.pos, lastPlace);
            desiredPlacement = null;
            debug("ставлю паутину на " + placement.pos.toShortString());
        } else {
            debug("не удалось взять паутину для установки");
        }
    }

    private void debug(String state) {
        if (!debug.getValue()) return;
        long now = System.currentTimeMillis();
        if (state.equals(lastDebugState) && now - lastDebugAt < 1000L) {
            return;
        }
        lastDebugState = state;
        lastDebugAt = now;
        ChatMessage.brandmessage("WebTrap: " + state);
    }

    private LivingEntity updateTarget() {
        TargetFinder.EntityFilter filter = new TargetFinder.EntityFilter(targetType.getValue());
        targetFinder.searchTargets(mc.level.entitiesForRendering(), range.getFloat() + 1.5F, 360.0F, ignoreWalls.getValue(), filter::isValid);
        targetFinder.validateTarget(filter::isValid);
        return targetFinder.getCurrentTarget();
    }

    private void rotateTowards(Vec3 aimPoint) {
        if (mc.player == null) return;
        Vec3 direction = aimPoint.subtract(mc.player.getEyePosition());
        Angle angle = MathAngle.fromVec3d(direction);
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(angle, direction),
                target,
                1,
                new AngleConfig(new LinearConstructor(), true, false),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    private boolean place(Placement p) {
        BlockHitResult hit = new BlockHitResult(p.hit, p.face, p.support, false);

        if (mc.player.getOffhandItem().is(Items.COBWEB)) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
            mc.player.swing(InteractionHand.OFF_HAND);
            return true;
        }

        int prev = mc.player.getInventory().getSelectedSlot();
        int hotbar = findWeb(0, 9);
        if (hotbar != -1) {
            if (hotbar != prev) InventoryUtils.syncSelectedHotbarSlot(hotbar);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (hotbar != prev) InventoryUtils.syncSelectedHotbarSlot(prev);
            return true;
        }

        if (fromInventory.getValue()) {
            int inv = findWeb(9, 36);
            if (inv != -1) {
                int slotId = InventoryUtils.wrapSlot(inv);
                InventoryUtils.click(slotId, prev, ClickType.SWAP);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                mc.player.swing(InteractionHand.MAIN_HAND);
                InventoryUtils.click(slotId, prev, ClickType.SWAP);
                InventoryUtils.syncSelectedHotbarSlot(prev);
                return true;
            }
        }

        return false;
    }

    private Placement findPlacement(LivingEntity t) {
        Vec3 pos = t.position();
        double ticks = predictTicks.getFloat() + (pingPredict.getValue() ? pingTicks() : 0.0);

        double maxDist = predictDistance.getFloat();
        Vec3 shift = new Vec3(targetVelocity.x, 0.0, targetVelocity.z).scale(ticks);
        double len = shift.length();
        if (len > maxDist && len > 0.0001) {
            shift = shift.scale(maxDist / len);
        }
        double dy = Mth.clamp(targetVelocity.y * ticks, -maxDist, 0.0);

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos predicted = BlockPos.containing(pos.x + shift.x, pos.y + dy + 0.01, pos.z + shift.z);
        candidates.add(predicted);

        BlockPos half = BlockPos.containing(pos.x + shift.x * 0.5, pos.y + dy * 0.5 + 0.01, pos.z + shift.z * 0.5);
        if (!candidates.contains(half)) candidates.add(half);

        BlockPos feet = t.blockPosition();
        if (!candidates.contains(feet)) candidates.add(feet);

        BlockPos above = feet.above();
        if (!candidates.contains(above)) candidates.add(above);

        for (BlockPos c : candidates) {
            if (!canPlaceAt(c)) continue;
            Placement p = supportFor(c);
            if (p != null) return p;
        }
        return null;
    }

    private Placement supportFor(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

        for (Direction dir : order) {
            BlockPos support = pos.relative(dir);
            BlockState state = mc.level.getBlockState(support);
            if (state.isAir() || state.canBeReplaced() || state.is(Blocks.COBWEB)) continue;

            Vec3 hit = Vec3.atCenterOf(pos).add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
            double d = eye.distanceTo(hit);
            if (d > range.getFloat()) continue;

            if (dir == Direction.DOWN) return new Placement(pos, support, dir.getOpposite(), hit);
            if (d < bestDist) {
                bestDist = d;
                best = new Placement(pos, support, dir.getOpposite(), hit);
            }
        }
        return best;
    }

    private boolean canPlaceAt(BlockPos pos) {
        if (recent.containsKey(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (!state.canBeReplaced() || state.is(Blocks.COBWEB) || !state.getFluidState().isEmpty()) return false;
        return !new AABB(pos).intersects(mc.player.getBoundingBox());
    }

    private double pingTicks() {
        if (mc.getConnection() == null) return 0.0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) return 0.0;
        return Mth.clamp(info.getLatency() / 50.0, 0.0, 6.0);
    }

    private boolean hasWebs() {
        if (mc.player.getOffhandItem().is(Items.COBWEB)) return true;
        if (findWeb(0, 9) != -1) return true;
        return fromInventory.getValue() && findWeb(9, 36) != -1;
    }

    private int findWeb(int from, int to) {
        for (int i = from; i < to; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.COBWEB)) return i;
        }
        return -1;
    }

    private record Placement(BlockPos pos, BlockPos support, Direction face, Vec3 hit) {
    }
}
