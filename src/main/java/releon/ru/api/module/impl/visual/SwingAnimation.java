package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.HandAnimationEvent;
import releon.ru.api.events.impl.SwingDurationEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

public class SwingAnimation extends Module {
    private final BooleanSetting hmiEnable = register(new BooleanSetting("Режим рук", "Включить пользовательскую анимацию рук.", false));
    private final ModeSetting hmiAnimationType = (ModeSetting) register(new ModeSetting("Тип анимации", "Стиль анимации рук.", "Classic", "Classic", "Sharp"))
            .visible(hmiEnable::getValue);
    private final NumberSetting hmiSmoothness = (NumberSetting) register(new NumberSetting("Плавность анимации", "Плавность анимации рук.", 1.0, 0.35, 2.5, 0.05))
            .visible(hmiEnable::getValue);
    private final BooleanSetting swingEnabled = (BooleanSetting) register(new BooleanSetting("Анимация взмаха", "Включить анимацию взмаха.", true))
            .visible(() -> !hmiEnable.getValue());
    private final ModeSetting swingType = (ModeSetting) register(new ModeSetting("Тип взмаха", "Тип анимации взмаха.", "Smooth",
            "Smooth", "Пропеллер", "Мельница", "Подброс", "Рассечение", "Выпад", "Апперкот", "Желе", "Ярость", "Левитация", "Свой"))
            .visible(() -> !hmiEnable.getValue() && swingEnabled.getValue());
    private final NumberSetting swingStrength = (NumberSetting) register(new NumberSetting("Сила анимации", "Сила анимации взмаха.", 1.0, 0.1, 3.0, 0.01))
            .visible(() -> !hmiEnable.getValue() && swingEnabled.getValue() && !swingType.is("Свой"));
    private final ModeSetting customCurve = (ModeSetting) register(new ModeSetting("Кривая взмаха", "Как меняется анимация во время удара.", "Плавная",
            "Плавная", "Резкая", "Мягкая", "Линейная", "Пружина"))
            .visible(this::isCustom);
    private final NumberSetting customPosX = custom("Позиция X", "Сдвиг влево/вправо в покое.", 0.56, -1.5, 1.5, 0.01);
    private final NumberSetting customPosY = custom("Позиция Y", "Сдвиг вверх/вниз в покое.", -0.52, -1.5, 1.5, 0.01);
    private final NumberSetting customPosZ = custom("Позиция Z", "Сдвиг вперёд/назад в покое.", -0.72, -2.0, 1.0, 0.01);
    private final NumberSetting customRotX = custom("Наклон X", "Наклон вперёд/назад в покое.", 0.0, -180.0, 180.0, 1.0);
    private final NumberSetting customRotY = custom("Поворот Y", "Поворот влево/вправо в покое.", 0.0, -180.0, 180.0, 1.0);
    private final NumberSetting customRotZ = custom("Крен Z", "Крен вбок в покое.", 0.0, -180.0, 180.0, 1.0);
    private final NumberSetting customScale = custom("Размер", "Размер предмета в руке.", 1.0, 0.2, 2.5, 0.05);
    private final NumberSetting customSwingX = custom("Взмах: сдвиг X", "Сдвиг влево/вправо во время удара.", 0.0, -1.5, 1.5, 0.01);
    private final NumberSetting customSwingY = custom("Взмах: сдвиг Y", "Сдвиг вверх/вниз во время удара.", 0.0, -1.5, 1.5, 0.01);
    private final NumberSetting customSwingZ = custom("Взмах: сдвиг Z", "Сдвиг вперёд/назад во время удара.", -0.2, -2.0, 2.0, 0.01);
    private final NumberSetting customSwingRotX = custom("Взмах: наклон X", "Наклон во время удара (360 = полный оборот).", -80.0, -720.0, 720.0, 1.0);
    private final NumberSetting customSwingRotY = custom("Взмах: поворот Y", "Поворот во время удара.", 0.0, -720.0, 720.0, 1.0);
    private final NumberSetting customSwingRotZ = custom("Взмах: крен Z", "Крен во время удара.", 0.0, -720.0, 720.0, 1.0);
    private final NumberSetting customSwingScale = custom("Взмах: размер", "Изменение размера во время удара.", 0.0, -0.8, 1.5, 0.01);
    private final BooleanSetting customIdle = (BooleanSetting) register(new BooleanSetting("Покачивание", "Предмет плавно покачивается даже без удара.", false))
            .visible(this::isCustom);
    private final NumberSetting customIdleSpeed = (NumberSetting) register(new NumberSetting("Скорость покачивания", "Скорость покачивания в покое.", 1.0, 0.1, 5.0, 0.05))
            .visible(() -> isCustom() && customIdle.getValue());
    private final NumberSetting customIdleAmount = (NumberSetting) register(new NumberSetting("Сила покачивания", "Амплитуда покачивания в покое.", 1.0, 0.1, 5.0, 0.05))
            .visible(() -> isCustom() && customIdle.getValue());

