package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.RotationRecorder;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.utils.chat.ChatMessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Dataset replay with explicit continuity and reset rules.
 * Engineering revision, not an anti-cheat bypass claim.
 * Runtime attack timer is not target-local; tsa is deliberately not a feature.
 * Previous onTarget is not available identically at runtime; it is not a feature.
 */
public class SlothAI extends RotateConstructor {
    public static final SlothAI INSTANCE = new SlothAI();

    private static final int F = 9;
    private static final int MIN_ROWS = 400;
    private static final int K = 6;
    private static final float LOG_C = 2.0F;
    // Existing metric weights; not retrained or inferred from anti-cheat sources.
    // eYaw, ePitch, eBoxYaw, eBoxPitch, rateYaw, ratePitch, previous applied yaw/pitch, distance
    private static final float[] W = {1F, .75F, 2F, 1.5F, 1F, 1F, 1F, 1F, 1F / 2.5F};

    private static final class Data {
        final float[][] feat;
        final float[] outY, outP;
        final int[] next;
        final int size;

        Data(float[][] feat, float[] outY, float[] outP, int[] next) {
            this.feat = feat; this.outY = outY; this.outP = outP; this.next = next; this.size = feat.length;
        }
    }

    private volatile Data data;
    private volatile boolean loading;
    private Data activeData;
    private long lastCheck;
    private volatile long lastModified = -1, lastLength = -1;

    private Entity lastTarget;
    private Object lastPlayer, lastLevel;
    private boolean hasPrevT;
    private float prevTY, prevTP, lastDY, lastDP, residY, residP;
    private float previousInputYaw, previousInputPitch;
    private int cursor = -1;
    private int lastTick = Integer.MIN_VALUE;
    private Angle lastResult;

    public SlothAI() {
        super("Sloth AI");
    }

    public void reset() {
        lastTarget = null;
        lastPlayer = lastLevel = null;
        hasPrevT = false;
        lastDY = lastDP = residY = residP = 0.0F;
        cursor = -1;
        lastTick = Integer.MIN_VALUE;
        lastResult = null;
    }

    // ================= датасет =================

