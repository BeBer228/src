package releon.ru.api.module.impl.combat;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;

public class NoInteract extends Module {
    public NoInteract() {
        super("NoInteract", "Блокирует взаимодействия.", ModuleCategory.COMBAT);
    }
}