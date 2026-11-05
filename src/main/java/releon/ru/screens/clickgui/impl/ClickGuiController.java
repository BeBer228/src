package releon.ru.screens.clickgui.impl;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.screens.clickgui.ClickGui;
import releon.ru.screens.clickgui.impl.panel.ClickGuiPanel;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;


public final class ClickGuiController {
    /** Множитель размера ClickGUI поверх авто-масштаба (1.75 = в 1.75 раза крупнее). */
    private static final float UI_SCALE = 1.75f;
    private static final SmoothAnimation OPEN_ANIMATION = new SmoothAnimation();
    private static final ClickGuiPanel WINDOW = new ClickGuiPanel();

    private static boolean closing;
    private static boolean overlayActive;
    private static boolean textWarmed;
    private static boolean pendingOpenAnimation;
    private static boolean sprintStateCaptured;
    private static boolean sprintKeyWasDown;
    private static boolean playerWasSprinting;
    private static double mouseX;
    private static double mouseY;

    private ClickGuiController() {
    }

    public static void init() {
        closing = false;
        overlayActive = true;
        pendingOpenAnimation = true;
        OPEN_ANIMATION.set(0.0);
        ClickGuiThemes.load();
        WINDOW.onOpen();
        captureSprintState();
        stopGuiMovement(Minecraft.getInstance());
    }