    private void maybeReload() {
        long now = System.currentTimeMillis();
        if (loading || now - lastCheck < 5000L) return;
        lastCheck = now;
        File f = RotationRecorder.datasetFile();
        long mod = f.exists() ? f.lastModified() : 0;
        long len = f.exists() ? f.length() : 0;
        if (mod == lastModified && len == lastLength) return;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                Data d = load(f);
                data = d;
                lastModified = mod;
                lastLength = len;
                Minecraft.getInstance().execute(() -> ChatMessage.brandmessage("SlothAI: в датасете " + d.size + " боевых тиков"
                        + (d.size < MIN_ROWS ? " (мало, нужно " + MIN_ROWS + "+)" : "")));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> ChatMessage.brandmessage("SlothAI: не смог прочитать датасет: " + e.getMessage()));
            } finally {
                loading = false;
            }
        }, "SlothAI-loader");
        t.setDaemon(true);
        t.start();
    }

    private static float tf(float x) {
        return Math.signum(x) * (float) Math.log1p(Math.abs(x) / LOG_C);
    }

    private static final String[] NEED = {
            "session", "tick", "seg", "valid", "targetId", "fight", "dYaw", "dPitch",
            "prevDYaw", "prevDPitch", "pitch", "t_ePreYaw", "t_ePrePitch", "t_halfW",
            "t_topPitch", "t_botPitch", "t_rateYaw", "t_ratePitch", "t_dist"
    };
    private static final int SES=0, TICK=1, SEG=2, VALID=3, TID=4, FIGHT=5,
            DY=6, DP=7, PDY=8, PDP=9, PITCH=10, EPY=11, EPP=12, HALFW=13,
            TOP=14, BOT=15, RY=16, RP=17, DIST=18;

    private static final class Row {
        final long session, tick, segment, target;
        final float[] v;
        Row(String[] p, int[] c) {
            session=Long.parseLong(p[c[SES]]);
            tick=Long.parseLong(p[c[TICK]]);
            segment=Long.parseLong(p[c[SEG]]);
            target=Long.parseLong(p[c[TID]]);
            v=new float[NEED.length];
            for (int j=VALID; j<NEED.length; j++) {
                if (j == TID) continue;
                v[j]=Float.parseFloat(p[c[j]]);
                if (!Float.isFinite(v[j])) throw new IllegalArgumentException("non-finite row");
            }
        }
    }
    private static boolean adjacent(Row a, Row b) {
        return a != null && b != null && b.session == a.session
                && b.segment == a.segment && b.target == a.target && b.tick == a.tick + 1;
    }
    private static Data emptyData() {
        return new Data(new float[0][], new float[0], new float[0], new int[0]);
    }
    private static Data load(File file) throws Exception {
        if (!file.exists()) return emptyData();
        List<float[]> feats=new ArrayList<>(), outs=new ArrayList<>();
        List<Integer> nexts=new ArrayList<>();
        try (BufferedReader br=new BufferedReader(new FileReader(file))) {
            String head=br.readLine();
            if (head == null) return emptyData();
            List<String> cols=Arrays.asList(head.replace("\uFEFF", "").split(",", -1));
            int[] c=new int[NEED.length];
            for (int i=0;i<c.length;i++) {
                c[i]=cols.indexOf(NEED[i]);
                if (c[i]<0) throw new IllegalStateException("нет колонки " + NEED[i]);
            }
            Row prev=null, lastAccepted=null;
            String line;
            while ((line=br.readLine()) != null) {
                Row row=null;
                String[] p=line.split(",", -1);
                if (p.length == cols.size()) {
                    try { row=new Row(p,c); } catch (RuntimeException ignored) { }
                }
                if (adjacent(prev,row) && prev.v[VALID] == 1 && row.v[VALID] == 1
                        && row.target >= 0 && row.v[FIGHT] == 1
                        && Math.abs(row.v[DY])<=180 && Math.abs(row.v[DP])<=180
                        && row.v[PITCH]>=-90 && row.v[PITCH]<=90
                        && row.v[HALFW]>=0 && row.v[TOP]<=row.v[BOT] && row.v[DIST]>=0) {
                    float[] r=row.v;
                    float eY=r[EPY], eP=r[EPP], m=eY<0 ? -1F : 1F;
                    float ebY=eY-Mth.clamp(eY,-r[HALFW],r[HALFW]);
                    float oldPitch=prev.v[PITCH];
                    float ebP=Mth.clamp(oldPitch,r[TOP],r[BOT])-oldPitch;
                    feats.add(new float[]{tf(eY*m)*W[0],tf(eP)*W[1],tf(ebY*m)*W[2],tf(ebP)*W[3],
                            tf(r[RY]*m)*W[4],tf(r[RP])*W[5],tf(r[PDY]*m)*W[6],tf(r[PDP])*W[7],r[DIST]*W[8]});
                    outs.add(new float[]{r[DY]*m,r[DP]});
                    int index=nexts.size();
                    if (adjacent(lastAccepted,row) && index>0) nexts.set(index-1,index);
                    nexts.add(-1);
                    lastAccepted=row;
                } else {
                    // No replay link may cross even one rejected source row.
                    lastAccepted=null;
                }
                prev=row;
            }
        }
        int n=feats.size();
        float[][] f=new float[n][];
        float[] y=new float[n],p=new float[n];
        int[] next=new int[n];
        for (int i=0;i<n;i++) {
            f[i]=feats.get(i); y[i]=outs.get(i)[0]; p[i]=outs.get(i)[1]; next[i]=nexts.get(i);
        }
        return new Data(f,y,p,next);
    }

    // ================= ротация =================

    private static float mouseGcd(Minecraft mc) {
        double sens = mc.options.sensitivity().get();
        float f = (float) (sens * 0.6 + 0.2);
        return Math.max(1.0E-4F, f * f * f * 8.0F * 0.15F);
    }

    private static float dist2(float[] a, float[] q) {
        float s = 0;
        for (int j = 0; j < F; j++) { float d = a[j] - q[j]; s += d * d; }
        return s;
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        AuraModule aura = AuraModule.getInstance();
        if (mc.player == null || mc.level == null || aura == null || entity == null || !entity.isAlive() || !aura.isEnabled() || mc.screen != null) {
            reset();
            return currentAngle;
        }
        if (lastPlayer != mc.player || lastLevel != mc.level) {
            reset(); lastPlayer=mc.player; lastLevel=mc.level;
        }
        maybeReload();
        int tickNow=mc.player.tickCount;
        if (tickNow == lastTick && lastResult != null) {
            // Target changes during a tick cannot cause a second step.
            return entity == lastTarget ? lastResult : currentAngle;
        }
        Data d=data;
        if (d != activeData) {
            reset();
            activeData=d;
            lastPlayer=mc.player; lastLevel=mc.level;
        }
        if (d == null || d.size < MIN_ROWS) { reset(); return currentAngle; }
        if (entity != lastTarget || (lastTick != Integer.MIN_VALUE && tickNow != lastTick+1)) {
            reset();
            lastTarget=entity;
            lastPlayer=mc.player; lastLevel=mc.level;
        }

        float yaw = currentAngle.getYaw(), pitch = currentAngle.getPitch();

        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) { reset(); return currentAngle; }
        // Observe the actually applied previous step instead of assuming our output was used.
        if (hasPrevT) {
            lastDY=Mth.wrapDegrees(yaw-previousInputYaw);
            lastDP=pitch-previousInputPitch;
        }
        previousInputYaw=yaw;
        previousInputPitch=pitch;

        // всё считается так же, как в RotationRecorder
        Vec3 eye = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        float[] t = RotationRecorder.targetAngle(eye, box);
        float eY = Mth.wrapDegrees(t[0] - yaw);
        float eP = t[1] - pitch;
        float rY = hasPrevT ? Mth.wrapDegrees(t[0] - prevTY) : 0.0F;
        float rP = hasPrevT ? t[1] - prevTP : 0.0F;
        prevTY = t[0];
        prevTP = t[1];
        hasPrevT = true;

        double cx = (box.minX + box.maxX) * 0.5 - eye.x, cz = (box.minZ + box.maxZ) * 0.5 - eye.z;
        double hd = Math.max(0.3, Math.sqrt(cx * cx + cz * cz));
        float halfW = (float) Math.toDegrees(Math.atan2((box.maxX - box.minX) * 0.5, hd));
        float topP = (float) -Math.toDegrees(Math.atan2(box.maxY - eye.y, hd));
        float botP = (float) -Math.toDegrees(Math.atan2(box.minY - eye.y, hd));
        float ebY = eY - Mth.clamp(eY, -halfW, halfW);
        float ebP = Mth.clamp(pitch, topP, botP) - pitch;

        float m = eY < 0 ? -1.0F : 1.0F;
        float[] q = {
                tf(eY * m) * W[0], tf(eP) * W[1], tf(ebY * m) * W[2], tf(ebP) * W[3],
                tf(rY * m) * W[4], tf(rP) * W[5], tf(lastDY * m) * W[6], tf(lastDP) * W[7],
                mc.player.distanceTo(entity) * W[8]
        };

        for (float v : q) if (!Float.isFinite(v)) { reset(); return currentAngle; }

        // 1. K самых похожих моментов из твоих боёв
        int[] bestI = new int[K];
        float[] bestD = new float[K];
        Arrays.fill(bestI, -1);
        Arrays.fill(bestD, Float.MAX_VALUE);
        for (int i = 0; i < d.size; i++) {
            float dd = dist2(d.feat[i], q);
            if (dd < bestD[K - 1]) {
                int p = K - 1;
                while (p > 0 && bestD[p - 1] > dd) { bestD[p] = bestD[p - 1]; bestI[p] = bestI[p - 1]; p--; }
                bestD[p] = dd; bestI[p] = i;
            }
        }
        if (bestI[0] < 0) return currentAngle;

        // 2. продолжение той же записанной серии, если она всё ещё подходит
        int pick = -1;
        int cont = cursor >= 0 && cursor < d.size ? d.next[cursor] : -1;
        if (cont >= 0 && cont < d.size
                && dist2(d.feat[cont], q) <= bestD[0] * 2.2F + 0.35F) {
            pick = cont;
        }
        if (pick < 0) {
            float total = 0;
            float[] w = new float[K];
            for (int i = 0; i < K; i++) if (bestI[i] >= 0) { w[i] = 1.0F / (0.05F + bestD[i]); total += w[i]; }
            float r = ThreadLocalRandom.current().nextFloat() * total;
            pick = bestI[0];
            for (int i = 0; i < K; i++) {
                if (bestI[i] < 0) continue;
                r -= w[i];
                if (r <= 0) { pick = bestI[i]; break; }
            }
        }
        cursor = pick;

        // 3. реальный шаг твоей руки + шаги мыши по сенсе
        float dY = d.outY[pick] * m;
        float dP = d.outP[pick];
        float gcd = mouseGcd(mc);
        float wy = dY + residY, wp = dP + residP;
        int uy = Math.round(wy / gcd), up = Math.round(wp / gcd);
        residY = Mth.clamp(wy - uy * gcd, -gcd, gcd);
        residP = Mth.clamp(wp - up * gcd, -gcd, gcd);
        float outYaw = uy * gcd, outPitch = up * gcd;
        lastDY = outYaw;
        lastDP = outPitch;

        lastTick = tickNow;
        float newPitch=Mth.clamp(pitch+outPitch,-90F,90F);
        // At the pitch boundary do not accumulate movement that was never applied.
        if (newPitch != pitch+outPitch) residP=0;
        lastDP=newPitch-pitch;
        // Already quantized once; avoid a second unknown sensitivity transform.
        lastResult = new Angle(yaw+outYaw,newPitch);
        return lastResult;
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
