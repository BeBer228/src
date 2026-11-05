package releon.ru.api.module.impl.visual.hudmodern;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
public final class HudLocalization {
    public enum Part { POTIONS_NAMES }
    private static JsonObject dictionary;
    private HudLocalization() {}
    public static String ui(String english,String russian,String ukrainian) { return russian; }
    public static String title(String id) {
        for(var kind:NewHudSettings.Kind.values())if(kind.id.equals(id))return kind.label;
        return id;
    }
    public static String game(Part part,String key) {
        if(dictionary==null){
            var stream=HudLocalization.class.getResourceAsStream("/assets/include/hud_lang/ru_ru.json");
            if(stream==null)throw new IllegalStateException("Missing HUD translations");
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){dictionary=JsonParser.parseReader(reader).getAsJsonObject();}
            catch(java.io.IOException failure){throw new IllegalStateException("Cannot read HUD translations",failure);}
        }
        return dictionary.has(key)?dictionary.get(key).getAsString():net.minecraft.client.resources.language.I18n.get(key);
    }
}
