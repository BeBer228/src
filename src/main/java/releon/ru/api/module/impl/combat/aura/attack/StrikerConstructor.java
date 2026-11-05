package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.UsingItemEvent;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.settings.impl.ModeSetting;
import java.util.Set;

public class StrikerConstructor implements IMinecraft {
    private StrikeManager attackHandler=new StrikeManager();
    public void tick() { if (!blockedByGui()) getAttackHandler().tick(); }
    public void onPacket(PacketEvent event) { getAttackHandler().onPacket(event); }
    public void performAttack(AttackPerpetratorConfigurable c) {
        if (!blockedByGui()) getAttackHandler().handleAttack(c);
    }
    public void performTriggerAttack(AttackPerpetratorConfigurable c,TriggerBot trigger) {
        if (!blockedByGui()) getAttackHandler().handleTriggerAttack(c,trigger);
    }
    public void onUsingItem(UsingItemEvent event) { getAttackHandler().onUsingItem(event); }
    private boolean blockedByGui() {
        if (mc.screen==null) return false;
        AuraModule.target=null;
        getAttackHandler().resetPendingState();
        return true;
    }
    public StrikeManager getAttackHandler() {
        if (attackHandler==null) attackHandler=new StrikeManager();
        return attackHandler;
    }
    public static class AttackPerpetratorConfigurable {
        private final LivingEntity target;
        private final Angle angle;
        private final float maximumRange;
        private final boolean onlyCritical,shouldBreakShield,shouldUnPressShield,eatAndAttack;
        private final boolean multiPoints,ignoreWalls,tpsSync,legacyPvp;
        private final AABB box;
        private final ModeSetting aimMode;
        public AttackPerpetratorConfigurable(LivingEntity target,Angle angle,float maximumRange,
                Set<String> options,ModeSetting aimMode,AABB box) {
            this(target,angle,maximumRange,options,aimMode,box,
                options!=null && options.contains("Только криты"),false,false);
        }
        public AttackPerpetratorConfigurable(LivingEntity target,Angle angle,float maximumRange,
                Set<String> options,ModeSetting aimMode,AABB box,boolean onlyCritical) {
            this(target,angle,maximumRange,options,aimMode,box,onlyCritical,false,false);
        }
        public AttackPerpetratorConfigurable(LivingEntity target,Angle angle,float maximumRange,
                Set<String> options,ModeSetting aimMode,AABB box,boolean onlyCritical,
                boolean tpsSync,boolean legacyPvp) {
            Set<String> safe=options==null ? Set.of() : options;
            this.target=target;this.angle=angle;this.maximumRange=maximumRange;
            this.onlyCritical=onlyCritical;
            this.shouldBreakShield=safe.contains("Break Shield");
            // Preserve the old option for compatibility; add an accurately named alias.
            this.shouldUnPressShield=safe.contains("Ломать щит") || safe.contains("Снимать свой щит");
            this.eatAndAttack=safe.contains("Не бить при использовании");
            this.multiPoints=safe.contains("Мульти точки");this.ignoreWalls=safe.contains("Бить через стены");
            this.tpsSync=tpsSync;this.legacyPvp=legacyPvp;this.box=box;this.aimMode=aimMode;
        }
        public LivingEntity getTarget() { return target; }
        public Angle getAngle() { return angle; }
        public float getMaximumRange() { return maximumRange; }
        public boolean isOnlyCritical() { return onlyCritical; }
        public boolean isShouldBreakShield() { return shouldBreakShield; }
        public boolean isShouldUnPressShield() { return shouldUnPressShield; }
        public boolean isEatAndAttack() { return eatAndAttack; }
        public boolean isMultiPoints() { return multiPoints; }
        public boolean isIgnoreWalls() { return ignoreWalls; }
        public boolean isTpsSync() { return tpsSync; }
        public boolean isLegacyPvp() { return legacyPvp; }
        public AABB getBox() { return box; }
        public ModeSetting getAimMode() { return aimMode; }
    }
}
