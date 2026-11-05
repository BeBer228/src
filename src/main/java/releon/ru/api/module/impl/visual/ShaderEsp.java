package releon.ru.api.module.impl.visual;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.api.settings.Setting;
import releon.ru.utils.render.IncludeDefaults;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.color.ClientTheme;
import releon.ru.utils.render.post.fx.IncludeEspRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;


/** Shader silhouette ESP: vanilla entity-outline mask, composited after vanilla and Iris world post effects. */
public class ShaderEsp extends Module {

    // ------------------------------------------------------------------ targets

    public final BooleanSetting localPlayer = register(new BooleanSetting(
            "Local Player", "Highlight your own player too", true));

    public final BooleanSetting players = register(new BooleanSetting(
            "Players", "Highlight other players", true));

    public final BooleanSetting ignoreNaked = register(new BooleanSetting(
            "Ignore Naked", "Skip players with no armour equipped", false));

    public final BooleanSetting mobs = register(new BooleanSetting(
            "Mobs", "Highlight living mobs", true));

    public final BooleanSetting items = register(new BooleanSetting(
            "Items", "Highlight dropped items", false));

    public final NumberSetting range = register(new NumberSetting(
            "Range", "Maximum distance to highlight entities", 96.0, 8.0, 256.0, 4.0));

    // ------------------------------------------------------------------ colour

    public final ModeSetting colorMode = register(new ModeSetting(
            "Color Mode", "Colour source for the outline", "Theme", "Theme", "Custom"));

    public final ColorSetting tintColor = register(new ColorSetting(
            "Tint", "Custom colour used in Custom mode", new java.awt.Color(0xFFFF3355,true)));

    public final NumberSetting saturation = register(new NumberSetting(
            "Saturation", "How colourful the tint is (0 = grey)", 1.15, 0.0, 2.0, 0.05));

    public final NumberSetting opacity = register(new NumberSetting(
            "Opacity", "Overall strength of the effect", 1.0, 0.05, 1.0, 0.01));

    public final BooleanSetting friendColor = register(new BooleanSetting(
            "Friend Color", "Tint friends green", true));

    public final ColorSetting friendTint = register(new ColorSetting(
            "Friend Tint", "Colour used for friends", new java.awt.Color(0xFF55FF55,true)));

    public final BooleanSetting hurtColor = register(new BooleanSetting(
            "Hurt Color", "Change the colour while the entity is taking damage", false));

    public final ColorSetting hurtTint = register(new ColorSetting(
            "Hurt Tint", "Colour flashed on damage", new java.awt.Color(0xFFFF2A2A,true)));

    // ------------------------------------------------------------------ halo

    public final BooleanSetting glow = register(new BooleanSetting(
            "Glow", "Outer halo around the silhouette", true));

    public final NumberSetting glowRadius = register(new NumberSetting(
            "Glow Radius", "How far the halo spreads", 0.55, 0.0, 1.0, 0.01));

    public final NumberSetting glowStrength = register(new NumberSetting(
            "Glow Strength", "Brightness of the halo", 1.6, 0.0, 4.0, 0.05));

    public final NumberSetting glowFalloff = register(new NumberSetting(
            "Glow Falloff", "How quickly the halo fades", 1.1, 0.2, 4.0, 0.05));

    public final NumberSetting edgeAvoidance = register(new NumberSetting(
            "Edge Avoidance", "Hide the halo behind walls (0 = shines through)", 0.22, 0.0, 1.0, 0.01));

    // ------------------------------------------------------------------ fill

    public final BooleanSetting fill = register(new BooleanSetting(
            "Fill", "Fill the silhouette", true));

    public final NumberSetting fillOpacity = register(new NumberSetting(
            "Fill Opacity", "Density of the fill", 0.45, 0.0, 1.5, 0.01));

    public final NumberSetting innerGlow = register(new NumberSetting(
            "Inner Glow", "Brightness towards the silhouette edge", 0.6, 0.0, 2.0, 0.05));

    // ------------------------------------------------------------------ outline

    public final BooleanSetting outline = register(new BooleanSetting(
            "Outline", "Rim along the silhouette boundary", true));

    public final ModeSetting outlineMode = register(new ModeSetting(
            "Outline Type", "Which side of the boundary to draw", "Outside", "Outside", "Inside", "Both"));

    public final NumberSetting outlineWidth = register(new NumberSetting(
            "Outline Width", "Rim thickness in pixels", 2.0, 1.0, 5.0, 1.0));

    public final NumberSetting outlineStrength = register(new NumberSetting(
            "Outline Strength", "Brightness of the rim", 1.8, 0.0, 3.0, 0.05));

    public final NumberSetting outlineWhite = register(new NumberSetting(
            "Outline Whiteness", "How white the rim core is", 0.45, 0.0, 1.0, 0.01));

    // ------------------------------------------------------------------ animation

    public final BooleanSetting pulse = register(new BooleanSetting(
            "Pulse", "Breathe the whole effect", false));

