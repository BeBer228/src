package releon.ru.api.module.impl.visual.esp.prism;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.impl.*;
import releon.ru.utils.render.IncludeDefaults;
import releon.ru.utils.render.color.ClientTheme;
import releon.ru.utils.render.Render3D;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.utils.repository.friend.FriendUtils;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Prism" entity NewEspRenderer: gradient brackets/frame, glow halo, health bar with damage trail,
 * glass tags with equipment and merged ground-item labels. Candidates are gathered once per tick;
 * each frame projects 8 AABB corners with inline matrix math into pooled {@link EspEntry}s and
 * {@link EspPainter} draws everything in a handful of batched render elements.
 */
public class NewEspRenderer extends Module {

    public final ModeSetting boxStyle = register(new ModeSetting("New: Box Style", "Brackets, full frame, frame with gradient fill, or no box.", "Corners", "Corners", "Frame", "Glass", "None"));
    public final ModeSetting colorMode = register(new ModeSetting("New: Color Mode", "Client theme, own gradient or colour by health.", "Theme", "Theme", "Custom", "Health"));
    public final ColorSetting primaryColor = register(new ColorSetting("New: Primary Color", "Top colour of the box gradient.", new java.awt.Color(0xFF8B7CFF, true)));
    public final ColorSetting secondaryColor = register(new ColorSetting("New: Secondary Color", "Bottom colour of the box gradient.", new java.awt.Color(0xFF5CE1E6, true)));
    public final NumberSetting cornerSize = register(new NumberSetting("New: Corner Size", "Bracket length, % of box side.", 25.0, 10.0, 50.0, 1.0));
    public final NumberSetting thickness = register(new NumberSetting("New: Thickness", "Box line thickness.", 1.0, 0.5, 3.0, 0.5));
    public final BooleanSetting boxShadow = register(new BooleanSetting("New: Box Shadow", "Dark outline under the lines for contrast.", true));
    public final NumberSetting fillOpacity = register(new NumberSetting("New: Fill Opacity", "Gradient fill opacity, %.", 18.0, 0.0, 60.0, 1.0));

    public final BooleanSetting glow = register(new BooleanSetting("New: Glow", "Soft halo around the box.", true));
    public final NumberSetting glowStrength = register(new NumberSetting("New: Glow Strength", "Halo brightness and size.", 0.45, 0.05, 1.0, 0.05));
    public final NumberSetting glowSpread = register(new NumberSetting("New: Glow Spread", "How far the halo reaches beyond the box.", 6.0, 0.0, 20.0, 1.0));
    public final BooleanSetting targetPulse = register(new BooleanSetting("New: Target Pulse", "Pulsing halo on the current target.", true));

    public final BooleanSetting healthBar = register(new BooleanSetting("New: Health Bar", "Vertical health bar next to the box.", true));
    public final ModeSetting barSide = register(new ModeSetting("New: Bar Side", "Which side of the box the bar is on.", "Left", "Left", "Right"));
    public final NumberSetting barWidth = register(new NumberSetting("New: Bar Width", "Health bar width.", 2.0, 1.0, 5.0, 0.5));
    public final BooleanSetting damageTrail = register(new BooleanSetting("New: Damage Trail", "Lost health fades out with a delay.", true));
    public final BooleanSetting absorption = register(new BooleanSetting("New: Absorption", "Golden absorption segment on top of the bar.", true));
    public final NumberSetting healthSpeed = register(new NumberSetting("New: Health Speed", "Health animation speed.", 12.0, 1.0, 30.0, 1.0));

    public final BooleanSetting tag = register(new BooleanSetting("New: Tag", "Glass name tag above the entity.", true));
    public final NumberSetting fontSize = register(new NumberSetting("New: Font Size", "Name font size in the tag.", 9.0, 7.0, 14.0, 1.0));
    public final BooleanSetting boldName = register(new BooleanSetting("New: Bold Name", "Bold font for the name.", true));
    public final NumberSetting tagOpacity = register(new NumberSetting("New: Tag Opacity", "Tag background opacity, % of the Interface background.", 100.0, 0.0, 100.0, 1.0));
    public final BooleanSetting face = register(new BooleanSetting("New: Face", "Player skin face in the tag.", true));
    public final BooleanSetting healthValue = register(new BooleanSetting("New: Health Value", "HP number in the tag.", true));
    public final BooleanSetting distance = register(new BooleanSetting("New: Show Distance", "Distance in the tag.", true));
    public final BooleanSetting ping = register(new BooleanSetting("New: Show Ping", "Player latency in the tag.", true));
    public final BooleanSetting healthStrip = register(new BooleanSetting("New: Health Strip", "Thin health line under the tag.", true));
    public final BooleanSetting itemUse = register(new BooleanSetting("New: Item Use", "Strip shows eating/drinking/blocking progress.", true));
    public final BooleanSetting tagBlur = register(new BooleanSetting("New: Tag Blur", "Frosted blur under tags (needs blur enabled in Interface).", false));
    public final BooleanSetting hideVanilla = register(new BooleanSetting("New: Hide Vanilla", "Hide vanilla name tags of shown entities.", true));

