package releon.ru.api.module.impl.visual.hudmodern;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import java.util.*;

public final class NewNotices {
    public enum Type { INFO, ERROR, WARNING, SUCCESS, MODULE }
    public static final class Entry {
        public final String moduleName;
        public final boolean enabled;
        public final Type type;
        public final ItemStack itemStack = ItemStack.EMPTY;
        public final Identifier effectTexture = null;
        public final long startTime = System.currentTimeMillis(), durationMs;
        private final String text;
        private final Component rich;
        private Entry(String name, String text, boolean toggle, boolean enabled, long duration, Component rich) {
            moduleName = name; this.text = text; this.enabled = enabled; type = toggle ? Type.MODULE : Type.INFO;
            durationMs = Math.max(1, duration); this.rich = rich;
        }
        public boolean isCustom() { return type != Type.MODULE; }
        public String text() { return text; }
        public Component richName() { return rich; }
    }
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
    private NewNotices() {}
    public static synchronized void add(String name, String text, boolean toggle, boolean enabled, long duration, Component rich) {
        if (ENTRIES.size() >= 16) ENTRIES.removeFirst();
        ENTRIES.addLast(new Entry(name, text, toggle, enabled, duration, rich));
    }
    public static synchronized List<Entry> getActive() {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(entry -> now - entry.startTime > entry.durationMs);
        return List.copyOf(ENTRIES);
    }
}