    private final BooleanSetting smoothEnabled = (BooleanSetting) register(new BooleanSetting("Плавная анимация", "Включить плавную анимацию.", false))
            .visible(() -> !hmiEnable.getValue());
    private final NumberSetting slowAnimationSpeed = (NumberSetting) register(new NumberSetting("Скорость анимации", "Скорость плавной анимации.", 12.0, 1.0, 50.0, 1.0))
            .visible(() -> !hmiEnable.getValue() && smoothEnabled.getValue());

    private final BooleanSetting onlyAura = (BooleanSetting) register(new BooleanSetting("Только Aura", "Анимировать только когда у Aura есть цель.", false))
            .visible(() -> !hmiEnable.getValue());
    private final BooleanSetting swapHands = (BooleanSetting) register(new BooleanSetting("Смена рук", "Анимация смены рук.", false))
            .visible(() -> !hmiEnable.getValue());
    private final BooleanSetting eatAnim = (BooleanSetting) register(new BooleanSetting("Анимация еды", "Включить анимацию еды.", false))
            .visible(() -> !hmiEnable.getValue());

    public SwingAnimation() {
        super("Swing Animation", "Custom first-person swing animations.", ModuleCategory.VISUAL);
    }

    @SubscribeEvent
    private void onSwingDuration(SwingDurationEvent event) {
        if (isBlockedForSwingAnimation(InteractionHand.MAIN_HAND) || isBlockedForSwingAnimation(InteractionHand.OFF_HAND)) {
            return;
        }
        if (passesAuraFilter()) {
            event.setAnimation(1.0F);
            event.cancel();
        }
    }

