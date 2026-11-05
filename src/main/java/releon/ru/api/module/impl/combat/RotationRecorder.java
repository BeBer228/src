package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.chat.ChatMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Пишет ВСЁ о твоём PvP каждый тик в один файл:
 *   .minecraft/titanium/rotation_dataset/dataset_full.csv
 *
 * ГЛАВНОЕ ДЛЯ РОТОК (одна строка = один тик, TickEvent.Pre):
 *   dYaw/dPitch        - шаг твоей руки за прошлый тик (это то, что ротка должна выдавать)
 *   t_ePreYaw/Pitch    - ошибка ДО этого шага: tYaw[t] - yaw[t-1]. Именно её видит ротка в limitAngleChange.
 *   t_eYaw/ePitch      - ошибка ПОСЛЕ шага (до точки 60% высоты, как targetAngle)
 *   t_eBoxYaw/Pitch    - ошибка до ближайшего края хитбокса (0 = прицел на хитбоксе)
 *   t_aimX/aimY        - где прицел относительно хитбокса: X -1..1 слева-направо, Y 0 верх..1 низ
 *   t_rateYaw/Pitch    - сдвиг угла цели за тик
 *   t_tsa              - тиков с твоего удара ИМЕННО по этой цели (100 = не бил)
 *   fight              - 1, если бил эту цель последние 40 тиков (реальный бой, а не просто кто-то рядом)
 *   seg / segTick      - номер непрерывного куска (та же цель, без телепортов/меню) и тик внутри него
 *   valid              - 0, если шаг нельзя учить (первый тик, телепорт/поворот сервером, меню, мышь не захвачена)
 *   gcd, dYawU/dPitchU - шаг мыши по сенсе и шаг руки в единицах мыши
 *
 * Если старый файл записан в другом формате (другие колонки) - он переименовывается,
 * новый файл начинается с правильным заголовком. Строки разных форматов не смешиваются.
 * Aura во время записи должна быть выключена.
 */
public final class RotationRecorder extends Module {
    private static final int OTHERS = 3;
    private static final int FIGHT_TICKS = 40;

    private final NumberSetting maxDistance = register(new NumberSetting(
            "Дистанция", "Игроки ближе этой дистанции попадают в запись.", 12.0, 3.0, 32.0, 0.5));
    private final BooleanSetting recordIdle = register(new BooleanSetting(
            "Писать без цели", "Записывать тики, когда рядом никого нет (движения мыши вне боя).", true));

    private BufferedWriter writer;
    private String existingHeader;   // заголовок уже лежащего файла (null = файл новый)
    private boolean headerChecked;
    private int session;
    private long tick;
    private int rows, sessionRows;

    // прошлый тик
    private float prevYaw, prevPitch, prevDYaw, prevDPitch;
    private boolean hasPrev;
    private float prevTY, prevTP;
    private int prevTargetId = -1;
    private long prevNano;

    // непрерывные куски для обучения
    private int seg;
    private int segTick;
    private int targetTicks;

    // события между тиками
    private int attacks, swings, movePackets, rotPackets;
    private boolean serverRotation;
    private float sentYaw = Float.NaN, sentPitch = Float.NaN;
    private int ticksSinceAttack = 100, ticksSinceSwing = 100;
    private int lastHitTargetId = -1;
    private int ticksSinceHitTarget = 100;   // тиков с удара по lastHitTargetId
    private boolean prevAttackKey;
    private int attackKeyPresses;

    public RotationRecorder() {
        super("Rotation Recorder", "Записывает всё о твоём PvP в один файл датасета.", ModuleCategory.COMBAT);
    }

