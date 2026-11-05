package releon.ru.api.module.impl.misc;

import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;

public class NoCommand extends Module {
    private static NoCommand instance;
    public static boolean blocksCommands() { return instance != null && instance.isEnabled(); }

    public NoCommand() {
        super("NoCommand", "Disables client-side chat commands (e.g. .gps, .friend, .macro).", ModuleCategory.MISC);
        instance = this;
    }
}
