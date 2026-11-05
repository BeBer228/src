package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.ESP;

/** Цели ESP попадают в entity outline буфер -> он служит маской для esp_glow.fsh. */
@Mixin(Minecraft.class)
public class EspGlowMinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void releon$forceEspGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ESP.shouldShaderGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
