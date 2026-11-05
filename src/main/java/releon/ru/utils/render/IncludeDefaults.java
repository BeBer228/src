package releon.ru.utils.render;

import com.google.gson.*;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A bundled snapshot of Include's selected presets; never reads the donor installation at runtime. */
public final class IncludeDefaults {
    private static final JsonObject PRESETS = load();
    private IncludeDefaults() {}

    private static JsonObject load() {
        var stream = IncludeDefaults.class.getResourceAsStream("/assets/releon/include-presets.json");
        if (stream == null) throw new IllegalStateException("Missing Include presets");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read Include presets", e);
        }
    }

    public static JsonObject settings(String module) { return PRESETS.getAsJsonObject(module); }

    public static void apply(String module, List<Setting<?>> settings, String prefix) {
        JsonObject values = settings(module);
        for (Setting<?> setting : settings) {
            String name = setting.getName();
            if (name.startsWith(prefix)) name = name.substring(prefix.length());
            JsonElement value = values.get(name);
            if (value == null) continue;
            if (setting instanceof ColorSetting color && value.isJsonObject()) {
                JsonObject source = value.getAsJsonObject(), target = new JsonObject();
                target.add("rgba", source.get("color"));
                target.add("secondary", source.get("color2"));
                target.add("mode", source.get("mode"));
                target.add("speed", source.get("speed"));
                target.add("preset", source.get("rainbowPreset"));
                color.fromJson(target);
            } else if (setting instanceof MultiModeSetting && value.isJsonObject()) {
                JsonArray selected = new JsonArray();
                for (var entry : value.getAsJsonObject().entrySet()) if (entry.getValue().getAsBoolean()) selected.add(entry.getKey());
                setting.fromJson(selected);
            } else setting.fromJson(value);
        }
    }
}
