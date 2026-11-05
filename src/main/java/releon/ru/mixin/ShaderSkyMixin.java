package releon.ru.mixin;

import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.ShaderSky;

@Mixin(SkyRenderer.class)
public class ShaderSkyMixin {
    @Inject(method = "renderSkyDisc", at = @At("TAIL"))
    private void releon$shaderSky(int skyColor, CallbackInfo ci) { ShaderSky.renderSky(); }
}