    public final BooleanSetting equipment = register(new BooleanSetting("New: Equipment", "Armor and held items above the tag.", true));
    public final BooleanSetting enchants = register(new BooleanSetting("New: Enchant Levels", "Highest enchantment level on each item.", true));
    public final BooleanSetting durability = register(new BooleanSetting("New: Durability", "Durability line under each item.", true));
    public final BooleanSetting mendingIndicator = register(new BooleanSetting("New: Mending Indicator", "Highlight armor with Mending.", false));
    public final ColorSetting mendingColor = register(new ColorSetting("New: Mending Color", "Mending armor glow color.", new java.awt.Color(0xFF62DBAA, true)));
    public final BooleanSetting krushIndicator = register(new BooleanSetting("New: Krush Indicator", "Armor with Protection V, Unbreaking V and Mending together.", false));
    public final ColorSetting krushColor = register(new ColorSetting("New: Krush Color", "Krush armor glow color.", new java.awt.Color(0xFFFFBD62, true)));
    public final BooleanSetting swordIndicator = register(new BooleanSetting("New: Sword Indicator", "Sword with Poison, Sharpness VII, Mending, Unbreaking V, maximum Smite and Looting V together.", false));
    public final ColorSetting swordColor = register(new ColorSetting("New: Sword Color", "Special sword glow color.", new java.awt.Color(0xFFD593FF, true)));
    public final NumberSetting equipmentRange = register(new NumberSetting("New: Equipment Range", "Max distance for equipment, blocks.", 32.0, 8.0, 96.0, 4.0));

    public final BooleanSetting mergeItems = register(new BooleanSetting("New: Merge Items", "Merge nearby stacks of the same item into one label.", true));
    public final BooleanSetting onlyValuable = register(new BooleanSetting("New: Only Valuable", "Show only valuable items.", false));
    public final BooleanSetting itemIcon = register(new BooleanSetting("New: Item Icon", "Item icon on the label: tiny or flat (Item Physic) drops stay recognisable.", true));
    public final NumberSetting itemOpacity = register(new NumberSetting("New: Item Opacity", "Dropped item label opacity, % of the Interface-new background.", 100.0, 0.0, 100.0, 1.0));
    public final BooleanSetting customItemColors = register(new BooleanSetting("New: Custom Item Colors", "Own name colour for chosen item types; the rest keep the item's own colour.", false));
    public final MultiModeSetting coloredTypes = register(multi(
            "New: Colored Types", "Item types that get a custom name colour.", ITEM_TYPES, false));
    private final ColorSetting[] typeColors = new ColorSetting[ITEM_TYPES.length];

    {
        int[] defaults = {0xFFFFD34D, 0xFF6FB8FF, 0xFFFF6B6B, 0xFFFFB86B, 0xFF8CF07A, 0xFFD18CFF, 0xFFC9B79C, 0xFFD6DBE6};
        for (int i = 0; i < ITEM_TYPES.length; i++) {
            String type = ITEM_TYPES[i];
            typeColors[i] = register(new ColorSetting("New: " + type + " Color", "Name colour of this item type.", new java.awt.Color(defaults[i], true)));
            typeColors[i].setVisible(() -> customItemColors.getValue() && coloredTypes.isSelected(type));
        }
    }

    public final MultiModeSetting targets = register(multi(
            "New: Targets", "Which entities to show.", new String[]{"Players", "Friends", "Naked", "Invisible", "Mobs", "Animals", "Items"}, true));
    public final BooleanSetting self = register(new BooleanSetting("New: Self", "Show yourself in third person.", false));
    public final ColorSetting friendColor = register(new ColorSetting("New: Friend Color", "Friends colour.", new java.awt.Color(0xFF4ADE80, true)));
    public final ColorSetting targetColor = register(new ColorSetting("New: Target Color", "Current target colour.", new java.awt.Color(0xFFFF4D6D, true)));

