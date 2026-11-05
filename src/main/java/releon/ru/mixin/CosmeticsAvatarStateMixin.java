package releon.ru.mixin;

import releon.ru.screens.cosmetics.CosmeticAttachments;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class CosmeticsAvatarStateMixin implements CosmeticAttachments {
    @Unique private final ItemStackRenderState[] include$attachments = {
            new ItemStackRenderState(), new ItemStackRenderState(),
            new ItemStackRenderState(), new ItemStackRenderState()
    };

    @Override
    public ItemStackRenderState[] include$attachments() {
        return include$attachments;
    }
}
