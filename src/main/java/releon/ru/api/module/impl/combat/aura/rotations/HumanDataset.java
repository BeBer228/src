package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import releon.ru.api.module.impl.combat.RotationRecorder;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.utils.chat.ChatMessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HumanDataset {
    public static final HumanDataset INSTANCE = new HumanDataset();

    private static final int F = 9;                 // признаков
    private static final int MIN_ROWS = 400;        // меньше - не используем
    private static final int K = 6;                 // из скольких похожих выбирать
    private static final float[] SCALE = {8.0F, 5.0F, 3.0F, 2.5F, 6.0F, 4.0F, 8.0F, 2.5F, 1.5F};

    private float[][] feat = new float[0][];
    private float[] outY = new float[0], outP = new float[0];
    private int[] seg = new int[0];
    private int size;
    private long lastCheck, lastModified = -1;
    private boolean announced;

    private int cursor = -1;
    private Entity lastTarget;
    private float prevTY, prevTP, lastDY, lastDP;
    private boolean hasPrevT;
    private float residY, residP;

    private HumanDataset() { }

    public boolean ready() {
        maybeReload();
        return size >= MIN_ROWS;
    }

    public int size() { return size; }

    public void reset() {
        cursor = -1;
        lastTarget = null;
        hasPrevT = false;
        lastDY = lastDP = 0.0F;
        residY = residP = 0.0F;
    }

    private void maybeReload() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 5000L) return;
        lastCheck = now;
        File f = RotationRecorder.datasetFile();
        long mod = f.exists() ? f.lastModified() + f.length() : 0;
        if (mod == lastModified) return;
        lastModified = mod;
        load(f);
    }

    private void load(File file) {
        List<float[]> rows = new ArrayList<>();
        List<Integer> segs = new ArrayList<>();
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String head = br.readLine();
                if (head != null) {
                    List<String> cols = java.util.Arrays.asList(head.split(","));
                    int cS = cols.indexOf("session"), cT = cols.indexOf("tick"), cDY = cols.indexOf("dYaw"), cDP = cols.indexOf("dPitch"),
                            cEY = cols.indexOf("eYaw"), cEP = cols.indexOf("ePitch"), cRY = cols.indexOf("tRateYaw"),
                            cRP = cols.indexOf("tRatePitch"), cTsa = cols.indexOf("tsa"), cDist = cols.indexOf("dist"),
                            cOn = cols.indexOf("onTarget");
                    String[] prev = null;
                    int segId = 0;
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] p = line.split(",");
                        if (p.length < cols.size()) { prev = null; continue; }
                        if (prev != null) {
                            boolean sameSession = prev[cS].equals(p[cS]);
                            boolean nextTick = Long.parseLong(p[cT]) == Long.parseLong(prev[cT]) + 1;
                            if (sameSession && nextTick) {
                                // состояние = прошлая строка, ответ руки = движение в этой строке
                                float[] r = new float[F + 2];
                                r[0] = Float.parseFloat(prev[cEY]);
                                r[1] = Float.parseFloat(prev[cEP]);
                                r[2] = Float.parseFloat(prev[cRY]);
                                r[3] = Float.parseFloat(prev[cRP]);
                                r[4] = Float.parseFloat(prev[cDY]);
                                r[5] = Float.parseFloat(prev[cDP]);
                                r[6] = Math.min(20, Integer.parseInt(prev[cTsa]));
                                r[7] = Float.parseFloat(prev[cDist]);
                                r[8] = Integer.parseInt(prev[cOn]);
                                r[9] = Float.parseFloat(p[cDY]);
                                r[10] = Float.parseFloat(p[cDP]);
                                if (Math.abs(r[9]) < 120.0F) {
                                    if (r[0] < 0) { r[0] = -r[0]; r[2] = -r[2]; r[4] = -r[4]; r[9] = -r[9]; }
                                    rows.add(r);
                                    segs.add(segId);
                                }
                            } else {
                                segId++;
                            }
                        }
                        prev = p;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        int n = rows.size();
        float[][] nf = new float[n][F];
        float[] ny = new float[n], np = new float[n];
        int[] ns = new int[n];
        for (int i = 0; i < n; i++) {
            float[] r = rows.get(i);
            for (int j = 0; j < F; j++) nf[i][j] = r[j] / SCALE[j];
            ny[i] = r[9];
            np[i] = r[10];
            ns[i] = segs.get(i);
        }
        feat = nf; outY = ny; outP = np; seg = ns; size = n;
        cursor = -1;
        if (!announced || n >= MIN_ROWS) {
            ChatMessage.brandmessage("SlothAi: датасет " + n + " тиков" + (n < MIN_ROWS ? " (мало, нужно " + MIN_ROWS + "+)" : ""));
            announced = true;
        }
    }

    private float dist2(int i, float[] q) {
        float[] f = feat[i];
        float s = 0;
        for (int j = 0; j < F; j++) { float d = f[j] - q[j]; s += d * d; }
        return s;
    }

    /**
     * @return новый угол или null, если датасета нет.
     */
    public Angle next(Angle current, Entity entity, int ticksSinceAttack, float gcd) {
        if (!ready() || entity == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        float yaw = current.getYaw(), pitch = current.getPitch();
        if (entity != lastTarget) {
            lastTarget = entity;
            hasPrevT = false;
            cursor = -1;
            lastDY = lastDP = 0.0F;
        }

        float[] tAng = RotationRecorder.targetAngle(mc.player.getEyePosition(), entity.getBoundingBox());
        float eY = Mth.wrapDegrees(tAng[0] - yaw);
        float eP = tAng[1] - pitch;
        float rY = hasPrevT ? Mth.wrapDegrees(tAng[0] - prevTY) : 0.0F;
        float rP = hasPrevT ? tAng[1] - prevTP : 0.0F;
        prevTY = tAng[0];
        prevTP = tAng[1];
        hasPrevT = true;
        boolean onT = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == entity;

        // зеркалим так же, как в датасете
        float mirror = eY < 0 ? -1.0F : 1.0F;
        float[] q = {
                eY * mirror / SCALE[0], eP / SCALE[1], rY * mirror / SCALE[2], rP / SCALE[3],
                lastDY * mirror / SCALE[4], lastDP / SCALE[5], Math.min(20, ticksSinceAttack) / SCALE[6],
                mc.player.distanceTo(entity) / SCALE[7], (onT ? 1 : 0) / SCALE[8]
        };

        // 1. ищем K самых похожих моментов
        int[] bestI = new int[K];
        float[] bestD = new float[K];
        java.util.Arrays.fill(bestI, -1);
        java.util.Arrays.fill(bestD, Float.MAX_VALUE);
        for (int i = 0; i < size; i++) {
            float d = dist2(i, q);
            if (d < bestD[K - 1]) {
                int p = K - 1;
                while (p > 0 && bestD[p - 1] > d) { bestD[p] = bestD[p - 1]; bestI[p] = bestI[p - 1]; p--; }
                bestD[p] = d; bestI[p] = i;
            }
        }

        // 2. продолжение текущей серии, если подходит (живые плавные движения)
        int pick = -1;
        int cont = cursor + 1;
        if (cursor >= 0 && cont < size && seg[cont] == seg[cursor]) {
            float dc = dist2(cont, q);
            if (dc <= bestD[0] * 2.2F + 0.35F) pick = cont;
        }
        if (pick < 0) {
            int found = 0;
            for (int i = 0; i < K; i++) if (bestI[i] >= 0) found++;
            if (found == 0) return null;
            // ближние выбираются чаще
            float total = 0;
            float[] w = new float[found];
            for (int i = 0; i < found; i++) { w[i] = 1.0F / (0.05F + bestD[i]); total += w[i]; }
            float r = ThreadLocalRandom.current().nextFloat() * total;
            pick = bestI[0];
            for (int i = 0; i < found; i++) { r -= w[i]; if (r <= 0) { pick = bestI[i]; break; } }
        }
        cursor = pick;

        // 3. движение твоей руки, подогнанное под текущую ошибку
        float recEY = feat[pick][0] * SCALE[0];
        float recEP = feat[pick][1] * SCALE[1];
        float dY = outY[pick];
        float dP = outP[pick];
        float absEY = Math.abs(eY);
        if (recEY > 1.5F && absEY > 1.5F) dY *= Mth.clamp(absEY / recEY, 0.75F, 1.35F);
        if (Math.abs(recEP) > 1.5F && Math.abs(eP) > 1.5F && Math.signum(recEP) == Math.signum(eP)) {
            dP *= Mth.clamp(Math.abs(eP) / Math.abs(recEP), 0.75F, 1.35F);
        }
        dY *= mirror;

        // 4. шаги мыши по твоей сенсе
        float wy = dY + residY, wp = dP + residP;
        int uy = Math.round(wy / gcd), up = Math.round(wp / gcd);
        residY = Mth.clamp(wy - uy * gcd, -gcd, gcd);
        residP = Mth.clamp(wp - up * gcd, -gcd, gcd);
        float outYaw = uy * gcd, outPitch = up * gcd;

        lastDY = outYaw;
        lastDP = outPitch;
        return new Angle(yaw + outYaw, Mth.clamp(pitch + outPitch, -90.0F, 90.0F)).adjustSensitivity();
    }
}
