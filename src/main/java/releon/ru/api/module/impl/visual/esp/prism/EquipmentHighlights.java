package releon.ru.api.module.impl.visual.esp.prism;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.regex.Pattern;

/** Equipment predicates use real enchantments; server-only Poison may also be supplied in lore. */
public final class EquipmentHighlights {
    public static final int MENDING = 1, KRUSH = 2, SWORD = 4;
    private static final Pattern POISON = Pattern.compile(
            "^[\\p{P}\\p{S}\\s]*(?:яд|poison|venom)(?:\\s+[ivxlcdm\\d]+)?[\\p{P}\\p{S}\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FORMATTING = Pattern.compile("§[0-9a-fk-orx]", Pattern.CASE_INSENSITIVE);

    private EquipmentHighlights() {}

    public static int classify(ItemStack stack) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        boolean armor = equippable != null && (equippable.slot() == EquipmentSlot.HEAD
                || equippable.slot() == EquipmentSlot.CHEST || equippable.slot() == EquipmentSlot.LEGS
                || equippable.slot() == EquipmentSlot.FEET);
        boolean sword = stack.is(ItemTags.SWORDS);
        if (!armor && !sword) return 0;
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || enchantments.isEmpty()) return 0;
        int protection = 0, unbreaking = 0, sharpness = 0, looting = 0;
        boolean mending = false, smite = false, poison = false;
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            int level = enchantments.getLevel(enchantment);
            if (enchantment.is(Enchantments.MENDING)) mending = level > 0;
            else if (enchantment.is(Enchantments.PROTECTION)) protection = level;
            else if (enchantment.is(Enchantments.UNBREAKING)) unbreaking = level;
            else if (enchantment.is(Enchantments.SHARPNESS)) sharpness = level;
            else if (enchantment.is(Enchantments.LOOTING)) looting = level;
            else if (enchantment.is(Enchantments.SMITE)) smite = level >= enchantment.value().getMaxLevel();
            else if (level > 0) {
                poison |= enchantment.unwrapKey().map(key -> {
                    String path = key.identifier().getPath();
                    return path.equals("poison") || path.equals("venom") || path.endsWith("/poison");
                }).orElse(false) || poisonLine(enchantment.value().description().getString());
            }
        }
        int result = armor && mending ? MENDING : 0;
        if (armor && mending && protection >= 5 && unbreaking >= 5) result |= KRUSH;
        if (sword && mending && sharpness >= 7 && unbreaking >= 5 && smite && looting >= 5) {
            var lore = stack.get(DataComponents.LORE);
            if (!poison && lore != null)
                for (var line : lore.lines()) if (poisonLine(line.getString())) { poison = true; break; }
            if (poison) result |= SWORD;
        }
        return result;
    }

    private static boolean poisonLine(String line) {
        return POISON.matcher(FORMATTING.matcher(line).replaceAll("").strip()).matches();
    }
}
