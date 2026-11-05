package releon.ru.api.module.impl.visual;

import releon.ru.utils.render.color.ClientTheme;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.world.targetesp.*;

import java.awt.Color;

public class TargetESP extends Module {
    private final ModeSetting style = register(new ModeSetting("Style", "Набор эффектов цели.", "New", "Base", "New"));
    private final PVPVisual modern = new PVPVisual();
    private static TargetESP instance;

    private final SmoothAnimation alphaAnimation = new SmoothAnimation();
    private final ModeSetting mode = register(new ModeSetting("Mode", "Target highlight type.", "Ghost", "Молнии", "Rhomb", "Rhomb 2", "Ghost", "Chain", "Мечи", "Skull", "Атом", "ДНК", "Магический круг", "Души", "Сканер", "Сингулярность", "Небесный суд", "Кристальный разлом"));
    private final ModeSetting cinematicQuality = register(new ModeSetting("Детализация FX", "Детализация трёх новых эффектов; вдали автоматически снижается.", "Средняя", "Низкая", "Средняя", "Высокая"));
    private final BooleanSetting cinematicHits = register(new BooleanSetting("Импульсы FX", "Локальные волны и реакция новых эффектов на получение целью урона.", true));
    private final ModeSetting lightningType = register(new ModeSetting("Тип молний", "Lightning style.", "Тип 1", "Тип 1", "Тип 2"));
    private final BooleanSetting themeColors = register(new BooleanSetting("Цвета темы", "Брать цвета из темы ClickGUI (Themes).", true));
    private final ColorSetting color1 = register(new ColorSetting("Первый цвет", "ЕСП ", new Color(127, 242, 255, 136)));
    private final ColorSetting color2 = register(new ColorSetting("Второй цвет", "Secondary target ESP color.", new Color(255, 50, 150, 255)));
    private Vec3 smoothedPos;
    private LivingEntity lastTarget;
    private float hurtProgress;
    private float chainImpactProgress;
    private long lastFrameNs;
    private Object renderLevel;

    public TargetESP() {
        super("Target ESP", "Highlights the current Aura target.", ModuleCategory.VISUAL);
        instance = this;
        alphaAnimation.set(0.0);
        color1.visibleWhen(() -> !themeColors.getValue());
        color2.visibleWhen(() -> !themeColors.getValue());
        for (var setting : getSettings()) if (setting != style) setting.visibleAlso(() -> style.is("Base"));
        for (var setting : modern.getSettings()) {
            setting.visibleAlso(() -> style.is("New"));
            register(setting);
        }
        style.addListener((setting, old, value) -> { resetRenderState(); modern.onDisable(); });
    }

