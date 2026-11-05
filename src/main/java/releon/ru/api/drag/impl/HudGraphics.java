package releon.ru.api.drag.impl;

import net.minecraft.client.gui.GuiGraphics;

public final class HudGraphics {
    private static GuiGraphics graphics;

    private HudGraphics() {
    }

    public static void set(GuiGraphics value) {
        graphics = value;
    }

    public static GuiGraphics get() {
        return graphics;
    }

    public static boolean available() {
        return graphics != null;
    }
}