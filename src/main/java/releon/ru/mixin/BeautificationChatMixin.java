package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import releon.ru.api.module.impl.visual.Beautification;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Beautification "Новые сообщения чата": alpha-only fade-in, so line positions, hover and clicks are unchanged. */
@Mixin(ChatComponent.class)
public class BeautificationChatMixin {

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
    private void include$markNew(GuiMessage message, CallbackInfo ci) {
        Beautification.chatMessageAdded(message.addedTime());
    }

    @ModifyExpressionValue(method = "forEachLine", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;calculate(Lnet/minecraft/client/GuiMessage$Line;)F"))
    private float include$fadeIn(float alpha, @Local GuiMessage.Line line) {
        return alpha * Beautification.chatAlpha(line.addedTime());
    }
}
