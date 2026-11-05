package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.screens.mainmenu.MainScreen;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$replaceMainMenu(CallbackInfo ci) {
        Minecraft.getInstance().setScreen(new MainScreen());
        ci.cancel();
    }
}