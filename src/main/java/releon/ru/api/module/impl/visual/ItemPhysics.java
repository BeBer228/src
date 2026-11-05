package releon.ru.api.module.impl.visual;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

public class ItemPhysics extends Module {
    private static ItemPhysics instance;

    private final ModeSetting mode = register(new ModeSetting("Physics", "Dropped item physics mode.", "Normal", "Normal"));
    private final BooleanSetting shrink = register(new BooleanSetting("Shrink Items", "Render dropped items smaller.", false));
    private final NumberSetting shrinkFactor = register(new NumberSetting("Shrink Factor", "How many times smaller dropped items are.", 1.5, 1.0, 4.0, 0.1));

    public ItemPhysics() {
        super("Item Physics", "Adds ground physics to dropped items.", ModuleCategory.VISUAL);
        instance = this;
        shrinkFactor.visibleWhen(shrink::getValue);
    }

    public static ItemPhysics getInstance() {
        return instance;
    }

    public static ItemPhysics active() {
        return instance != null && instance.isEnabled() ? instance : null;
    }

    public boolean shrinkEnabled() {
        return shrink.getValue();
    }

    public float scale() {
        return shrink.getValue() ? 1.0F / shrinkFactor.getFloat() : 1.0F;
    }
}