    public static void renderPanels(GuiGraphics graphics, int screenWidth, int screenHeight) {
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(UI_SCALE, UI_SCALE);
            renderPanelsScaled(graphics, screenWidth, screenHeight);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void renderPanelsScaled(GuiGraphics graphics, int screenWidth, int screenHeight) {
        updateMousePosition();
        float coordinateScale = coordinateScale();
        int designScreenWidth = Math.max(1, Math.round(screenWidth / coordinateScale));
        int designScreenHeight = Math.max(1, Math.round(screenHeight / coordinateScale));

        if (pendingOpenAnimation) {
            pendingOpenAnimation = false;
            OPEN_ANIMATION.set(0.0);
            OPEN_ANIMATION.run(1.0, 0.36, Easings.CUBIC_OUT);
        }
        OPEN_ANIMATION.update();
        float openProgress = clamp(OPEN_ANIMATION.get(), 0.0f, 1.0f);

        if (closing && !OPEN_ANIMATION.isAlive() && openProgress <= 0.01f) {
            closing = false;
            overlayActive = false;
            return;
        }

        if (openProgress <= 0.01f && !closing) {
            return;
        }

        updatePixelSize(coordinateScale);
        ClickGuiThemes.update();
        Render2D.beginFrame(graphics);
        renderBackgroundDim(designScreenWidth, designScreenHeight, openProgress);
        graphics.nextStratum();
        WINDOW.render(graphics, designScreenWidth, designScreenHeight, openProgress);
        Render2D.flush();
    }

    public static void warmupText() {
        if (textWarmed) {
            return;
        }

        textWarmed = true;
        WINDOW.warmupText();
    }

    public static boolean keyPressed(KeyEvent event) {
        if (closing) {
            return false;
        }
        boolean capturing = WINDOW.capturesKeyboard();
        if (WINDOW.keyPressed(event)) {
            return true;
        }
        if (!capturing && (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            startClosing();
            return true;
        }
        return false;
    }

    public static boolean charTyped(CharacterEvent event) {
        return !closing && WINDOW.charTyped(event);
    }

    public static boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        event = toDesignEvent(event);
        mouseX = event.x();
        mouseY = event.y();
        if (closing) {
            return false;
        }
        WINDOW.mouseClicked(event, doubled);
        return true;
    }

    public static boolean mouseReleased(MouseButtonEvent event) {
        event = toDesignEvent(event);
        mouseX = event.x();
        mouseY = event.y();
        return WINDOW.mouseReleased(event);
    }

    public static boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        event = toDesignEvent(event);
        dragX /= coordinateScale();
        dragY /= coordinateScale();
        mouseX = event.x();
        mouseY = event.y();
        return WINDOW.mouseDragged(event, dragX, dragY);
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) {
            return false;
        }
        mouseX /= coordinateScale();
        mouseY /= coordinateScale();
        ClickGuiController.mouseX = mouseX;
        ClickGuiController.mouseY = mouseY;
        return WINDOW.mouseScrolled(mouseX, mouseY, scrollY);
    }

    /** Открыть модуль в меню (категория + прокрутка + раскрытие настроек). */
    public static void reveal(Module module) {
        WINDOW.reveal(module);
    }

    public static double mouseX() {
        return mouseX;
    }

    public static double mouseY() {
        return mouseY;
    }

    public static void startClosing() {
        if (closing) {
            return;
        }
        closing = true;
        overlayActive = true;
        WINDOW.onClose();
        OPEN_ANIMATION.run(0.0, 0.22, Easings.CUBIC_OUT);

        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof ClickGui) {
            client.setScreen(null);
        }
        restoreSprintState();
    }

    /** Экран ClickGui убрали не через закрытие меню (смерть, смена мира, кик) — гасим мгновенно. */
    public static void onScreenRemoved() {
        if (closing) {
            return; // обычное закрытие: даём доиграть анимацию
        }
        WINDOW.onClose();
        resetOverlayState();
    }

    public static void resetOverlayState() {
        restoreSprintState();
        closing = false;
        overlayActive = false;
        pendingOpenAnimation = false;
        OPEN_ANIMATION.set(0.0);
    }

    public static boolean shouldRenderOverlay() {
        Minecraft client = Minecraft.getInstance();
        boolean guiOpen = client.screen instanceof ClickGui;
        boolean noWorld = client.level == null || client.player == null;
        // Другой экран, нет мира или меню закрыто без анимации закрытия — оверлей рисовать нельзя.
        if ((client.screen != null && !guiOpen) || (noWorld && !guiOpen) || (!guiOpen && !closing)) {
            if (overlayActive || closing) {
                resetOverlayState();
            }
            return false;
        }
        return overlayActive || guiOpen;
    }

    public static void tickMovementKeys() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof ClickGui)) {
            restoreSprintState();
            if (overlayActive && !closing) {
                resetOverlayState();
            }
            return;
        }

        // Умерли или вылетели из мира с открытым меню — закрываем его сразу.
        if (client.level == null || client.player == null || client.player.isDeadOrDying()) {
            WINDOW.onClose();
            resetOverlayState();
            client.setScreen(null);
            return;
        }

        if (client.player == null || client.getWindow() == null) {
            return;
        }

        long handle = client.getWindow().handle();
        if (WINDOW.capturesKeyboard()) {
            stopGuiMovement(client);
            return;
        }

        boolean forward = updateKey(client.options.keyUp, handle);
        boolean left = updateKey(client.options.keyLeft, handle);
        boolean back = updateKey(client.options.keyDown, handle);
        boolean right = updateKey(client.options.keyRight, handle);
        updateKey(client.options.keyJump, handle);
        updateKey(client.options.keyShift, handle);
        setGuiSprint(client, forward || left || back || right);
    }

    private static boolean updateKey(KeyMapping keyMapping, long windowHandle) {
        InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
        if (key.getType() != InputConstants.Type.KEYSYM) {
            return false;
        }

        boolean down = GLFW.glfwGetKey(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
        KeyMapping.set(key, down);
        return down;
    }

    private static void captureSprintState() {
        if (sprintStateCaptured) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        sprintStateCaptured = true;
        sprintKeyWasDown = client.options != null && client.options.keySprint.isDown();
        playerWasSprinting = client.player != null && client.player.isSprinting();
    }

    private static void stopGuiMovement(Minecraft client) {
        releaseMovementKeys(client);
        setGuiSprint(client, false);
    }

    private static void setGuiSprint(Minecraft client, boolean sprint) {
        if (client.options != null) {
            client.options.keySprint.setDown(sprint);
        }

        if (client.player != null) {
            client.player.setSprinting(sprint);
        }
    }

    private static void restoreSprintState() {
        Minecraft client = Minecraft.getInstance();
        if (!sprintStateCaptured) {
            return;
        }

        if (client.options != null) {
            client.options.keySprint.setDown(sprintKeyWasDown);
        }

        if (client.player != null) {
            client.player.setSprinting(playerWasSprinting);
        }

        sprintStateCaptured = false;
    }

    private static void releaseMovementKeys(Minecraft client) {
        releaseKey(client.options.keyUp);
        releaseKey(client.options.keyLeft);
        releaseKey(client.options.keyDown);
        releaseKey(client.options.keyRight);
        releaseKey(client.options.keyJump);
        releaseKey(client.options.keyShift);
        releaseKey(client.options.keySprint);
    }

    private static void releaseKey(KeyMapping keyMapping) {
        InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
        if (key.getType() == InputConstants.Type.KEYSYM) {
            KeyMapping.set(key, false);
        }
    }

    /** Один полупрозрачный прямоугольник вместо виньеток и свечения на весь экран. */
    private static void renderBackgroundDim(int screenWidth, int screenHeight, float alpha) {
        float fade = alpha * alpha * (3.0f - 2.0f * alpha);
        Render2D.rect(0.0f, 0.0f, screenWidth, screenHeight, 0.0f, ColorUtil.rgba(8, 8, 11, Math.round(150.0f * fade)));
    }

    private static void updatePixelSize(float coordinateScale) {
        Minecraft client = Minecraft.getInstance();
        float guiScale = 2.0f;
        if (client != null && client.getWindow() != null) {
            guiScale = (float) client.getWindow().getGuiScale();
        }
        ClickGuiTheme.setPixelSize(1.0f / Math.max(0.0001f, coordinateScale * Math.max(1.0f, guiScale)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static MouseButtonEvent toDesignEvent(MouseButtonEvent event) {
        float scale = coordinateScale();
        if (Math.abs(scale - 1.0f) <= 0.0001f) {
            return event;
        }
        MouseButtonInfo info = new MouseButtonInfo(event.button(), event.modifiers());
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, info);
    }

    private static float coordinateScale() {
        return Render2DCoordinateSpace.guiIndependentScale() * UI_SCALE;
    }

    private static void updateMousePosition() {
        Minecraft client = Minecraft.getInstance();
        if (client.mouseHandler == null || client.getWindow() == null) {
            return;
        }

        float scale = coordinateScale();
        mouseX = client.mouseHandler.getScaledXPos(client.getWindow()) / scale;
        mouseY = client.mouseHandler.getScaledYPos(client.getWindow()) / scale;
    }
}
