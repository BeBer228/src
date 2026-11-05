package releon.ru.mixin.accessor;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

@Mixin(ItemCooldowns.class)
public interface ItemCooldownsAccessor {
    @Accessor("cooldowns")
    Map<Identifier, ?> releon$getCooldowns();

    @Accessor("tickCount")
    int releon$getTickCount();
    @Invoker("getCooldownGroup")
    Identifier releon$getCooldownGroup(ItemStack stack);
}
