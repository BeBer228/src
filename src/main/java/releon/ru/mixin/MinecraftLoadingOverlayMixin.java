package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import releon.ru.screens.loading.CustomLoadingOverlay;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(Minecraft.class)
public class MinecraftLoadingOverlayMixin {

    @Redirect(
            method = "*",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadInstance;Ljava/util/function/Consumer;Z)Lnet/minecraft/client/gui/screens/LoadingOverlay;"
            )
    )
    private LoadingOverlay replaceLoadingOverlay(Minecraft minecraft, ReloadInstance reload,
                                                 Consumer<Optional<Throwable>> onFinish, boolean fadeIn) {
        return new CustomLoadingOverlay(minecraft, reload, onFinish, fadeIn);
    }
}