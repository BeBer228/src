package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

import java.awt.Color;
import releon.ru.utils.render.color.ClientTheme;

public class ColorSetting extends Setting<Color> {
    public enum Mode { CUSTOM, THEME, RAINBOW, GRADIENT }
    public static final String[] PRESETS = {"Classic", "Pastel", "Neon", "Cyber", "Sunset"};
    private Mode mode = Mode.CUSTOM;
    private Color secondary = new Color(0x4F46E5);
    private float speed = 1.0f;
    private int preset;

    public ColorSetting(String name, String description, Color defaultValue) {
        super(name, description, defaultValue, SettingType.COLOR);
    }

    public Color getCustomColor() { return super.getValue(); }
    public Mode getMode() { return mode; }
    public Color getSecondary() { return secondary; }
    public float getSpeed() { return speed; }
    public int getPreset() { return preset; }

    public void setMode(Mode value) {
        Mode next = value == null ? Mode.CUSTOM : value;
        if (mode != next) { mode = next; changed(); }
    }

    public void setSecondary(Color value) {
        Color next = normalize(value);
        if (!secondary.equals(next)) { secondary = next; changed(); }
    }

    public void setSpeed(float value) {
        float next = Float.isFinite(value) ? Math.max(0.1f, Math.min(3f, value)) : 1f;
        if (speed != next) { speed = next; changed(); }
    }

    public void setPreset(int value) {
        int next = Math.max(0, Math.min(PRESETS.length - 1, value));
        if (preset != next) { preset = next; changed(); }
    }

    private void changed() { notifyListeners(super.getValue(), super.getValue()); }

    @Override
    public Color getValue() {
        Color custom = super.getValue();
        if (mode == Mode.CUSTOM) return custom;
        if (mode == Mode.THEME) return new Color(ClientTheme.primary(custom.getAlpha()), true);
        double time = System.currentTimeMillis() * (double) speed;
        if (mode == Mode.GRADIENT) {
            float p = (float) (Math.sin(time * Math.PI * 2 / 3200) * 0.5 + 0.5);
            return new Color(mix(custom.getRed(), secondary.getRed(), p),
                    mix(custom.getGreen(), secondary.getGreen(), p), mix(custom.getBlue(), secondary.getBlue(), p),
                    mix(custom.getAlpha(), secondary.getAlpha(), p));
        }
        float hue = (float) ((time % 4000) / 4000);
        float saturation = preset == 1 ? 0.45f : preset == 2 ? 1f : 0.85f;
        if (preset == 3) hue = (float) (Math.sin(time * Math.PI * 2 / 2600) * 0.25 + 0.5);
        if (preset == 4) hue = (float) ((0.85 + (Math.sin(time * Math.PI * 2 / 3000) * 0.5 + 0.5) * 0.25) % 1);
        int rgb = Color.HSBtoRGB(hue, saturation, preset == 1 ? 0.95f : 1f);
        return new Color((custom.getAlpha() << 24) | (rgb & 0xFFFFFF), true);
    }

    private static int mix(int a, int b, float p) { return Math.round(a + (b - a) * p); }

    @Override
    public void reset() {
        mode = Mode.CUSTOM;
        secondary = new Color(0x4F46E5);
        speed = 1f;
        preset = 0;
        super.reset();
        changed();
    }

    @Override
    public JsonElement toJson() {
        Color color = getCustomColor();
        JsonObject object = new JsonObject();
        object.addProperty("red", color.getRed());
        object.addProperty("green", color.getGreen());
        object.addProperty("blue", color.getBlue());
        object.addProperty("alpha", color.getAlpha());
        object.addProperty("rgba", color.getRGB());
        object.addProperty("mode", mode.name());
        object.addProperty("secondary", secondary.getRGB());
        object.addProperty("speed", speed);
        object.addProperty("preset", preset);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("mode")) {
            String name = object.get("mode").getAsString();
            for (Mode candidate : Mode.values()) {
                if (candidate.name().equalsIgnoreCase(name)) { setMode(candidate); break; }
            }
        }
        if (object.has("secondary")) setSecondary(new Color(object.get("secondary").getAsInt(), true));
        if (object.has("speed")) setSpeed(object.get("speed").getAsFloat());
        if (object.has("preset")) setPreset(object.get("preset").getAsInt());
        if (object.has("rgba")) {
            setValue(new Color(object.get("rgba").getAsInt(), true));
            return;
        }
        int red = object.has("red") ? object.get("red").getAsInt() : getValue().getRed();
        int green = object.has("green") ? object.get("green").getAsInt() : getValue().getGreen();
        int blue = object.has("blue") ? object.get("blue").getAsInt() : getValue().getBlue();
        int alpha = object.has("alpha") ? object.get("alpha").getAsInt() : getValue().getAlpha();
        setValue(new Color(clamp(red), clamp(green), clamp(blue), clamp(alpha)));
    }

    @Override
    protected Color normalize(Color value) {
        return value == null ? new Color(255, 255, 255, 255) : value;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
