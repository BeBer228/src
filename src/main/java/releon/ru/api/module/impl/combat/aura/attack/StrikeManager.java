package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.UsingItemEvent;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.api.module.impl.combat.MaceHelper;
import releon.ru.api.module.impl.combat.Criticals;
import releon.ru.api.module.impl.player.FreeCam;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.context.AutoRegressionContext;
import releon.ru.api.module.impl.combat.aura.target.RaycastAngle;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.utils.inventory.swap.SwapExecutor;
import releon.ru.utils.player.PlayerSimulation;
import releon.ru.mixin.accessor.LivingEntityAccessor;

/** Conservative critical synchronization. No position spoofing or artificial wall exemption.
 * Outgoing-packet observations are NOT server acknowledgments. See README for event-order limits.
 */
public class StrikeManager implements IMinecraft {
    private static final long CRIT_STARTUP_HOLD_MS=450L;
    private long nextAttackDelayMs=550L;
    private final StopWatch attackTimer=new StopWatch();
    private final Pressing clickScheduler=new Pressing();
    private final SwapExecutor shieldSwapExecutor=new SwapExecutor();
    private int count,lastAttackTick=Integer.MIN_VALUE;
    private Object contextPlayer,contextLevel;
    private int shieldSuppressedUntilTick=-1,lastShieldReleaseTick=-1;
    private boolean waitingForPostReleaseTick,waitingForSprintStop;
    private int sprintStopRequestedTick=Integer.MIN_VALUE;
    private int lastMovementTick=Integer.MIN_VALUE,lastTickEndTick=Integer.MIN_VALUE;
    private boolean positionKnown,reportedOnGround=true,reportedFalling;
    private double reportedY;
    private boolean sprintKnown,reportedSprint;
    private boolean maceAttackSpent;
    private void noteMaceAttack() {
        if (mc.player == null || mc.player.onGround() || !mc.player.getMainHandItem().is(Items.MACE)) return;
        maceAttackSpent = true;
        MaceHelper helper = MaceHelper.getInstance();
        if (helper != null) helper.onAuraAttack();
    }
    private boolean maceSmashReady() {
        MaceHelper helper = MaceHelper.getInstance();
        return !maceAttackSpent && (helper == null || !helper.blocksAuraAttack())
                && !mc.player.onGround() && !mc.player.isPassenger()
                && !mc.player.isInWater() && !mc.player.isFallFlying()
                && !mc.player.getAbilities().flying
                && mc.player.getMainHandItem().is(Items.MACE)
                && mc.player.fallDistance > 1.5F && mc.player.getDeltaMovement().y < 0D;
    }


