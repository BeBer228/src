package releon.ru;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import releon.ru.manager.Manager;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DReloadListener;
import releon.ru.utils.sounds.SoundManager;

public class Engine implements ModInitializer, ClientModInitializer {

    private final Manager manager = new Manager();

    @Override
    public void onInitialize() {
        SoundManager.init();
        Render2D.init();
    }

    @Override
    public void onInitializeClient() {
        manager.initClient();
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new Render2DReloadListener());
    }
}