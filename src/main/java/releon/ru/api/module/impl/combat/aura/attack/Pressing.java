package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import releon.ru.api.module.impl.combat.AuraModule;

/** Weapon cooldown AND restored minimum mode intervals.
 * Recalculate only after an ATTACK; a swing/slot change must not restart this timer.
 */
public class Pressing {
    public static final float READY_STRENGTH=0.9F; // Genuine critical readiness.
    private static final float NORMAL_STRENGTH=0.82F; // Original normal-attack gate.
    private static final int[] FUN_TIME_TICKS={10,11,10,12};
    private static final int[] SPOOKY_TICKS={10,11,12,13,12};
    private static final int[] DEFAULT_TICKS={10,11};
    private long lastAttackNano;

    public boolean isCooldownComplete(boolean tpsSync,float predictionTicks) {
        Minecraft client=Minecraft.getInstance();
        if (client.player==null || !Float.isFinite(predictionTicks)) return false;
        float ahead=Math.max(0F,predictionTicks);
        // Restore the original mace exception to the strength gate, not to all timing gates.
        boolean mace=client.player.getMainHandItem().getItem()==Items.MACE;
        if (!mace && !(client.player.getAttackStrengthScale(0.5F+ahead)>NORMAL_STRENGTH)) return false;
        if (lastAttackNano==0L) return true;
        double tickMs=1000D/(tpsSync ? tps() : 20F);
        // Restore the original patterns and their original '-1' adjustment.
        // A fist's short weapon cooldown can no longer bypass this lower bound.
        double requiredMs=Math.max(0D,minimumModeTicks()-ahead)*tickMs;
        // The original code still imposed elapsed weapon-delay time for the mace.
        float period=client.player.getCurrentItemAttackStrengthDelay();
        if (!Float.isFinite(period) || period<=0F) return false;
        requiredMs=Math.max(requiredMs,Math.max(0D,period*NORMAL_STRENGTH-ahead)*tickMs);
        // Prediction only prepares a future tick; attack execution must call with ahead=0.
        return lastClickPassed()>=requiredMs;
    }
    /** TriggerBot does not inherit Aura's fixed mode cadence. */
    public boolean isTriggerCooldownComplete(boolean tpsSync, float predictionTicks) {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null || !Float.isFinite(predictionTicks)) return false;
        float ahead = Math.max(0F, predictionTicks);
        if (!(c.player.getAttackStrengthScale(0.5F + ahead) > NORMAL_STRENGTH)) return false;
        if (lastAttackNano == 0L) return true;
        float period = c.player.getCurrentItemAttackStrengthDelay();
        if (!Float.isFinite(period) || period <= 0F) return false;
        double tickMs = 1000D / (tpsSync ? tps() : 20F);
        return lastClickPassed() >= Math.max(0D, period * NORMAL_STRENGTH - ahead) * tickMs;
    }
    private int minimumModeTicks() {
        AuraModule aura=AuraModule.getInstance();
        int count=0;String mode=null;
        if (aura!=null) {
            if (aura.getAttackHandlerRaw()!=null) count=aura.getAttackHandlerRaw().getCount();
            if (aura.isEnabled() && aura.getMode()!=null) mode=aura.getMode().getValue();
        }
        int[] pattern=DEFAULT_TICKS;
        if ("FunTime".equals(mode)) pattern=FUN_TIME_TICKS;
        else if ("SpookyTime".equals(mode) || "SpookyTime 1.21".equals(mode) || "SPAngle".equals(mode))
            pattern=SPOOKY_TICKS;
        return Math.max(1,pattern[Math.floorMod(count,pattern.length)]-1);
    }
    public boolean hasTicksElapsedSinceLastClick(int ticks) {
        return lastClickPassed()>=Math.max(0,ticks)*1000D/tps();
    }
    public long lastClickPassed() {
        return lastAttackNano==0L ? Long.MAX_VALUE : Math.max(0L,(System.nanoTime()-lastAttackNano)/1_000_000L);
    }
    public void recalculate() { lastAttackNano=System.nanoTime(); }
    public void reset() { lastAttackNano=0L; }
    private static float tps() {
        float value=AuraModule.activeTps();
        return Float.isFinite(value) ? Math.max(1F,Math.min(20F,value)) : 20F;
    }
}