    public final NumberSetting pulseSpeed = register(new NumberSetting(
            "Pulse Speed", "Pulses per second", 1.0, 0.1, 5.0, 0.1));

    public final NumberSetting pulseAmount = register(new NumberSetting(
            "Pulse Amount", "Depth of the pulse", 0.3, 0.0, 1.0, 0.05));

    public final BooleanSetting shimmer = register(new BooleanSetting(
            "Shimmer", "Sweep a highlight band down the screen", false));

    public final NumberSetting shimmerWidth = register(new NumberSetting(
            "Shimmer Width", "Width of the sweep band", 0.04, 0.01, 0.15, 0.01));

    public final NumberSetting shimmerPeriod = register(new NumberSetting(
            "Shimmer Period", "Seconds per sweep", 5.0, 1.0, 15.0, 0.5));

    public final NumberSetting shimmerBrightness = register(new NumberSetting(
            "Shimmer Brightness", "Brightness of the sweep", 0.8, 0.0, 2.0, 0.05));

    // ------------------------------------------------------------------ internal

    private static ShaderEsp instance;
    private Object world;

    private final IncludeEspRenderer renderer = new IncludeEspRenderer();
    private final IncludeEspRenderer.Params params = new IncludeEspRenderer.Params();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f corner = new Vector4f();
    /** Current targets with their base mask colour, refreshed once per tick (tick and render share a thread). */
    private static final Reference2IntOpenHashMap<Entity> TARGETS = new Reference2IntOpenHashMap<>();

    public ShaderEsp() {
        super("Shader ESP",
                "Shader-based ESP with a glow halo, silhouette fill and bright rim.",
                ModuleCategory.VISUAL);
        instance = this;
        IncludeDefaults.apply("Shader ESP", getSettings(), "");
        configureControls();
    }

    private void configureControls() {
        tintColor.setVisible(() -> colorMode.is("Custom"));
        hurtTint.setVisible(() -> hurtColor.getValue());
    }

    public static ShaderEsp getInstance() {
        return instance;
    }

    /**
     * Vanilla outline colour forcing {@code entity} into the silhouette mask, 0 if not a target.
     * Mask encoding read by esp_combine: G is always 1, friends lower B to 0x55, damage lowers R.
     */
    public static int outlineColor(Entity entity, float partialTick) {
        if (instance == null || !instance.isEnabled()) return 0;
        int color = TARGETS.getInt(entity);
        if (color == 0 || !instance.hurtColor.getValue() || !(entity instanceof LivingEntity living)
                || living.hurtTime <= 0) return color;
        float hurt = Math.clamp((living.hurtTime - partialTick) / Math.max(1, living.hurtDuration), 0f, 1f);
        int red = Math.round(255f * (1f - 0.66f * hurt));
        return (color & 0xFF00FFFF) | red << 16;
    }

    /** True while the vanilla outline post chain and blit are replaced by this module. */
    public static boolean replacesOutline() {
        return instance != null && instance.isEnabled() && !TARGETS.isEmpty();
    }

    @Override
    public void onEnable() {
        renderer.invalidate();
        TARGETS.clear();
    }

