package releon.ru.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.ESP;
import releon.ru.api.module.impl.visual.ShaderEsp;

/** Цвет силуэта в маске = цвет ESP (друзья зелёные и т.д.). */
@Mixin(Entity.class)
public class EspGlowEntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void releon$espGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        int color = ShaderEsp.outlineColor(self, 0);
        if (color != 0) { cir.setReturnValue(color & 0xFFFFFF); return; }
        if (ESP.shouldShaderGlow(self)) {
            cir.setReturnValue(ESP.shaderGlowColor(self));
        }
    }
}
