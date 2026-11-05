package releon.ru.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.manager.Manager;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin {
    // These fields are mutable in AbstractContainerScreen: do not mark them @Final.
    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;
    @Shadow @Nullable protected Slot hoveredSlot;

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"),
            require = 1
    )
    private void releon$handledScreenRender(GuiGraphics graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        // The inventory has its own hook below its override; do not post twice
        // when InventoryScreen.render calls super.render.
        if ((Object) this instanceof InventoryScreen) return;
        Manager.postEvent(new HandledScreenEvent(graphics, hoveredSlot, imageWidth, imageHeight));
    }
}
