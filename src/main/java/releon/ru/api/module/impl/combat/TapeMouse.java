package releon.ru.api.module.impl.combat;

import releon.ru.mixin.accessor.KeyMappingAccessor;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.concurrent.ThreadLocalRandom;
/** Clicks through the vanilla key queue (KeyMapping.click), so every click goes through the normal attack/use path of the tick. */
public class TapeMouse extends Module {

    private final ModeSetting mode = register(new ModeSetting(
            "Button", "Mouse button to emulate", "Left", "Left", "Right"
    ));


    private final NumberSetting cps = register(new NumberSetting(
            "CPS", "Target clicks per second", 12.0, 1.0, 30.0, 0.5
    ));

    private final BooleanSetting cooldown = register(new BooleanSetting(
            "Respect Cooldown", "Left: wait for the 1.9 attack cooldown before clicking an entity", true
    ));

    private final BooleanSetting checkEntity = register(new BooleanSetting(
            "Check Entity", "Only click when an entity is under the crosshair", false
    ));

    private final BooleanSetting onlyWeapon = register(new BooleanSetting(
            "Only Weapon", "Only click when holding a weapon", false
    ));

    private final BooleanSetting breakBlocks = register(new BooleanSetting(
            "Break Blocks", "Destroy block under cursor on left click", false
    ));

    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Add random jitter to click intervals", true
    ));

    private final NumberSetting randomStrength = register(new NumberSetting(
            "Random Strength", "Random CPS jitter strength", 2.0, 0.0, 5.0, 0.1
    ));

    private long nextClickTime = 0L;
    private double drift;

    public TapeMouse() {
        super("TapeMouse", "Simulates continuous mouse clicking with randomized CPS jitter.", ModuleCategory.COMBAT);
        randomStrength.setVisible(randomization::getValue);
        breakBlocks.setVisible(() -> mode.is("Left"));
        onlyWeapon.setVisible(() -> mode.is("Left"));
        cooldown.setVisible(() -> mode.is("Left"));
    }

    @Override
    public void onEnable() {
        // a human needs a moment to start clicking
        nextClickTime = System.currentTimeMillis() + nextDelay();
        drift = 0.0;
    }

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui != null && mc.screen != null) return;

        boolean left = mode.is("Left");
        long now = System.currentTimeMillis();
        if (!canClick(left)) {
            nextClickTime = Math.max(nextClickTime, now + 40L);
            return;
        }
        KeyMapping key = left ? mc.options.keyAttack : mc.options.keyUse;
        // a click due earlier than now is queued for this tick; never more than two per tick, like a real fast clicker
        if (now >= nextClickTime) {
            KeyMapping.click(((KeyMappingAccessor) key).releon$getKey());
            nextClickTime = now + nextDelay();
        }
        if (now >= nextClickTime) nextClickTime = now + nextDelay();
    }

    private boolean canClick(boolean left) {
        if (!(left ? mc.options.keyAttack : mc.options.keyUse).isDown()) return false;
        if (left && mc.player.isUsingItem()) return false; // vanilla does not attack while eating/blocking either
        if (left && onlyWeapon.getValue() && !holdingWeapon()) return false;
        boolean onEntity = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() != null;
        if (checkEntity.getValue() && !onEntity) return false;
        if (left && !breakBlocks.getValue() && mc.hitResult instanceof BlockHitResult block
                && block.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
        return !left || !cooldown.getValue() || !onEntity || mc.player.getAttackStrengthScale(0.5F) >= 0.92F;
    }

    /** Gaussian interval around the CPS with a slow drift (hand fatigue) and rare longer gaps. */
    private long nextDelay() {
        double target = cps.getValue();
        if (randomization.getValue()) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double strength = randomStrength.getValue();
            drift = drift * 0.92 + r.nextGaussian() * 0.08 * strength;
            target = Math.max(1.0, Math.min(30.0, target + drift + r.nextGaussian() * strength * 0.5));
            double ms = 1000.0 / target;
            if (r.nextDouble() < 0.03) ms *= 1.5 + r.nextDouble(); // a missed beat
            return Math.max(25L, Math.round(ms));
        }
        return Math.round(1000.0 / target);
    }

    private boolean holdingWeapon() {
        var stack = mc.player.getMainHandItem();
        return stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES)
                || stack.is(net.minecraft.world.item.Items.MACE) || stack.is(net.minecraft.world.item.Items.TRIDENT);
    }
}
