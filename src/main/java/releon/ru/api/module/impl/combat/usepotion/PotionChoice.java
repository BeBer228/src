package releon.ru.api.module.impl.combat.usepotion;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exact chosen splash-potion components; count and inventory position are not part of its identity. */
public final class PotionChoice {
    private final ItemStack sample;
    public final String title;

    public PotionChoice(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.SPLASH_POTION)) throw new IllegalArgumentException("Not a splash potion");
        sample = stack.copy(); sample.setCount(1);
        title = sample.getHoverName().getString();
    }
    public ItemStack icon() { return sample.copy(); }
    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.SPLASH_POTION) && ItemStack.isSameItemSameComponents(sample, stack);
    }
}