    @SubscribeEvent
    private void onHandAnimation(HandAnimationEvent event) {
        if (mc.player == null || event.getHand() != InteractionHand.MAIN_HAND || isBlockedForSwingAnimation(event.getHand()) || !passesAuraFilter()) {
            return;
        }
        PoseStack matrices = event.getMatrices();
        float swingProgress = event.getSwingProgress();
        int armSide = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        float sin1 = Mth.sin(swingProgress * swingProgress * Mth.PI);
        float sin2 = Mth.sin(Mth.sqrt(swingProgress) * Mth.PI);
        float sinSmooth = (float) (Math.sin(swingProgress * Math.PI) * 0.5F);
        float strength = swingStrength.getFloat();

        if (hmiEnable.getValue()) {
            if (hmiAnimationType.is("Classic")) {
                matrices.translate(armSide * 0.56F, -0.52F - sin2 * 0.5F * strength, -0.72F);
                matrices.mulPose(Axis.YP.rotationDegrees(45.0F * armSide));
                matrices.mulPose(Axis.YP.rotationDegrees(-45.0F * armSide));
            } else if (hmiAnimationType.is("Sharp")) {
                matrices.translate(armSide * 0.56F, -0.32F, -0.72F);
                matrices.mulPose(Axis.YP.rotationDegrees(76.0F * armSide));
                matrices.mulPose(Axis.YP.rotationDegrees(sin2 * -5.0F * strength));
                matrices.mulPose(Axis.XN.rotationDegrees(sin2 * -100.0F * strength));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -155.0F * strength));
                matrices.mulPose(Axis.XP.rotationDegrees(-100.0F));
            }
        } else if (swingEnabled.getValue()) {
            if (swingType.is("Smooth")) {
                matrices.translate(armSide * 0.56F, -0.42F, -0.72F);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * (45.0F + sin1 * -20.0F * strength)));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * sin2 * -20.0F * strength));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -80.0F * strength));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -45.0F));
                matrices.translate(0.0F, -0.1F, 0.0F);
            } else if (swingType.is("Пропеллер")) {
                float spin = easeOutCubic(swingProgress);
                matrices.translate(armSide * 0.56F, -0.46F - sinSmooth * 0.12F * strength, -0.72F - sinSmooth * 0.15F * strength);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * 45.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * -360.0F * spin));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -45.0F));
            } else if (swingType.is("Мельница")) {
                float spin = easeInOutQuad(swingProgress);
                matrices.translate(armSide * 0.56F, -0.46F, -0.72F - sinSmooth * 0.3F * strength);
                matrices.mulPose(Axis.XP.rotationDegrees(-360.0F * spin));
            } else if (swingType.is("Подброс")) {
                float toss = Mth.sin(swingProgress * Mth.PI);
                matrices.translate(armSide * 0.56F, -0.52F + toss * 0.38F * strength, -0.72F - toss * 0.1F);
                matrices.mulPose(Axis.XP.rotationDegrees(-360.0F * swingProgress));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * toss * 15.0F));
            } else if (swingType.is("Рассечение")) {
                matrices.translate(armSide * (0.56F - sin2 * 0.28F * strength), -0.40F - sin2 * 0.12F * strength, -0.72F);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * 40.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * (25.0F - sin2 * 85.0F * strength)));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -55.0F * strength));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -40.0F));
            } else if (swingType.is("Выпад")) {
                float lunge = sin2 * strength;
                matrices.translate(armSide * 0.56F * (1.0F - sin2 * 0.45F), -0.52F + sin2 * 0.14F, -0.72F - lunge * 0.5F);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * sin2 * 40.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -75.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * sin1 * -10.0F * strength));
            } else if (swingType.is("Апперкот")) {
                matrices.translate(armSide * 0.56F, -0.62F + sin2 * 0.25F * strength, -0.72F);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * 30.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(40.0F - sin2 * 110.0F * strength));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * sin2 * 20.0F));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -30.0F));
            } else if (swingType.is("Желе")) {
                float wobble = Mth.sin(swingProgress * Mth.PI * 3.0F) * (1.0F - swingProgress) * strength;
                matrices.translate(armSide * 0.56F, -0.52F - sin2 * 0.08F, -0.72F);
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * wobble * 12.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -25.0F * strength));
                float squash = wobble * 0.28F;
                matrices.scale(1.0F + squash, 1.0F - squash, 1.0F + squash);
            } else if (swingType.is("Ярость")) {
                float decay = 1.0F - swingProgress;
                float shake = Mth.sin(swingProgress * Mth.PI * 10.0F) * decay * strength;
                matrices.translate(armSide * 0.56F + shake * 0.03F, -0.46F + shake * 0.02F, -0.72F - sin2 * 0.18F * strength);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * 45.0F));
                matrices.mulPose(Axis.ZP.rotationDegrees(shake * 18.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -70.0F * strength + shake * 10.0F));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -45.0F));
            } else if (swingType.is("Левитация")) {
                float time = (System.currentTimeMillis() % 100000L) / 1000.0F;
                float bob = Mth.sin(time * 2.2F) * 0.035F;
                float sway = Mth.sin(time * 1.3F) * 6.0F;
                matrices.translate(armSide * 0.6F, -0.40F + bob - sin2 * 0.12F * strength, -0.80F + sin2 * 0.1F);
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * (20.0F + sway)));
                matrices.mulPose(Axis.ZP.rotationDegrees(armSide * Mth.sin(time * 1.7F) * 4.0F));
                matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -90.0F * strength));
                matrices.mulPose(Axis.YP.rotationDegrees(armSide * -20.0F));
            } else if (swingType.is("Свой")) {
                applyCustom(matrices, swingProgress, armSide);
            }
        }

        event.cancel();
    }

    private NumberSetting custom(String name, String description, double def, double min, double max, double step) {
        return (NumberSetting) register(new NumberSetting(name, description, def, min, max, step)).visible(this::isCustom);
    }

    private boolean isCustom() {
        return !hmiEnable.getValue() && swingEnabled.getValue() && swingType.is("Свой");
    }

    private void applyCustom(PoseStack matrices, float progress, int armSide) {
        float k;
        if (customCurve.is("Резкая")) {
            k = Mth.sin(progress * progress * Mth.PI);
        } else if (customCurve.is("Мягкая")) {
            k = Mth.sin(progress * Mth.PI);
        } else if (customCurve.is("Линейная")) {
            k = progress;
        } else if (customCurve.is("Пружина")) {
            k = Mth.sin(Mth.sqrt(progress) * Mth.PI) + Mth.sin(progress * Mth.PI * 3.0F) * (1.0F - progress) * 0.35F;
        } else {
            k = Mth.sin(Mth.sqrt(progress) * Mth.PI);
        }

        float idleX = 0.0F;
        float idleY = 0.0F;
        float idleRot = 0.0F;
        if (customIdle.getValue()) {
            float time = (System.currentTimeMillis() % 100000L) / 1000.0F * customIdleSpeed.getFloat();
            float amount = customIdleAmount.getFloat();
            idleX = Mth.sin(time * 1.3F) * 0.015F * amount;
            idleY = Mth.sin(time * 2.2F) * 0.03F * amount;
            idleRot = Mth.sin(time * 1.7F) * 4.0F * amount;
        }

        matrices.translate(
                armSide * (customPosX.getFloat() + customSwingX.getFloat() * k + idleX),
                customPosY.getFloat() + customSwingY.getFloat() * k + idleY,
                customPosZ.getFloat() + customSwingZ.getFloat() * k);
        matrices.mulPose(Axis.YP.rotationDegrees(armSide * (customRotY.getFloat() + customSwingRotY.getFloat() * k + idleRot)));
        matrices.mulPose(Axis.XP.rotationDegrees(customRotX.getFloat() + customSwingRotX.getFloat() * k));
        matrices.mulPose(Axis.ZP.rotationDegrees(armSide * (customRotZ.getFloat() + customSwingRotZ.getFloat() * k + idleRot * 0.5F)));
        float scale = Math.max(0.05F, customScale.getFloat() + customSwingScale.getFloat() * k);
        matrices.scale(scale, scale, scale);
    }

    private static float easeOutCubic(float x) {
        float inv = 1.0F - Mth.clamp(x, 0.0F, 1.0F);
        return 1.0F - inv * inv * inv;
    }

    private static float easeInOutQuad(float x) {
        float v = Mth.clamp(x, 0.0F, 1.0F);
        return v < 0.5F ? 2.0F * v * v : 1.0F - (float) Math.pow(-2.0F * v + 2.0F, 2) / 2.0F;
    }

    private boolean passesAuraFilter() {
        AuraModule aura = AuraModule.getInstance();
        return !onlyAura.getValue() || aura != null && aura.isEnabled() && AuraModule.target != null;
    }

    private boolean isBlockedForSwingAnimation(InteractionHand hand) {
        return mc.player != null && isExcludedSwingItem(mc.player.getItemInHand(hand));
    }

    private boolean isExcludedSwingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem) {
            return true;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.contains("spear");
    }
}