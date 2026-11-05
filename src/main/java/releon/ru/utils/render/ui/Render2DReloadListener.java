package releon.ru.utils.render.ui;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class Render2DReloadListener implements IdentifiableResourceReloadListener {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("releon", "render2d_reload");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(
            PreparableReloadListener.SharedState sharedState,
            Executor backgroundExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor gameExecutor
    ) {
        return barrier.wait(null).thenRunAsync(Render2D::close, gameExecutor);
    }
}