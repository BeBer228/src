package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Requires BackTrackReceiveMixin. No outgoing packets or server hitboxes are changed.
 * The FIFO and entity position prediction are confined to the Minecraft client thread.
 */
public final class BackTrack extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger("Releon/BackTrack");
    private static final int MAX_PENDING = 512;
    private static final ThreadLocal<Integer> REPLAY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static BackTrack instance;

    public final NumberSetting delayMs = register(new NumberSetting(
            "Задержка", "Задержка входящих пакетов движения цели, мс.", 200.0, 10.0, 600.0, 10.0));

    private final Deque<DelayedPacket> pending = new ArrayDeque<>();
    private final VecDeltaCodec shadowPosition = new VecDeltaCodec();
    private final AtomicLong lifecycle = new AtomicLong();
    private volatile boolean active;
    private boolean faulted;
    private ClientLevel ownerLevel;
    private LocalPlayer ownerPlayer;
    private ClientPacketListener ownerListener;
    private LivingEntity tracked;
    private Vec3 serverPosition;

    public BackTrack() {
        super("BackTrack", "Задерживает обработку движения текущей цели Aura.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static BackTrack getInstance() { return instance; }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        long generation = lifecycle.incrementAndGet();
        active = false;
        runOnClient(mc, () -> {
            if (generation != lifecycle.get()) return;
            flushAndClear(mc);
            faulted = false;
            active = true;
        });
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        active = false;
        long generation = lifecycle.incrementAndGet();
        runOnClient(mc, () -> {
            if (generation == lifecycle.get()) flushAndClear(mc);
        });
    }

    @Override
    public void onTick(Minecraft mc) {
        if (!mc.isSameThread()) return;
        if (!active || faulted || !isEnabled()) return;
        if (!refreshContextAndTarget(mc)) return;
        releaseExpired(mc, System.nanoTime());
    }

    /** True means the mixin must cancel vanilla handling of THIS movement packet. */
    public static boolean interceptIncoming(Packet<?> packet, ClientPacketListener source) {
        Minecraft mc = Minecraft.getInstance();
        BackTrack module = instance;
        if (!mc.isSameThread() || REPLAY_DEPTH.get() != 0 || module == null
                || !module.active || module.faulted || !module.isEnabled()
                || source != mc.getConnection()) return false;
        return module.intercept(mc, packet);
    }

    /** Flush movement before removal packets or a local-player position correction. */
    public static void beforeVanillaBarrier(ClientPacketListener source) {
        Minecraft mc = Minecraft.getInstance();
        BackTrack module = instance;
        if (module == null || !mc.isSameThread() || REPLAY_DEPTH.get() != 0 || source != mc.getConnection()) return;
        module.flushAndClear(mc);
    }

    /** A world/session boundary invalidates old packets instead of replaying them into a new world. */
    public static void beforeWorldReset(ClientPacketListener source) {
        Minecraft mc = Minecraft.getInstance();
        BackTrack module = instance;
        if (module != null && mc.isSameThread() && (source == module.ownerListener || source == mc.getConnection())) {
            module.discardAndClear();
        }
    }

    private boolean intercept(Minecraft mc, Packet<?> packet) {
        if (!isMovement(packet) || !refreshContextAndTarget(mc)) return false;
        Entity entity = packetEntity(mc, packet);
        if (entity != tracked) return false;

        releaseExpired(mc, System.nanoTime());
        if (faulted || tracked == null) return false;

        // Relative teleports also transform rotation/motion and use live pose state.
        // Do not reconstruct only XYZ and pretend that the rest of their semantics is preserved.
        // Flush predecessors and let vanilla process the relative teleport immediately.
        if (packet instanceof ClientboundTeleportEntityPacket teleport && !teleport.relatives().isEmpty()) {
            releaseAll(mc);
            serverPosition = null;
            return false;
        }

        if (pending.isEmpty()) shadowPosition.setBase(tracked.getPositionCodec().getBase());
        Vec3 next = nextPosition(packet);
        if (!finite(next)) {
            flushAndClear(mc);
            return false;
        }
        shadowPosition.setBase(next);
        serverPosition = next;

        if (!shouldDelay(mc, next)) {
            // Never let a newer movement packet overtake older delayed movements.
            releaseAll(mc);
            return false;
        }
        if (pending.size() >= MAX_PENDING) {
            releaseAll(mc);
            return false;
        }
        pending.addLast(new DelayedPacket(packet, System.nanoTime()));
        return true;
    }

    private boolean refreshContextAndTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            discardAndClear();
            return false;
        }
        if (ownerLevel != mc.level || ownerPlayer != mc.player || ownerListener != mc.getConnection()) {
            discardAndClear();
            ownerLevel = mc.level; ownerPlayer = mc.player; ownerListener = mc.getConnection();
        }
        AuraModule aura = AuraModule.getInstance();
        LivingEntity next = aura != null && aura.isEnabled() ? AuraModule.target : null;
        if (next == mc.player || next != null && (!next.isAlive() || mc.level.getEntity(next.getId()) != next)) next = null;
        if (next != tracked) {
            releaseAll(mc);
            tracked = next;
            serverPosition = null;
            if (tracked != null) shadowPosition.setBase(tracked.getPositionCodec().getBase());
        }
        return tracked != null && !faulted;
    }

    private boolean shouldDelay(Minecraft mc, Vec3 receivedPosition) {
        AuraModule aura = AuraModule.getInstance();
        if (aura == null || tracked == null) return false;
        if (mc.player.isInWater() || mc.player.isUnderWater() || tracked.isInWater() || tracked.isUnderWater()) return false;
        double range = aura.attackDistance();
        if (!Double.isFinite(range) || range <= 0.0D) return false;
        double currentDistance = mc.player.position().distanceTo(tracked.position());
        double receivedDistance = mc.player.position().distanceTo(receivedPosition);
        // Preserves the actual condition in the supplied source, not a reversed "retreat" condition.
        return currentDistance > range && receivedDistance < range + 1.5D;
    }

    private Vec3 nextPosition(Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket movement) {
            return movement.hasPosition()
                    ? shadowPosition.decode(movement.getXa(), movement.getYa(), movement.getZa())
                    : shadowPosition.getBase();
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) return sync.values().position();
        if (packet instanceof ClientboundTeleportEntityPacket teleport) return teleport.change().position();
        return null;
    }

    private void releaseExpired(Minecraft mc, long now) {
        double delay = delayMs.getValue();
        if (!Double.isFinite(delay)) delay = 200.0D;
        long duration = (long) (Math.max(10.0D, Math.min(600.0D, delay)) * 1_000_000.0D);
        while (!pending.isEmpty() && now - pending.peekFirst().receivedNanos >= duration) {
            DelayedPacket entry = pending.removeFirst();
            if (!apply(mc, entry.packet)) break;
        }
    }

    private void releaseAll(Minecraft mc) {
        if (!canReplay(mc)) { pending.clear(); return; }
        while (!pending.isEmpty()) {
            DelayedPacket entry = pending.removeFirst();
            if (!apply(mc, entry.packet)) break;
        }
    }

    private boolean apply(Minecraft mc, Packet<?> packet) {
        if (!canReplay(mc)) { pending.clear(); return false; }
        int depth = REPLAY_DEPTH.get();
        REPLAY_DEPTH.set(depth + 1);
        try {
            // Retain vanilla entity interpolation, rotation, grounded state, and position-codec updates.
            if (packet instanceof ClientboundMoveEntityPacket p) p.handle(ownerListener);
            else if (packet instanceof ClientboundEntityPositionSyncPacket p) p.handle(ownerListener);
            else if (packet instanceof ClientboundTeleportEntityPacket p) p.handle(ownerListener);
            return true;
        } catch (RuntimeException exception) {
            faulted = true;
            pending.clear();
            LOGGER.error("BackTrack stopped: could not replay movement packet", exception);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(
                    "§c[BackTrack] Ошибка обработки пакета. Модуль приостановлен; выключите его и проверьте соединение."), false);
            return false;
        } finally {
            if (depth == 0) REPLAY_DEPTH.remove(); else REPLAY_DEPTH.set(depth);
        }
    }

    private boolean canReplay(Minecraft mc) {
        return mc.isSameThread() && ownerLevel != null && mc.level == ownerLevel && mc.player == ownerPlayer
                && ownerListener != null && mc.getConnection() == ownerListener && tracked != null
                && ownerLevel.getEntity(tracked.getId()) == tracked;
    }

    private void flushAndClear(Minecraft mc) {
        releaseAll(mc);
        discardAndClear();
    }

    private void discardAndClear() {
        pending.clear(); tracked = null; serverPosition = null;
        ownerLevel = null; ownerPlayer = null; ownerListener = null;
        shadowPosition.setBase(Vec3.ZERO);
    }

    /** Latest decoded received position, not an independent real-time server query. */
    public Vec3 getServerPosition() {
        Minecraft mc = Minecraft.getInstance();
        return active && !faulted && !pending.isEmpty() && canReplay(mc) ? serverPosition : null;
    }

    /** World-space AABB for a future renderer; no camera-relative offset is applied here. */
    public AABB getServerBox() {
        Vec3 p = getServerPosition();
        return p == null ? null : tracked.getBoundingBox().move(p.subtract(tracked.position()));
    }

    public int getDelayedPacketCount() { return pending.size(); }

    private static Entity packetEntity(Minecraft mc, Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket p) return p.getEntity(mc.level);
        if (packet instanceof ClientboundEntityPositionSyncPacket p) return mc.level.getEntity(p.id());
        if (packet instanceof ClientboundTeleportEntityPacket p) return mc.level.getEntity(p.id());
        return null;
    }

    private static boolean isMovement(Packet<?> p) {
        return p instanceof ClientboundMoveEntityPacket || p instanceof ClientboundEntityPositionSyncPacket
                || p instanceof ClientboundTeleportEntityPacket;
    }

    private static boolean finite(Vec3 p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }

    private static void runOnClient(Minecraft mc, Runnable action) {
        if (mc.isSameThread()) action.run(); else mc.execute(action);
    }

    private record DelayedPacket(Packet<?> packet, long receivedNanos) { }
}
