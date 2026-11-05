package releon.ru.mixin;

import releon.ru.screens.cosmetics.CosmeticAttachments;
import releon.ru.screens.cosmetics.CosmeticLayer;
import releon.ru.api.module.impl.misc.Cosmetics;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class CosmeticsAvatarRendererMixin extends LivingEntityRenderer<Avatar, AvatarRenderState, PlayerModel> {
    @org.spongepowered.asm.mixin.Unique
    private static final Cosmetics.Group[] include$groups = {Cosmetics.Group.Hats, Cosmetics.Group.Wings,
            Cosmetics.Group.Bodywear, Cosmetics.Group.Pets};

    protected CosmeticsAvatarRendererMixin(EntityRendererProvider.Context context, PlayerModel model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void include$addCosmeticsLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        addLayer(new CosmeticLayer(this));
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void include$extractCosmetics(Avatar avatar, AvatarRenderState state, float tick, CallbackInfo ci) {
        Cosmetics cosmetics = Cosmetics.get();
        ItemStackRenderState[] slots = ((CosmeticAttachments) state).include$attachments();
        for (int i = 0; i < slots.length; i++) {
            slots[i].clear();
            Cosmetics.Entry entry = cosmetics == null ? null : cosmetics.shown(avatar, include$groups[i]);
            if (entry != null) {
                itemModelResolver.updateForLiving(slots[i], cosmetics.displayStack(entry),
                        ItemDisplayContext.FIXED, avatar);
            }
        }
    }
}
