package releon.ru.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;

public class VoiceChat implements VoicechatPlugin {

    public static VoicechatApi api;

    @Override
    public String getPluginId() {
        return "releon-tech";
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceChat.api = api;
    }
}