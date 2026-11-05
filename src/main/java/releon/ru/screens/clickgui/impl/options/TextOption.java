package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class TextOption extends ClickGuiOption {
    private static final float TEXT_SIZE = 6.2f;
    private final SmoothAnimation scrollAnimation = new SmoothAnimation();
    private final SmoothAnimation outlineAnimation = new SmoothAnimation();
    private final StringSetting setting;
    private String value;
    private boolean editing;
    private boolean selectedAll;
    private int cursor;
    private int selectionStart;
    private String cachedCursorValue;
    private int cachedCursor = -1;
    private float cachedCursorWidth;
    private String cachedSelValue;
    private int cachedSelStart = -1;
    private int cachedSelEnd = -1;
    private float cachedSelStartWidth;
    private float cachedSelEndWidth;
    private float controlX;
    private float controlY;
    private float controlWidth;
    private float controlHeight;

    public TextOption(StringSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getValue();
        this.cursor = value.length();
        this.selectionStart = this.cursor;
        scrollAnimation.set(0.0);
        outlineAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        if (!editing) {
            value = setting.getValue();
            cursor = Math.min(cursor, value.length());
            selectionStart = cursor;
        }
        controlWidth = Math.min(Math.max(50.0f, width * 0.5f), 86.0f);
        controlHeight = 12.0f;
        controlX = x + width - controlWidth;
        controlY = firstLineCenter() - controlHeight * 0.5f;
        float textSize = TEXT_SIZE;
        float textX = controlX + 5.0f;
        float textY = top(controlY + controlHeight * 0.5f, textSize);
        float available = controlWidth - 10.0f;
        float valueWidth = textWidth(value, textSize);
        float caretW = cursorWidth(textSize);
        float targetScroll;
        if (editing) {
            float caretGlobal = textX + scrollAnimation.get() + caretW;
            if (caretGlobal < textX + 2.0f) {
                targetScroll = -(caretW - 2.0f);
            } else if (caretGlobal > textX + available - 3.0f) {
                targetScroll = available - caretW - 3.0f;
            } else {
                targetScroll = scrollAnimation.get();
            }
            targetScroll = Math.min(0.0f, Math.max(available - valueWidth - 1.0f, targetScroll));
        } else {
            targetScroll = 0.0f;
        }
        scrollAnimation.run(targetScroll, 0.16, Easings.CUBIC_OUT, true);
        scrollAnimation.update();
        outlineAnimation.run(editing ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        outlineAnimation.update();
        float outlineP = outlineAnimation.get();

        ClickGuiTheme.field(controlX, controlY, controlWidth, controlHeight, hoverProgress, outlineP, alpha);

        if (editing) {
            Render2D.pushScissor(graphics, controlX + 3.0f, controlY, available + 4.0f, controlHeight);
            int selS = Math.min(selectionStart, cursor);
            int selE = Math.max(selectionStart, cursor);
            boolean hasSel = selE > selS;
            if (hasSel && !value.isEmpty()) {
                float sw = selWidth(selS, selE, textSize);
                float sx = textX + scrollAnimation.get() + selStartWidth(selS, textSize);
                Render2D.rect(sx - 0.5f, controlY + 2.5f, sw + 1.5f, controlHeight - 5.0f, 1.5f,
                        ClickGuiTheme.c(ClickGuiTheme.ACCENT, 80, alpha));
            }
            Render2D.text(FontType.SEMIBOLD, value, textX + scrollAnimation.get(), textY, textSize, valueColor());
            if ((!hasSel || selectedAll) && (System.currentTimeMillis() / 420L) % 2L == 0L) {
                float cursorX = textX + scrollAnimation.get() + caretW;
                Render2D.rect(cursorX + 0.5f, controlY + 2.5f, 0.8f, controlHeight - 5.0f, 0.4f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha));
            }
            Render2D.popScissor(graphics);
        } else if (value.isEmpty()) {
            Render2D.text(FontType.SEMIBOLD, "Empty", textX, textY, textSize, dimLabelColor());
        } else {
            renderScrollingText(graphics, "text:value", value, textX, textY, textSize, valueColor(),
                    controlX + 3.0f, controlY, available + 4.0f, controlHeight,
                    controlX, controlY, controlWidth, controlHeight);
        }
        renderLabelBlock(graphics, "text", controlX - 6.0f);
    }

    @Override
    public boolean capturesKeyboard() {
        return editing;
    }

    @Override
    public void closePopup() {
        editing = false;
        selectedAll = false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (hovered(event.x(), event.y(), controlX - 2.0f, controlY - 2.0f, controlWidth + 4.0f, controlHeight + 4.0f)) {
            boolean wasEditing = editing;
            editing = true;
            selectedAll = false;
            float textSize = TEXT_SIZE;
            float localX = (float) event.x() - (controlX + 5.0f + scrollAnimation.get());
            float cw = 0;
            int found = value.length();
            for (int i = 0; i <= value.length(); i++) {
                float w = textWidth(value.substring(0, i), textSize);
                if (w >= localX) {
                    if (i == 0) { found = 0; break; }
                    float prevW = textWidth(value.substring(0, i - 1), textSize);
                    found = (Math.abs(w - localX) < Math.abs(prevW - localX)) ? i : i - 1;
                    break;
                }
                cw = w;
                found = i;
            }
            cursor = (int) clamp(found, 0, value.length());
            if (!wasEditing || doubled) {
                selectionStart = 0;
                cursor = value.length();
                selectedAll = true;
            } else {
                selectionStart = cursor;
            }
            return true;
        }
        editing = false;
        selectedAll = false;
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!editing) {
            return false;
        }

        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!shift) selectionStart = cursor;

        if (ctrl && key == GLFW.GLFW_KEY_A) {
            selectedAll = true;
            selectionStart = 0;
            cursor = value.length();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_C) {
            setClipboard(selectedText());
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_X) {
            setClipboard(selectedText());
            deleteSelected();
            syncSetting();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_V) {
            insertText(getClipboard());
            return true;
        }

        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (hasSelection()) {
                deleteSelected();
            } else if (cursor > 0) {
                value = value.substring(0, cursor - 1) + value.substring(cursor);
                cursor--;
                selectionStart = cursor;
            }
            syncSetting();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            if (hasSelection()) {
                deleteSelected();
            } else if (cursor < value.length()) {
                value = value.substring(0, cursor) + value.substring(cursor + 1);
            }
            syncSetting();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            cursor = Math.max(0, cursor - 1);
            if (!shift) selectionStart = cursor;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            cursor = Math.min(value.length(), cursor + 1);
            if (!shift) selectionStart = cursor;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            cursor = 0;
            if (!shift) selectionStart = cursor;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            cursor = value.length();
            if (!shift) selectionStart = cursor;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
            editing = false;
            selectedAll = false;
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!editing || !event.isAllowedChatCharacter()) {
            return false;
        }
        insertText(event.codepointAsString());
        return true;
    }

    private boolean hasSelection() {
        if (selectedAll && !value.isEmpty()) return true;
        return selectionStart != cursor;
    }

    private String selectedText() {
        if (selectedAll) return value;
        int s = Math.min(selectionStart, cursor);
        int e = Math.max(selectionStart, cursor);
        return value.substring(s, e);
    }

    private void deleteSelected() {
        if (selectedAll) {
            value = "";
            cursor = 0;
            selectionStart = 0;
            selectedAll = false;
            return;
        }
        int s = Math.min(selectionStart, cursor);
        int e = Math.max(selectionStart, cursor);
        value = value.substring(0, s) + value.substring(e);
        cursor = s;
        selectionStart = s;
    }

    private void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String cleaned = text.replace("\r", "").replace("\n", " ");
        if (hasSelection()) deleteSelected();
        value = value.substring(0, cursor) + cleaned + value.substring(cursor);
        cursor += cleaned.length();
        selectionStart = cursor;
        syncSetting();
    }

    private String getClipboard() {
        Minecraft client = Minecraft.getInstance();
        String clipboard = GLFW.glfwGetClipboardString(client.getWindow().handle());
        return clipboard == null ? "" : clipboard;
    }

    private void setClipboard(String text) {
        Minecraft client = Minecraft.getInstance();
        GLFW.glfwSetClipboardString(client.getWindow().handle(), text);
    }

    private void syncSetting() {
        setting.setValue(value);
        value = setting.getValue();
        cursor = Math.min(cursor, value.length());
        selectionStart = Math.min(selectionStart, value.length());
        invalidateCursorWidth();
    }

    private float cursorWidth(float textSize) {
        int safeCursor = Math.min(cursor, value.length());
        if (cachedCursorValue != value || cachedCursor != safeCursor) {
            cachedCursorValue = value;
            cachedCursor = safeCursor;
            cachedCursorWidth = textWidth(value.substring(0, safeCursor), textSize);
        }
        return cachedCursorWidth;
    }

    private float selStartWidth(int s, float textSize) {
        return textWidth(value.substring(0, s), textSize);
    }

    private float selWidth(int s, int e, float textSize) {
        return textWidth(value.substring(s, e), textSize);
    }

    private void invalidateCursorWidth() {
        cachedCursorValue = null;
        cachedCursor = -1;
        cachedCursorWidth = 0.0f;
        cachedSelValue = null;
        cachedSelStart = -1;
        cachedSelEnd = -1;
        cachedSelStartWidth = 0.0f;
        cachedSelEndWidth = 0.0f;
    }
}
