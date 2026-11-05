package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import releon.ru.api.module.impl.visual.Beautification;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;

/** Beautification "Плавный Tab": scales the list from its top centre while it unfolds/folds. */
@Mixin(PlayerTabOverlay.class)
public class BeautificationTabMixin {

    @WrapMethod(method = "render")
    private void include$scaleTab(GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, Operation<Void> original) {
        float scale = Beautification.tabScale();
        if (scale >= 1) {
            original.call(graphics, width, scoreboard, objective);
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width * 0.5f, 0);
        pose.scale(scale, scale);
        pose.translate(-width * 0.5f, 0);
        try {
            original.call(graphics, width, scoreboard, objective);
        } finally {
            pose.popMatrix();
        }
    }
}