    public final NumberSetting maxDistance = register(new NumberSetting("New: Max Distance", "Render distance, blocks.", 128.0, 16.0, 256.0, 4.0));
    public final NumberSetting renderLimit = register(new NumberSetting("New: Render Limit", "Max entities per frame, target and players first.", 96.0, 8.0, 256.0, 8.0));
    public final NumberSetting tagScale = register(new NumberSetting("New: Tag Scale", "Tag size.", 1.0, 0.5, 2.0, 0.05));
    public final NumberSetting minScale = register(new NumberSetting("New: Min Scale", "Smallest tag scale far away.", 0.6, 0.3, 1.0, 0.05));
    public final NumberSetting fullSizeAt = register(new NumberSetting("New: Full Size At", "Tags keep full size up to this distance, blocks.", 10.0, 2.0, 48.0, 1.0));
    public final BooleanSetting fade = register(new BooleanSetting("New: Fade", "Fade in and out instead of popping.", true));

    public final NumberSetting boxScale = register(new NumberSetting("New: Box Scale", "Box size around the projected entity centre.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting barScale = register(new NumberSetting("New: Health Bar Scale", "Health bar size.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting nameScale = register(new NumberSetting("New: Name Scale", "Name size inside the tag.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting hpScale = register(new NumberSetting("New: Health Value Scale", "HP text size inside the tag.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting faceScale = register(new NumberSetting("New: Face Scale", "Player face size inside the tag.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting distanceScale = register(new NumberSetting("New: Distance Scale", "Distance text size inside the tag.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting pingScale = register(new NumberSetting("New: Ping Scale", "Ping text size inside the tag.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting equipmentScale = register(new NumberSetting("New: Equipment Scale", "Armor and held item row size.", 1.0, 0.5, 2.5, 0.05));
    public final NumberSetting itemScale = register(new NumberSetting("New: Item Scale", "Dropped item label size.", 1.0, 0.5, 2.5, 0.05));

    private static final int MAX_DROPS = 64;
    /** Index = {@link EspPainter.Drop#type}; "Valuable" wins over the real type when selected. */
    private static final String[] ITEM_TYPES = {"Valuable", "Armor", "Weapons", "Tools", "Food", "Potions", "Blocks", "Other"};

    private final EspPainter painter = new EspPainter();
    private final Int2ObjectOpenHashMap<EspEntry> pool = new Int2ObjectOpenHashMap<>();
    private final List<EspEntry> drawList = new ArrayList<>();
    private final List<Entity> candidates = new ArrayList<>();
    private final Set<Entity> shown = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Entity> friends = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EspPainter.Drop[] drops = new EspPainter.Drop[MAX_DROPS];
    private final ItemEntity[] dropAnchor = new ItemEntity[MAX_DROPS];
    private final List<ItemEntity> itemBuf = new ArrayList<>();
    private final Comparator<ItemEntity> nearest = Comparator.comparingDouble(i -> i.distanceToSqr(mc.player));
    private int dropCount, visibleDrops, frame;
    private boolean showNaked, showInvisible;
    /** Per-frame setting snapshot: avoids string compares and colour lookups per entity. */
    private int colorKind, primary, secondary, friendC, targetC, nameFont;
    private boolean tagOn, pingOn, useOn, equipOn, enchOn, trailOn, boldOn;
    private float equipRange;
    /** Plain (not renamed) item names per item, one slot per rarity. */
    private final Map<Item, ItemLabel[]> itemLabels = new IdentityHashMap<>();
    private final Matrix4f viewProj = new Matrix4f();
    private float m00, m01, m03, m10, m11, m13, m20, m21, m23, m30, m31, m33;
    private float px0, py0, px1, py1;

    /** Target first, then friends, players, other living, items; nearest first inside a group. */
    private final Comparator<Entity> order = (a, b) -> {
        int ra = rank(a), rb = rank(b);
        return ra != rb ? Integer.compare(ra, rb) : Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player));
    };

    public NewEspRenderer() {
        super("ESP New", "Include Prism ESP", ModuleCategory.VISUAL);
        for (int i = 0; i < MAX_DROPS; i++) drops[i] = new EspPainter.Drop();
        IncludeDefaults.apply("ESP", getSettings(), "New: ");
        configureControls();
    }

    private static MultiModeSetting multi(String name, String desc, String[] values, boolean selected) {
        return new MultiModeSetting(name, desc, values, selected ? values : new String[0]);
    }

    private MultiModeSetting group(String name, BooleanSetting... options) {
        String[] names = new String[options.length];
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            names[i] = options[i].getName().replace("New: ", "");
            if (options[i].getValue()) selected.add(names[i]);
        }
        MultiModeSetting result = register(new MultiModeSetting("New: " + name, "", names, selected.toArray(String[]::new)));
        result.addListener((setting, old, value) -> {
            for (int i = 0; i < options.length; i++) options[i].setValue(value.contains(names[i]));
            for (EspEntry entry : pool.values()) entry.eqFrame = -1000;
        });
        return result;
    }

    private final BooleanSetting hands = new BooleanSetting("New: Hands Above Tag", "", true);
    private Object world;
    public java.util.List<Entity> entities() { return java.util.Collections.unmodifiableList(candidates); }

    private void configureControls() {
        for (Setting<?> setting : getSettings()) setting.setVisible(false);
        for (var setting : java.util.List.of(tagScale, tagOpacity, itemOpacity, maxDistance, boxStyle, targets)) setting.setVisible(true);
        group("Показывать", tag, face, healthValue, distance, ping, healthStrip, healthBar, tagBlur, itemUse, hideVanilla, self);
        group("Снаряжение", equipment, hands, enchants, durability, mendingIndicator, krushIndicator, swordIndicator);
        group("Предметы на земле", mergeItems, onlyValuable, itemIcon, customItemColors);
        coloredTypes.setVisible(customItemColors::getValue);
        for (int i = 0; i < typeColors.length; i++) {
            final int index = i;
            typeColors[i].setVisible(() -> customItemColors.getValue() && coloredTypes.isSelected(ITEM_TYPES[index]));
        }
    }

    public boolean hides(Entity entity) {
        return hideVanilla.getValue() && tag.getValue() && shown.contains(entity);
    }

    private int rank(Entity e) {
        if (e == AuraModule.target) return 0;
        if (e instanceof Player) return friends.contains(e) ? 1 : 2;
        return e instanceof LivingEntity ? 3 : 4;
    }

    // ================================================================== queries used by mixins

    /** True when the vanilla tag of {@code entity} should be hidden because the NewEspRenderer shows it. */
    // ================================================================== tick: candidates

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        if (world != mc.level) { onDisable(); itemLabels.clear(); world = mc.level; }
        candidates.clear();
        itemBuf.clear();
        shown.clear();
        friends.clear();
        dropCount = 0;
        if (mc.level == null || mc.player == null) return;
        double max = maxDistance.getValue() * maxDistance.getValue();
        boolean items = targets.isSelected("Items");
        showNaked = targets.isSelected("Naked");
        showInvisible = targets.isSelected("Invisible");
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!e.isAlive() || e.distanceToSqr(mc.player) > max) continue;
            if (e instanceof LivingEntity living) {
                if (e instanceof Player p && p != mc.player && isFriend(p)) friends.add(p);
                if (!accept(living)) continue;
                candidates.add(e);
                shown.add(e);
            } else if (items && e instanceof ItemEntity item) {
                itemBuf.add(item);
            }
        }
        candidates.sort(order);
        // nearest first: the drop cap keeps the closest stacks and layoutDrops stacks far labels above near ones
        itemBuf.sort(nearest);
        for (int i = 0, n = itemBuf.size(); i < n; i++) collectDrop(itemBuf.get(i));
        itemBuf.clear();
    }

    private boolean accept(LivingEntity e) {
        if (e == mc.player) return self.getValue() && !mc.options.getCameraType().isFirstPerson();
        if (e.isInvisible() && !showInvisible) return false;
        if (e instanceof Player p) {
            if (friends.contains(p)) return targets.isSelected("Friends");
            return targets.isSelected("Players") && (showNaked || !isNaked(p));
        }
        if (e instanceof Monster) return targets.isSelected("Mobs");
        return !(e instanceof ArmorStand) && targets.isSelected("Animals");
    }

    private static boolean isNaked(Player p) {
        for (int i = 0; i < 4; i++) if (!p.getItemBySlot(EspEntry.SLOTS[i]).isEmpty()) return false;
        return true;
    }

    private static boolean isFriend(Player p) { return FriendUtils.isFriend(p); }

    private void collectDrop(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty()) return;
        boolean valuable = isValuable(stack);
        if (onlyValuable.getValue() && !valuable) return;
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (mergeItems.getValue()) {
            for (int i = 0; i < dropCount; i++) {
                if (drops[i].stack.getItem() == stack.getItem() && dropAnchor[i].distanceToSqr(item) < 2.25
                        && Objects.equals(custom, drops[i].stack.get(DataComponents.CUSTOM_NAME))) {
                    drops[i].count += stack.getCount();
                    return;
                }
            }
        }
        if (dropCount >= MAX_DROPS) return;
        EspPainter.Drop d = drops[dropCount];
        dropAnchor[dropCount++] = item;
        d.stack = stack;
        d.count = stack.getCount();
        d.valuable = valuable;
        d.type = typeOf(stack);
    }

    private static int typeOf(ItemStack s) {
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        if (eq != null && eq.slot().isArmor()) return 1;
        if (s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(ItemTags.SPEARS) || s.is(Items.MACE) || s.is(Items.TRIDENT)
                || s.getItem() instanceof ProjectileWeaponItem) return 2;
        if (s.has(DataComponents.TOOL)) return 3;
        if (s.has(DataComponents.FOOD)) return 4;
        if (s.has(DataComponents.POTION_CONTENTS)) return 5;
        return s.getItem() instanceof BlockItem ? 6 : 7;
    }

    /** Custom type colour, or 0 to keep the colours of the item's own name. */
    private int overrideColor(EspPainter.Drop d) {
        if (!customItemColors.getValue()) return 0;
        if (d.valuable && coloredTypes.isSelected(ITEM_TYPES[0])) return typeColors[0].getValue().getRGB();
        return coloredTypes.isSelected(ITEM_TYPES[d.type]) ? typeColors[d.type].getValue().getRGB() : 0;
    }

    private static boolean isValuable(ItemStack s) {
        return s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE)
                || s.is(Items.END_CRYSTAL) || s.is(Items.RESPAWN_ANCHOR) || s.is(Items.ENDER_PEARL) || s.is(Items.ELYTRA)
                || s.is(Items.NETHERITE_SWORD) || s.is(Items.NETHERITE_HELMET) || s.is(Items.NETHERITE_CHESTPLATE)
                || s.is(Items.NETHERITE_LEGGINGS) || s.is(Items.NETHERITE_BOOTS) || s.is(Items.NETHERITE_INGOT)
                || s.is(Items.NETHERITE_SCRAP) || s.is(Items.NETHERITE_BLOCK) || s.is(Items.DIAMOND) || s.is(Items.DIAMOND_BLOCK);
    }

