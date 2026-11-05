package releon.ru.api.module.impl.movement;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.phys.AABB;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.utils.network.Network;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.color.ColorUtil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Blink extends Module {
    private final List<Packet<?>> packets = new CopyOnWriteArrayList<>();
    private AABB box;
    public static int tickStop = -1;
    private int flushCooldown = 0;
    private final StopWatch timer = new StopWatch();

    public Blink() {
        super("Blink", "Задерживает пакеты для телепортации.", ModuleCategory.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        if (mc.player != null) {
            box = mc.player.getBoundingBox();
        }
    }

    @Override
    protected void onDisable() {
        for (Packet<?> packet : packets) {
            if (mc.getConnection() != null) {
                mc.getConnection().send(packet);
            }
        }
        packets.clear();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (--tickStop >= 0 && !packets.isEmpty()) {
            if (mc.player != null) {
                box = mc.player.getBoundingBox();
            }
            for (Packet<?> packet : packets) {
                if (mc.getConnection() != null) {
                    mc.getConnection().send(packet);
                }
            }
            packets.clear();
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        Packet<?> packet = event.getPacket();

        if (packet instanceof ClientboundRespawnPacket ||
                packet instanceof ClientboundLoginPacket) {
            setEnabled(false);
            return;
        }

        if (packet instanceof ClientboundPlayerInfoUpdatePacket infoPacket) {
        }

        if (event.isSend() && tickStop < 0) {
            packets.add(packet);
            event.setCancelled(true);
        }
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        if (box != null && mc.player != null) {
            AABB renderBox = box.move(-mc.player.getX(), -mc.player.getY(), -mc.player.getZ());
            int color = ColorUtil.rgba(85, 255, 255, 100);
            Render3D.drawBox(renderBox, color, 1.0f);
        }
    }
}