    @Override
    public void onDisable() {
        TARGETS.clear();
        renderer.invalidate();
    }

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        if (world != mc.level) { renderer.invalidate(); world = mc.level; }
        TARGETS.clear();
        if (!isEnabled() || mc.level == null || mc.player == null) return;
        double maxDistSq = range.getValue() * range.getValue();
        boolean friends = friendColor.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == null || !entity.isAlive()) continue;
            if (entity.distanceToSqr(mc.player) > maxDistSq) continue;
            if (shouldTarget(entity)) {
                TARGETS.put(entity, friends && IncludeEspRenderer.isFriend(entity) ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
        }
    }

    /** The accent colour the shader tints the contour with. */
    public int getColor() {
        if (colorMode.is("Theme")) {
            return ClientTheme.primary();
        }
        return tintColor.getValue().getRGB();
    }

    // ------------------------------------------------------------------ rendering

    public void render(RenderTarget target, RenderTarget mask, float partialTick) {
        if (!isEnabled() || mc.level == null || mc.player == null || TARGETS.isEmpty()) return;
        if (target == null || mask == null || (!glow.getValue() && !fill.getValue() && !outline.getValue())) return;
        if (!screenBounds(partialTick, mask.width, mask.height)) return;
        applySettings();
        boolean needsBlur = params.glowEnabled || params.outlineEnabled
                || (params.fillEnabled && params.innerGlow > 0f);
        float radius = Math.max(params.glowRadius, params.outlineEnabled && params.outlineWidth > 2 ? .25f : 0f);
        renderer.useMask(mask, needsBlur ? radius : -1f, boundX0, boundY0, boundX1, boundY1);
        if (!renderer.isReady()) return;
        if (needsBlur) renderer.renderGlow();
        // No scene read is needed when edge avoidance is off; bind the mask as an unused sampler.
        var scene = params.edgeThreshold > 0f ? renderer.snapshotScene(target) : null;
        renderer.composite(params, target, scene);
    }

    private int boundX0, boundY0, boundX1, boundY1;

    /**
     * Pixel rect (GL, bottom-left origin) covering every visible target's inflated bounds; GPU passes
     * are scissored to it. Falls back to the full screen when the camera is inside a box.
     * @return false when no target is on screen
     */
    private boolean screenBounds(float partialTick, int width, int height) {
        var camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return false;
        Vec3 cam = camera.position();
        viewProjection.set(Render3D.lastProjMat).mul(Render3D.lastModMat);
        float partial = partialTick;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Entity entity : TARGETS.keySet()) {
            if (entity.isRemoved() || (entity == mc.player && firstPerson)) continue;
            // Held items, limbs and capes leave the hitbox, so the box is inflated.
            float half = entity.getBbWidth() * 0.5f + 0.6f;
            double x = Mth.lerp(partial, entity.xOld, entity.getX()) - cam.x;
            double y = Mth.lerp(partial, entity.yOld, entity.getY()) - cam.y;
            double z = Mth.lerp(partial, entity.zOld, entity.getZ()) - cam.z;
            float y0 = (float) y - 0.3f, y1 = (float) y + entity.getBbHeight() + 0.4f;
            for (int i = 0; i < 8; i++) {
                corner.set((float) x + ((i & 1) == 0 ? -half : half), (i & 2) == 0 ? y0 : y1,
                        (float) z + ((i & 4) == 0 ? -half : half), 1f);
                viewProjection.transform(corner);
                if (corner.w <= 0.05f) {
                    minX = minY = -1f;
                    maxX = maxY = 1f;
                    break;
                }
                float nx = corner.x / corner.w, ny = corner.y / corner.w;
                minX = Math.min(minX, nx);
                minY = Math.min(minY, ny);
                maxX = Math.max(maxX, nx);
                maxY = Math.max(maxY, ny);
            }
        }
        minX = Math.max(minX, -1f);
        minY = Math.max(minY, -1f);
        maxX = Math.min(maxX, 1f);
        maxY = Math.min(maxY, 1f);
        if (minX >= maxX || minY >= maxY) return false;
        boundX0 = (int) ((minX * 0.5f + 0.5f) * width);
        boundY0 = (int) ((minY * 0.5f + 0.5f) * height);
        boundX1 = (int) Math.ceil((maxX * 0.5f + 0.5f) * width);
        boundY1 = (int) Math.ceil((maxY * 0.5f + 0.5f) * height);
        return true;
    }

    private void applySettings() {
        params.color = getColor();
        params.friendColor = friendTint.getValue().getRGB();
        params.friendEnabled = friendColor.getValue();
        params.hurtEnabled = hurtColor.getValue();
        params.hurtColor = hurtTint.getValue().getRGB();
        params.opacity = opacity.getFloat();
        params.saturation = saturation.getFloat();
        params.edgeThreshold = edgeAvoidance.getFloat();

        params.glowEnabled = glow.getValue();
        params.glowRadius = glowRadius.getFloat();
        params.glowStrength = glowStrength.getFloat();
        params.glowFalloff = glowFalloff.getFloat();

        params.fillEnabled = fill.getValue();
        params.fillOpacity = fillOpacity.getFloat();
        params.innerGlow = innerGlow.getFloat();

        params.outlineEnabled = outline.getValue();
        params.outlineWidth = outlineWidth.getValue().intValue();
        params.outlineMode = outlineModeIndex();
        params.outlineStrength = outlineStrength.getFloat();
        params.outlineWhite = outlineWhite.getFloat();

        params.pulseEnabled = pulse.getValue();
        params.pulseSpeed = pulseSpeed.getFloat();
        params.pulseAmount = pulseAmount.getFloat();

        params.shimmerEnabled = shimmer.getValue();
        params.shimmerWidth = shimmerWidth.getFloat();
        params.shimmerPeriodSec = shimmerPeriod.getFloat();
        params.shimmerBrightness = shimmerBrightness.getFloat();
    }

    private int outlineModeIndex() {
        if (outlineMode.is("Inside")) return 1;
        if (outlineMode.is("Both")) return 2;
        return 0;
    }

    // ------------------------------------------------------------------ targeting

    private boolean shouldTarget(Entity entity) {
        LocalPlayer self = mc.player;
        if (entity == self) return localPlayer.getValue();
        if (entity instanceof Player p) {
            if (p == self) return localPlayer.getValue();
            if (!players.getValue()) return false;
            if (ignoreNaked.getValue() && !hasArmor(p)) return false;
            return true;
        }
        if (entity instanceof LivingEntity) return mobs.getValue();
        if (entity instanceof ItemEntity) return items.getValue();
        return false;
    }

    private static boolean hasArmor(Player player) {
        return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                || !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    /** Hint used by the ClickGUI to keep the two outline sub-sliders readable. */
    public String outlineSummary() {
        if (!outline.getValue()) return "Off";
        return outlineMode.getValue() + " / " + outlineWidth.getValue().intValue() + "px";
    }

}
