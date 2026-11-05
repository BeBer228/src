package releon.ru.mixin;
import releon.ru.api.module.impl.visual.Beautification;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Screen.class)
public class BeautificationScreenMixin {

    @org.spongepowered.asm.mixin.Unique
    private float include$beautifyScale = 1.0f;

    @org.spongepowered.asm.mixin.Unique
    private boolean include$backgroundUnscaled = false;

    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"))
    private void include$beginBeautifyScale(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        include$beautifyScale = 1.0f;
        if (!Beautification.animate((Screen)(Object)this)) return;
        float scale = Beautification.beginScale((Screen) (Object) this);
        if (scale >= 1.0f) return;
        org.joml.Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        Screen self = (Screen) (Object) this;
        pose.translate(self.width * 0.5f, self.height * 0.5f);
        if (Beautification.getInstance().slide.getValue()) {
            pose.translate(0, (1.0f-scale)*100);
        }
        pose.scale(scale, scale);
        pose.translate(-self.width * 0.5f, -self.height * 0.5f);
        include$beautifyScale = scale;
    }

    /**
     * The grey menu backdrop must not ride the opening scale/slide animation: it is drawn from
     * inside the scaled block, so push the inverse transform around {@code renderBackground}
     * while the animation is still running. The result is a full-screen gradient at full size
     * from the very first frame.
     */
    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void include$beginUnscaledBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (include$beautifyScale >= 1.0f || !Beautification.animate((Screen)(Object)this)) return;
        Screen self = (Screen) (Object) this;
        float scale = include$beautifyScale;
        org.joml.Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(self.width * 0.5f, self.height * 0.5f);
        pose.scale(1.0f / scale, 1.0f / scale);
        if (Beautification.getInstance().slide.getValue()) {
            pose.translate(0, -(1.0f - scale) * 100);
        }
        pose.translate(-self.width * 0.5f, -self.height * 0.5f);
        include$backgroundUnscaled = true;
    }

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void include$endUnscaledBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (include$backgroundUnscaled) {
            graphics.pose().popMatrix();
            include$backgroundUnscaled = false;
        }
    }

    /** Beautification's "Фон меню" off removes the dark gradient behind animated in-game menus. */
    @Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void include$hideMenuBackdrop(GuiGraphics graphics, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (self instanceof AbstractContainerScreen<?>) return;
        if (Beautification.animate(self)
                && !Beautification.menuDimEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("TAIL"))
    private void include$endBeautifyScale(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (include$beautifyScale < 1.0f) {
            graphics.pose().popMatrix();
            include$beautifyScale = 1.0f;
        }
    }

    @Inject(method = "added", at = @At("HEAD"))
    private void include$resetAnimation(CallbackInfo ci) {
        Beautification.opened((Screen)(Object)this);
    }

}
