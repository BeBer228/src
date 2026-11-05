package releon.ru.api.module.impl.visual;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.*;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.utils.render.*;
import releon.ru.utils.render.color.ClientTheme;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalInt;
import java.util.OptionalDouble;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import java.util.HashMap;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Ported from Nightix (MC 1.21.11) {@code ru.white.module.impl.render.PVPVisual}.
 *
 * <p>Target indicators for PvP: a rotating 3D cube that snaps by 90-degree steps on every hit and
 * shatters into animated fragments on death, a vertical ring with a filled skirt, orbiting cubes,
 * and an optional world-space pulse on hit.</p>
 *
 * <h2>How it is wired in 26.3</h2>
 * <p>Include now exposes {@link WorldRenderEvent} ({@code AFTER_LEVEL}), where the world depth buffer
 * is live, so the donor's six world-space pipelines collapse into two here:</p>
 * <ul>
 *   <li>a flat vertex-coloured pipeline for the cube bars, corner cubes, fragments, ring skirt and
 *       orbiting cubes - the same procedural geometry the donor generated, submitted as real
 *       {@code POSITION_COLOR} triangles and lines rather than projected to the HUD;</li>
 *   <li>a textured billboard pipeline for the "Noor" glyph on {@code textures/visuals/union.png}.</li>
 * </ul>
 * <p>Vertices are built camera-relative and depth-tested with {@code GREATER_THAN_OR_EQUAL} without
 * depth writes, so indicators are correctly hidden behind terrain.</p>
 *
 * <h2>Covered vs dropped</h2>
 * <ul>
 *   <li><b>Ported:</b> {@code Cube} (idle rotation, 90-degree hit snaps, glow halo, death shatter),
 *       {@code Ring} (skirt + rim + trailing particles), {@code Cubes} (orbiting small cubes),
 *       {@code Noor} (billboard glyph).</li>
 *   <li><b>Dropped:</b> {@code Spirits} (custom + celestial) - a per-segment trail of
 *       {@code particles_3.png} billboards, up to {@code count * spiritLength = 320} quads per
 *       frame. Porting it needs its own batched impostor pass; the parametric paths are preserved
 *       in {@link #spiritTrajectory} so only the submission is missing.
 *       {@code Ghost} - needs the donor's {@code EntityEsp.drawBoxOutline} helper.</li>
 *   <li><b>Pulse</b> - an expanding world-space shockwave centred on the hit entity, drawn as
 *       depth-tested rings in three planes; see {@link #renderPulse}.</li>
 * </ul>
 */
import net.minecraft.core.BlockPos;
import org.joml.Vector2f;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class PVPVisual extends Module {

    /** Vanilla {@code Entity.handleEntityEvent} id for a totem-of-undying pop. */
    private static final int ENTITY_EVENT_TOTEM = 35;

    private static final int CUBES_ORBIT_MAX = 24;
    private static final int MAX_FRAGMENTS = 240;
    private static final int EXPECTED_VERTICES = 8192;

    // ------------------------------------------------------------------ pipelines

    static final RenderPipeline COLOR_TRANSLUCENT=IncludeWorldMesh.pipeline("pvp/color","world_color",true,false,false,false,true,null);
    static final RenderPipeline COLOR_ADDITIVE=IncludeWorldMesh.pipeline("pvp/color_glow","world_color",true,true,false,false,true,null);
    static final RenderPipeline TEX_TRANSLUCENT=IncludeWorldMesh.pipeline("pvp/texture","world_tex_color",false,false,true,false);
    static final RenderPipeline TEX_ADDITIVE=IncludeWorldMesh.pipeline("pvp/texture_glow","world_tex_color",false,true,true,false);
    static final RenderPipeline TEX_GROUND_ADDITIVE=IncludeWorldMesh.pipeline("pvp/ground","world_tex_color",true,true,true,false);
    private static final IncludeWorldMesh GPU=new IncludeWorldMesh();
    private static final Identifier TEX_UNION = Identifier.fromNamespaceAndPath("client", "textures/visuals/union.png");
    private static final Identifier TEX_PARTICLES = Identifier.fromNamespaceAndPath("client", "textures/visuals/particles_1.png");
    private static final Identifier TEX_PARTICLES_3 = Identifier.fromNamespaceAndPath("client", "textures/visuals/particles_3.png");

    // TargetESP textures
    private static final Identifier TEX_TARGET_GLOW = Identifier.fromNamespaceAndPath("include", "textures/targetesp/glow.png");
    private static final Identifier TEX_SKULL_0 = Identifier.fromNamespaceAndPath("include", "textures/targetesp/skull_state_0.png");
    private static final Identifier TEX_SKULL_1 = Identifier.fromNamespaceAndPath("include", "textures/targetesp/skull_state_1.png");
    private static final Identifier TEX_SKULL_2 = Identifier.fromNamespaceAndPath("include", "textures/targetesp/skull_state_2.png");
    private static final Identifier TEX_TARGET_DIAMOND = Identifier.fromNamespaceAndPath("include", "textures/targetesp/target.png");

    // ------------------------------------------------------------------ settings

    public final BooleanSetting targetEsp = register(new BooleanSetting("Include: Target ESP", "Подсвечивать текущую цель", true));

    public final ModeSetting mode = register(new ModeSetting("Include: Mode",
            "Стиль индикатора цели",
            "Souls",
            "Cube", "Ring", "Cubes", "Noor", "Spirits", "Souls", "Skull", "Target",
            "Ghosts", "Lightning", "Circle", "Crystals", "Chains"
    ));
    public final ModeSetting circleStyle = register(new ModeSetting("Include: Circle Style", "Wave: bobbing ring with a skirt; Sweep: ring running over the body; Band: belt hugging the body; Scan: line sliding down the model",
            "Sweep", "Wave", "Sweep", "Band", "Scan"));
    public final BooleanSetting lightningProjectiles = register(new BooleanSetting("Include: Projectile Lightning", "Draw lightning from the hand to your flying arrows and tridents", true));
    public final BooleanSetting chainProjectiles = register(new BooleanSetting("Include: Projectile Chain", "Draw a chain from the hand to your flying arrows and tridents", true));
    private final TargetEspModes modes = new TargetEspModes(this);

    public final MultiModeSetting sources = register(multi(
            "Target Sources",
            "Источники цели",
            new String[]{"Aura", "Crosshair", "Manual"},
            true
    ));

    public final NumberSetting targetRange = register(new NumberSetting("Include: Target Range", "Maximum distance to a target in blocks.", 32.0, 3.0, 96.0, 1.0));

    public final ModeSetting colorMode = register(new ModeSetting("Include: Color Mode",
            "Цветовая схема индикаторов",
            "Theme",
            "Theme", "Custom", "Rainbow"
    ));

    public final ColorSetting customColor = register(new ColorSetting("Include: Color", "Свой цвет индикаторов", new Color(0xFF00FFFF,true)));

    // ---- cube
    public final NumberSetting cubeSize = register(new NumberSetting("Include: Cube Size", "Размер куба", 1.15, 0.4, 2.5, 0.05));
    public final NumberSetting cubeRotateSpeed = register(new NumberSetting("Include: Cube Speed", "Скорость вращения куба", 1.2, 0.2, 4.0, 0.05));
    public final NumberSetting cubeThickness = register(new NumberSetting("Include: Cube Thickness", "Толщина рёбер куба", 2.2, 0.6, 5.0, 0.1));
    public final BooleanSetting cubeColorOnHit = register(new BooleanSetting("Include: Cube Hit Color", "Красный цвет куба при ударе", true));
    public final BooleanSetting cubeGlow = register(new BooleanSetting("Include: Cube Glow", "Свечение вокруг куба", true));
    public final BooleanSetting cubeShatter = register(new BooleanSetting("Include: Cube Shatter", "Разлетающиеся фрагменты при смерти цели", true));

    // ---- ring
    public final NumberSetting ringRadius = register(new NumberSetting("Include: Ring Radius", "Радиус кольца", 0.75, 0.3, 2.0, 0.05));
    public final NumberSetting ringSpeed = register(new NumberSetting("Include: Ring Speed", "Скорость кольца", 1.0, 0.2, 3.0, 0.1));

    // ---- orbiting cubes
    public final NumberSetting cubesCount = register(new NumberSetting("Include: Cubes Count", "Количество кубиков", 12, 4, 24, 2));
    public final NumberSetting cubesSize = register(new NumberSetting("Include: Cubes Size", "Размер кубиков", 0.06, 0.02, 0.2, 0.01));
    public final NumberSetting cubesSpeed = register(new NumberSetting("Include: Cubes Speed", "Скорость кубиков", 1.0, 0.2, 3.0, 0.1));
    public final NumberSetting cubesTilt = register(new NumberSetting("Include: Cubes Tilt", "Наклон кубиков", 25.0, 0.0, 60.0, 5.0));

    // ---- noor
    public final NumberSetting noorSize = register(new NumberSetting("Include: Noor Size", "Размер Нура", 1.0, 0.4, 2.5, 0.05));
    public final NumberSetting noorSpeed = register(new NumberSetting("Include: Noor Speed", "Скорость Нура", 1.0, 0.2, 4.0, 0.1));

    // ---- spirits
    public final ModeSetting spiritPattern = register(new ModeSetting("Include: Spirit Pattern", "Паттерн движения духов", "Orbit",
            "Orbit", "Spiral", "Lissajous", "Wave", "Butterfly", "Double Spiral"));
    public final NumberSetting spiritCount = register(new NumberSetting("Include: Spirit Count", "Количество духов", 3, 1, 8, 1));
    public final NumberSetting spiritRadius = register(new NumberSetting("Include: Spirit Radius", "Радиус орбиты духов", 0.9, 0.3, 2.5, 0.05));
    public final NumberSetting spiritSpeed = register(new NumberSetting("Include: Spirit Speed", "Скорость духов", 0.6, 0.05, 2.0, 0.05));
    public final NumberSetting spiritSize = register(new NumberSetting("Include: Spirit Size", "Размер частиц шлейфа", 0.35, 0.1, 1.0, 0.05));

    /** Souls leave a chase trail: the swarm lags a moving target and catches up when it stops. */
    public final BooleanSetting soulsChase = register(new BooleanSetting("Include: Souls Chase", "Души гонятся за движущейся целью и тянутся за ней следом.", true));
    public final NumberSetting soulsCount = register(new NumberSetting("Include: Souls Count", "Количество душ вокруг цели.", 4, 1, 12, 1));
    public final NumberSetting soulsTrail = register(new NumberSetting("Include: Souls Trail", "Длина следа погони (0 - души сидят на цели).", 0.6, 0.0, 1.0, 0.05));
    public final NumberSetting soulsGlow = register(new NumberSetting("Include: Souls Glow", "Сила свечения душ.", 1.0, 0.2, 2.0, 0.05));
    public final ModeSetting soulsPath = register(new ModeSetting("Include: Souls Path", "Траектория, по которой кружат души.", "Orbit",
            "Orbit", "Spiral", "Lissajous", "Wave", "Butterfly", "Double Spiral", "Random"));

    // ---- Pulse
    public final BooleanSetting pulseOnHit = register(new BooleanSetting("Include: Pulse On Hit", "Волна энергии по миру при ударе", true));
    public final NumberSetting pulseDuration = register(new NumberSetting("Include: Pulse Duration", "Длительность импульса (сек)", 1.8, 0.5, 5.0, 0.1));
    public final NumberSetting pulseBand = register(new NumberSetting("Include: Pulse Band", "Ширина волны (блоки)", 4.0, 0.5, 16.0, 0.5));
    public final NumberSetting pulseRadius = register(new NumberSetting("Include: Pulse Radius", "Максимальный радиус (блоки)", 32.0, 5.0, 120.0, 2.0));

    // ---- Hit Particles
    public final BooleanSetting hitParticlesEnabled = register(new BooleanSetting("Include: Hit Particles", "Физические светящиеся частицы при ударе", true));
    private final PvpHitParticles hits = new PvpHitParticles();
    public final MultiModeSetting hitImages = register(multi(
            "Hit Images", "Images that fly out of the struck target", PvpAssets.WORLD_IMAGES, false));
    public final NumberSetting hitParticleSize = register(new NumberSetting("Include: Hit Size", "Size of the hit images", 0.1, 0.03, 0.5, 0.005));
    public final NumberSetting hitParticleLife = register(new NumberSetting("Include: Hit Lifetime", "Hit image lifetime in milliseconds", 2200, 500, 5000, 100));
    public final BooleanSetting hitGlow = register(new BooleanSetting("Include: Hit Glow", "Soft halo around the hit images", true));
    public final NumberSetting hitGlowStrength = register(new NumberSetting("Include: Hit Glow Strength", "Brightness and size of the halo", 0.6, 0.1, 2.0, 0.1));
    public final BooleanSetting hitCollisions = register(new BooleanSetting("Include: Hit Collisions", "Hit images push each other apart", true));
    public final BooleanSetting hitOnlyCrits = register(new BooleanSetting("Include: Only Crits", "Spawn hit particles only on critical hits", false));
    public final NumberSetting hitParticleCount = register(new NumberSetting("Include: Particle Amount", "Количество частиц при ударе", 20, 5, 50, 1));

    // ------------------------------------------------------------------ state

    private final Random random = new Random();
    private final List<CubeFragment> cubeFragments = new ArrayList<>();

    private float cubeRotationY;
    private float cubeRotationX;
    private float cubeTargetY;
    private float cubeTargetX;
    private float cubeHitFlash;
    private int cubePrevHurtTime;
    private long cubeLastUpdate = System.currentTimeMillis();
    private LivingEntity cubeTrackedTarget;
    private Vec3 cubeLastCenter;
    private float cubeLastHalf = 0.6f;
    private double cubeLastFeetY;
    private boolean cubeShattered;

    // Spirit trail: one ring buffer of recent points per spirit.
    private final List<float[]> spiritTrail = new ArrayList<>();
    private static final int SPIRIT_TRAIL_LEN = 18;

    private long pulseStart = -1L;
    private Vec3 pulseOrigin;

    // Reused vertex scratch.
    private final Vector3f tmp = new Vector3f();
    private final Vector3f fragDir = new Vector3f(), fragRef = new Vector3f(), fragN1 = new Vector3f(), fragN2 = new Vector3f(), fragOffset = new Vector3f();
    private final Vector4f targetBoxScratch = new Vector4f();
    private final Vector2f headScreenScratch = new Vector2f();

    private static MultiModeSetting multi(String n,String d,String[] o,boolean all){return new MultiModeSetting("Include: "+n,d,o,all?o:new String[0]);}
    public PVPVisual() {
        super("PVP Visual", "Визуализация цели боя: вращающийся куб, кольцо и фрагменты при смерти.", ModuleCategory.VISUAL);

        IncludeDefaults.apply("PVP Visual",getSettings(),"Include: ");
        mode.setVisible(targetEsp::getValue);
        sources.setVisible(targetEsp::getValue);
        targetRange.setVisible(targetEsp::getValue);
        colorMode.setVisible(targetEsp::getValue);
        customColor.setVisible(() -> targetEsp.getValue() && colorMode.is("Custom"));
        circleStyle.setVisible(() -> targetEsp.getValue() && mode.is("Circle"));
        lightningProjectiles.setVisible(() -> targetEsp.getValue() && mode.is("Lightning"));
        chainProjectiles.setVisible(() -> targetEsp.getValue() && mode.is("Chains"));

        cubeSize.setVisible(this::cubeMode);
        cubeRotateSpeed.setVisible(this::cubeMode);
        cubeThickness.setVisible(this::cubeMode);
        cubeColorOnHit.setVisible(this::cubeMode);
        cubeGlow.setVisible(this::cubeMode);
        cubeShatter.setVisible(this::cubeMode);

        ringRadius.setVisible(this::ringMode);
        ringSpeed.setVisible(this::ringMode);

        cubesCount.setVisible(this::cubesMode);
        cubesSize.setVisible(this::cubesMode);
        cubesSpeed.setVisible(this::cubesMode);
        cubesTilt.setVisible(this::cubesMode);

        noorSize.setVisible(this::noorMode);
        noorSpeed.setVisible(this::noorMode);

        spiritPattern.setVisible(this::spiritsMode);
        spiritCount.setVisible(this::spiritsMode);
        spiritRadius.setVisible(() -> targetEsp.getValue() && (mode.is("Spirits") || mode.is("Souls")));
        spiritSpeed.setVisible(() -> targetEsp.getValue() && (mode.is("Spirits") || mode.is("Souls")));
        spiritSize.setVisible(() -> targetEsp.getValue() && (mode.is("Spirits") || mode.is("Souls")));
        soulsChase.setVisible(() -> targetEsp.getValue() && mode.is("Souls"));
        soulsCount.setVisible(() -> targetEsp.getValue() && mode.is("Souls"));
        soulsTrail.setVisible(() -> targetEsp.getValue() && mode.is("Souls"));
        soulsGlow.setVisible(() -> targetEsp.getValue() && mode.is("Souls"));
        soulsPath.setVisible(() -> targetEsp.getValue() && mode.is("Souls"));

        pulseDuration.setVisible(pulseOnHit::getValue);
        pulseBand.setVisible(pulseOnHit::getValue);
        pulseRadius.setVisible(pulseOnHit::getValue);

        for (var setting : java.util.List.of(hitParticleCount, hitImages, hitOnlyCrits, hitParticleSize, hitParticleLife, hitGlow, hitCollisions)) {
            setting.setVisible(hitParticlesEnabled::getValue);
        }
        hitGlowStrength.setVisible(() -> hitParticlesEnabled.getValue() && hitGlow.getValue());
    }

    private boolean cubeMode() {
        return targetEsp.getValue() && mode.is("Cube");
    }

    private boolean ringMode() {
        return targetEsp.getValue() && mode.is("Ring");
    }

    private boolean cubesMode() {
        return targetEsp.getValue() && mode.is("Cubes");
    }

    private boolean noorMode() {
        return targetEsp.getValue() && mode.is("Noor");
    }

    private boolean spiritsMode() {
        return targetEsp.getValue() && mode.is("Spirits");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        resetTargetAppearance();
    }

    private void resetTargetAppearance() {
        manualTarget = appearingTarget = null;
        manualTargetUntil = 0L;
        appearanceNanos = 0L;
        targetVisibility = 0f;
        modes.reset();
    }

    @Override
    public void onDisable() {
        hits.clear();
        GPU.close();
        if(SHARED_BUILDER!=null){SHARED_BUILDER.close();SHARED_BUILDER=null;}
        resetTargetAppearance();
        cubeFragments.clear();
        spiritTrail.clear();
        resetCubeState();
        stopPulse();
    }



    // ------------------------------------------------------------------ events


    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        if (mc.level == null) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        var aura = AuraModule.getInstance();
        var trigger = TriggerBot.getInstance();
        if (sources.isSelected("Manual") && (aura == null || !aura.isEnabled())
                && (trigger == null || !trigger.isEnabled()) && validTarget(target)) {
            manualTarget = target;
            manualTargetUntil = System.nanoTime() + 1_000_000_000L;
        }

        if (pulseOnHit.getValue() && target != mc.player) {
            startPulse(target.getBoundingBox().getCenter());
        }

        if (hitParticlesEnabled.getValue() && (!hitOnlyCrits.getValue() || isCriticalHit())) {
            hits.spawn(this, target);
        }
    }

    private boolean isCriticalHit() {
        return mc.player != null && mc.player.fallDistance > 0.0 && !mc.player.onGround() && !mc.player.onClimbable()
                && !mc.player.isInWater() && !mc.player.isPassenger() && !mc.player.isSprinting();
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        // The donor spawned a rising wireframe "ghost" box on a totem pop. That mode needs an
        // outline helper; the position is recorded so it can be restored without re-wiring.
        if (!event.isReceive() || !spiritsMode()) return;
        if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != ENTITY_EVENT_TOTEM) return;
        if (mc.level == null) return;
        Entity entity = packet.getEntity(mc.level);
        if (entity == null) return;
        lastTotemPop = entity.position();
    }

    private Vec3 lastTotemPop;

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        if(world!=mc.level){onDisable();world=mc.level;}
        if(mc.player==null||mc.level==null)return;
        updateCubeFragments();
        expirePulse();
        if (hitParticlesEnabled.getValue()) hits.tick(this);
        else hits.clear();

        LivingEntity tracked = cubeTrackedTarget;
        if (tracked == null) return;

        boolean dead = tracked.isRemoved() || tracked.getHealth() <= 0.0f || !tracked.isAlive();
        if (dead) {
            if (cubeShatter.getValue() && !cubeShattered && cubeLastCenter != null) {
                shatterCube();
            }
            cubeShattered = true;
            cubeTrackedTarget = null;
            cubePrevHurtTime = 0;
            return;
        }

        int hurt = tracked.hurtTime;
        if (hurt > cubePrevHurtTime) {
            int quartersY = 1 + random.nextInt(3);
            int quartersX = random.nextInt(2);
            float directionY = random.nextBoolean() ? 1.0f : -1.0f;
            float directionX = random.nextBoolean() ? 1.0f : -1.0f;
            cubeTargetY += directionY * 90.0f * quartersY;
            cubeTargetX += directionX * 90.0f * quartersX;
            cubeHitFlash = 1.0f;
        }
        cubePrevHurtTime = hurt;
    }

    // ------------------------------------------------------------------ target resolution

    private LivingEntity manualTarget;
    private long manualTargetUntil;
    private LivingEntity appearingTarget;
    private long appearanceNanos;
    private float targetVisibility;
    static float targetVertexAlpha = 1f;

    private Object world;
    private boolean validTarget(LivingEntity target) {
        return target != null && target != mc.player && target.isAlive() && !target.isRemoved()
                && mc.level != null && mc.level.getEntity(target.getId()) == target
                && mc.player != null && mc.player.distanceToSqr(target) <= targetRange.getValue() * targetRange.getValue();
    }

    private LivingEntity animatedTarget(LivingEntity target) {
        long now = System.nanoTime();
        float dt = appearanceNanos == 0L ? 1f / 60f : Math.min(.05f, (now - appearanceNanos) * 1e-9f);
        appearanceNanos = now;
        if (!targetEsp.getValue()) {
            appearingTarget = null;
            targetVisibility = 0f;
            return null;
        }
        if (!validTarget(target)) {
            appearingTarget = null;
            targetVisibility = 0f;
            return null;
        }
        if (target != null && target != appearingTarget) {
            appearingTarget = target;
            targetVisibility = 0f;
        }
        targetVisibility = Mth.clamp(targetVisibility + dt * (target != null ? 5f : -5f), 0f, 1f);
        if (targetVisibility <= 0f || !validTarget(appearingTarget)) {
            appearingTarget = null;
            targetVisibility = 0f;
        }
        return appearingTarget;
    }


    public LivingEntity findActiveTarget() {
        if (mc.player == null || mc.level == null) return null;
        if (sources.isSelected("Aura")) {
            var aura = AuraModule.getInstance();
            if (aura != null && aura.isEnabled() && validTarget(AuraModule.target)) return AuraModule.target;
        }
        if (sources.isSelected("Crosshair")) {
            // Vanilla's pick already respects blocks and interaction reach; no distant entity sweep.
            if (mc.crosshairPickEntity instanceof LivingEntity living && validTarget(living)) return living;
            if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
                    && hit.getEntity() instanceof LivingEntity living && validTarget(living)) return living;
        }
        if (sources.isSelected("Manual") && System.nanoTime() < manualTargetUntil && validTarget(manualTarget))
            return manualTarget;
        return null;
    }

    // ------------------------------------------------------------------ colours

    public int getColor() {
        String cm = colorMode.getValue();
        if ("Rainbow".equals(cm)) {
            return Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000.0f, 0.85f, 1.0f) | 0xFF000000;
        }
        if ("Custom".equals(cm)) {
            return customColor.getValue().getRGB();
        }
        return ClientTheme.primary();
    }

    // ------------------------------------------------------------------ render

    @SubscribeEvent
    public void onRender3D(WorldRenderEvent event) {
        // Without shaders the AFTER_LEVEL window is the proven one (world depth live). With an
        // Iris pack active that window is composited over by Iris, so the indicators re-render
        // at AFTER_BLIT - screen final - with a rebuilt view rotation instead of the model-view
        // stack (which is back to identity there).


        if (mc.player == null || mc.level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        Vec3 camPos = camera.position();
        if (camPos == null) return;

        LivingEntity target = animatedTarget(targetEsp.getValue() ? findActiveTarget() : null);
        boolean hasFragments = targetEsp.getValue() && mode.is("Cube") && cubeShatter.getValue() && !cubeFragments.isEmpty();
        boolean hasPulse = pulseOnHit.getValue() && pulseStart > 0 && pulseOrigin != null;
        boolean projectileFx = targetEsp.getValue()
                && (mode.is("Lightning") && lightningProjectiles.getValue() || mode.is("Chains") && chainProjectiles.getValue());
        boolean hasHits = hitParticlesEnabled.getValue() && !hits.isEmpty();
        if (target == null && !hasFragments && !hasPulse && !projectileFx && !hasHits) return;



        try {
            if (hasHits) hits.render(this, camera, camPos, event.getPartialTicks());
            if (pulseOnHit.getValue()) {
                renderPulse(camPos);
            }

            if (!targetEsp.getValue()) return;

            float pt = event.getPartialTicks();
            modes.frame(camera, target);
            if (projectileFx) {
                if (mode.is("Chains")) modes.projectileChains(pt, camPos);
                else modes.projectileLightning(pt, camPos);
            }
            // Fragments outlive the target, so they draw even when nothing is tracked.
            if (mode.is("Cube") && cubeShatter.getValue()) {
                renderCubeFragments(camPos, pt);
            }

            if (target == null || !target.isAlive()) return;

            Vec3 targetPos = new Vec3(
                    Mth.lerp(pt, target.xOld, target.getX()),
                    Mth.lerp(pt, target.yOld, target.getY()),
                    Mth.lerp(pt, target.zOld, target.getZ())
            );

            targetVertexAlpha = targetVisibility * targetVisibility * (3f - 2f * targetVisibility);
            float rx = (float) (targetPos.x - camPos.x);
            float ry = (float) (targetPos.y - camPos.y);
            float rz = (float) (targetPos.z - camPos.z);
            switch (mode.getValue()) {
                case "Cube" -> renderCube(target, targetPos, camPos, pt);
                case "Ring" -> renderRing(target, targetPos, camPos);
                case "Cubes" -> renderCubes(target, targetPos, camPos);
                case "Noor" -> renderNoor(target, targetPos, camPos);
                case "Spirits" -> renderSpirits(target, targetPos, camPos, pt);
                case "Souls" -> renderSouls(target, targetPos, camPos, pt);
                case "Skull" -> renderSkull(target, targetPos, camPos, pt);
                case "Target" -> renderTarget(target, targetPos, camPos, pt);
                case "Ghosts" -> modes.ghosts(target, rx, ry, rz);
                case "Lightning" -> modes.lightning(target, rx, ry, rz);
                case "Circle" -> {
                    switch (circleStyle.getValue()) {
                        case "Wave" -> modes.wave(target, rx, ry, rz);
                        case "Band" -> modes.band(target, rx, ry, rz);
                        case "Scan" -> modes.scan(target, rx, ry, rz);
                        default -> modes.circle(target, rx, ry, rz, camPos.distanceTo(targetPos));
                    }
                }
                case "Crystals" -> modes.crystals(target, rx, ry, rz);
                case "Chains" -> modes.chains(target, rx, ry, rz);
                default -> {
                }
            }
        } finally {
            targetVertexAlpha = 1f;
            frameViewMatrix = null;

        }
    }

    /** Non-null only inside the shader-compatible AFTER_BLIT path: replaces the model-view stack. */
    private static volatile Matrix4f frameViewMatrix;

    // ================================================================== 1. Cube

    private void renderCube(LivingEntity target, Vec3 targetPos, Vec3 camPos, float pt) {
        if (target.isAlive()) {
            if (cubeTrackedTarget != target) {
                cubePrevHurtTime = target.hurtTime;
            }
            cubeTrackedTarget = target;
            cubeShattered = false;
        }

        float entityHeight = target.getBbHeight();
        float entityWidth = target.getBbWidth();

        float half = (entityWidth * 0.5f + 0.08f) * cubeSize.getFloat();
        Vec3 center = targetPos.add(0.0, entityHeight * 0.5, 0.0);
        cubeLastCenter = center;
        cubeLastHalf = half;
        cubeLastFeetY = targetPos.y;

        long now = System.currentTimeMillis();
        float deltaTime = Mth.clamp((now - cubeLastUpdate) / 1000.0f, 0.0f, 0.1f);
        cubeLastUpdate = now;
        float ease = 1.0f - (float) Math.exp(-(6.0f + cubeRotateSpeed.getFloat() * 3.0f) * deltaTime);
        cubeRotationY += (cubeTargetY - cubeRotationY) * ease;
        cubeRotationX += (cubeTargetX - cubeRotationX) * ease;
        if (Math.abs(cubeTargetY - cubeRotationY) < 0.05f) cubeRotationY = cubeTargetY;
        if (Math.abs(cubeTargetX - cubeRotationX) < 0.05f) cubeRotationX = cubeTargetX;

        cubeHitFlash = Math.max(0.0f, cubeHitFlash - deltaTime * 2.2f);

        final int baseColor = getColor();
        int color = baseColor;
        if (cubeColorOnHit.getValue() && cubeHitFlash > 0.0f) {
            color = blendColors(baseColor, 0xFFFF1919, cubeHitFlash);
        }
        // Final copy: the lambdas below capture this, and `color` above is reassigned.
        final int barColor = replaceAlpha(color, 210);

        Matrix4f rotation = new Matrix4f()
                .rotateY((float) Math.toRadians(cubeRotationY))
                .rotateX((float) Math.toRadians(cubeRotationX));

        float coreHalf = distanceScale(camPos, center) * cubeThickness.getFloat() * 0.05f;
        float cornerHalf = coreHalf * 1.35f;

        float rx = (float) (center.x - camPos.x);
        float ry = (float) (center.y - camPos.y);
        float rz = (float) (center.z - camPos.z);

        // ---- solid edges + corner cubes ----
        MeshData body = build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, mesh -> {
            for (float[] arm : CUBE_ARMS) {
                emitBar(mesh, rotation, rx, ry, rz, arm, half, coreHalf, barColor, false);
            }
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        emitBox(mesh, rotation, rx, ry, rz,
                                sx * half, sy * half, sz * half, cornerHalf, barColor);
                    }
                }
            }
        });
        if (body != null) {
            drawMesh(body, COLOR_TRANSLUCENT, "include:pvp_cube", null);
        }

        // ---- additive glow halo ----
        if (cubeGlow.getValue()) {
            int glowColor = multiplyAlpha(replaceAlpha(baseColor, 55), 0.45f);
            MeshData glow = build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, mesh -> {
                for (float[] arm : CUBE_ARMS) {
                    emitBar(mesh, rotation, rx, ry, rz, arm, half, cornerHalf * 2.4f, glowColor, true);
                }
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        for (int sz = -1; sz <= 1; sz += 2) {
                            emitBox(mesh, rotation, rx, ry, rz,
                                    sx * half, sy * half, sz * half, cornerHalf * 2.9f, glowColor);
                        }
                    }
                }
            });
            if (glow != null) {
                drawMesh(glow, COLOR_ADDITIVE, "include:pvp_cube_glow", null);
            }
        }
    }

    /**
     * Donor {@code buildCubeArms}: three orthogonal arms per corner, 8 corners -> 24 arms.
     * Stored as {@code {cornerSignX, cornerSignY, cornerSignZ, axis}} where {@code axis} picks
     * which coordinate is pulled inward toward {@code half - half*0.46}.
     */
    private static final float[][] CUBE_ARMS = buildArms();

    private static float[][] buildArms() {
        float[][] arms = new float[24][];
        int index = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    arms[index++] = new float[]{sx, sy, sz, 0};
                    arms[index++] = new float[]{sx, sy, sz, 1};
                    arms[index++] = new float[]{sx, sy, sz, 2};
                }
            }
        }
        return arms;
    }

    /**
     * Emits one arm as a thin box (donor {@code drawCubeEdgeBar} + {@code emitCubeBox}). A bar is a
     * 4-sided prism; at these sizes a crossed pair of quads reads identically and is half the
     * geometry, so the bar is emitted as two perpendicular quads around its axis.
     */
    private void emitBar(BufferBuilder mesh, Matrix4f rotation, float rx, float ry, float rz,
                         float[] arm, float half, float thickness, int color, boolean cross) {
        if (alpha(color) <= 0) return;

        float armLength = half * 0.46f;
        float inner = half - armLength;
        float cx = arm[0] * half;
        float cy = arm[1] * half;
        float cz = arm[2] * half;
        int axis = (int) arm[3];

        float ex = cx, ey = cy, ez = cz;
        switch (axis) {
            case 0 -> ex = arm[0] * inner;
            case 1 -> ey = arm[1] * inner;
            default -> ez = arm[2] * inner;
        }

        // Two perpendicular normals around the arm.
        float nx, ny, nz, mx, my, mz;
        if (axis == 0) {
            nx = 0; ny = thickness; nz = 0;
            mx = 0; my = 0; mz = thickness;
        } else if (axis == 1) {
            nx = thickness; ny = 0; nz = 0;
            mx = 0; my = 0; mz = thickness;
        } else {
            nx = thickness; ny = 0; nz = 0;
            mx = 0; my = thickness; mz = 0;
        }

        // Quad 1 spans normal n; quad 2 spans normal m.
        emitPrismQuad(mesh, rotation, rx, ry, rz, cx, cy, cz, ex, ey, ez, nx, ny, nz, color);
        if (!cross) {
            emitPrismQuad(mesh, rotation, rx, ry, rz, cx, cy, cz, ex, ey, ez, mx, my, mz, color);
        }
    }

    /** One quad of a bar: (start-n, start+n, end+n, end-n). */
    private void emitPrismQuad(BufferBuilder mesh, Matrix4f rotation, float rx, float ry, float rz,
                               float sx, float sy, float sz, float ex, float ey, float ez,
                               float nx, float ny, float nz, int color) {
        quad(mesh, rotation, rx, ry, rz,
                sx - nx, sy - ny, sz - nz,
                sx + nx, sy + ny, sz + nz,
                ex + nx, ey + ny, ez + nz,
                ex - nx, ey - ny, ez - nz, color);
    }

    /** Emits a small solid box (donor {@code drawCornerCube}), all six faces. */
    private void emitBox(BufferBuilder mesh, Matrix4f rotation, float rx, float ry, float rz,
                         float x, float y, float z, float t, int color) {
        if (alpha(color) <= 0) return;
        float x0 = x - t, x1 = x + t;
        float y0 = y - t, y1 = y + t;
        float z0 = z - t, z1 = z + t;

        // -Z / +Z
        quad(mesh, rotation, rx, ry, rz, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, color);
        quad(mesh, rotation, rx, ry, rz, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, color);
        // -X / +X
        quad(mesh, rotation, rx, ry, rz, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, color);
        quad(mesh, rotation, rx, ry, rz, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, color);
        // -Y / +Y
        quad(mesh, rotation, rx, ry, rz, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, color);
        quad(mesh, rotation, rx, ry, rz, x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, color);
    }

    // ---- death shatter

    private void shatterCube() {
        float half = cubeLastHalf;
        Vec3 center = cubeLastCenter;
        if (center == null) return;

        Matrix4f rotation = new Matrix4f()
                .rotateY((float) Math.toRadians(cubeRotationY))
                .rotateX((float) Math.toRadians(cubeRotationX));
        double groundY = cubeLastFeetY + 0.05;

        for (float[] arm : CUBE_ARMS) {
            float armLength = half * 0.46f;
            float inner = half - armLength;
            float cx = arm[0] * half, cy = arm[1] * half, cz = arm[2] * half;
            int axis = (int) arm[3];
            float ex = cx, ey = cy, ez = cz;
            switch (axis) {
                case 0 -> ex = arm[0] * inner;
                case 1 -> ey = arm[1] * inner;
                default -> ez = arm[2] * inner;
            }

            tmp.set(cx, cy, cz).mulPosition(rotation);
            float startX = tmp.x, startY = tmp.y, startZ = tmp.z;
            tmp.set(ex, ey, ez).mulPosition(rotation);
            float endX = tmp.x, endY = tmp.y, endZ = tmp.z;

            Vec3 worldStart = center.add(startX, startY, startZ);
            Vec3 worldEnd = center.add(endX, endY, endZ);
            Vec3 fragmentCenter = worldStart.add(worldEnd).multiply(0.5, 0.5, 0.5);
            Vector3f offset = new Vector3f(
                    (float) (worldEnd.x - fragmentCenter.x),
                    (float) (worldEnd.y - fragmentCenter.y),
                    (float) (worldEnd.z - fragmentCenter.z));

            Vec3 outward = fragmentCenter.subtract(center);
            if (outward.lengthSqr() < 1e-6) {
                outward = new Vec3(random.nextDouble() - 0.5, 0.0, random.nextDouble() - 0.5);
            }
            outward = outward.normalize();
            double speed = 0.12 + random.nextDouble() * 0.14;
            Vec3 velocity = outward.multiply(speed, speed, speed).add(
                    (random.nextDouble() - 0.5) * 0.06,
                    0.08 + random.nextDouble() * 0.16,
                    (random.nextDouble() - 0.5) * 0.06);

            Vector3f spinAxis = new Vector3f(
                    random.nextFloat() - 0.5f,
                    random.nextFloat() - 0.5f,
                    random.nextFloat() - 0.5f);
            if (spinAxis.lengthSquared() < 1e-6f) spinAxis.set(0, 1, 0);
            spinAxis.normalize();

            cubeFragments.add(new CubeFragment(fragmentCenter, offset, velocity, spinAxis,
                    0.12f + random.nextFloat() * 0.28f, groundY, 50 + random.nextInt(30)));
        }
        while (cubeFragments.size() > MAX_FRAGMENTS) cubeFragments.remove(0);
    }

    private void updateCubeFragments() {
        if (cubeFragments.isEmpty()) return;
        for (Iterator<CubeFragment> it = cubeFragments.iterator(); it.hasNext(); ) {
            CubeFragment f = it.next();
            f.previousCenter = f.center;
            f.previousOffset.set(f.offset);
            f.center = f.center.add(f.velocity);
            f.velocity = new Vec3(f.velocity.x * 0.98, f.velocity.y - 0.028, f.velocity.z * 0.98);

            if (f.center.y <= f.groundY && f.velocity.y < 0.0) {
                f.center = new Vec3(f.center.x, f.groundY, f.center.z);
                f.velocity = new Vec3(f.velocity.x * 0.55, -f.velocity.y * 0.38, f.velocity.z * 0.55);
                f.angularSpeed *= 0.6f;
            }

            float cos = (float) Math.cos(f.angularSpeed);
            float sin = (float) Math.sin(f.angularSpeed);
            // Axis-angle rotation of the offset vector (Rodrigues).
            Vector3f k = f.axis;
            Vector3f v = f.offset;
            float dot = k.dot(v);
            Vector3f cross = new Vector3f(k).cross(v);
            v.mul(cos)
                    .add(cross.mul(sin))
                    .add(k.mul(dot * (1f - cos)));

            f.age++;
            if (f.age >= f.maxAge) it.remove();
        }
    }

    private void renderCubeFragments(Vec3 camPos, float pt) {
        if (cubeFragments.isEmpty()) return;
        int baseColor = getColor();
        float coreHalf = cubeThickness.getFloat() * 0.03f;

        MeshData mesh = build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, builder -> {
            for (CubeFragment f : cubeFragments) {
                float life = 1.0f - (float) f.age / Math.max(1, f.maxAge);
                float fade = life > 0.3f ? 1.0f : Math.max(0.0f, life / 0.3f);
                if (fade <= 0.0f) continue;

                Vec3 c = new Vec3(
                        Mth.lerp(pt, f.previousCenter.x, f.center.x),
                        Mth.lerp(pt, f.previousCenter.y, f.center.y),
                        Mth.lerp(pt, f.previousCenter.z, f.center.z));
                Vector3f offset = fragOffset.set(f.previousOffset).lerp(f.offset, pt);
                int color = multiplyAlpha(replaceAlpha(baseColor, 255), fade);
                emitFragmentBar(builder, c, camPos, offset, coreHalf, color);
            }
        });
        if (mesh != null) {
            drawMesh(mesh, COLOR_TRANSLUCENT, "include:pvp_fragments", null);
        }
    }

    /** A fragment is the current offset vector drawn as a small crossed prism. */
    private void emitFragmentBar(BufferBuilder mesh, Vec3 center, Vec3 camPos,
                                 Vector3f offset, float thickness, int color) {
        if (alpha(color) <= 0) return;
        Vector3f dir = fragDir.set(offset);
        if (dir.lengthSquared() < 1e-10f) return;
        dir.normalize();
        Vector3f ref = Math.abs(dir.y) < 0.99f ? fragRef.set(0, 1, 0) : fragRef.set(1, 0, 0);
        Vector3f n1 = fragN1.set(dir).cross(ref).normalize().mul(thickness);
        Vector3f n2 = fragN2.set(dir).cross(n1).normalize().mul(thickness);

        float cx = (float) (center.x - camPos.x);
        float cy = (float) (center.y - camPos.y);
        float cz = (float) (center.z - camPos.z);
        float sx = cx - offset.x, sy = cy - offset.y, sz = cz - offset.z;
        float ex = cx + offset.x, ey = cy + offset.y, ez = cz + offset.z;

        rawQuad(mesh,
                sx - n1.x, sy - n1.y, sz - n1.z,
                sx + n1.x, sy + n1.y, sz + n1.z,
                ex + n1.x, ey + n1.y, ez + n1.z,
                ex - n1.x, ey - n1.y, ez - n1.z, color);
        rawQuad(mesh,
                sx - n2.x, sy - n2.y, sz - n2.z,
                sx + n2.x, sy + n2.y, sz + n2.z,
                ex + n2.x, ey + n2.y, ez + n2.z,
                ex - n2.x, ey - n2.y, ez - n2.z, color);
    }

    public void resetCubeState() {
        cubeRotationY = 0f;
        cubeRotationX = 0f;
        cubeTargetY = 0f;
        cubeTargetX = 0f;
        cubeHitFlash = 0f;
        cubePrevHurtTime = 0;
        cubeLastUpdate = System.currentTimeMillis();
        cubeTrackedTarget = null;
        cubeLastCenter = null;
        cubeLastHalf = 0.6f;
        cubeLastFeetY = 0.0;
        cubeShattered = false;
        cubeFragments.clear();
    }

    /** Donor {@code getCubeDistanceScale}. */
    private float distanceScale(Vec3 camPos, Vec3 center) {
        double dx = center.x - camPos.x;
        double dy = center.y - camPos.y;
        double dz = center.z - camPos.z;
        return (float) Math.max(0.1, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.007);
    }

    // ================================================================== 2. Ring

    private void renderRing(LivingEntity target, Vec3 targetPos, Vec3 camPos) {
        GpuTextureView ringTex = PvpAssets.ring();
        if (ringTex == null) return;

        float height = target.getBbHeight();
        double period = Math.max(1.0, 2000.0 / ringSpeed.getFloat());
        double time = (System.currentTimeMillis() % (long) period) / period;
        float yOffset = (float) (Math.sin(time * Math.PI * 2.0) * 0.5 + 0.5) * (height - 0.2f) + 0.1f;

        float half = ringRadius.getFloat() * 1.25f;
        int base = getColor();
        final int col = target.hurtTime > 0 ? 0xFFFF3232 : (base >>> 24) == 0 ? base | 0xFF000000 : base;
        float cx = (float) (targetPos.x - camPos.x);
        float cy = (float) (targetPos.y + yOffset - camPos.y);
        float cz = (float) (targetPos.z - camPos.z);

        MeshData mesh = buildTextured(builder -> texQuad(builder,
                cx - half, cy, cz - half,
                cx + half, cy, cz - half,
                cx + half, cy, cz + half,
                cx - half, cy, cz + half, col));
        if (mesh != null) {
            drawMesh(mesh, TEX_GROUND_ADDITIVE, "include:pvp_ring", ringTex);
        }
    }

    // ================================================================== 3. Orbiting cubes

    private void renderCubes(LivingEntity target, Vec3 targetPos, Vec3 camPos) {
        int count = Math.min(Math.max(1, cubesCount.getValue().intValue()), CUBES_ORBIT_MAX);
        float size = cubesSize.getFloat();
        float speed = cubesSpeed.getFloat();
        float tilt = cubesTilt.getFloat();
        final int baseColor = target.hurtTime > 0 ? 0xFFFF3C3C : getColor();

        float time = (System.currentTimeMillis() % 360000L) * 0.002f * speed;
        float radius = target.getBbWidth() * 1.35f;
        float height = target.getBbHeight();

        MeshData mesh = build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, builder -> {
            for (int i = 0; i < count; i++) {
                float a = time + (float) (i * Math.PI * 2.0 / count);
                float cx = (float) (targetPos.x + Math.cos(a) * radius - camPos.x);
                float cy = (float) (targetPos.y + height * 0.5 + Math.sin(a * 2.0) * 0.25 - camPos.y);
                float cz = (float) (targetPos.z + Math.sin(a) * radius - camPos.z);

                Matrix4f rot = new Matrix4f()
                        .rotateY(-a)
                        .rotateX((float) Math.toRadians(tilt));

                // Small wireframe-ish cube: six faces at low alpha plus a brighter top.
                emitBox(builder, rot, cx, cy, cz, 0f, 0f, 0f, size, replaceAlpha(baseColor, 90));
                emitBox(builder, rot, cx, cy, cz, 0f, 0f, 0f, size * 0.62f, baseColor);
            }
        });
        if (mesh != null) {
            drawMesh(mesh, COLOR_TRANSLUCENT, "include:pvp_cubes", null);
        }
    }

    // ================================================================== 4. Noor

    private void renderNoor(LivingEntity target, Vec3 targetPos, Vec3 camPos) {
        // Donor: union.png billboard rotating around the target, red flash on hurt.
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        final int color = target.hurtTime > 0 ? 0xFFFF3C3C : getColor();

        float angle = (System.currentTimeMillis() % 36000L) * 0.1f * noorSpeed.getFloat();
        float size = noorSize.getFloat() * 0.85f;
        float y = (float) (targetPos.y + target.getBbHeight() * 0.55 - camPos.y);
        float x = (float) (targetPos.x - camPos.x);
        float z = (float) (targetPos.z - camPos.z);

        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f up = new Vector3f(0, 1, 0);
        try {
            var rotationQ = camera.rotation();
            right.set(1, 0, 0).rotate(rotationQ);
            up.set(0, 1, 0).rotate(rotationQ);
        } catch (Throwable ignored) {
        }
        // Spin in the view plane, like the donor's rotateY around the camera axis.
        float cos = (float) Math.cos(Math.toRadians(angle));
        float sin = (float) Math.sin(Math.toRadians(angle));
        Vector3f r = new Vector3f(right).mul(cos).add(new Vector3f(up).mul(sin)).mul(size);
        Vector3f u = new Vector3f(up).mul(cos).sub(new Vector3f(right).mul(sin)).mul(size);

        GpuTextureView tex = boundTexture(TEX_UNION);
        if (tex == null) return;

        MeshData mesh = buildTextured(builder -> {
            texQuad(builder, x - r.x - u.x, y - r.y - u.y, z - r.z - u.z,
                    x + r.x - u.x, y + r.y - u.y, z + r.z - u.z,
                    x + r.x + u.x, y + r.y + u.y, z + r.z + u.z,
                    x - r.x + u.x, y - r.y + u.y, z - r.z + u.z, color);
        });
        if (mesh != null) {
            drawMesh(mesh, TEX_TRANSLUCENT, "include:pvp_noor", tex);
        }
    }

    // ================================================================== 5. Spirits

    /**
     * Donor {@code renderSpirits}: a trail of small billboards following parametric paths around
     * the target.
     *
     * <p>Unlike the other modes this one is genuinely geometry-heavy: {@code spiritCount} spirits x
     * {@link #SPIRIT_TRAIL_LEN} trail points = 3 x 18 = 54 quads at the defaults, which is well
     * within budget as a single batched mesh. The donor's own "custom" mode used
     * {@code count * length} freshly-computed segments (up to 320); using the recorded trail instead
     * keeps the visual (a curling tail behind each spirit) at a fraction of the vertex count.</p>
     */
    private long spiritSampleAt;

    private void renderSpirits(LivingEntity target, Vec3 targetPos, Vec3 camPos, float pt) {
        int count = Math.min(Math.max(1, spiritCount.getValue().intValue()), 8);
        while (spiritTrail.size() < count) {
            spiritTrail.add(new float[SPIRIT_TRAIL_LEN * 3]);
        }
        while (spiritTrail.size() > count) {
            spiritTrail.remove(spiritTrail.size() - 1);
        }

        float radius = spiritRadius.getFloat() * Math.max(0.65f, target.getBbWidth() * 1.1f);
        float height = target.getBbHeight();
        float speed = spiritSpeed.getFloat();
        float angle = (System.currentTimeMillis() % 100000L) * 0.001f * speed;
        final int baseColor = target.hurtTime > 0 ? 0xFFFF3C3C : getColor();
        float sz = spiritSize.getFloat() * 0.32f;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f up = new Vector3f(0, 1, 0);
        try {
            var rotationQ = camera.rotation();
            right.set(1, 0, 0).rotate(rotationQ);
            up.set(0, 1, 0).rotate(rotationQ);
        } catch (Throwable ignored) {
        }

        // Advance every trail at a fixed 60 Hz sample rate: trail length no longer depends on FPS and high-FPS frames skip the shift.
        long sampleNow = System.currentTimeMillis();
        if (sampleNow - spiritSampleAt >= 16) {
            spiritSampleAt = sampleNow;
            String pattern = spiritPattern.getValue();
            for (int i = 0; i < count; i++) {
                float phase = (float) (i * Math.PI * 2.0 / count);
                Vec3 local = spiritTrajectory(pattern, angle + phase, phase, radius, height);
                float[] trail = spiritTrail.get(i);
                System.arraycopy(trail, 0, trail, 3, trail.length - 3);
                trail[0] = (float) local.x;
                trail[1] = (float) local.y;
                trail[2] = (float) local.z;
            }
        }

        GpuTextureView tex = boundTexture(TEX_PARTICLES_3);
        if (tex == null) tex = boundTexture(TEX_PARTICLES);
        if (tex == null) return;
        GpuTextureView finalTex = tex;

        MeshData mesh = buildTextured(builder -> {
            for (int i = 0; i < count; i++) {
                float[] trail = spiritTrail.get(i);
                for (int j = 0; j < SPIRIT_TRAIL_LEN; j++) {
                    float px = trail[j * 3];
                    float py = trail[j * 3 + 1];
                    float pz = trail[j * 3 + 2];
                    if (px == 0f && py == 0f && pz == 0f) break;
                    float progress = 1.0f - (float) j / SPIRIT_TRAIL_LEN;
                    int alpha = (int) (progress * 240);
                    if (alpha <= 5) continue;
                    int color = replaceAlpha(baseColor, alpha);
                    float s = sz * (0.4f + progress * 0.6f);
                    float x = (float) (targetPos.x + px - camPos.x);
                    float y = (float) (targetPos.y + py - camPos.y);
                    float z = (float) (targetPos.z + pz - camPos.z);
                    emitBillboardQuad(builder, x, y, z, right, up, s, color);
                }
            }
        });
        if (mesh != null) {
            drawMesh(mesh, TEX_ADDITIVE, "include:pvp_spirits", finalTex);
        }
    }

    /**
     * Donor {@code calculateSpiritTrajectory}: the parametric paths. Kept verbatim so restoring the
     * donor's per-segment look is a submission change only.
     */
    private Vec3 spiritTrajectory(String pattern, float t, float phase, float radius, float height) {
        float angle = t + phase;
        double cy = height * 0.5;
        return switch (pattern) {
            case "Spiral" -> new Vec3(Math.cos(angle) * radius, ((t * 0.4) % Math.max(0.01, height) + height) % Math.max(0.01, height),
                    Math.sin(angle) * radius);
            case "Double Spiral" -> {
                boolean second = phase > Math.PI - 0.05f || (((int) (phase * 10)) % 2 == 1);
                float dir = second ? -1.0f : 1.0f;
                float cur = (t * 1.6f * dir) + phase;
                double yMin = height * 0.18;
                double yMax = height * 0.88;
                double y = yMin + (Math.sin(t * 1.5f + (second ? Math.PI : 0)) * 0.5 + 0.5) * (yMax - yMin);
                double tiltX = Math.cos(t * 1.5f) * 0.22 * dir;
                double tiltZ = Math.sin(t * 1.5f) * 0.22 * dir;
                yield new Vec3(Math.cos(cur) * radius + tiltX, y, Math.sin(cur) * radius + tiltZ);
            }
            case "Lissajous" -> new Vec3(Math.sin(angle * 2.0) * radius,
                    cy + Math.sin(angle * 3.0) * (height * 0.4), Math.cos(angle * 2.0) * radius);
            case "Wave" -> new Vec3(Math.cos(angle) * radius, cy + Math.sin(angle * 4.0) * 0.38,
                    Math.sin(angle) * radius);
            case "Butterfly" -> {
                double factor = Math.exp(Math.cos(angle)) - 2.0 * Math.cos(4.0 * angle);
                yield new Vec3(Math.sin(angle) * factor * radius * 0.35,
                        cy + Math.cos(angle * 2.0) * 0.35, Math.cos(angle) * factor * radius * 0.35);
            }
            default -> new Vec3(Math.cos(angle) * radius, cy + Math.sin(angle * 2.0) * 0.3,
                    Math.sin(angle) * radius);
        };
    }

    static void emitBillboardQuad(BufferBuilder mesh, float x, float y, float z,
                                          Vector3f right, Vector3f up, float s, int color) {
        float rx = right.x * s, ry = right.y * s, rz = right.z * s;
        float ux = up.x * s, uy = up.y * s, uz = up.z * s;
        texQuad(mesh,
                x - rx - ux, y - ry - uy, z - rz - uz,
                x + rx - ux, y + ry - uy, z + rz - uz,
                x + rx + ux, y + ry + uy, z + rz + uz,
                x - rx + ux, y - ry + uy, z - rz + uz, color);
    }

    /**
     * Untransformed textured quad (UVs 0,0 / 1,0 / 1,1 / 0,1 in the given corner order) - used by
     * every billboard, which already carries its screen-facing basis in the positions.
     */
    static void texQuad(BufferBuilder mesh,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3, int color) {
        color = multiplyAlpha(color, targetVertexAlpha);
        mesh.addVertex(x0, y0, z0).setUv(0f, 0f).setColor(color);
        mesh.addVertex(x1, y1, z1).setUv(1f, 0f).setColor(color);
        mesh.addVertex(x2, y2, z2).setUv(1f, 1f).setColor(color);
        mesh.addVertex(x0, y0, z0).setUv(0f, 0f).setColor(color);
        mesh.addVertex(x2, y2, z2).setUv(1f, 1f).setColor(color);
        mesh.addVertex(x3, y3, z3).setUv(0f, 1f).setColor(color);
    }

    // ================================================================== 6. Souls / Glow
    private double soulsAnimPhase = 0.0;
    private double targetDiamondRot = 0.0;

    // Chase-trail state: spring-smoothed swarm centre + adaptive spread driven by the trail length.
    private Vec3 soulsChaseCenter;
    private Vec3 soulsPrevAnchor;
    private float soulsSpread = 1.0f;
    private long soulsLastFrame;
    private int soulsTrackedId = -1;
    /** Per-soul path index when "Random" is selected - stable while the target stays the same. */
    private int[] soulsRandomPaths = new int[12];
    private static final String[] SOULS_PATHS = {"Orbit", "Spiral", "Lissajous", "Wave", "Butterfly", "Double Spiral"};

    private String soulsPathFor(int soul, int count) {
        String chosen = soulsPath.getValue();
        if (!chosen.equals("Random")) return chosen;
        if (soulsRandomPaths.length < count) soulsRandomPaths = new int[count];
        return SOULS_PATHS[soulsRandomPaths[soul] % SOULS_PATHS.length];
    }

    private static final int SOUL_HISTORY = 48;
    private final Vec3[][] soulHistory = new Vec3[12][SOUL_HISTORY];
    private final long[] soulTimes = new long[SOUL_HISTORY];
    private int soulHead, soulSamples;
    private long soulSampleAt;
    private net.minecraft.client.multiplayer.ClientLevel soulWorld;

    private Vec3[] soulHeads = new Vec3[12];
    private final Vector3f soulsRight = new Vector3f(), soulsUp = new Vector3f();

    private void renderSouls(LivingEntity target, Vec3 targetPos, Vec3 camPos, float pt) {
        GpuTextureView texture = boundTexture(TEX_TARGET_GLOW);
        if (texture == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f right = soulsRight.set(1,0,0).rotate(camera.rotation());
        Vector3f up = soulsUp.set(0,1,0).rotate(camera.rotation());
        long now = System.currentTimeMillis();
        float dt = soulsLastFrame == 0 ? .016f : Mth.clamp((now-soulsLastFrame)/1000f,0,.1f);
        soulsLastFrame = now;
        int count = Math.min(12, Math.max(1,soulsCount.getValue().intValue()));
        // Store world positions: camera motion must never drag the swarm or its history.
        if (soulWorld != mc.level || soulsTrackedId != target.getId() || soulsChaseCenter == null
                || soulsChaseCenter.distanceToSqr(targetPos) > 64 || now-soulSampleAt > 1000) {
            soulWorld = mc.level;
            soulsTrackedId = target.getId();
            soulsChaseCenter = targetPos;
            soulHead = soulSamples = 0;
            soulSampleAt = 0;
            java.util.Random random = new Random(target.getId()*31L+7);
            for (int i=0;i<12;i++) { soulsRandomPaths[i]=random.nextInt(SOULS_PATHS.length); java.util.Arrays.fill(soulHistory[i],null); }
        }
        float trail = soulsTrail.getFloat();
        float catchUp = 1-(float)Math.exp(-dt*(16-10*trail));
        soulsChaseCenter = soulsChase.getValue()
                ? soulsChaseCenter.lerp(targetPos,catchUp) : targetPos;
        double time = (now%360000L)*.001*3.333*Math.max(.5,spiritSpeed.getFloat());
        float radius = Math.max(target.getBbWidth()*.65f,spiritRadius.getFloat());
        Vec3[] heads = soulHeads.length >= count ? soulHeads : (soulHeads = new Vec3[12]);
        for(int i=0;i<count;i++) {
            float phase = (float)(i*Math.PI*2/count);
            // Trajectories already contain their own 0..height offset; the anchor is at the feet.
            Vec3 local = spiritTrajectory(soulsPathFor(i,count),(float)time,phase,radius,target.getBbHeight());
            double safeY = Mth.clamp(local.y,target.getBbHeight()*.12,target.getBbHeight()*.88);
            heads[i] = soulsChaseCenter.add(local.x,safeY,local.z);
        }
        if(now-soulSampleAt >= 16) {
            soulHead = (soulHead+1)%SOUL_HISTORY;
            soulTimes[soulHead] = now;
            for(int i=0;i<count;i++) soulHistory[i][soulHead] = heads[i];
            soulSamples = Math.min(SOUL_HISTORY,soulSamples+1);
            soulSampleAt = now;
        }
        // Hurt flash fades out with hurtTime instead of snapping between two colours.
        int color = blendColors(getColor() | 0xFF000000, 0xFFFF3A3A, target.hurtTime/10f);
        int hot = blendColors(color, 0xFFFFFFFF, .6f);
        float size = spiritSize.getFloat(), glow = soulsGlow.getFloat();
        int tail = Math.min(soulSamples, Math.round(trail*(SOUL_HISTORY-1)));
        double flickerTime = now*.001;
        MeshData mesh = buildTextured(builder -> {
            for(int i=0;i<count;i++) {
                float hx = (float) (heads[i].x - camPos.x), hy = (float) (heads[i].y - camPos.y), hz = (float) (heads[i].z - camPos.z);
                // Trail first so the bright heads blend on top; it whitens toward the head and tapers away from it.
                for(int j=tail-1;j>=1;j--) {
                    int index = (soulHead-j+SOUL_HISTORY)%SOUL_HISTORY;
                    Vec3 point = soulHistory[i][index];
                    if(point==null || now-soulTimes[index] > 100+trail*800) continue;
                    float fade = 1-j/(float)tail;
                    emitBillboardQuad(builder,(float)(point.x-camPos.x),(float)(point.y-camPos.y),(float)(point.z-camPos.z),right,up,
                            (.03f+.2f*fade)*size,replaceAlpha(blendColors(color,hot,fade*fade),Math.round(165*fade*(float)Math.sqrt(fade))));
                }
                float flicker = 1+.09f*(float)Math.sin(flickerTime*7.3+i*1.7);
                emitBillboardQuad(builder,hx,hy,hz,right,up,.95f*size*flicker,replaceAlpha(color,Math.round(26*glow)));
                emitBillboardQuad(builder,hx,hy,hz,right,up,.45f*size*flicker,replaceAlpha(color,Math.round(Math.min(255,95*glow))));
                emitBillboardQuad(builder,hx,hy,hz,right,up,.2f*size,replaceAlpha(color,235));
                emitBillboardQuad(builder,hx,hy,hz,right,up,.1f*size,replaceAlpha(hot,255));
            }
        });
        if(mesh!=null) drawMesh(mesh,TEX_ADDITIVE,"include:pvp_souls",texture);
    }

    private void renderSkull(LivingEntity target, Vec3 targetPos, Vec3 camPos, float pt) {
        float maxHp = Math.max(1.0f, target.getMaxHealth());
        float currentHp = target.getHealth();
        float ratio = currentHp / maxHp;

        Identifier skullId = TEX_SKULL_0;
        if (ratio < 0.33f) {
            skullId = TEX_SKULL_2;
        } else if (ratio < 0.66f) {
            skullId = TEX_SKULL_1;
        }

        GpuTextureView skullTex = boundTexture(skullId);
        if (skullTex == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f up = new Vector3f(0, 1, 0);
        try {
            var rotationQ = camera.rotation();
            right.set(1, 0, 0).rotate(rotationQ);
            up.set(0, 1, 0).rotate(rotationQ);
        } catch (Throwable ignored) {}

        float floatOffset = (float) Math.sin((System.currentTimeMillis() % 2000L) / 2000.0 * Math.PI * 2.0) * 0.05f;
        float x = (float) (targetPos.x - camPos.x);
        float y = (float) (targetPos.y + target.getBbHeight() * 0.5f + floatOffset - camPos.y);
        float z = (float) (targetPos.z - camPos.z);

        // The skull texture ships upside down for this billboard basis - flip V to face upright.
        up.negate();

        final int skullColor = target.hurtTime > 0 ? 0xFFFF3C3C : 0xFFFFFFFF;
        float size = 0.52f;

        MeshData mesh = buildTextured(builder -> {
            emitBillboardQuad(builder, x, y, z, right, up, size, skullColor);
        });
        if (mesh != null) {
            drawMesh(mesh, TEX_TRANSLUCENT, "include:pvp_skull", skullTex);
        }
    }

    // ================================================================== 8. Target / Diamond
    private void renderTarget(LivingEntity target, Vec3 targetPos, Vec3 camPos, float pt) {
        GpuTextureView targetTex = boundTexture(TEX_TARGET_DIAMOND);
        if (targetTex == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f up = new Vector3f(0, 1, 0);
        try {
            var rotationQ = camera.rotation();
            right.set(1, 0, 0).rotate(rotationQ);
            up.set(0, 1, 0).rotate(rotationQ);
        } catch (Throwable ignored) {}

        // Same V flip as the skull: the texture ships upside down for this billboard basis
        up.negate();
        targetDiamondRot = (System.currentTimeMillis() % 360000L) * .12;
        float rotAngle = (float) (Math.sin(Math.toRadians(targetDiamondRot)) * 180.0);
        float rad = (float) Math.toRadians(rotAngle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        Vector3f r = new Vector3f(right).mul(cos).add(new Vector3f(up).mul(sin));
        Vector3f u = new Vector3f(up).mul(cos).sub(new Vector3f(right).mul(sin));

        float x = (float) (targetPos.x - camPos.x);
        float y = (float) (targetPos.y + target.getBbHeight() * 0.5 - camPos.y);
        float z = (float) (targetPos.z - camPos.z);

        // The accent colour carries no alpha (0x00RRGGBB): without this the quad is fully transparent
        int base = getColor();
        final int col = target.hurtTime > 0 ? 0xFFFF3232 : (base >>> 24) == 0 ? base | 0xFF000000 : base;
        float size = 0.55f;

        MeshData mesh = buildTextured(builder -> {
            texQuad(builder,
                    x - (r.x + u.x) * size, y - (r.y + u.y) * size, z - (r.z + u.z) * size,
                    x + (r.x - u.x) * size, y + (r.y - u.y) * size, z + (r.z - u.z) * size,
                    x + (r.x + u.x) * size, y + (r.y + u.y) * size, z + (r.z + u.z) * size,
                    x - (r.x - u.x) * size, y - (r.y - u.y) * size, z - (r.z - u.z) * size, col);
        });
        if (mesh != null) {
            drawMesh(mesh, TEX_TRANSLUCENT, "include:pvp_target", targetTex);
        }
    }

    private void startPulse(Vec3 origin) {
        pulseOrigin = origin;
        pulseStart = System.currentTimeMillis();
    }

    private void stopPulse() {
        pulseOrigin = null;
        pulseStart = -1L;
    }

    private void expirePulse() {
        if (pulseStart <= 0) return;
        long elapsed = System.currentTimeMillis() - pulseStart;
        if (elapsed > (long) (pulseDuration.getValue() * 1000.0)) {
            stopPulse();
        }
    }

    private void renderPulse(Vec3 camPos) {
        if (pulseStart <= 0 || pulseOrigin == null) return;

        long elapsed = System.currentTimeMillis() - pulseStart;
        float life = Math.max(0.001f, pulseDuration.getFloat());
        float progress = Mth.clamp(elapsed / 1000.0f / life, 0.0f, 1.0f);
        if (progress >= 1.0f) return;

        float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
        float radius = (float) (pulseRadius.getValue() * eased);
        float band = (float) pulseBand.getValue().floatValue() * (0.35f + 0.65f * (1.0f - progress));
        float fade = 1.0f - progress;

        int baseColor = getColor();
        int color = replaceAlpha(baseColor, Math.round(150.0f * fade * fade));

        float ox = (float) (pulseOrigin.x - camPos.x);
        float oy = (float) (pulseOrigin.y - camPos.y);
        float oz = (float) (pulseOrigin.z - camPos.z);

        MeshData mesh = build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, builder -> {
            emitPulseRing(builder, ox, oy, oz, radius, band, 0, color);
            emitPulseRing(builder, ox, oy, oz, radius, band, 1, color);
            emitPulseRing(builder, ox, oy, oz, radius, band, 2, color);
        });
        if (mesh != null) {
            drawMesh(mesh, COLOR_ADDITIVE, "include:pvp_pulse", null);
        }
    }

    private void emitPulseRing(BufferBuilder mesh, float cx, float cy, float cz,
                               float radius, float band, int plane, int color) {
        if (radius <= 0.0f || alpha(color) <= 0) return;

        int segments = Math.max(48, Math.min(160, (int) (radius * 5.0f)));
        float inner = Math.max(0.01f, radius - band * 0.5f);
        float outer = radius + band * 0.5f;

        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2.0 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2.0 / segments);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);

            if (plane == 0) {
                rawQuad(mesh,
                        cx + c1 * inner, cy + s1 * inner, cz,
                        cx + c2 * inner, cy + s2 * inner, cz,
                        cx + c2 * outer, cy + s2 * outer, cz,
                        cx + c1 * outer, cy + s1 * outer, cz, color);
            } else if (plane == 1) {
                rawQuad(mesh,
                        cx + c1 * inner, cy, cz + s1 * inner,
                        cx + c2 * inner, cy, cz + s2 * inner,
                        cx + c2 * outer, cy, cz + s2 * outer,
                        cx + c1 * outer, cy, cz + s1 * outer, color);
            } else {
                rawQuad(mesh,
                        cx, cy + c1 * inner, cz + s1 * inner,
                        cx, cy + c2 * inner, cz + s2 * inner,
                        cx, cy + c2 * outer, cz + s2 * outer,
                        cx, cy + c1 * outer, cz + s1 * outer, color);
            }
        }
    }

    // ================================================================== geometry helpers

    /** Emits a quad through {@code rotation} (as a model matrix), with flat colour. */
    private void quad(BufferBuilder mesh, Matrix4f rotation, float ox, float oy, float oz,
                      float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3, int color) {
        tmp.set(x0, y0, z0).mulPosition(rotation);
        float ax = tmp.x + ox, ay = tmp.y + oy, az = tmp.z + oz;
        tmp.set(x1, y1, z1).mulPosition(rotation);
        float bx = tmp.x + ox, by = tmp.y + oy, bz = tmp.z + oz;
        tmp.set(x2, y2, z2).mulPosition(rotation);
        float cx = tmp.x + ox, cy = tmp.y + oy, cz = tmp.z + oz;
        tmp.set(x3, y3, z3).mulPosition(rotation);
        float dx = tmp.x + ox, dy = tmp.y + oy, dz = tmp.z + oz;
        rawQuad(mesh, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, color);
    }

    /** Untransformed quad with a flat colour, emitted as two triangles. */
    private static void rawQuad(BufferBuilder mesh,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3, int color) {
        color = multiplyAlpha(color, targetVertexAlpha);
        mesh.addVertex(x0, y0, z0).setColor(color);
        mesh.addVertex(x1, y1, z1).setColor(color);
        mesh.addVertex(x2, y2, z2).setColor(color);
        mesh.addVertex(x0, y0, z0).setColor(color);
        mesh.addVertex(x2, y2, z2).setColor(color);
        mesh.addVertex(x3, y3, z3).setColor(color);
    }

    private static void rawTriangle(BufferBuilder mesh,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2, int color) {
        color = multiplyAlpha(color, targetVertexAlpha);
        mesh.addVertex(x0, y0, z0).setColor(color);
        mesh.addVertex(x1, y1, z1).setColor(color);
        mesh.addVertex(x2, y2, z2).setColor(color);
    }

    static MeshData build(com.mojang.blaze3d.vertex.VertexFormat.Mode topology,
                                  java.util.function.Consumer<BufferBuilder> body) {
        return buildWith(DefaultVertexFormat.POSITION_COLOR, topology, body);
    }

    /** Textured billboards: {@code POSITION_TEX_COLOR}, matching the {@code TEX_*} pipelines. */
    static MeshData buildTextured(java.util.function.Consumer<BufferBuilder> body) {
        return buildWith(DefaultVertexFormat.POSITION_TEX_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, body);
    }

    private static ByteBufferBuilder SHARED_BUILDER;

    private static synchronized MeshData buildWith(com.mojang.blaze3d.vertex.VertexFormat format,
                                                   com.mojang.blaze3d.vertex.VertexFormat.Mode topology,
                                                   java.util.function.Consumer<BufferBuilder> body) {
        if(SHARED_BUILDER==null)SHARED_BUILDER=new ByteBufferBuilder(16384);
        BufferBuilder builder=new BufferBuilder(SHARED_BUILDER,topology,format);
        body.accept(builder);return builder.build();
    }

    static void drawMesh(MeshData mesh, RenderPipeline pipeline, String label, GpuTextureView texture) {
        drawMesh(mesh, pipeline, label, texture, false);
    }

    static void drawMesh(MeshData mesh, RenderPipeline pipeline, String label, GpuTextureView texture, boolean repeat) {
        drawMesh(mesh, pipeline, label, texture, repeat, null, null);
    }

    /** As above, plus one std140 uniform block named {@code uboName} filled with {@code ubo} (vec4s, in order). */
    static void drawMesh(MeshData mesh, RenderPipeline pipeline, String label, GpuTextureView texture, boolean repeat,
                         String uboName, float[] ubo) {
        if(mesh==null)return;
        try(mesh){
            var target=net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
            if(target==null||target.getColorTextureView()==null)return;
            var encoder=RenderSystem.getDevice().createCommandEncoder();GPU.upload(encoder,mesh);
            if(ubo!=null)GPU.uniform(encoder,uboName,ubo);
            try(var pass=encoder.createRenderPass(()->label,target.getColorTextureView(),OptionalInt.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
                pass.setPipeline(pipeline);GPU.bind(pass,false);
                if(texture!=null)pass.bindTexture("Sampler0",texture,repeat?RenderSampler.repeat():RenderSampler.linear());
                if(ubo!=null)GPU.bindExtra(pass,uboName);pass.draw(0,mesh.drawState().vertexCount());
            }
        }
    }

    private static void closeQuietly(MeshData mesh) {
        if (mesh == null) return;
        try {
            mesh.close();
        } catch (Throwable ignored) {
        }
    }

    private static GpuTextureView depthView(RenderTarget target) {
        if (target == null) return null;
        try {
            return target.getDepthTextureView();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static GpuTextureView boundTexture(Identifier id) {
        if(id==null)return null;
        // TextureManager owns loading/reloading and GPU lifetime; never cache a view across F3+T.
        var texture=net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id);
        return texture.getTextureView();
    }

    // ================================================================== colour helpers

    private static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    static int replaceAlpha(int color, int a) {
        return (Mth.clamp(a, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    static int multiplyAlpha(int color, float multiplier) {
        return replaceAlpha(color, Math.round(alpha(color) * multiplier));
    }

    static int blendColors(int first, int second, float factor) {
        float f = Mth.clamp(factor, 0.0f, 1.0f);
        int a1 = (first >> 24) & 0xFF, r1 = (first >> 16) & 0xFF, g1 = (first >> 8) & 0xFF, b1 = first & 0xFF;
        int a2 = (second >> 24) & 0xFF, r2 = (second >> 16) & 0xFF, g2 = (second >> 8) & 0xFF, b2 = second & 0xFF;
        return (Math.round(a1 + (a2 - a1) * f) << 24)
                | (Math.round(r1 + (r2 - r1) * f) << 16)
                | (Math.round(g1 + (g2 - g1) * f) << 8)
                | Math.round(b1 + (b2 - b1) * f);
    }

    // ================================================================== donor value types

    private static final class CubeFragment {
        Vec3 previousCenter;
        Vec3 center;
        final Vector3f previousOffset = new Vector3f();
        final Vector3f offset;
        Vec3 velocity;
        final Vector3f axis;
        float angularSpeed;
        final double groundY;
        final int maxAge;
        int age;

        CubeFragment(Vec3 center, Vector3f offset, Vec3 velocity, Vector3f axis,
                     float angularSpeed, double groundY, int maxAge) {
            this.previousCenter = center;
            this.center = center;
            this.offset = offset;
            this.velocity = velocity;
            this.axis = axis;
            this.angularSpeed = angularSpeed;
            this.groundY = groundY;
            this.maxAge = maxAge;
        }
    }
}