    private boolean contextReady() {
        if (mc.player==null || mc.level==null) return false;
        if (contextPlayer!=mc.player || contextLevel!=mc.level) {
            resetPendingState();clickScheduler.reset();count=0;lastAttackTick=Integer.MIN_VALUE;
            contextPlayer=mc.player;contextLevel=mc.level;
            maceAttackSpent=false;
        }
        if (mc.player.onGround()) maceAttackSpent=false;
        return true;
    }
    void tick() {
        if (!contextReady()) return;
        if (shieldSwapExecutor.isRunning()) shieldSwapExecutor.tick();
        refreshShieldWait();syncShieldState();
    }
    void onPacket(PacketEvent e) {
        if (!e.isSend() || !contextReady()) return;
        Object p=e.getPacket();int tick=mc.player.tickCount;
        if (p instanceof ServerboundMovePlayerPacket move) {
            lastMovementTick=tick;
            reportedOnGround=move.isOnGround();
            if (move.hasPosition()) {
                double y=move.getY(mc.player.getY());
                reportedFalling=positionKnown && !reportedOnGround && y<reportedY;
                reportedY=y;positionKnown=true;
            }
            if (reportedOnGround) reportedFalling=false;
            refreshShieldWait();
        } else if (p instanceof ServerboundClientTickEndPacket) {
            lastTickEndTick=tick;refreshShieldWait();
        } else if (p instanceof ServerboundPlayerCommandPacket command) {
            if (command.getAction()==ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                sprintKnown=true;reportedSprint=true;
            } else if (command.getAction()==ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                sprintKnown=true;reportedSprint=false;waitingForSprintStop=false;
            }
        } else if (p instanceof ServerboundInteractPacket interact) {
            if (isAttack(interact)) {
                // Includes observed manual attacks; swing packets are NOT attacks.
                clickScheduler.recalculate();lastAttackTick=tick;noteMaceAttack();
            } else if (waitingForPostReleaseTick) e.cancel();
        }
    }
    void onUsingItem(UsingItemEvent e) { }
    public boolean unpressShield() { return false; } // Original public stub; real release stays in the attack stage.
    public void resetPendingState() {
        if (shieldSwapExecutor.isRunning()) shieldSwapExecutor.cancel();
        shieldSuppressedUntilTick=lastShieldReleaseTick=-1;
        waitingForPostReleaseTick=waitingForSprintStop=false;
        sprintStopRequestedTick=Integer.MIN_VALUE;
        lastMovementTick=lastTickEndTick=Integer.MIN_VALUE;
        positionKnown=false;reportedOnGround=true;reportedFalling=false;
        sprintKnown=reportedSprint=false;
    }
    public void syncShieldState() {
        if (mc.options==null || !mc.options.keyUse.isDown()) shieldSuppressedUntilTick=-1;
    }
    private void refreshShieldWait() {
        if (mc.player==null || !waitingForPostReleaseTick) return;
        int tick=mc.player.tickCount;
        if (tick>lastShieldReleaseTick &&
                (lastMovementTick>lastShieldReleaseTick || lastTickEndTick>lastShieldReleaseTick))
            waitingForPostReleaseTick=false;
    }
    public boolean shouldCancelShieldUse(InteractionHand hand) {
        if (mc.player==null || hand==null || mc.player.getItemInHand(hand).getItem()!=Items.SHIELD) return false;
        refreshShieldWait();syncShieldState();
        return waitingForPostReleaseTick || mc.player.tickCount<=shieldSuppressedUntilTick;
    }
    public boolean shouldCancelUseItemOn() { refreshShieldWait();return waitingForPostReleaseTick; }
    public boolean shouldCancelEntityInteraction() { refreshShieldWait();return waitingForPostReleaseTick; }

