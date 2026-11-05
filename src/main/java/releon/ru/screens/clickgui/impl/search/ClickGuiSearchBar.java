package releon.ru.screens.clickgui.impl.search;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.settings.Setting;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.Locale;

public final class ClickGuiSearchBar {
    private static final int MAX_LENGTH = 48;
    private static final float TEXT_SIZE = 6.2f;

    private final SmoothAnimation focusAnimation = new SmoothAnimation();
    private String query = "";
    private boolean focused;
    private boolean selectedAll;
    private int cursor;
    private float x;
    private float y;
    private float width;
    private float height;

    public ClickGuiSearchBar() {
        focusAnimation.set(0.0);
    }

    public void render(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        focusAnimation.run(focused ? 1.0 : 0.0, 0.16, Easings.CUBIC_OUT, true);
        focusAnimation.update();
        float focus = focusAnimation.get();
        boolean hover = hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), x, y, width, height);

        ClickGuiTheme.field(x, y, width, height, hover ? 1.0f : 0.0f, focus, alpha);

        // иконка лупы справа / крестик очистки — по центру поля
        float iconSize = 8.0f;
        float mid = y + height * 0.5f;
        float iconCx = x + width - 5.0f - iconSize * 0.5f;
        float iconX = iconCx - iconSize * 0.5f;
        if (query.isEmpty()) {
            ClickGuiTheme.textCenter(FontType.ICONS, "U", iconCx, mid, iconSize, ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.ACCENT, focus, alpha));
        } else {
            boolean clearHover = hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), iconX - 2.0f, y, iconSize + 6.0f, height);
            ClickGuiTheme.textCenter(FontType.ICONS, "C", iconCx, mid, iconSize - 2.0f,
                    ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.TEXT, clearHover ? 1.0f : 0.0f, alpha));
        }

        float textX = x + 6.0f;
        float clipW = width - iconSize - 14.0f;
        if (query.isEmpty() && !focused) {
            // самый частый случай — без scissor
            ClickGuiTheme.text(FontType.SEMIBOLD, "Search for modules...", textX, mid, TEXT_SIZE, ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, alpha));
            return;
        }
        float qw = query.isEmpty() ? 0.0f : Render2D.textWidth(FontType.SEMIBOLD, query, TEXT_SIZE);
        boolean clip = qw > clipW - 4.0f;
        if (clip) {
            Render2D.pushScissor(graphics, x + 3.0f, y, clipW + 3.0f, height);
        }
        try {
            if (!query.isEmpty()) {
                float shift = Math.min(0.0f, clipW - qw - 4.0f);
                if (selectedAll && focused) {
                    ClickGuiTheme.rect(textX + shift - 1.0f, y + 3.0f, qw + 2.0f, height - 6.0f, 1.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, 80, alpha));
                }
                ClickGuiTheme.text(FontType.SEMIBOLD, query, textX + shift, mid, TEXT_SIZE, ClickGuiTheme.c(ClickGuiTheme.TEXT, alpha));
                textX += shift;
            }
            if (focused && !selectedAll && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                float caret = cursor <= 0 ? 0.0f : Render2D.textWidth(FontType.SEMIBOLD, query.substring(0, Math.min(cursor, query.length())), TEXT_SIZE);
                ClickGuiTheme.rect(textX + caret + 0.5f, y + 3.5f, ClickGuiTheme.line(), height - 7.0f, 0.0f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, focus * alpha));
            }
        } finally {
            if (clip) {
                Render2D.popScissor(graphics);
            }
        }
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (hovered(mx, my, x, y, width, height)) {
            if (event.button() == 0) {
                float iconX = x + width - 13.0f;
                if (!query.isEmpty() && mx >= iconX - 2.0f) {
                    clear();
                    return true;
                }
                focused = true;
                selectedAll = doubled && !query.isEmpty();
                cursor = query.length();
            } else if (event.button() == 1) {
                clear();
            }
            return true;
        }
        if (focused) {
            focused = false;
            selectedAll = false;
        }
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (!focused) {
            return false;
        }
        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_A) {
            selectedAll = !query.isEmpty();
            cursor = query.length();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_V) {
            String clip = clipboard();
            if (!clip.isEmpty()) {
                insert(clip.replace("\n", " ").replace("\r", ""));
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!query.isEmpty()) {
                clear();
            } else {
                focused = false;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            focused = false;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (selectedAll) {
                clear();
                focused = true;
            } else if (cursor > 0) {
                query = query.substring(0, cursor - 1) + query.substring(cursor);
                cursor--;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            if (selectedAll) {
                clear();
                focused = true;
            } else if (cursor < query.length()) {
                query = query.substring(0, cursor) + query.substring(cursor + 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            cursor = Math.max(0, cursor - 1);
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            cursor = Math.min(query.length(), cursor + 1);
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            cursor = 0;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            cursor = query.length();
            selectedAll = false;
            return true;
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!focused || !event.isAllowedChatCharacter()) {
            return false;
        }
        insert(event.codepointAsString());
        return true;
    }

    private void insert(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (selectedAll) {
            query = "";
            cursor = 0;
            selectedAll = false;
        }
        int room = MAX_LENGTH - query.length();
        if (room <= 0) {
            return;
        }
        if (text.length() > room) {
            text = text.substring(0, room);
        }
        cursor = Math.max(0, Math.min(cursor, query.length()));
        query = query.substring(0, cursor) + text + query.substring(cursor);
        cursor += text.length();
    }

    public void focus() {
        focused = true;
        selectedAll = !query.isEmpty();
        cursor = query.length();
    }

    public void clear() {
        query = "";
        cursor = 0;
        selectedAll = false;
    }

    public boolean isFocused() {
        return focused;
    }

    public boolean isActive() {
        return !normalize(query).isEmpty();
    }

    public String query() {
        return query;
    }

    // ---------------------------------------------------------------- scoring

    /** Релевантность модуля запросу (0 — не подходит). Имя модуля весит больше настроек. */
    public int score(Module module) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return 1;
        }
        int best = score(q, normalize(module.getName()));
        if (best > 0) {
            best += 200;
        }
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isVisible()) {
                best = Math.max(best, score(q, normalize(setting.getName())));
            }
        }
        return best;
    }

    private static int score(String query, String candidate) {
        if (query.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        if (candidate.equals(query)) {
            return 1000;
        }
        if (candidate.startsWith(query)) {
            return 850 - candidate.length();
        }
        int index = candidate.indexOf(query);
        if (index >= 0) {
            return 650 - index * 8 - candidate.length();
        }
        int matched = 0;
        int lastIndex = -1;
        int penalty = 0;
        for (int i = 0; i < query.length(); i++) {
            int next = candidate.indexOf(query.charAt(i), lastIndex + 1);
            if (next < 0) {
                return 0;
            }
            matched++;
            penalty += Math.max(0, next - lastIndex - 1);
            lastIndex = next;
        }
        int fuzzy = 420 + matched * 18 - penalty * 6 - candidate.length();
        return Math.max(0, fuzzy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String clipboard() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            String s = GLFW.glfwGetClipboardString(client.getWindow().handle());
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public void unfocus() {
        focused = false;
        selectedAll = false;
    }
}