    // ================================================================== render

    @Override
    public void onDisable() {
        pool.clear();
        drawList.clear();
        candidates.clear();
        shown.clear();
        dropCount = visibleDrops = 0;
        painter.blurCount = 0;
    }

    public void onRender2D(GuiGraphics g, int sw, int sh) {
        if (g == null || mc.level == null || mc.player == null || mc.gameRenderer == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || camera.position() == null) return;
        Vec3 cam = camera.position();
        loadMatrix(viewProj.set(Render3D.lastProjMat).mul(Render3D.lastModMat));
        float pt = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) : 1f;
        float dt = PrismDraw.dt();
        frame++;
        syncStyle();

        boolean anim = fade.getValue();
        float fadeK = anim ? Math.min(1f, dt * 10f) : 1f;
        float hpK = 1f - (float) Math.exp(-dt * healthSpeed.getFloat());
        int info = Math.max(6, fontSize.getValue().intValue() - 2);
        int limit = renderLimit.getValue().intValue();
        drawList.clear();

        for (int i = 0, n = candidates.size(); i < n && drawList.size() < limit; i++) {
            LivingEntity entity = (LivingEntity) candidates.get(i);
            if (entity.isRemoved() || !project(entity, pt, sw, sh, cam)) continue;
            boolean target = entity == AuraModule.target, friend = friends.contains(entity);
            float w = px1 - px0, h = py1 - py0;
            if ((w < 3f || h < 3f) && !target && !friend) continue;
            EspEntry e = pool.get(entity.getId());
            if (e == null) pool.put(entity.getId(), e = new EspEntry());
            if (e.entity != entity) e.reset(entity);
            fill(e, entity, Math.max(3f, w), Math.max(3f, h), target, friend, hpK, dt, info);
            e.seenFrame = frame;
            e.visible = true;
            e.alpha = anim ? e.alpha + (1f - e.alpha) * fadeK : 1f;
            drawList.add(e);
        }

