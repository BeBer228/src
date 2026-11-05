package releon.ru.screens.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

    public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector collector, int light,
                       AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible) return;
        ItemStackRenderState[] slots = ((CosmeticAttachments) state).include$attachments();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            pose.pushPose();
            switch (i) {
                case 0 -> {
                    getParentModel().head.translateAndRotate(pose);
                    pose.translate(0, -0.57f, 0);
                    pose.scale(0.75f, 0.75f, 0.75f);
                }
                case 1 -> {
                    getParentModel().body.translateAndRotate(pose);
                    float flap = (float) Math.sin(state.ageInTicks * 0.24f);
                    pose.translate(0, 0.25f - 0.035f * flap, 0.29f);
                    pose.mulPose(Axis.XP.rotationDegrees(11 * flap));
                    pose.mulPose(Axis.ZP.rotationDegrees(3 * flap));
                    pose.scale(1.65f, 1.65f, 1.65f);
                }
                case 2 -> {
                    getParentModel().body.translateAndRotate(pose);
                    pose.translate(0, 0.32f, 0.22f);
                    pose.mulPose(Axis.ZP.rotationDegrees(2.5f * (float) Math.sin(state.ageInTicks * 0.16f)));
                    pose.scale(0.67f, 0.67f, 0.67f);
                }
                case 3 -> {
                    float orbit = state.ageInTicks * 0.035f;
                    pose.translate(0.78f * (float) Math.cos(orbit),
                            0.62f + 0.12f * (float) Math.sin(orbit * 3),
                            0.48f * (float) Math.sin(orbit));
                    pose.mulPose(Axis.YP.rotationDegrees(-orbit * (180f / (float) Math.PI) + 30));
                    pose.mulPose(Axis.ZP.rotationDegrees(8 * (float) Math.sin(orbit * 2)));
                    pose.scale(0.56f, 0.56f, 0.56f);
                }
            }
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            slots[i].submit(pose, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }
}
