package releon.ru.api.module.impl.combat;

import net.minecraft.world.entity.LivingEntity;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.sounds.SoundManager;

import java.util.concurrent.ThreadLocalRandom;

public class HitSound extends Module {
    private final ModeSetting soundType = register(new ModeSetting("Звук", "Тип звука при ударе.", "Click", "Click", "Akrien", "Tone", "Bubble", "Ding", "Стоны", "Металл", "Криминал", "Фотограф"));
    private final NumberSetting volume = register(new NumberSetting("Громкость", "Громкость звука.", .6, 0, 2.0, .05));
    private final NumberSetting pitch = register(new NumberSetting("Высота", "Высота звука.", 1, .5, 2, .05));
    private final releon.ru.api.settings.impl.MultiModeSetting conditions = register(new releon.ru.api.settings.impl.MultiModeSetting("Условия", "",
            new String[]{"Только криты", "Только оружие", "Случайная высота"}));

    public HitSound() {
        super("HitSound", "Весёлые криты", ModuleCategory.COMBAT);
    }

    @SubscribeEvent
    private void onAttack(AttackEvent event) {
        if (mc.player == null) return;
        var cosmetics = releon.ru.api.module.impl.misc.Cosmetics.get();
        if (cosmetics != null && cosmetics.isEnabled() && cosmetics.replacesMaceHit()) return;
        if (conditions.isSelected("Только криты") && (mc.player.fallDistance <= 0 || mc.player.onGround()
                || mc.player.onClimbable() || mc.player.isInWater() || mc.player.isPassenger()
                || mc.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS))) return;
        var stack = mc.player.getMainHandItem();
        if (conditions.isSelected("Только оружие") && !(stack.is(net.minecraft.tags.ItemTags.SWORDS)
                || stack.is(net.minecraft.tags.ItemTags.AXES) || stack.is(net.minecraft.world.item.Items.MACE)
                || stack.is(net.minecraft.world.item.Items.TRIDENT))) return;
        if (event.getTarget() instanceof LivingEntity) {
            playSelectedSound();
        }
    }

    private void playSelectedSound() {
        float vol = volume.getFloat();
        String cue = switch (soundType.getValue()) {
            case "Click" -> "click";
            case "Akrien" -> "akrien";
            case "Tone" -> "tone";
            case "Bubble" -> "bubble";
            case "Ding" -> "ding";
            default -> null;
        };
        if (cue != null) {
            float tone = pitch.getFloat() + (conditions.isSelected("Случайная высота") ? ThreadLocalRandom.current().nextFloat(-.125f, .125f) : 0);
            releon.ru.utils.sounds.IncludeHitSounds.play(cue, vol, tone);
            return;
        }

        if (soundType.is("Металл")) {
            SoundManager.playSound(SoundManager.METALLIC, vol, 1.0F);
        } else if (soundType.is("Криминал")) {
            SoundManager.playSound(SoundManager.CRIME, vol, 1.0F);
        } else if (soundType.is("Фотограф")) {
            SoundManager.playSound(SoundManager.FOTOGRAF, vol, 1.0F);
        } else {
            switch (ThreadLocalRandom.current().nextInt(4)) {
                case 0 -> SoundManager.playSound(SoundManager.MOAN1, vol, 1.0F);
                case 1 -> SoundManager.playSound(SoundManager.MOAN2, vol, 1.0F);
                case 2 -> SoundManager.playSound(SoundManager.MOAN3, vol, 1.0F);
                default -> SoundManager.playSound(SoundManager.MOAN4, vol, 1.0F);
            }
        }
    }
}
