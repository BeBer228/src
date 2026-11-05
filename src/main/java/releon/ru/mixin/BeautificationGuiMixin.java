package releon.ru.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import releon.ru.api.module.impl.visual.Beautification;

@Mixin(Gui.class)
public class BeautificationGuiMixin {
    @ModifyExpressionValue(method = "renderTabList", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean releon$foldTab(boolean pressed) { return Beautification.tabVisible(pressed); }
}
