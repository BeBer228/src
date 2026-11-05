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
    private final ModeSetting soundType = register(new ModeSetting("Звук", "Тип звука при ударе.", "Стоны", "Стоны", "Металл", "Криминал", "Фотограф"));
    private final NumberSetting volume = register(new NumberSetting("Громкость", "Громкость звука.", 1.0, 0.1, 2.0, 0.1));

    public HitSound() {
        super("HitSound", "Весёлые криты", ModuleCategory.COMBAT);
    }

    @SubscribeEvent
    private void onAttack(AttackEvent event) {
        if (event.getTarget() instanceof LivingEntity) {
            playSelectedSound();
        }
    }

    private void playSelectedSound() {
        float vol = volume.getFloat();

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