package releon.ru.api.module.impl.visual;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.types.EventPriority;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.utils.repository.gps.GpsUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class Arrows extends Module {
    private static final String ARROW_TEXTURE = "releon:textures/particles/ghost-triangle.png";
    private static final float GPS_LIFT = 14.0f;

    private final NumberSetting arrowsDistance = register(new NumberSetting("Дистанция", "Дистанция стрелок от прицела.", 20.0, 1.0, 20.0, 0.5));
    private final NumberSetting arrowSize = register(new NumberSetting("Размер", "Размер стрелки.", 15, 10.0, 25.0, 0.5));
    private final ColorSetting arrowColor = register(new ColorSetting("Цвет", "Цвет обычной стрелки.", new Color(255, 255, 255, 255)));
    private final ColorSetting friendColor = register(new ColorSetting("Цвет друга", "Цвет стрелки на друга.", new Color(0, 255, 0, 255)));
    private final BooleanSetting friendDistance = register(new BooleanSetting("Дистанция друга", "Показывать расстояние над стрелкой друга.", true));
    private final ColorSetting gpsColor = register(new ColorSetting("Цвет GPS", "Цвет GPS-стрелки.", new Color(0, 0, 0, 255)));
    private final BooleanSetting gpsDistanceSetting = register(new BooleanSetting("Дистанция GPS", "Показывать расстояние над GPS-стрелкой.", true));

    private final SmoothAnimation distanceAnimation = new SmoothAnimation();
    private final SmoothAnimation yawAnimation = new SmoothAnimation();
    private final SmoothAnimation pitchAnimation = new SmoothAnimation();
    private final SmoothAnimation cameraYawAnimation = new SmoothAnimation();
    private final SmoothAnimation gpsFade = new SmoothAnimation();
    private final List<Arrow> arrows = new ArrayList<>();

    private double gpsTargetX;
    private double gpsTargetZ;

    public Arrows() {
        super("Arrows", "Рисует стрелки на игроков за пределами экрана.", ModuleCategory.VISUAL);
    }

    @Override
    protected void onEnable() {
        arrows.clear();
        gpsFade.set(0.0);
    }

    @Override
    protected void onDisable() {
        arrows.clear();
        gpsFade.set(0.0);
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    private void onDraw(DrawEvent event) {
        if (mc.player == null || mc.level == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            return;
        }

        try {
            distanceAnimation.update();
            yawAnimation.update();
            pitchAnimation.update();
            cameraYawAnimation.update();

            float size = 45.0f + arrowsDistance.getFloat();
            if (mc.screen instanceof InventoryScreen) {
                size += 80.0f;
            }
            if (mc.player.isShiftKeyDown()) {
                size -= 20.0f;
            }
            if (isMoving()) {
                size += 10.0f;
            }

            float strafeInput = mc.player.input.getMoveVector().x;
            float forwardInput = mc.player.input.getMoveVector().y;
            yawAnimation.run(strafeInput * 5.0f, 0.75, Easings.EXPO_OUT, true);
            pitchAnimation.run(forwardInput * 5.0f, 0.75, Easings.EXPO_OUT, true);
            cameraYawAnimation.run(mc.gameRenderer.getMainCamera().yRot(), 0.75, Easings.EXPO_OUT, true);
            distanceAnimation.run(size, 1.0, Easings.EXPO_OUT, true);

            for (Arrow arrow : arrows) {
                arrow.active = false;
            }

            if (mc.level.players() != null) {
                for (AbstractClientPlayer player : mc.level.players()) {
                    if (!isValidPlayer(player)) {
                        continue;
                    }
                    Arrow arrow = findArrow(player);
                    if (arrow == null) {
                        arrow = new Arrow(player);
                        arrows.add(arrow);
                    }
                    arrow.active = true;
                    arrow.fade.run(1.0, 0.20, Easings.CUBIC_OUT, true);
                }
            }

            for (int i = arrows.size() - 1; i >= 0; i--) {
                Arrow arrow = arrows.get(i);
                if (!arrow.active) {
                    arrow.fade.run(0.0, 0.20, Easings.CUBIC_OUT, true);
                    if (arrow.fade.get() <= 0.01f && !arrow.fade.isAlive()) {
                        arrows.remove(i);
                    }
                }
            }

            GpsUtils.GpsTarget gpsTarget = GpsUtils.get();
            boolean gpsActive = gpsTarget != null;
            if (gpsActive) {
                gpsTargetX = gpsTarget.x();
                gpsTargetZ = gpsTarget.z();
            }
            gpsFade.update();
            gpsFade.run(gpsActive ? 1.0 : 0.0, 0.20, Easings.CUBIC_OUT, true);

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int screenHeight = mc.getWindow().getGuiScaledHeight();
            float centerX = screenWidth / 2.0f;
            float centerY = screenHeight / 2.0f;
            double cameraYaw = cameraYawAnimation.getValue();
            double cos = Mth.cos((float) (cameraYaw * (Math.PI * 2.0 / 360.0)));
            double sin = Mth.sin((float) (cameraYaw * (Math.PI * 2.0 / 360.0)));
            double cameraX = mc.gameRenderer.getMainCamera().position().x;
            double cameraZ = mc.gameRenderer.getMainCamera().position().z;

            for (Arrow arrow : arrows) {
                arrow.fade.update();
                float alpha = arrow.fade.get();
                if (alpha <= 0.01f) {
                    continue;
                }

                Player player = arrow.player;
                if (player == null || player.isRemoved() || !player.isAlive()) {
                    continue;
                }

                double playerX = Mth.lerp(event.getPartialTicks(), player.xOld, player.getX()) - cameraX;
                double playerZ = Mth.lerp(event.getPartialTicks(), player.zOld, player.getZ()) - cameraZ;
                double rotY = -(playerZ * cos - playerX * sin);
                double rotX = -(playerX * cos + playerZ * sin);
                float angle = (float) (Math.atan2(rotY, rotX) * 180.0 / Math.PI);
                float x = (float) (distanceAnimation.getValue() * alpha * Mth.cos((float) Math.toRadians(angle)) + centerX + yawAnimation.getValue());
                float y = (float) (distanceAnimation.getValue() * alpha * Mth.sin((float) Math.toRadians(angle)) + centerY + pitchAnimation.getValue());
                boolean isFriend = FriendUtils.isFriend(player);
                int baseColor = isFriend ? friendColor.getValue().getRGB() : arrowColor.getValue().getRGB();
                int color = ColorUtil.multAlpha(baseColor, alpha);
                drawArrow(x, y, angle, color);

                if (isFriend && friendDistance.getValue()) {
                    double distance = Math.sqrt(playerX * playerX + playerZ * playerZ);
                    float halfSize = arrowSize.getFloat() * 0.5f;
                    drawDistanceLabel(x, y - halfSize - 8.0f, Math.round(distance) + "m", color);
                }
            }

            float gpsAlpha = gpsFade.get();
            if (gpsAlpha > 0.01f) {
                double gpsX = gpsTargetX - cameraX;
                double gpsZ = gpsTargetZ - cameraZ;
                double rotY = -(gpsZ * cos - gpsX * sin);
                double rotX = -(gpsX * cos + gpsZ * sin);
                float angle = (float) (Math.atan2(rotY, rotX) * 180.0 / Math.PI);
                float x = (float) (distanceAnimation.getValue() * gpsAlpha * Mth.cos((float) Math.toRadians(angle)) + centerX + yawAnimation.getValue());
                float y = (float) (distanceAnimation.getValue() * gpsAlpha * Mth.sin((float) Math.toRadians(angle)) + centerY + pitchAnimation.getValue()) - GPS_LIFT * gpsAlpha;
                int color = ColorUtil.multAlpha(gpsColor.getValue().getRGB(), gpsAlpha);
                drawArrow(x, y, angle, color);

                if (gpsDistanceSetting.getValue()) {
                    double distance = Math.sqrt(gpsX * gpsX + gpsZ * gpsZ);
                    float halfSize = arrowSize.getFloat() * 0.5f;
                    drawDistanceLabel(x, y - halfSize - 8.0f, Math.round(distance) + "m", color);
                }
            }
        } catch (Exception ignored) {}
    }

    private void drawArrow(float x, float y, float angle, int color) {
        try {
            float size = arrowSize.getFloat();
            float halfSize = size * 0.5f;
            Render2D.image(ARROW_TEXTURE, x - halfSize, y - halfSize, size, 0.0f, angle + 90.0f, x, y, color);
        } catch (Exception ignored) {}
    }

    private void drawDistanceLabel(float x, float y, String text, int color) {
        try {
            float size = 6.0f;
            float width = Render2D.textWidth(FontType.SEMIBOLD, text, size);
            Render2D.text(FontType.SEMIBOLD, text, x - width * 0.5f, y, size, color);
        } catch (Exception ignored) {}
    }

    private Arrow findArrow(Player player) {
        for (Arrow arrow : arrows) {
            if (arrow.player == player) {
                return arrow;
            }
        }
        return null;
    }

    private boolean isValidPlayer(Player player) {
        return player != null && player != mc.player && !player.isRemoved() && player.isAlive();
    }

    private boolean isMoving() {
        return mc.player.input.getMoveVector().y != 0.0f || mc.player.input.getMoveVector().x != 0.0f;
    }

    private static final class Arrow {
        private final Player player;
        private final SmoothAnimation fade = new SmoothAnimation();
        private boolean active;

        private Arrow(Player player) {
            this.player = player;
            this.fade.set(0.0);
        }
    }
}