    public void handleAttack(StrikerConstructor.AttackPerpetratorConfigurable c) {
        if (!valid(c) || lastAttackTick==mc.player.tickCount) return;
        boolean soon=canAttack(c,1);
        if (soon && !preAttackEntity(c)) return;
        if (soon && requiresCritical(c.isOnlyCritical(),isSmartCritEnabled())) requestSprintStop();
        if (checkElytraMode(c) && !checkElytraRaycast(c)) return;
        if (shouldDelayRandomAttack(c) || !RaycastAngle.rayTrace(c) || !canAttack(c,0)) return;
        boolean strict=requiresCritical(c.isOnlyCritical(),isSmartCritEnabled());
        if (strict && !sprintStopped()) return;
        if (shouldDelayAttackAfterShieldRelease(c)) return;
        // Recheck the present state AFTER preparation/releasing items.
        if (!canAttack(c,0) || (strict && !sprintStopped())) return;
        attackEntity(c);
    }
    void handleTriggerAttack(StrikerConstructor.AttackPerpetratorConfigurable c,TriggerBot trigger) {
        if (trigger==null || !valid(c) || lastAttackTick==mc.player.tickCount) return;
        if (!attackTimer.finished(trigger.getAttackDelayMs())) return;
        boolean soon=canAttackTrigger(c,trigger,1);
        if (soon && !preAttackEntity(c)) return;
        boolean strict=requiresTriggerCritical(trigger);
        // Prepare sprint before the narrow falling window; do not send an attack early.
        if (shouldPrepareTriggerSprint(c, trigger)) requestSprintStop();
        if (!trigger.isLookingAtTarget() || !canAttackTrigger(c,trigger,0)) return;
        if ((strict || trigger.isResetSprintLegit()) && !sprintStopped()) return;
        if (shouldDelayAttackAfterShieldRelease(c) || !trigger.shouldPassHitChance()) return;
        if (!trigger.isLookingAtTarget() || !canAttackTrigger(c,trigger,0)) return;
        attackEntity(c);
    }
    public boolean shouldPrepareTriggerSprint(StrikerConstructor.AttackPerpetratorConfigurable c, TriggerBot t) {
        return t != null && valid(c) && t.isLookingAtTarget()
                && (t.isResetSprintLegit() || requiresTriggerCritical(t))
                && attackTimer.finished(t.getAttackDelayMs())
                && clickScheduler.isTriggerCooldownComplete(c.isTpsSync(), 1);
    }
    boolean preAttackEntity(StrikerConstructor.AttackPerpetratorConfigurable c) { return true; }
    private boolean valid(StrikerConstructor.AttackPerpetratorConfigurable c) {
        return contextReady() && mc.screen==null && mc.gameMode!=null && mc.options!=null && c!=null
            && c.getTarget()!=null && c.getTarget().isAlive()
            && Float.isFinite(c.getMaximumRange()) && c.getMaximumRange()>0;
    }
    private void requestSprintStop() {
        if (!waitingForSprintStop && (mc.player.isSprinting() || (sprintKnown && reportedSprint))) {
            waitingForSprintStop=true;sprintStopRequestedTick=mc.player.tickCount;
        }
        mc.options.keySprint.setDown(false);mc.player.setSprinting(false);
        // Let vanilla send STOP_SPRINTING; do NOT inject movement or attack from a packet callback.
    }
    private boolean sprintStopped() {
        // If sprint was only local and no START was sent, vanilla has no STOP to send.
        // Wait for a later movement/tick boundary instead of waiting forever for STOP.
        if (waitingForSprintStop && !mc.player.isSprinting() && (!sprintKnown || !reportedSprint)
                && (lastMovementTick>sprintStopRequestedTick || lastTickEndTick>sprintStopRequestedTick))
            waitingForSprintStop=false;
        return !mc.player.isSprinting() && !waitingForSprintStop && (!sprintKnown || !reportedSprint);
    }
    private boolean releaseShieldForAttack() {
        if (mc.player==null || mc.gameMode==null || !mc.player.isUsingItem()
                || mc.player.getUseItem().getItem()!=Items.SHIELD) return false;
        mc.gameMode.releaseUsingItem(mc.player);mc.player.stopUsingItem();
        lastShieldReleaseTick=mc.player.tickCount;
        shieldSuppressedUntilTick=lastShieldReleaseTick+1;
        waitingForPostReleaseTick=true;return true;
    }
    private boolean shouldDelayAttackAfterShieldRelease(StrikerConstructor.AttackPerpetratorConfigurable c) {
        if (!c.isShouldUnPressShield()) return false;
        refreshShieldWait();
        if (waitingForPostReleaseTick || lastShieldReleaseTick==mc.player.tickCount) return true;
        return releaseShieldForAttack();
    }
    public boolean canAttack(StrikerConstructor.AttackPerpetratorConfigurable c,int ticks) {
        if (!valid(c)) return false;
        for (int i=0;i<=Math.max(0,ticks);i++) if (canCrit(c,i)) return true;
        return false;
    }
    /** ticks>0 is PREPARATION only; final attacks always use ticks=0. */
    public boolean canCrit(StrikerConstructor.AttackPerpetratorConfigurable c,int ticks) {
        if (!valid(c) || ticks<0) return false;
        boolean strict=requiresCritical(c.isOnlyCritical(),isSmartCritEnabled());
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem()!=Items.SHIELD && c.isEatAndAttack()) return false;
        if (!clickScheduler.isCooldownComplete(c.isTpsSync(),ticks)) return false;
        if (!strict) return true;
        PlayerSimulation predicted=PlayerSimulation.simulateLocalPlayer(ticks);
        boolean smart=isSmartCritEnabled();
        if (predicted==null || shouldHoldCriticalOnStartup(predicted,smart,smart && jumpIntent())) return false;
        return criticalWindow(predicted,ticks,false);
    }
    private boolean canAttackTrigger(StrikerConstructor.AttackPerpetratorConfigurable c,TriggerBot trigger,int ticks) {
        for (int i=0;i<=Math.max(0,ticks);i++) if (canCritTrigger(c,trigger,i)) return true;
        return false;
    }
    private boolean canCritTrigger(StrikerConstructor.AttackPerpetratorConfigurable c,TriggerBot trigger,int ticks) {
        if (!valid(c) || trigger==null || ticks<0) return false;
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem()!=Items.SHIELD && c.isEatAndAttack()) return false;
        if (mc.player.getMainHandItem().is(Items.MACE) && !mc.player.onGround()) {
            MaceHelper helper = MaceHelper.getInstance();
            if (maceAttackSpent || (helper != null && helper.blocksAuraAttack())) return false;
            // The actual current falling state, not a predicted future fall.
            if (maceSmashReady()) return true;
        }
        boolean strict=requiresTriggerCritical(trigger);
        if (strict && ticks == 0 && (mc.player.onGround()
                || !(mc.player.fallDistance > 0F)
                || !(mc.player.getDeltaMovement().y < 0D))) return false;
        if (!clickScheduler.isTriggerCooldownComplete(c.isTpsSync(),ticks)) return false;
        if (!strict) return true;
        PlayerSimulation predicted=PlayerSimulation.simulateLocalPlayer(ticks);
        boolean smart=trigger.isSmartCritsEnabled();
        if (predicted==null || shouldHoldCriticalOnStartupTrigger(predicted,trigger,smart,smart && jumpIntent())) return false;
        return criticalWindow(predicted,ticks,true);
    }
    /** Strict TriggerBot mode never silently falls back to ordinary attacks.
     * Smart mode is the only explicit permission for ordinary grounded attacks.
     * Mace smash retains its separate existing path above.
     */
    private boolean requiresTriggerCritical(TriggerBot trigger) {
        return trigger != null && trigger.isOnlyCrits()
                && (!trigger.isSmartCritsEnabled() || jumpIntent());
    }
    private boolean requiresCritical(boolean onlyCrit,boolean smart) {
        if (!onlyCrit) return false;
        // Restore ordinary-attack fallbacks. These are permissions to attack, not proof of a crit.
        PlayerSimulation current=PlayerSimulation.simulateLocalPlayer(0);
        if (current!=null && allowsNormalFallback(current)) return false;
        // Smart explicitly permits a normal grounded attack with no jump intent.
        return !smart || jumpIntent();
    }
    private boolean jumpIntent() {
        if (mc.options.keyJump.isDown() || !mc.player.onGround()) return true;
        return mc.player instanceof LivingEntityAccessor accessor && accessor.releon$isJumping();
    }
    private boolean isSmartCritEnabled() {
        AuraModule aura=AuraModule.getInstance();
        return aura!=null && aura.isEnabled() && aura.getSmartCriticals().getValue();
    }
    private boolean criticalWindow(PlayerSimulation s,int ticks,boolean triggerPath) {
        if (!(mc.player.getAttackStrengthScale(0.5F+ticks)>Pressing.READY_STRENGTH)) return false;
        if (s==null || s.onGround || !(s.fallDistance>0F) || hasCriticalRestrictions(s)
                || mc.player.isPassenger()) return false;
        boolean window;
        if (triggerPath && isFreeCamMode()) window=isFreeCamCritWindow(s);
        else if (isInsideTightSpace(s)) window=isTightSpaceCriticalWindow(s);
        else window=isPlayerInCriticalState(s);
        if (!window) return false;
        if (ticks>0) return true;
        int now=mc.player.tickCount;
        return positionKnown && !reportedOnGround && reportedFalling
            && lastMovementTick>=(long)now-1 && lastMovementTick<=now;
    }
    private boolean allowsNormalFallback(PlayerSimulation s) {
        // Restore Criticals/cobweb compatibility. The module owns its packet behavior.
        if (criticalModuleCobwebException(s)) return true;
        return hasMovementRestrictions(s) || isAirAttackMode(s);
    }
    private boolean criticalModuleCobwebException(PlayerSimulation s) {
        Criticals module=Criticals.getInstance();
        return module!=null && module.isEnabled()
            && PlayerInteractionHelper.isBoxInBlock(s.boundingBox.inflate(-1e-3),Blocks.COBWEB);
    }
    private boolean hasMovementRestrictions(PlayerSimulation s) {
        return s.hasStatusEffect(MobEffects.BLINDNESS) || s.hasStatusEffect(MobEffects.LEVITATION)
            || PlayerInteractionHelper.isBoxInBlock(s.boundingBox.inflate(-1e-3),Blocks.COBWEB)
            || s.isSubmergedInWater() || s.isInLava() || s.isClimbing()
            || s.player.getAbilities().flying;
    }
    private boolean isAirAttackMode(PlayerSimulation s) {
        return s.isFallFlying || s.player.getAbilities().flying || mc.player.isFallFlying();
    }
    private boolean hasCriticalRestrictions(PlayerSimulation s) {
        return s.hasStatusEffect(MobEffects.BLINDNESS) || s.hasStatusEffect(MobEffects.LEVITATION)
            || s.isSubmergedInWater() || s.isInLava() || s.isClimbing() || s.isSwimming
            || s.isFallFlying || s.player.getAbilities().flying;
    }
    private boolean isInsideTightSpace(PlayerSimulation s) {
        return !PlayerInteractionHelper.canChangeIntoPose(Pose.STANDING,s.pos)
            || s.horizontalCollision || hasHorizontalBoxPressure(s) || countBlockingSides(s)>=2;
    }
    private boolean hasHorizontalBoxPressure(PlayerSimulation s) {
        if (mc.level==null || s==null) return false;
        AABB box=s.boundingBox.inflate(.22D,0D,.22D).deflate(1e-7D);
        return !mc.level.noCollision(s.player,box);
    }
    private int countBlockingSides(PlayerSimulation s) {
        int sides=0;
        if (isLateralSideBlocked(s,.72,0)) sides++;
        if (isLateralSideBlocked(s,-.72,0)) sides++;
        if (isLateralSideBlocked(s,0,.72)) sides++;
        if (isLateralSideBlocked(s,0,-.72)) sides++;
        return sides;
    }
    private boolean isLateralSideBlocked(PlayerSimulation s,double x,double z) {
        if (mc.level==null || s==null) return false;
        double foot=s.boundingBox.minY+.10,body=Math.min(s.boundingBox.maxY-.10,s.boundingBox.minY+.95);
        return isSolidCollisionBlock(BlockPos.containing(s.pos.x+x,foot,s.pos.z+z))
            || isSolidCollisionBlock(BlockPos.containing(s.pos.x+x,body,s.pos.z+z));
    }
    private boolean isSolidCollisionBlock(BlockPos pos) {
        if (mc.level==null) return false;
        BlockState state=mc.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(mc.level,pos).isEmpty();
    }
    private boolean isTightSpaceCriticalWindow(PlayerSimulation s) {
        if (isPvpTradeCriticalWindow(s)) return true;
        float fall=resolveCriticalFallDistance(s,true);
        double velocity=resolveCriticalVelocity(s,true);
        if (s.horizontalCollision || hasHorizontalBoxPressure(s)) {
            fall=Math.min(fall,.004F);velocity=Math.max(velocity,-.010D);
        }
        return !s.onGround && s.fallDistance>fall && s.velocity.y<velocity;
    }
    private boolean isPlayerInCriticalState(PlayerSimulation s) {
        if (s.onGround || hasCriticalRestrictions(s)) return false;
        if (isPvpTradeCriticalWindow(s)) return true;
        return s.fallDistance>resolveCriticalFallDistance(s,false)
            && s.velocity.y<resolveCriticalVelocity(s,false);
    }
    private boolean isPvpTradeCriticalWindow(PlayerSimulation s) {
        return isPvpPressureState(s) && !s.onGround && s.fallDistance>0F && s.velocity.y<-.01D;
    }
    private boolean isPvpPressureState(PlayerSimulation s) {
        return mc.player.hurtTime>0 || s.hasStatusEffect(MobEffects.SLOWNESS);
    }
    private float resolveCriticalFallDistance(PlayerSimulation s,boolean tight) {
        float fall=tight ? .01F : .03F;
        if (isPvpPressureState(s)) fall=Math.min(fall,tight ? .008F : .012F);
        if (s.horizontalCollision) fall=Math.min(fall,.012F);
        return fall;
    }
    private double resolveCriticalVelocity(PlayerSimulation s,boolean tight) {
        double velocity=tight ? -.02D : -.03D;
        if (isPvpPressureState(s)) velocity=Math.max(velocity,tight ? -.012D : -.018D);
        if (s.horizontalCollision) velocity=Math.max(velocity,-.015D);
        return velocity;
    }
    private boolean shouldHoldCriticalOnStartup(PlayerSimulation s,boolean smart,boolean intent) {
        AuraModule aura=AuraModule.getInstance();
        if (aura==null || !aura.isEnabled() || !aura.getOnlyCriticals().getValue()) return false;
        if (smart && !intent) return false;
        return startupHold(s,aura.getActivationTimeMs());
    }
    private boolean shouldHoldCriticalOnStartupTrigger(PlayerSimulation s,TriggerBot trigger,boolean smart,boolean intent) {
        if (trigger==null || !trigger.isEnabled() || !trigger.isOnlyCrits()) return false;
        if (smart && !intent) return false;
        return startupHold(s,trigger.getActivationTimeMs());
    }
    private boolean startupHold(PlayerSimulation s,long activated) {
        if (activated<=0 || System.currentTimeMillis()-activated>CRIT_STARTUP_HOLD_MS) return false;
        return !s.onGround && s.fallDistance<=0F && s.velocity.y>-.03D;
    }
    private boolean isFreeCamMode() {
        FreeCam f=FreeCam.getInstance();return f!=null && f.isEnabled();
    }
    private boolean isFreeCamCritWindow(PlayerSimulation s) {
        // Restore the freecam path without mistaking a floating camera for a falling body.
        return !s.onGround && s.fallDistance>0F && s.velocity.y<=-.01D && !hasCriticalRestrictions(s);
    }
    private int findAxeInHotbar() {
        for (int i=0;i<9;i++) {
            ItemStack stack=mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) return i;
        }
        return -1;
    }
    private Slot findAxeInInventory() {
        for (int i=9;i<=35;i++) {
            Slot slot=mc.player.inventoryMenu.getSlot(i);
            if (slot!=null && !slot.getItem().isEmpty() && slot.getItem().getItem() instanceof AxeItem) return slot;
        }
        return null;
    }
    void attackEntity(StrikerConstructor.AttackPerpetratorConfigurable c) {
        if (!valid(c) || lastAttackTick==mc.player.tickCount) return;
        mc.gameMode.attack(mc.player,c.getTarget());mc.player.swing(InteractionHand.MAIN_HAND);
        // Also update if the project's packet event subscription is disabled for this call.
        clickScheduler.recalculate();lastAttackTick=mc.player.tickCount;attackTimer.reset();count++;
        noteMaceAttack();
        if (isRandomAttackDelayMode(c)) nextAttackDelayMs=(long)MathUtils.getRandom(500,600);
        if (c.getAimMode()!=null && "FunTime".equals(c.getAimMode().getValue())) {
            AutoRegressionContext context=AutoRegressionContext.getInstance();
            context.updateLastAttackTime();context.hitContentQueue();
        }
    }
    private boolean shouldDelayRandomAttack(StrikerConstructor.AttackPerpetratorConfigurable c) {
        return isRandomAttackDelayMode(c) && count>0 && !attackTimer.finished(nextAttackDelayMs);
    }
    private boolean isRandomAttackDelayMode(StrikerConstructor.AttackPerpetratorConfigurable c) {
        if (c==null || c.getAimMode()==null) return false;
        String mode=c.getAimMode().getValue();return "SpookyTime".equals(mode) || "SPAngle".equals(mode);
    }
    private static boolean isAttack(ServerboundInteractPacket packet) {
        final boolean[] attack={false};
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override public void onInteraction(InteractionHand hand) { }
            @Override public void onInteraction(InteractionHand hand,Vec3 pos) { }
            @Override public void onAttack() { attack[0]=true; }
        });return attack[0];
    }
    private boolean checkElytraMode(StrikerConstructor.AttackPerpetratorConfigurable c) {
        ElytraTarget e=ElytraTarget.getInstance();
        return c.getTarget().isFallFlying() && mc.player.isFallFlying() && e!=null && e.isEnabled();
    }
    private boolean checkElytraRaycast(StrikerConstructor.AttackPerpetratorConfigurable c) {
        ElytraTarget e=ElytraTarget.getInstance();
        float lead=ElytraTarget.shouldElytraTarget && e!=null ? e.getForward() : 0;
        Vec3 pos=c.getTarget().position().add(c.getTarget().getDeltaMovement().scale(lead));
        float w=c.getTarget().getBbWidth()/2F,h=c.getTarget().getBbHeight();
        AABB box=new AABB(pos.x-w,pos.y,pos.z-w,pos.x+w,pos.y+h,pos.z+w);
        Vec3 eye=mc.player.getEyePosition(),look=AngleConnection.INSTANCE.getRotation().toVector();
        return box.clip(eye,eye.add(look.scale(c.getMaximumRange()))).isPresent();
    }
    public StopWatch getAttackTimer() { return attackTimer; }
    public int getCount() { return count; }
}
