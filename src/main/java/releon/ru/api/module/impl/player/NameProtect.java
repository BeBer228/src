package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TextFactoryEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.repository.friend.Friend;
import releon.ru.utils.repository.friend.FriendUtils;

public final class NameProtect extends Module {

    private static NameProtect instance;

    private final StringSetting nameSetting = register(new StringSetting(
            "Name",
            "Типо ник",
            "Titanium Client",
            32
    ));

    private final BooleanSetting friendsSetting = register(new BooleanSetting(
            "Friends",
            "Скрыть ники тиммейтов",
            true
    ));

    public NameProtect() {
        super(
                "Name Protect",
                "Скрывает никнеймы",
                ModuleCategory.PLAYER
        );
        instance = this;
    }

    public static NameProtect getInstance() {
        return instance;
    }

    public static String protect(String text) {
        NameProtect module = instance;
        if (module == null || !module.isEnabled() || text == null || text.isEmpty()) {
            return text;
        }

        Minecraft client = Minecraft.getInstance();
        String replacement = module.getReplacement();

        if (client.getUser() != null) {
            String username = client.getUser().getName();
            if (username != null && !username.isBlank()) {
                text = text.replace(username, replacement);
            }
        }

        if (client.player != null) {
            String playerName = client.player.getName().getString();
            if (playerName != null && !playerName.isBlank()) {
                text = text.replace(playerName, replacement);
            }
        }

        if (module.friendsSetting.getValue()) {
            for (Friend friend : FriendUtils.getFriends()) {
                if (friend == null) {
                    continue;
                }
                String friendName = String.valueOf(friend);
                if (!friendName.isBlank()) {
                    text = text.replace(friendName, "Friend");
                }
            }
        }

        return text;
    }

    private String getReplacement() {
        String replacement = nameSetting.getValue();
        return replacement == null || replacement.isBlank() ? "Player" : replacement;
    }

    @SubscribeEvent
    private void onTextFactory(TextFactoryEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.getUser() == null) {
            return;
        }

        String replacement = getReplacement();

        String username = client.getUser().getName();
        if (username != null && !username.isBlank()) {
            event.replaceText(username, replacement);
        }

        if (client.player != null) {
            event.replaceText(client.player.getName().getString(), replacement);
        }

        if (!friendsSetting.getValue()) {
            return;
        }

        for (Friend friend : FriendUtils.getFriends()) {
            if (friend == null) {
                continue;
            }

            String friendName = String.valueOf(friend);
            if (!friendName.isBlank()) {
                event.replaceText(friendName, "Friend");
            }
        }
    }
}