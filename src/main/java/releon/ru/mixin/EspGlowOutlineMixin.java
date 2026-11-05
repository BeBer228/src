package releon.ru.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.ESP;

@Mixin(LevelRenderer.class)
public class EspGlowOutlineMixin {
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$hideVanillaOutline(CallbackInfo ci) {
        if (ESP.hasShaderGlowTargets()) {
            ci.cancel();
        }
    }
}
