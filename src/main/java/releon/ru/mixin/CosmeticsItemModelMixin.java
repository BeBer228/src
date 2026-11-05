package releon.ru.mixin;

import releon.ru.api.module.impl.misc.Cosmetics;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemModelResolver.class)
public abstract class CosmeticsItemModelMixin {
    @ModifyVariable(method = "appendItemLayers", at = @At("STORE"), ordinal = 0)
    private Identifier include$cosmeticModel(Identifier original, ItemStackRenderState state,
                                             ItemStack stack, ItemDisplayContext context, Level level,
                                             ItemOwner owner, int seed) {
        if (original == null || !original.getNamespace().equals("minecraft")
                || owner == null || !(owner.asLivingEntity() instanceof Player player)) return original;
        String path = original.getPath();
        Cosmetics.Group group;
        if (path.equals("mace")) group = Cosmetics.Group.Weapons;
        else if (path.endsWith("_spear")) group = Cosmetics.Group.Spears;
        else if (path.equals("totem_of_undying")) group = Cosmetics.Group.Talismans;
        else return original;
        Cosmetics cosmetics = Cosmetics.get();
        if (cosmetics == null) return original;
        Cosmetics.Entry entry = cosmetics.shown(player, group);
        Identifier model = entry == null ? null : entry.weapon(path, context);
        return model == null ? original : model;
    }
}
