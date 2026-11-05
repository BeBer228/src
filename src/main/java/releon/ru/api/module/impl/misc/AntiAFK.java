package releon.ru.api.module.impl.misc;

import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class AntiAFK extends Module {

    private static final String[] ACTIONS = {"Rotate", "Jump", "Sneak", "Walk", "Swing", "Chat"};
    // walk legs as {forward, strafe}: square closes the loop, so the player returns to the start
    private static final float[][] SQUARE = {{1, 0}, {0, -1}, {-1, 0}, {0, 1}};

    private final MultiModeSetting actions = register(new MultiModeSetting("Actions", "Anti-AFK actions to pick from randomly", ACTIONS, ACTIONS));
    private final ModeSetting walkMode = register(new ModeSetting("Walk Mode", "Square walks a small square, Random walks out and back", "Square", "Square", "Random"));
    private final NumberSetting idleTime = register(new NumberSetting("Idle Time (s)", "Seconds without your input before anti-AFK starts", 15.0, 1.0, 120.0, 1.0));
    private final NumberSetting minInterval = register(new NumberSetting("Min Interval (s)", "Minimum seconds between anti-AFK actions", 4.0, 1.0, 60.0, 1.0));
    private final NumberSetting maxInterval = register(new NumberSetting("Max Interval (s)", "Maximum seconds between anti-AFK actions", 12.0, 1.0, 120.0, 1.0));
    private final BooleanSetting pauseInGui = register(new BooleanSetting("Pause In GUI", "Do nothing while any screen is open", true));
    private final StringSetting message = register(new StringSetting("Message", "Chat message sent by the Chat action", "/afk", 256));
    private final NumberSetting chatDelay = register(new NumberSetting("Chat Delay (s)", "Minimum seconds between chat messages", 120.0, 30.0, 600.0, 10.0));

    private int idleTicks;
    private int waitTicks;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
    private long lastChatMs;
    private String lastAction;

    private String action;
    private int actionTicks;
    private final List<float[]> walkLegs = new ArrayList<>();
    private int legTicks;
    private float yawStep, pitchStep;

    public AntiAFK() {
        super("AntiAFK", "Prevents server kick for inactivity by performing subtle periodic actions.", ModuleCategory.MISC);
        walkMode.visibleWhen(() -> actions.isSelected("Walk"));
        message.visibleWhen(() -> actions.isSelected("Chat"));
        chatDelay.visibleWhen(() -> actions.isSelected("Chat"));
    }

    @Override
    public void onEnable() {
        idleTicks = 0;
        lastMouseX = Double.NaN;
        stopAction();
        rollWait();
    }

    @Override
    public void onDisable() {
        stopAction();
    }

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        if (hasUserInput() || (pauseInGui.getValue() && mc.screen != null)) {
            idleTicks = 0;
            stopAction();
            return;
        }
        if (++idleTicks < idleTime.getValue() * 20) return;

        if (action != null) {
            tickAction();
            return;
        }
        if (--waitTicks > 0) return;
        rollWait();
        startAction();
    }

    @SubscribeEvent
    public void onMoveInput(InputEvent event) {
        if (action == null) return;
        switch (action) {
            case "Jump" -> event.setJumping(true);
            case "Sneak" -> event.setDirectional(event.getInput().forward(), event.getInput().backward(), event.getInput().left(), event.getInput().right(), true, false, false);
            case "Walk" -> {
                if (walkLegs.isEmpty()) return;
                float[] leg = walkLegs.get(0);
                event.setDirectionalLow(leg[0] > 0, leg[0] < 0, leg[1] > 0, leg[1] < 0);
            }
            default -> {}
        }
    }

    private boolean hasUserInput() {
        double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
        boolean mouseMoved = !Double.isNaN(lastMouseX) && (mx != lastMouseX || my != lastMouseY);
        lastMouseX = mx;
        lastMouseY = my;
        if (mouseMoved) return true;
        for (KeyMapping key : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyAttack, mc.options.keyUse}) {
            if (key.isDown()) return true;
        }
        return false;
    }

    private void startAction() {
        List<String> enabled = new ArrayList<>();
        for (String a : ACTIONS) {
            if (!actions.isSelected(a)) continue;
            if (a.equals("Chat") && (message.getValue().isBlank() || System.currentTimeMillis() - lastChatMs < chatDelay.getValue() * 1000)) continue;
            enabled.add(a);
        }
        if (enabled.size() > 1) enabled.remove(lastAction);
        if (enabled.isEmpty()) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        action = enabled.get(r.nextInt(enabled.size()));
        lastAction = action;
        switch (action) {
            case "Rotate" -> {
                actionTicks = r.nextInt(6, 15);
                float yaw = (r.nextBoolean() ? 1 : -1) * r.nextFloat(20f, 70f);
                float pitch = Mth.clamp(mc.player.getXRot() + r.nextFloat(-12f, 12f), -60f, 60f) - mc.player.getXRot();
                yawStep = yaw / actionTicks;
                pitchStep = pitch / actionTicks;
            }
            case "Jump" -> actionTicks = mc.player.onGround() ? 1 : 0;
            case "Sneak" -> actionTicks = r.nextInt(4, 13);
            case "Walk" -> {
                walkLegs.clear();
                if (walkMode.is("Square")) {
                    int start = r.nextInt(4);
                    for (int i = 0; i < 4; i++) walkLegs.add(SQUARE[(start + i) % 4]);
                } else {
                    float[] dir = SQUARE[r.nextInt(4)];
                    walkLegs.add(dir);
                    walkLegs.add(new float[]{-dir[0], -dir[1]});
                }
                legTicks = r.nextInt(3, 7);
                actionTicks = legTicks * walkLegs.size();
            }
            case "Swing" -> {
                mc.player.swing(InteractionHand.MAIN_HAND);
                actionTicks = 0;
            }
            case "Chat" -> {
                if (mc.getConnection() != null) { String text = message.getValue(); if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1)); else mc.getConnection().sendChat(text); }
                lastChatMs = System.currentTimeMillis();
                actionTicks = 0;
            }
            default -> actionTicks = 0;
        }
        if (actionTicks <= 0) stopAction();
    }

    private void tickAction() {
        if (action.equals("Rotate")) {
            mc.player.setYRot(mc.player.getYRot() + yawStep);
            mc.player.setXRot(mc.player.getXRot() + pitchStep);
        } else if (action.equals("Walk") && !walkLegs.isEmpty() && (actionTicks - 1) % legTicks == 0) {
            walkLegs.remove(0);
        }
        if (--actionTicks <= 0) stopAction();
    }

    private void stopAction() {
        action = null;
        walkLegs.clear();
    }

    private void rollWait() {
        double min = minInterval.getValue(), max = Math.max(min, maxInterval.getValue());
        waitTicks = (int) (ThreadLocalRandom.current().nextDouble(min, max + 0.001) * 20);
    }
}
