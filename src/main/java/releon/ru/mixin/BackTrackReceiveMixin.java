package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.combat.BackTrack;

/**
 * Targeted receive hook for BackTrack only; does not replace ConnectionMixin's SEND hook.
 * Off-thread HEAD invocations pass through to vanilla's thread-enqueue machinery.
 * Interception is performed only when the handler is invoked on the Minecraft client thread,
 * including packets handled inside a vanilla bundle.
 */
@Mixin(ClientPacketListener.class)
public abstract class BackTrackReceiveMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void releon$backTrackMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (BackTrack.interceptIncoming(packet, (ClientPacketListener) (Object) this)) ci.cancel();
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true, require = 1)
    private void releon$backTrackSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (BackTrack.interceptIncoming(packet, (ClientPacketListener) (Object) this)) ci.cancel();
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void releon$backTrackTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (BackTrack.interceptIncoming(packet, (ClientPacketListener) (Object) this)) ci.cancel();
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"), require = 1)
    private void releon$backTrackBeforeRemoval(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        BackTrack.beforeVanillaBarrier((ClientPacketListener) (Object) this);
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 1)
    private void releon$backTrackBeforeCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        BackTrack.beforeVanillaBarrier((ClientPacketListener) (Object) this);
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"), require = 1)
    private void releon$backTrackBeforeRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) BackTrack.beforeWorldReset((ClientPacketListener) (Object) this);
    }

    @Inject(method = "clearLevel", at = @At("HEAD"), require = 1)
    private void releon$backTrackBeforeClear(CallbackInfo ci) {
        BackTrack.beforeWorldReset((ClientPacketListener) (Object) this);
    }
}
