package releon.ru.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.ESP;

/** Цвет силуэта в маске = цвет ESP (друзья зелёные и т.д.). */
@Mixin(Entity.class)
public class EspGlowEntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void releon$espGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (ESP.shouldShaderGlow(self)) {
            cir.setReturnValue(ESP.shaderGlowColor(self));
        }
    }
}
