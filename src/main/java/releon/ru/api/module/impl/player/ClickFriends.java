package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.chat.ChatMessage;
import releon.ru.utils.repository.friend.FriendUtils;

public final class ClickFriends extends Module {
    private final BindSetting keySetting = register(new BindSetting("Кнопка", "Кнопка для добавления игрока под прицелом в друзья.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));

    private boolean addFriend;
    private boolean lastBindDown;

    public ClickFriends() {
        super("ClickFriend", "Click Friend", ModuleCategory.PLAYER);
    }

    @Override
    protected void onDisable() {
        addFriend = false;
        lastBindDown = false;
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();

        if (mc.player == null || mc.level == null) {
            lastBindDown = false;
            return;
        }

        updateBindState(client);

        if (!addFriend || mc.screen != null) {
            return;
        }
        addFriend = false;

        Player target = targetPlayer();
        if (target == null) {
            ChatMessage.brandmessage("Нету игрока под прицелом");
            return;
        }

        String name = target.getName().getString();
        if (FriendUtils.isFriend(name)) {
            ChatMessage.brandmessage("Уже в друзьях");
            return;
        }

        FriendUtils.addFriendAndSave(name);
        ChatMessage.brandmessage("Добавлен в друзья: " + name);
    }

    private void updateBindState(Minecraft client) {
        if (client.getWindow() == null || client.screen != null) {
            lastBindDown = false;
            return;
        }

        boolean bindDown = keySetting.getValue().isDown(client.getWindow().handle());
        if (bindDown && !lastBindDown) {
            addFriend = true;
        }
        lastBindDown = bindDown;
    }

    private Player targetPlayer() {
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return null;
        }
        if (!(entityHitResult.getEntity() instanceof Player player)) {
            return null;
        }
        if (player == mc.player) {
            return null;
        }
        return player;
    }
}