        for (ObjectIterator<EspEntry> it = pool.values().iterator(); it.hasNext(); ) {
            EspEntry e = it.next();
            if (e.seenFrame == frame) continue;
            e.visible = false;
            e.alpha -= e.alpha * fadeK + 0.002f;
            if (!anim || e.alpha <= 0.02f) it.remove();
            else drawList.add(e);
        }

        visibleDrops = 0;
        for (int i = 0; i < dropCount; i++) projectDrop(i, pt, sw, sh, cam);

        painter.layout(drawList);
        painter.layoutDrops(drops, visibleDrops);
        smoothDrops(dt);
        PrismDraw.begin(g);
        if (tagBlur.getValue()) for (int i = 0; i < painter.blurCount * 5; i += 5) {
            float[] b = painter.blur;
            PrismDraw.blur(g, b[i], b[i+1], b[i+2], b[i+3], b[i+4]);
        }
        painter.render(g, drawList, drops, visibleDrops);
    }

    private final float[] smoothX = new float[MAX_DROPS], smoothY = new float[MAX_DROPS];
    private final Object[] smoothKey = new Object[MAX_DROPS];
    private final boolean[] smoothTaken = new boolean[MAX_DROPS];
    private int smoothCount;

    /**
     * Plates are rebuilt every frame (merge anchors and stacking order change as items are added), so each one is matched
     * to the nearest plate of the same item from the previous frame and eased toward its new spot instead of jumping.
     */
    private void smoothDrops(float dt) {
        float k = 1f - (float) Math.exp(-Math.min(dt, .1f) * 16f);
        float[] nx = new float[visibleDrops], ny = new float[visibleDrops];
        Object[] nk = new Object[visibleDrops];
        java.util.Arrays.fill(smoothTaken, 0, smoothCount, false);
        for (int i = 0; i < visibleDrops; i++) {
            EspPainter.Drop d = drops[i];
            int best = -1;
            float bestD = 120f * 120f;
            for (int j = 0; j < smoothCount; j++) {
                if (smoothTaken[j] || smoothKey[j] != d.nameKey) continue;
                float dx = smoothX[j] - d.sx, dy = smoothY[j] - d.sy, q = dx * dx + dy * dy;
                if (q < bestD) {
                    bestD = q;
                    best = j;
                }
            }
            if (best >= 0) {
                smoothTaken[best] = true;
                d.sx = smoothX[best] + (d.sx - smoothX[best]) * k;
                d.sy = smoothY[best] + (d.sy - smoothY[best]) * k;
            }
            nx[i] = d.sx;
            ny[i] = d.sy;
            nk[i] = d.nameKey;
        }
        smoothCount = visibleDrops;
        System.arraycopy(nx, 0, smoothX, 0, smoothCount);
        System.arraycopy(ny, 0, smoothY, 0, smoothCount);
        System.arraycopy(nk, 0, smoothKey, 0, smoothCount);
    }

    /** Fills a pooled entry from the projected box in {@code px0..py1}. */
    private void fill(EspEntry e, LivingEntity entity, float w, float h, boolean target, boolean friend,
                      float hpK, float dt, int info) {
        float cx = (px0 + px1) * .5f;
        e.x0 = cx - w * .5f;
        e.x1 = cx + w * .5f;
        e.y1 = py1;
        e.y0 = py1 - h;
        e.dist = (float) Math.sqrt(entity.distanceToSqr(mc.player));
        e.player = entity instanceof Player;
        e.friend = friend;
        e.target = target;
        int special = target ? targetC : friend ? friendC : 0;
        if (special != 0) {
            e.c1 = special;
            e.c2 = EspPainter.lerp(special, 0xFFFFFFFF, .35f);
        } else if (colorKind == 2) {
            e.c1 = e.c2 = EspPainter.healthColor(entity.getHealth() / Math.max(1f, entity.getMaxHealth()));
        } else if (colorKind == 1) {
            e.c1 = primary;
            e.c2 = secondary;
        } else {
            e.c1 = ClientTheme.primary();
            e.c2 = EspPainter.lerp(e.c1, 0xFFFFFFFF, .45f);
        }
        e.updateHealth(entity.getHealth(), entity.getMaxHealth(), entity.getAbsorptionAmount(), hpK, dt, trailOn);
        if (!tagOn) return;

        int id = entity.getId();
        if (e.name.isEmpty() || ((frame + id) & 127) == 0) {
            String name = entity.getName().getString().replace('§', '#');
            if (!name.equals(e.name)) {
                e.name = name;
                e.nameSize = -1;
            }
        }
        if (e.player && pingOn) {
            if (e.ping < 0 || ((frame + id) % 20) == 0) e.ping = fetchPing(entity);
        } else {
            e.ping = -1;
        }
        e.updateStrings(nameFont, info, entity.getHealth(), e.ping, boldOn);

        if (useOn && entity.isUsingItem()) {
            int total = entity.getUseItem().getUseDuration(entity);
            e.use = total <= 0 ? 0f : Mth.clamp(1f - entity.getUseItemRemainingTicks() / (float) total, 0f, 1f);
        } else {
            e.use = 0f;
        }

        // equipment changes rarely: refresh every 5 frames
        if (frame - e.eqFrame >= 5) {
            e.eqFrame = frame;
            e.eqCount = 0;
            if (equipOn && e.dist <= equipRange)
                for (var slot : EspEntry.SLOTS) {
                    if (!hands.getValue() && (slot == net.minecraft.world.entity.EquipmentSlot.MAINHAND || slot == net.minecraft.world.entity.EquipmentSlot.OFFHAND)) continue;
                    e.addEquip(entity.getItemBySlot(slot), enchOn);
                }
        }
    }

    private int fetchPing(LivingEntity entity) {
        if (mc.getConnection() == null) return -1;
        PlayerInfo info = mc.getConnection().getPlayerInfo(entity.getUUID());
        return info != null ? info.getLatency() : -1;
    }

    private void loadMatrix(Matrix4f m) {
        m00 = m.m00(); m01 = m.m01(); m03 = m.m03();
        m10 = m.m10(); m11 = m.m11(); m13 = m.m13();
        m20 = m.m20(); m21 = m.m21(); m23 = m.m23();
        m30 = m.m30(); m31 = m.m31(); m33 = m.m33();
    }

    /**
     * Projects the 8 AABB corners as base ± axis offsets in clip space (3 axis vectors, no per-corner
     * matrix multiply); corners behind the near plane are skipped. Result in {@code px0..py1}.
     */
    private boolean project(LivingEntity entity, float pt, int sw, int sh, Vec3 cam) {
        float x = (float) (Mth.lerp(pt, entity.xOld, entity.getX()) - cam.x);
        float y = (float) (Mth.lerp(pt, entity.yOld, entity.getY()) - cam.y);
        float z = (float) (Mth.lerp(pt, entity.zOld, entity.getZ()) - cam.z);
        float hw = entity.getBbWidth() * .5f, bh = entity.getBbHeight();
        float bx = m00 * x + m10 * y + m20 * z + m30;
        float by = m01 * x + m11 * y + m21 * z + m31;
        float bw = m03 * x + m13 * y + m23 * z + m33;
        float reach = Math.max(hw * 2f, bh) * 2f;
        if (bw < -reach) return false;
        float ax = m00 * hw, ay = m01 * hw, aw = m03 * hw;
        float yx = m10 * bh, yy = m11 * bh, yw = m13 * bh;
        float zx = m20 * hw, zy = m21 * hw, zw = m23 * hw;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int front = 0;
        for (int i = 0; i < 8; i++) {
            float sx = (i & 1) == 0 ? -1f : 1f, sy = (i & 2) == 0 ? 0f : 1f, sz = (i & 4) == 0 ? -1f : 1f;
            float cw = bw + sx * aw + sy * yw + sz * zw;
            if (cw <= .05f) continue;
            float inv = 1f / cw;
            float px = ((bx + sx * ax + sy * yx + sz * zx) * inv + 1f) * .5f * sw;
            float py = (1f - (by + sx * ay + sy * yy + sz * zy) * inv) * .5f * sh;
            front++;
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }
        if (front < 2 || maxX < -32 || minX > sw + 32 || maxY < -32 || minY > sh + 32) return false;
        px0 = minX; py0 = minY; px1 = maxX; py1 = maxY;
        return true;
    }

    private void projectDrop(int i, float pt, int sw, int sh, Vec3 cam) {
        ItemEntity item = dropAnchor[i];
        if (item.isRemoved()) return;
        float x = (float) (Mth.lerp(pt, item.xOld, item.getX()) - cam.x);
        float y = (float) (Mth.lerp(pt, item.yOld, item.getY()) + 0.3 - cam.y);
        float z = (float) (Mth.lerp(pt, item.zOld, item.getZ()) - cam.z);
        float w = m03 * x + m13 * y + m23 * z + m33;
        if (w <= .05f) return;
        float sx = ((m00 * x + m10 * y + m20 * z + m30) / w + 1f) * .5f * sw;
        float sy = (1f - (m01 * x + m11 * y + m21 * z + m31) / w) * .5f * sh;
        if (sx < -48 || sx > sw + 48 || sy < -16 || sy > sh + 16) return;
        EspPainter.Drop src = drops[i];
        // keep projected drops compacted at the front; swap so the anchor array stays aligned
        int j = visibleDrops++;
        if (j != i) {
            drops[i] = drops[j];
            drops[j] = src;
            ItemEntity a = dropAnchor[j];
            dropAnchor[j] = item;
            dropAnchor[i] = a;
        }
        src.sx = sx;
        src.sy = sy;
        src.dist = (float) Math.sqrt(item.distanceToSqr(mc.player));
        if (src.count > 1 && src.count != src.shownCount) {
            src.shownCount = src.count;
            src.countStr = "x" + EspEntry.num(src.count);
            src.countW = PrismDraw.interBoldWidth(src.countStr, EspPainter.DROP_FONT);
        }
        // renamed items rebuild when the server swaps the name component (animated names); plain names are cached
        Component custom = src.stack.get(DataComponents.CUSTOM_NAME);
        Object key = custom != null ? custom : src.stack.getItem();
        int rarity = src.stack.getRarity().ordinal();
        if (key != src.nameKey || rarity != src.nameRarity) {
            src.nameKey = key;
            src.nameRarity = rarity;
            if (custom != null) src.label = ItemLabel.of(src.stack.getStyledHoverName(), EspPainter.DROP_FONT);
            else {
                ItemLabel[] byRarity = itemLabels.computeIfAbsent(src.stack.getItem(), k -> new ItemLabel[Rarity.values().length]);
                if (byRarity[rarity] == null) byRarity[rarity] = ItemLabel.of(src.stack.getStyledHoverName(), EspPainter.DROP_FONT);
                src.label = byRarity[rarity];
            }
        }
        src.override = overrideColor(src);
    }

    private static int denserBackground(int color, float opacity) {
        return PrismDraw.setAlpha(themed(color), Math.round(Math.min(255, (color >>> 24) + 24) * opacity));
    }

    /** Interface theme colour mixed into the Interface background, so plates follow theme edits like the boxes do. */
    private static int themed(int color) {
        int mixed = EspPainter.lerp(color | 0xFF000000, ClientTheme.primary(), .16f);
        return (color & 0xFF000000) | (mixed & 0xFFFFFF);
    }

    private void syncStyle() {
        EspPainter.Style s = painter.style;
        s.box = switch (boxStyle.getValue()) {
            case "Frame" -> EspPainter.Box.FRAME;
            case "Glass" -> EspPainter.Box.GLASS;
            case "None" -> EspPainter.Box.NONE;
            default -> EspPainter.Box.CORNERS;
        };
        s.corner = cornerSize.getFloat() / 100f;
        s.thick = thickness.getFloat();
        s.shadow = boxShadow.getValue();
        s.fillA = fillOpacity.getFloat() / 100f;
        s.glow = glow.getValue();
        s.glowA = glowStrength.getFloat();
        s.spread = glowSpread.getFloat();
        s.pulse = targetPulse.getValue();
        s.bar = healthBar.getValue();
        s.barRight = barSide.is("Right");
        s.barW = barWidth.getFloat();
        s.trail = damageTrail.getValue();
        s.absorption = absorption.getValue();
        s.tag = tag.getValue();
        s.font = fontSize.getValue().intValue();
        s.bold = boldName.getValue();
        int bg = ClientTheme.background(204);
        float tagAlpha = tagOpacity.getFloat() / 100f;
        s.tagBg = denserBackground(bg, tagAlpha);
        s.equipmentBg = s.tagBg;
        s.dropBg = denserBackground(bg, itemOpacity.getFloat() / 100f);
        s.dropIcon = itemIcon.getValue();
        s.faceBg = s.tagBg;
        s.mendingIndicator = mendingIndicator.getValue();
        s.krushIndicator = krushIndicator.getValue();
        s.swordIndicator = swordIndicator.getValue();
        s.mendingColor = mendingColor.getValue().getRGB();
        s.krushColor = krushColor.getValue().getRGB();
        s.swordColor = swordColor.getValue().getRGB();
        s.face = face.getValue();
        s.hpValue = healthValue.getValue();
        s.distance = distance.getValue();
        s.ping = ping.getValue();
        s.strip = healthStrip.getValue();
        s.use = itemUse.getValue();
        s.equip = equipment.getValue();
        s.enchants = enchants.getValue();
        s.durability = durability.getValue();
        s.friendColor = friendC = friendColor.getValue().getRGB();
        s.targetColor = targetC = targetColor.getValue().getRGB();
        colorKind = colorMode.is("Custom") ? 1 : colorMode.is("Health") ? 2 : 0;
        primary = primaryColor.getValue().getRGB();
        secondary = secondaryColor.getValue().getRGB();
        nameFont = fontSize.getValue().intValue();
        tagOn = tag.getValue();
        pingOn = ping.getValue();
        useOn = itemUse.getValue();
        equipOn = equipment.getValue();
        enchOn = enchants.getValue();
        trailOn = damageTrail.getValue();
        boldOn = boldName.getValue();
        equipRange = equipmentRange.getFloat();
        s.boxScale = boxScale.getFloat();
        s.barScale = barScale.getFloat();
        s.nameScale = nameScale.getFloat();
        s.hpScale = hpScale.getFloat();
        s.faceScale = faceScale.getFloat();
        s.distanceScale = distanceScale.getFloat();
        s.pingScale = pingScale.getFloat();
        s.equipmentScale = equipmentScale.getFloat();
        s.itemScale = itemScale.getFloat();
        s.scale = tagScale.getFloat();
        s.minScale = minScale.getFloat();
        s.fullAt = fullSizeAt.getFloat();
    }

}