    public static File datasetDir() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "titanium/rotation_dataset");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File datasetFile() {
        return new File(datasetDir(), "dataset_full.csv");
    }

    @Override
    protected void onEnable() {
        AuraModule aura = AuraModule.getInstance();
        if (aura != null && aura.isEnabled()) {
            ChatMessage.brandmessage("Recorder: выключи Aura! Пока она включена - запись на паузе.");
        }
        try {
            File f = datasetFile();
            boolean fresh = !f.exists() || f.length() == 0;
            existingHeader = fresh ? null : readHeader(f);
            session = fresh ? 1 : readLastSession(f) + 1;
            writer = new BufferedWriter(new FileWriter(f, true));
            headerChecked = false;
        } catch (IOException e) {
            writer = null;
            ChatMessage.brandmessage("Recorder: не удалось открыть файл: " + e.getMessage());
            return;
        }
        tick = 0;
        hasPrev = false;
        prevTargetId = -1;
        sessionRows = 0;
        seg++;
        segTick = 0;
        targetTicks = 0;
        ChatMessage.brandmessage("Recorder: сессия #" + session + " - пишу ВСЁ. Дерись сам.");
    }

    @Override
    protected void onDisable() {
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignored) { }
            writer = null;
        }
        ChatMessage.brandmessage("Recorder: сессия #" + session + " - " + sessionRows + " тиков. Файл: " + datasetFile().getAbsolutePath());
    }

    private static String readHeader(File f) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static int readLastSession(File f) {
        int last = 0;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long pos = Math.max(0, len - 65536);
            raf.seek(pos);
            byte[] buf = new byte[(int) (len - pos)];
            raf.readFully(buf);
            String[] lines = new String(buf).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (l.isEmpty() || l.startsWith("session")) continue;
                try { last = Integer.parseInt(l.substring(0, l.indexOf(','))); break; } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        return last;
    }

    // ================= пакеты =================

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (mc.player == null) return;
        Object p = event.getPacket();

        if (!event.isSend()) {
            // сервер сам повернул/телепортировал - этот шаг не твоя рука
            if (p instanceof ClientboundPlayerPositionPacket) serverRotation = true;
            return;
        }

        if (p instanceof ServerboundInteractPacket) {
            attacks++;
            ticksSinceAttack = 0;
            if (mc.hitResult instanceof EntityHitResult hit) {
                lastHitTargetId = hit.getEntity().getId();
                ticksSinceHitTarget = 0;
            }
        } else if (p instanceof ServerboundSwingPacket) {
            swings++;
            ticksSinceSwing = 0;
        } else if (p instanceof ServerboundMovePlayerPacket move) {
            movePackets++;
            if (move.hasRotation()) {
                rotPackets++;
                sentYaw = move.getYRot(mc.player.getYRot());
                sentPitch = move.getXRot(mc.player.getXRot());
            }
        }
    }

    // ================= тик =================

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || writer == null) return;
        tick++;

        boolean attackKey = mc.options.keyAttack.isDown();
        if (attackKey && !prevAttackKey) attackKeyPresses++;
        prevAttackKey = attackKey;

        AuraModule aura = AuraModule.getInstance();
        if (aura != null && aura.isEnabled()) { breakSeg(); hasPrev = false; clearEvents(); return; }

        long nano = System.nanoTime();
        float dtMs = prevNano == 0 ? 0.0F : (nano - prevNano) / 1.0E6F;
        prevNano = nano;

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        float dYaw = hasPrev ? Mth.wrapDegrees(yaw - prevYaw) : 0.0F;
        float dPitch = hasPrev ? pitch - prevPitch : 0.0F;

        boolean screen = mc.screen != null;
        boolean grabbed = mc.mouseHandler.isMouseGrabbed();
        boolean valid = hasPrev && !serverRotation && !screen && grabbed;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F);

        // ---- игроки рядом ----
        List<Player> near = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (mc.player.distanceTo(p) <= maxDistance.getValue()) near.add(p);
        }
        near.sort((a, b) -> Float.compare(angErr(eye, a, yaw, pitch), angErr(eye, b, yaw, pitch)));

        // ---- главная цель: на прицеле > кого бил последним > ближайший к прицелу ----
        Player target = null;
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player hp && near.contains(hp)) target = hp;
        if (target == null) for (Player p : near) if (p.getId() == lastHitTargetId && angErr(eye, p, yaw, pitch) < 90) { target = p; break; }
        if (target == null && !near.isEmpty()) target = near.get(0);

        int targetId = target == null ? -1 : target.getId();
        boolean targetChanged = targetId != prevTargetId;

        // ---- непрерывный кусок: та же цель, шаг валидный ----
        if (!valid || targetChanged) { seg++; segTick = 0; } else segTick++;
        targetTicks = targetChanged ? 0 : targetTicks + 1;

        if (target == null && !recordIdle.getValue()) { finishTick(yaw, pitch, dYaw, dPitch, targetId); return; }

        float gcd = mouseGcd();
        int tsaT = target != null && targetId == lastHitTargetId ? ticksSinceHitTarget : 100;

        Row r = new Row();
        r.i("session", session); r.l("tick", tick); r.l("time", System.currentTimeMillis()); r.f("dtMs", dtMs);
        r.i("valid", valid ? 1 : 0); r.i("seg", seg); r.i("segTick", segTick); r.i("teleport", serverRotation ? 1 : 0);

        // ---- я: поворот ----
        r.f("yaw", yaw); r.f("pitch", pitch); r.f("dYaw", dYaw); r.f("dPitch", dPitch);
        r.f("prevDYaw", prevDYaw); r.f("prevDPitch", prevDPitch);
        r.f("gcd", gcd); r.f("dYawU", dYaw / gcd); r.f("dPitchU", dPitch / gcd);
        r.f("headYaw", mc.player.getYHeadRot()); r.f("bodyYaw", mc.player.yBodyRot);
        r.f("sentYaw", sentYaw); r.f("sentPitch", sentPitch);
        r.i("movePk", movePackets); r.i("rotPk", rotPackets);

        // ---- я: клики/удары ----
        r.i("attack", attacks); r.i("swing", swings); r.i("tsa", ticksSinceAttack); r.i("tss", ticksSinceSwing);
        r.i("atkKey", attackKey ? 1 : 0); r.i("atkPress", attackKeyPresses); r.i("useKey", mc.options.keyUse.isDown() ? 1 : 0);
        r.f("cooldown", mc.player.getAttackStrengthScale(0.0F));
        r.s("item", BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).getPath());
        r.i("slot", mc.player.getInventory().getSelectedSlot());

        // ---- я: движение ----
        Input in = mc.player.input.keyPresses;
        r.i("kF", in.forward() ? 1 : 0); r.i("kB", in.backward() ? 1 : 0); r.i("kL", in.left() ? 1 : 0); r.i("kR", in.right() ? 1 : 0);
        r.i("kJump", in.jump() ? 1 : 0); r.i("kShift", in.shift() ? 1 : 0); r.i("kSprint", in.sprint() ? 1 : 0);
        Vec3 mv = mc.player.getDeltaMovement();
        r.f("x", (float) mc.player.getX()); r.f("y", (float) mc.player.getY()); r.f("z", (float) mc.player.getZ());
        r.f("vx", (float) mv.x); r.f("vy", (float) mv.y); r.f("vz", (float) mv.z);
        r.i("sprint", mc.player.isSprinting() ? 1 : 0); r.i("ground", mc.player.onGround() ? 1 : 0);
        r.i("crouch", mc.player.isShiftKeyDown() ? 1 : 0); r.f("fall", (float) mc.player.fallDistance);
        r.f("hp", mc.player.getHealth()); r.f("absorb", mc.player.getAbsorptionAmount()); r.i("armor", mc.player.getArmorValue());
        r.i("myHurt", mc.player.hurtTime); r.i("food", mc.player.getFoodData().getFoodLevel());

        // ---- прицел ----
        HitResult hr = mc.hitResult;
        r.s("hitType", hr == null ? "NONE" : hr.getType().name());
        r.i("hitEntityId", hr instanceof EntityHitResult eh ? eh.getEntity().getId() : -1);

        // ---- окружение ----
        r.f("sens", mc.options.sensitivity().get().floatValue()); r.i("fov", mc.options.fov().get());
        r.i("fps", mc.getFps()); r.i("ping", ping()); r.i("screen", screen ? 1 : 0); r.i("grabbed", grabbed ? 1 : 0);
        r.i("nearCount", near.size());

        // ---- главная цель (полностью) ----
        r.i("targetId", targetId);
        r.i("targetChanged", target != null && targetChanged ? 1 : 0);
        r.i("targetTicks", targetTicks);
        r.i("fight", target != null && tsaT < FIGHT_TICKS ? 1 : 0);
        writePlayer(r, "t_", target, eye, look, yaw, pitch, true, tsaT);

        // ---- ещё до 3 игроков ----
        int k = 0;
        for (Player p : near) {
            if (p == target) continue;
            if (k >= OTHERS) break;
            writePlayer(r, "o" + (k + 1) + "_", p, eye, look, yaw, pitch, false, 100);
            k++;
        }
        for (; k < OTHERS; k++) writePlayer(r, "o" + (k + 1) + "_", null, eye, look, yaw, pitch, false, 100);

        write(r);
        finishTick(yaw, pitch, dYaw, dPitch, targetId);
    }

    private void breakSeg() {
        seg++;
        segTick = 0;
        targetTicks = 0;
        prevTargetId = -1;
    }

    private void finishTick(float yaw, float pitch, float dYaw, float dPitch, int targetId) {
        prevYaw = yaw; prevPitch = pitch; prevDYaw = dYaw; prevDPitch = dPitch; hasPrev = true;
        prevTargetId = targetId;
        clearEvents();
    }

    private void clearEvents() {
        attacks = swings = movePackets = rotPackets = attackKeyPresses = 0;
        serverRotation = false;
        if (ticksSinceAttack < 100) ticksSinceAttack++;
        if (ticksSinceSwing < 100) ticksSinceSwing++;
        if (ticksSinceHitTarget < 100) ticksSinceHitTarget++;
    }

    // ================= игрок =================

    private void writePlayer(Row r, String p, Player e, Vec3 eye, Vec3 look, float yaw, float pitch, boolean main, int tsaT) {
        if (e == null) {
            String[] names = main ? MAIN_COLS : OTHER_COLS;
            for (String n : names) r.s(p + n, "");
            return;
        }
        AABB box = e.getBoundingBox();
        float[] ta = targetAngle(eye, box);
        float eY = Mth.wrapDegrees(ta[0] - yaw), eP = ta[1] - pitch;

        double cx = (box.minX + box.maxX) * 0.5 - eye.x, cz = (box.minZ + box.maxZ) * 0.5 - eye.z;
        double hd = Math.max(0.3, Math.sqrt(cx * cx + cz * cz));
        float halfW = (float) Math.toDegrees(Math.atan2((box.maxX - box.minX) * 0.5, hd));
        float halfH = (float) Math.toDegrees(Math.atan2((box.maxY - box.minY) * 0.5, hd));
        // угол до верха/низа хитбокса
        float topP = (float) -Math.toDegrees(Math.atan2(box.maxY - eye.y, hd));
        float botP = (float) -Math.toDegrees(Math.atan2(box.minY - eye.y, hd));

        r.i(p + "id", e.getId());
        r.f(p + "dist", mc.player.distanceTo(e));
        r.f(p + "eYaw", eY); r.f(p + "ePitch", eP);
        r.f(p + "tYaw", ta[0]); r.f(p + "tPitch", ta[1]);
        r.f(p + "halfW", halfW); r.f(p + "halfH", halfH);
        r.f(p + "vx", (float) (e.getX() - e.xo)); r.f(p + "vy", (float) (e.getY() - e.yo)); r.f(p + "vz", (float) (e.getZ() - e.zo));
        r.i(p + "hurt", e.hurtTime);
        r.f(p + "hp", e.getHealth());
        if (!main) return;

        // только для главной цели
        boolean same = e.getId() == prevTargetId;
        float rY = 0, rP = 0;
        if (same) { rY = Mth.wrapDegrees(ta[0] - prevTY); rP = ta[1] - prevTP; }
        prevTY = ta[0]; prevTP = ta[1];
        r.f(p + "rateYaw", rY); r.f(p + "ratePitch", rP);

        // ошибка ДО шага руки: куда цель сейчас, а прицел ещё там, где был прошлый тик
        if (same && hasPrev) {
            r.f(p + "ePreYaw", Mth.wrapDegrees(ta[0] - prevYaw));
            r.f(p + "ePrePitch", ta[1] - prevPitch);
        } else {
            r.s(p + "ePreYaw", ""); r.s(p + "ePrePitch", "");
        }

        // ошибка до ближайшего края хитбокса (0 = прицел внутри по этой оси)
        float yawIn = Mth.clamp(eY, -halfW, halfW);
        float pitchIn = Mth.clamp(pitch, topP, botP);
        r.f(p + "eBoxYaw", eY - yawIn);
        r.f(p + "eBoxPitch", pitchIn - pitch);
        // где прицел относительно хитбокса (работает и мимо него)
        r.f(p + "aimX", halfW > 1.0E-4F ? -eY / halfW : 0.0F);
        r.f(p + "aimY", botP - topP > 1.0E-4F ? (pitch - topP) / (botP - topP) : 0.5F);

        r.f(p + "topPitch", topP); r.f(p + "botPitch", botP);
        r.f(p + "x", (float) e.getX()); r.f(p + "y", (float) e.getY()); r.f(p + "z", (float) e.getZ());
        r.f(p + "yaw", e.getYRot()); r.f(p + "headYaw", e.getYHeadRot()); r.f(p + "pitch", e.getXRot());
        r.i(p + "ground", e.onGround() ? 1 : 0); r.i(p + "sprint", e.isSprinting() ? 1 : 0);
        r.i(p + "crouch", e.isShiftKeyDown() ? 1 : 0); r.i(p + "block", e.isBlocking() ? 1 : 0);
        r.i(p + "armor", e.getArmorValue());
        r.i(p + "tsa", tsaT);

        // куда прицел попадает на хитбоксе (0..1), или пусто, если мимо
        Optional<Vec3> clip = box.clip(eye, eye.add(look.scale(8.0)));
        r.i(p + "onTarget", clip.isPresent() ? 1 : 0);
        if (clip.isPresent()) {
            Vec3 h = clip.get();
            r.f(p + "hitFx", (float) ((h.x - box.minX) / (box.maxX - box.minX)));
            r.f(p + "hitFy", (float) ((h.y - box.minY) / (box.maxY - box.minY)));
            r.f(p + "hitFz", (float) ((h.z - box.minZ) / (box.maxZ - box.minZ)));
        } else {
            r.s(p + "hitFx", ""); r.s(p + "hitFy", ""); r.s(p + "hitFz", "");
        }
        // самое короткое расстояние луча прицела до хитбокса (в блоках) - насколько "мимо"
        r.f(p + "missDist", (float) rayBoxMiss(eye, look, box));
    }

    private static final String[] OTHER_COLS = {"id", "dist", "eYaw", "ePitch", "tYaw", "tPitch", "halfW", "halfH", "vx", "vy", "vz", "hurt", "hp"};
    private static final String[] MAIN_COLS = {"id", "dist", "eYaw", "ePitch", "tYaw", "tPitch", "halfW", "halfH", "vx", "vy", "vz", "hurt", "hp",
            "rateYaw", "ratePitch", "ePreYaw", "ePrePitch", "eBoxYaw", "eBoxPitch", "aimX", "aimY",
            "topPitch", "botPitch", "x", "y", "z", "yaw", "headYaw", "pitch",
            "ground", "sprint", "crouch", "block", "armor", "tsa", "onTarget", "hitFx", "hitFy", "hitFz", "missDist"};

    private static double rayBoxMiss(Vec3 eye, Vec3 look, AABB box) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= 40; i++) {
            Vec3 pt = eye.add(look.scale(i * 0.2));
            double dx = Math.max(Math.max(box.minX - pt.x, 0), pt.x - box.maxX);
            double dy = Math.max(Math.max(box.minY - pt.y, 0), pt.y - box.maxY);
            double dz = Math.max(Math.max(box.minZ - pt.z, 0), pt.z - box.maxZ);
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private float angErr(Vec3 eye, Entity e, float yaw, float pitch) {
        float[] a = targetAngle(eye, e.getBoundingBox());
        return (float) Math.hypot(Mth.wrapDegrees(a[0] - yaw), a[1] - pitch);
    }

    private float mouseGcd() {
        double sens = mc.options.sensitivity().get();
        float f = (float) (sens * 0.6 + 0.2);
        return Math.max(1.0E-4F, f * f * f * 8.0F * 0.15F);
    }

    private int ping() {
        if (mc.getConnection() == null) return 0;
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Центр хитбокса на 60% высоты (как раньше). */
    public static float[] targetAngle(Vec3 eye, AABB box) {
        double dx = (box.minX + box.maxX) * 0.5 - eye.x;
        double dy = box.minY + (box.maxY - box.minY) * 0.6 - eye.y;
        double dz = (box.minZ + box.maxZ) * 0.5 - eye.z;
        double hd = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, hd) * (180.0 / Math.PI));
        return new float[]{yaw, pitch};
    }

    // ================= запись =================

    private void write(Row r) {
        try {
            if (!headerChecked) {
                String head = r.head.toString();
                if (existingHeader != null && !existingHeader.equals(head)) {
                    // старый файл другого формата - откладываем его, начинаем новый
                    writer.close();
                    File f = datasetFile();
                    File old = new File(datasetDir(), "dataset_old_" + System.currentTimeMillis() + ".csv");
                    boolean moved = f.renameTo(old);
                    writer = new BufferedWriter(new FileWriter(f, !moved));
                    ChatMessage.brandmessage(moved
                            ? "Recorder: старый формат переименован в " + old.getName() + ", начат новый файл"
                            : "Recorder: не смог переименовать старый файл - удали его вручную");
                    if (!moved) { writer.close(); writer = null; return; }
                    session = 1;
                    existingHeader = null;
                }
                if (existingHeader == null) { writer.write(head); writer.newLine(); }
                headerChecked = true;
            }
            writer.write(r.vals.toString());
            writer.newLine();
            sessionRows++;
            if (++rows >= 100) { writer.flush(); rows = 0; }
            if (sessionRows % 2000 == 0) ChatMessage.brandmessage("Recorder: " + sessionRows + " тиков");
        } catch (IOException e) {
            ChatMessage.brandmessage("Recorder: ошибка записи: " + e.getMessage());
        }
    }

    private static final class Row {
        final StringBuilder head = new StringBuilder(4096), vals = new StringBuilder(4096);

        private void add(String n, String v) {
            if (head.length() > 0) { head.append(','); vals.append(','); }
            head.append(n); vals.append(v);
        }

        void f(String n, float v) { add(n, Float.isNaN(v) || Float.isInfinite(v) ? "" : String.format(Locale.ROOT, "%.4f", v)); }
        void i(String n, int v) { add(n, Integer.toString(v)); }
        void l(String n, long v) { add(n, Long.toString(v)); }
        void s(String n, String v) { add(n, v.replace(',', '_')); }
    }
}
