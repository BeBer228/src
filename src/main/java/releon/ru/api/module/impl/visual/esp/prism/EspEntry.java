package releon.ru.api.module.impl.visual.esp.prism;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.IdentityHashMap;

/**
 * Pooled per-entity ESP state: projected box, animated health, cached strings and the tag layout
 * computed by {@link EspPainter#layout}. Strings are rebuilt only when their rounded value changes.
 */
public final class EspEntry {
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final IdentityHashMap<ItemEnchantments, Integer> LEVELS = new IdentityHashMap<>();
    private static final String[] NUMS = new String[1024];

    public LivingEntity entity;
    public int seenFrame;
    public float alpha;
    public boolean visible;

    public float x0, y0, x1, y1, dist;
    public int c1, c2;
    public boolean player, friend, target;

    public float maxHp, abs, shownHp, trailHp, trailHold, use;

    public String name = "";
    public int nameW, nameSize = -1;
    public String hpStr, distStr, pingStr;
    public int hpW, distW, pingW;
    private int hpVal = Integer.MIN_VALUE, distVal = -1, pingVal = Integer.MIN_VALUE, infoSize = -1, boldName = -1;
    public int ping = -1;

    public final ItemStack[] eq = new ItemStack[SLOTS.length];
    public final int[] equipmentFlags = new int[SLOTS.length];
    public final int[] ench = new int[SLOTS.length];
    public int eqCount;

    /** Tag layout in local units (origin = box top centre); screen rect {@code rl..rb}; {@code solo} = overlaps another tag. */
    public float scale, tagW, tagH, eqW, lead, rl, rt, rr, rb;
    public boolean solo;
    /** Frame of the last equipment refresh. */
    public int eqFrame = -1000;

    public void reset(LivingEntity e) {
        entity = e;
        alpha = 0f;
        name = "";
        nameSize = -1;
        hpVal = pingVal = Integer.MIN_VALUE;
        distVal = -1;
        ping = -1;
        eqFrame = -1000;
        shownHp = trailHp = e.getHealth();
        trailHold = 0f;
    }

    public void updateHealth(float hp, float max, float absorption, float k, float dt, boolean trail) {
        maxHp = Math.max(1f, max);
        abs = Math.max(0f, absorption);
        shownHp += (hp - shownHp) * k;
        if (!trail || trailHp < shownHp) {
            trailHp = shownHp;
            trailHold = 0.35f;
        } else if ((trailHold -= dt) <= 0f) {
            trailHp += (shownHp - trailHp) * Math.min(1f, dt * 5f);
        }
    }

    public float total() {
        return Math.max(maxHp, shownHp + abs);
    }

    /** Refreshes the cached info strings and their widths. */
    public void updateStrings(int nameFont, int infoFont, float hp, int pingMs, boolean bold) {
        if (nameSize != nameFont || boldName != (bold ? 1 : 0)) {
            nameSize = nameFont;
            boldName = bold ? 1 : 0;
            nameW = bold ? PrismDraw.interBoldWidth(name, nameFont) : PrismDraw.interWidth(name, nameFont);
        }
        boolean re = infoSize != infoFont;
        infoSize = infoFont;
        int hv = Math.round(hp + abs);
        if (re || hv != hpVal) {
            hpVal = hv;
            hpStr = num(Math.max(0, hv));
            hpW = PrismDraw.interBoldWidth(hpStr, infoFont);
        }
        int dv = Math.round(dist);
        if (re || dv != distVal) {
            distVal = dv;
            distStr = num(dv) + "m";
            distW = PrismDraw.interWidth(distStr, infoFont);
        }
        if (re || pingMs != pingVal) {
            pingVal = pingMs;
            pingStr = pingMs < 0 ? null : num(pingMs) + "ms";
            pingW = pingStr == null ? 0 : PrismDraw.interWidth(pingStr, infoFont);
        }
    }

    public void addEquip(ItemStack stack, boolean enchants) {
        if (stack == null || stack.isEmpty()) return;
        eq[eqCount] = stack;
        equipmentFlags[eqCount] = EquipmentHighlights.classify(stack);
        ench[eqCount++] = enchants ? enchantLevel(stack) : 0;
    }

    public static String num(int n) {
        if (n < 0 || n >= NUMS.length) return Integer.toString(n);
        String s = NUMS[n];
        return s != null ? s : (NUMS[n] = Integer.toString(n));
    }

    /** Highest enchantment level, cached per (immutable) component instance. */
    private static int enchantLevel(ItemStack stack) {
        ItemEnchantments en = stack.get(DataComponents.ENCHANTMENTS);
        if (en == null || en.isEmpty()) return 0;
        Integer cached = LEVELS.get(en);
        if (cached != null) return cached;
        int max = 0;
        for (Holder<Enchantment> h : en.keySet()) max = Math.max(max, en.getLevel(h));
        if (LEVELS.size() > 512) LEVELS.clear();
        LEVELS.put(en, max);
        return max;
    }
}