    public static TargetESP getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        modern.onDisable();
        resetRenderState();
    }

    @Override public void onTick(net.minecraft.client.Minecraft client) {
        if (style.is("New")) modern.onTick(client);
    }
    @SubscribeEvent private void onAttack(releon.ru.api.events.impl.AttackEvent event) {
        if (style.is("New")) modern.onAttack(event);
    }
    @SubscribeEvent private void onPacket(releon.ru.api.events.impl.PacketEvent event) {
        if (style.is("New")) modern.onPacket(event);
    }

    private void resetRenderState() {
        lastFrameNs = 0L;
        renderLevel = null;
        CinematicTargetEspRenderer.reset();
        alphaAnimation.set(0.0);
        smoothedPos = null;
        lastTarget = null;
        hurtProgress = 0.0f;
        chainImpactProgress = 0.0f;
        LightningOrbit.reset();
        LightningArcs.reset();
        AtomTargetEspRenderer.reset();
        HelixTargetEspRenderer.reset();
        RuneCircleTargetEspRenderer.reset();
        SoulsTargetEspRenderer.reset();
        ScannerTargetEspRenderer.reset();
    }

    @SubscribeEvent
    private void onRender3D(WorldRenderEvent event) {
        if (style.is("New")) { modern.onRender3D(event); return; }
        if (mc.level == null || mc.player == null) {
            resetRenderState();
            return;
        }
        if (renderLevel != mc.level) {
            resetRenderState();
            renderLevel = mc.level;
        }
        long frameTime = System.currentTimeMillis();
        long frameNs = System.nanoTime();
        float deltaSeconds = lastFrameNs == 0L ? 1.0f / 60.0f
                : (float) Math.max(0.0, Math.min((frameNs - lastFrameNs) / 1000000000.0, 0.1));
        lastFrameNs = frameNs;
        float deltaTime = deltaSeconds * 60.0f;

        LivingEntity target = AuraModule.getInstance() != null && AuraModule.getInstance().isEnabled() ? AuraModule.target : null;
        if (target == null || !target.isAlive()) {
            alphaAnimation.run(0.0, 0.25, Easings.CUBIC_OUT, true);
        } else {
            if (lastTarget != target || smoothedPos == null) {
                lastTarget = target;
                smoothedPos = target.getPosition(event.getPartialTicks());
            } else {
                smoothToTarget(target, event.getPartialTicks(), deltaSeconds);
            }
            alphaAnimation.run(1.0, 0.25, Easings.CUBIC_OUT, true);
        }

        alphaAnimation.update();
        float alpha = Mth.clamp(alphaAnimation.get(), 0.0f, 1.0f);
        if (alpha <= 0.01f || lastTarget == null || smoothedPos == null) {
            return;
        }

        hurtProgress = lastTarget.hurtTime > 0 ? (float) lastTarget.hurtTime / 10.0f : Math.max(0.0f, hurtProgress - 0.1f * deltaTime);
        float impactTarget = lastTarget.hurtTime > 0 ? 1.0f : 0.0f;
        float impactStep = (impactTarget > chainImpactProgress ? 0.30f : 0.10f) * deltaTime;
        chainImpactProgress = TargetEspMath.approach(chainImpactProgress, impactTarget, impactStep);

        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        float cameraFade = computeCameraFade(lastTarget, cameraPos, smoothedPos);
        float renderAlpha = alpha * cameraFade;
        if (renderAlpha <= 0.01f) {
            return;
        }

        PoseStack stack = event.getStack();
        MultiBufferSource.BufferSource provider = mc.renderBuffers().bufferSource();
        stack.pushPose();
        try {
            stack.translate(smoothedPos.x - cameraPos.x, smoothedPos.y - cameraPos.y, smoothedPos.z - cameraPos.z);

            TargetEspRenderContext context = new TargetEspRenderContext(
                    lastTarget,
                    renderAlpha,
                    event.getPartialTicks(),
                    frameTime,
                    themeColors.getValue() ? ClientTheme.secondary(136) : color1.getValue().getRGB(),
                    themeColors.getValue() ? ClientTheme.primary(255) : color2.getValue().getRGB(),
                    hurtProgress,
                    chainImpactProgress
            );

            if (mode.is("Сингулярность") || mode.is("Небесный суд") || mode.is("Кристальный разлом")) {
                int style = mode.is("Сингулярность") ? CinematicTargetEspRenderer.SINGULARITY
                        : mode.is("Небесный суд") ? CinematicTargetEspRenderer.JUDGEMENT : CinematicTargetEspRenderer.FRACTURE;
                int quality = cinematicQuality.is("Низкая") ? 0 : cinematicQuality.is("Высокая") ? 2 : 1;
                CinematicTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos),
                        style, quality, cinematicHits.getValue());
                CinematicTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Rhomb")) {
                RhombTargetEspRenderer.render(stack, provider, context);
                RhombTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Rhomb 2")) {
                MarkerTargetEspRenderer.render(stack, provider, context);
                MarkerTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Ghost")) {
                GhostTargetEspRenderer.render(stack, provider, context);
                GhostTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Chain")) {
                ChainTargetEspRenderer.render(stack, provider, context);
                ChainTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Мечи")) {
                SwordEspRenderer.render(stack, provider, context);
                SwordEspRenderer.endBatch(provider);
            } else if (mode.is("Skull")) {
                SkullTargetEspRenderer.render(stack, provider, context);
                SkullTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Атом")) {
                AtomTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos));
                AtomTargetEspRenderer.endBatch(provider);
            } else if (mode.is("ДНК")) {
                HelixTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos));
                HelixTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Магический круг")) {
                RuneCircleTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos));
                RuneCircleTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Души")) {
                SoulsTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos));
                SoulsTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Сканер")) {
                ScannerTargetEspRenderer.render(stack, provider, context, cameraPos.subtract(smoothedPos));
                ScannerTargetEspRenderer.endBatch(provider);
            } else if (mode.is("Молнии")) {
                Vec3 cameraLocalPos = cameraPos.subtract(smoothedPos);
                if (lightningType.is("Тип 2")) {
                    LightningArcs.render(stack, provider, context, cameraLocalPos);
                    LightningArcs.endBatch(provider);
                } else {
                    LightningOrbit.render(stack, provider, context, cameraLocalPos);
                    LightningOrbit.endBatch(provider);
                }
            }
        } finally {
            stack.popPose();
        }
    }

    private void smoothToTarget(LivingEntity target, float partialTicks, float deltaSeconds) {
        Vec3 targetPos = target.getPosition(partialTicks);
        // Interpolation ticks locate the entity; elapsed render time controls the smoothing.
        double dx = targetPos.x - smoothedPos.x, dy = targetPos.y - smoothedPos.y, dz = targetPos.z - smoothedPos.z;
        if (dx * dx + dy * dy + dz * dz > 144.0) {
            smoothedPos = targetPos;
            return;
        }
        float smoothing = (float) (1.0 - Math.exp(-16.0 * deltaSeconds));
        smoothedPos = new Vec3(
                smoothedPos.x + (targetPos.x - smoothedPos.x) * smoothing,
                smoothedPos.y + (targetPos.y - smoothedPos.y) * smoothing,
                smoothedPos.z + (targetPos.z - smoothedPos.z) * smoothing
        );
    }

    private float computeCameraFade(LivingEntity target, Vec3 cameraPos, Vec3 targetPos) {
        AABB dangerZone = target.getBoundingBox().inflate(0.35);
        if (dangerZone.contains(cameraPos)) {
            return 0.0f;
        }
        double distance = cameraPos.distanceTo(targetPos);
        float fadeStart = Math.max(1.25f, target.getBbWidth() * 1.6f);
        float fadeEnd = fadeStart + 0.9f;
        return Mth.clamp((float) ((distance - fadeStart) / (fadeEnd - fadeStart)), 0.0f, 1.0f);
    }
}
