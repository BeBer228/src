package releon.ru.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.Beautification;
import releon.ru.api.module.impl.player.FreeCam;

@Mixin(Camera.class)
public abstract class BeautificationCameraMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Unique private Vec3 releon$previous, releon$offset = Vec3.ZERO;
    @Unique private CameraType releon$type;
    @Unique private long releon$frame;
    @Unique private Object releon$world;
    @Inject(method = "setup", at = @At("TAIL"))
    private void releon$smoothPerspective(CallbackInfo ci) {
        var client = Minecraft.getInstance();
        var self = (Camera) (Object) this;
        var beauty = Beautification.getInstance();
        var free = FreeCam.getInstance();
        boolean smooth = beauty != null && beauty.isEnabled() && beauty.perspective.getValue() && (free == null || !free.isEnabled());
        var type = client.options.getCameraType();
        long now = System.nanoTime();
        double dt = releon$frame == 0 ? 0 : Math.min(.1, (now - releon$frame) / 1e9);
        if (releon$world != client.level) { releon$previous = null; releon$offset = Vec3.ZERO; releon$world = client.level; }
        if (smooth && releon$type != null && type != releon$type && releon$previous != null) {
            releon$offset = releon$previous.subtract(self.position());
            if (releon$offset.lengthSqr() > 100) releon$offset = Vec3.ZERO;
        }
        if (!smooth || releon$offset.lengthSqr() < 1e-6) releon$offset = Vec3.ZERO;
        else releon$offset = releon$offset.scale(Math.exp(-dt * 1000 / Math.max(40, beauty.durationPerspective.getValue() * .25)));
        Vec3 desired = self.position().add(releon$offset);
        if (smooth && client.level != null && client.player != null && releon$offset.lengthSqr() > .00001) {
            var eye = client.player.getEyePosition(client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            var hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, desired,
                    net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) desired = hit.getLocation();
        }
        setPosition(desired.x, desired.y, desired.z);
        releon$previous = desired; releon$type = type; releon$frame = now;
    }
}
