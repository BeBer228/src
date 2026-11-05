package releon.ru.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.manager.Manager;

/** Explicit inventory hook; register ItemScrollerInventoryScreenMixin in the client mixin list. */
@Mixin(InventoryScreen.class)
public abstract class ItemScrollerInventoryScreenMixin extends AbstractContainerScreen<InventoryMenu> {
    protected ItemScrollerInventoryScreenMixin(InventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"),
            require = 1
    )
    private void releon$itemScrollerInventoryRender(GuiGraphics graphics, int mouseX, int mouseY,
                                                  float partialTick, CallbackInfo ci) {
        // Use the screen's actual hovered slot after its render override completes.
        // Never estimate coordinates beneath a recipe-book overlay or use an old slot ID.
        Manager.postEvent(new HandledScreenEvent(graphics, hoveredSlot, imageWidth, imageHeight));
    }
}
