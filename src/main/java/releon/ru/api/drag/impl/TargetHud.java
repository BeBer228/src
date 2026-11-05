package releon.ru.api.drag.impl;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.visual.ESP;
import releon.ru.api.module.impl.visual.esp.EspHealthTracker;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

public final class TargetHud extends HudPanel {
    private LivingEntity lastTarget;
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation healthAnimation = new SmoothAnimation();
    private final SmoothAnimation barAnimation = new SmoothAnimation();
    private final SmoothAnimation absorptionAnimation = new SmoothAnimation();
    private final SmoothAnimation absorptionTextAnimation = new SmoothAnimation();
    private final SmoothAnimation healthIconXAnimation = new SmoothAnimation();
    private final EspHealthTracker localHealthTracker = new EspHealthTracker();
    private int animatedTargetId = Integer.MIN_VALUE;
    private int animatedAbsorptionTargetId = Integer.MIN_VALUE;
    private boolean healthIconXReady;
    private float trailProgress = 1.0F;
    private long trailHoldUntil;
    private long trailLastMs = System.currentTimeMillis();
    private int trailTargetId = Integer.MIN_VALUE;

    // ---------------------------------------------------------- hit fx
    private static final int MAX_PARTICLES = 28;
    private static final int MAX_POPUPS = 5;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final float[] pX = new float[MAX_PARTICLES];
    private final float[] pY = new float[MAX_PARTICLES];
    private final float[] pVX = new float[MAX_PARTICLES];
    private final float[] pVY = new float[MAX_PARTICLES];
    private final float[] pSize = new float[MAX_PARTICLES];
    private final long[] pBorn = new long[MAX_PARTICLES];
    private final int[] pColor = new int[MAX_PARTICLES];
    private int pHead;
    private final String[] popText = new String[MAX_POPUPS];
    private final long[] popBorn = new long[MAX_POPUPS];
    private final float[] popDrift = new float[MAX_POPUPS];
    private final boolean[] popHeal = new boolean[MAX_POPUPS];
    private int popHead;
    private int fxTargetId = Integer.MIN_VALUE;
    private float fxLastHealth = -1.0F;
    private int fxLastHurt;
    private long hitMs;
    private long fxLastNanos;
    private final java.util.Random random = new java.util.Random();

    private static final float WIDTH = 146.0F;
    private static final float HEIGHT = 40.0F;
    private static final float HEAD = 30.0F;

    public TargetHud() {
        super("targethud", "TargetHud", 140.0F, 130.0F, WIDTH, HEIGHT);
    }

    @Override
    protected void renderHud() {
        TargetHudState state = logics();
        if (state == null) {
            return;
        }
        renderTargetHud(state);
    }

    private TargetHudState logics() {
        LivingEntity target = target();
        boolean preview = target == null && editPreview() && mc.player != null;
        boolean visible = target != null || preview;

        panelAnimation.update();
        panelAnimation.run(visible ? 1.0 : 0.0, 0.24F, visible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        contentVisible(visible || alpha > 0.01F || panelAnimation.isAlive());
        if (alpha <= 0.0F && !panelAnimation.isAlive()) {
            return null;
        }
        if (preview) {
            target = mc.player;
        } else if (target != null) {
            lastTarget = target;
        } else if (lastTarget != null && lastTarget.isAlive()) {
            target = lastTarget;
        } else if (mc.player != null) {
            target = mc.player;
        }
        if (target == null) {
            return null;
        }

        size(WIDTH, HEIGHT);
        return new TargetHudState(target, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderTargetHud(TargetHudState state) {
        float alpha = state.alpha;
        long nowMs = System.currentTimeMillis();
        float hitShake = 1.0F - HudStyle.clamp01((nowMs - hitMs) / 320.0F);
        float shakeX = hitShake > 0.0F ? (float) Math.sin(nowMs * 0.11D) * 1.8F * hitShake : 0.0F;
        float shakeY = hitShake > 0.0F ? (float) Math.cos(nowMs * 0.13D) * 0.9F * hitShake : 0.0F;
        float x = state.x + shakeX;
        float y = state.y + shakeY;
        float w = state.width;

        String name = state.target.getName().getString();
        float targetHealth = displayHealth(state.target);
        float targetAbsorption = Math.max(0.0F, state.target.getAbsorptionAmount());
        float maxHealth = Math.max(1.0F, state.target.getMaxHealth());
        maxHealth = Math.max(maxHealth, targetHealth);
        float targetProgress = clamp(targetHealth / maxHealth, 0.0F, 1.0F);
        HealthState healthState = animatedHealth(state.target, targetHealth, targetProgress);
        AbsorptionState absorptionState = animatedAbsorption(state.target, targetAbsorption, maxHealth);
        float healthProgress = healthState.progress;
        float trail = updateTrail(state.target, healthProgress);
        float hurt = clamp(state.target.hurtTime / 10.0F, 0.0F, 1.0F);
        detectHit(state.target, targetHealth, x + 5.0F + HEAD * 0.5F, y + 5.0F + HEAD * 0.5F, x + w - 6.0F, y + 6.0F, healthProgress);

        int healthColor = HudStyle.health(healthProgress, 255.0F, alpha);
        int healthLight = HudStyle.mix(healthColor, HudStyle.white(255.0F, alpha), 0.35F);

        // ---------------------------------------------------------- card
        HudStyle.glass(x, y, w, state.height, alpha);
        if (hurt > 0.01F) {
            Render2D.outline(x, y, w, state.height, HudStyle.RADIUS, 0.8F, HudStyle.bad(160.0F * hurt, alpha));
        }

        // ---------------------------------------------------------- head
        float squish = 1.0F - 0.1F * hitShake;
        float head = HEAD * squish;
        float headX = x + 5.0F + (HEAD - head) * 0.5F;
        float headY = y + 5.0F + (HEAD - head) * 0.5F;
        HudStyle.glow(headX - 8.0F, headY - 8.0F, head + 16.0F, head + 16.0F, HudStyle.withAlpha(healthColor, 60.0F, alpha));
        Render2D.rect(headX - 0.8F, headY - 0.8F, head + 1.6F, head + 1.6F, 5.5F, HudStyle.withAlpha(healthColor, 150.0F, alpha));
        renderHead(state.target, headX, headY, head, alpha);
        if (hurt > 0.01F) {
            Render2D.rect(headX, headY, head, head, 5.0F, HudStyle.bad(120.0F * hurt, alpha));
        }

        // ---------------------------------------------------------- hp (top-right)
        float contentX = x + 5.0F + HEAD + 6.0F;
        float right = x + w - 6.0F;
        String hp = formatWholeHealth(targetHealth);
        float cell = 3.8F;
        float hpSize = 6.5F;
        float iconSize = 7.0F;
        float targetIconLocalX = (right - HudStyle.stableWidth(hp, cell) - iconSize - 1.5F) - x;
        healthIconXAnimation.update();
        if (!healthIconXReady || Math.abs(healthIconXAnimation.get() - targetIconLocalX) > 18.0F) {
            healthIconXAnimation.set(targetIconLocalX);
            healthIconXReady = true;
        } else {
            healthIconXAnimation.run(targetIconLocalX, 0.24F, Easings.EXPO_OUT, true);
        }
        float iconX = x + healthIconXAnimation.get();
        float topY = y + 6.0F;
        HudStyle.glow(iconX - 6.0F, topY - 5.0F, 19.0F, 17.0F, HudStyle.withAlpha(healthColor, 90.0F, alpha));
        Render2D.text(FontType.MAINMENUSCREEN, "e", iconX, topY + 0.5F, iconSize, healthColor);
        HudStyle.stableTextRight(hp, right, topY + 0.8F, hpSize, cell, HudStyle.text(alpha));

        // ---------------------------------------------------------- name (clipped + fade)
        float nameSize = 7.0F;
        float nameRight = iconX - 3.0F;
        float clip = Math.max(0.0F, nameRight - contentX);
        if (clip > 0.5F) {
            float fadeWidth = Math.min(8.0F, Math.max(2.0F, clip * 0.35F));
            boolean overflow = Render2D.textWidth(TITLE_FONT, name, nameSize) > clip + 0.5F;
            ScissorUtil.push(
                    Render2DCoordinateSpace.toGuiInt(contentX),
                    Render2DCoordinateSpace.toGuiInt(topY - 4.0F),
                    Render2DCoordinateSpace.toGuiInt(nameRight),
                    Render2DCoordinateSpace.toGuiInt(topY + nameSize + 5.0F)
            );
            if (overflow) {
                Render2D.textFade(TITLE_FONT, name, contentX, topY, nameSize, HudStyle.text(alpha), contentX, nameRight, fadeWidth, 0.0F, 0.88F);
            } else {
                Render2D.text(TITLE_FONT, name, contentX, topY, nameSize, HudStyle.text(alpha));
            }
            ScissorUtil.pop();
        }

        // ---------------------------------------------------------- sub-line
        float subY = y + 16.5F;
        float cursor = contentX;
        String distance = distanceText(state.target);
        cursor += HudStyle.pill(cursor, subY - 1.5F, 8.0F, distance, HudStyle.NUM, HudStyle.TINY_SIZE,
                HudStyle.white(16.0F, alpha), HudStyle.muted(alpha)) + 2.5F;
        if (absorptionState.textAlpha > 0.01F) {
            float aAlpha = alpha * absorptionState.textAlpha;
            String abs = "+" + formatWholeHealth(absorptionState.textAmount);
            HudStyle.pill(cursor, subY - 1.5F + absorptionState.textYOffset, 8.0F, abs, HudStyle.NUM, HudStyle.TINY_SIZE,
                    HudStyle.gold(40.0F, aAlpha), HudStyle.gold(255.0F, aAlpha));
        }

        // ---------------------------------------------------------- equipment
        renderEquipment(state.target, right, subY - 2.0F, alpha);

        // ---------------------------------------------------------- health bar
        float barX = contentX;
        float barW = right - contentX;
        float barH = 4.5F;
        float barY = y + state.height - barH - 6.5F;
        Render2D.rect(barX, barY, barW, barH, barH * 0.5F, HudStyle.white(16.0F, alpha));
        if (trail > healthProgress + 0.002F) {
            Render2D.rect(barX, barY, Math.max(barH, barW * trail), barH, barH * 0.5F, HudStyle.white(90.0F, alpha));
        }
        HudStyle.progress(barX, barY, barW, barH, healthProgress, healthColor, healthLight, alpha);
        if (absorptionState.barAlpha > 0.01F && absorptionState.progress > 0.001F) {
            float aAlpha = alpha * absorptionState.barAlpha;
            Render2D.rect(barX, barY - 2.5F, Math.max(1.5F, barW * absorptionState.progress), 1.5F, 0.75F,
                    HudStyle.gold(255.0F, aAlpha), HudStyle.rgba(255, 150, 40, 255.0F, aAlpha),
                    HudStyle.rgba(255, 150, 40, 255.0F, aAlpha), HudStyle.gold(255.0F, aAlpha));
        }

        // ---------------------------------------------------------- hit flash + fx
        if (hitShake > 0.0F) {
            Render2D.rect(x, y, w, state.height, HudStyle.RADIUS, HudStyle.bad(34.0F * hitShake * hitShake, alpha));
        }
        renderParticles(alpha);
        renderPopups(x + w - 6.0F, y + 2.0F, alpha);
    }

    /** Броня + предмет в руке справа на второй строке. */
    private void renderEquipment(LivingEntity target, float right, float y, float alpha) {
        float size = 9.0F;
        float gap = 1.0F;
        float cursor = right - size;
        ItemStack main = target.getMainHandItem();
        if (main != null && !main.isEmpty()) {
            Render2D.rect(cursor - 0.5F, y - 0.5F, size + 1.0F, size + 1.0F, 2.5F, HudStyle.accent(40.0F, alpha));
            RenderItem.item(main, cursor, y, size, RenderItemOptions.noDecorations(alpha));
            cursor -= size + gap + 2.0F;
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = target.getItemBySlot(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            RenderItem.item(stack, cursor, y, size, RenderItemOptions.noDecorations(alpha));
            cursor -= size + gap;
        }
    }

    /** Ловим получение урона/лечение: частицы, всплывающие цифры, тряска. */
    private void detectHit(LivingEntity target, float health, float headCx, float headCy, float popX, float popY, float progress) {
        long now = System.currentTimeMillis();
        if (target.getId() != fxTargetId) {
            fxTargetId = target.getId();
            fxLastHealth = health;
            fxLastHurt = target.hurtTime;
            return;
        }
        float delta = health - fxLastHealth;
        boolean newHurt = target.hurtTime > fxLastHurt && target.hurtTime >= 8;
        fxLastHurt = target.hurtTime;
        if (delta <= -0.25F || (newHurt && delta < 0.0F)) {
            hitMs = now;
            spawnPopup("-" + formatDamage(-delta), false);
            int color = HudStyle.health(progress, 255.0F, 1.0F);
            for (int i = 0; i < 10; i++) {
                spawnParticle(headCx, headCy, i % 3 == 0 ? HudStyle.accent(255.0F, 1.0F) : color);
            }
        } else if (newHurt) {
            hitMs = now;
            for (int i = 0; i < 6; i++) {
                spawnParticle(headCx, headCy, HudStyle.accent(255.0F, 1.0F));
            }
        } else if (delta >= 0.5F) {
            spawnPopup("+" + formatDamage(delta), true);
        }
        fxLastHealth = health;
    }

    private void spawnParticle(float x, float y, int color) {
        int i = pHead;
        pHead = (pHead + 1) % MAX_PARTICLES;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        float speed = 30.0F + random.nextFloat() * 55.0F;
        pX[i] = x;
        pY[i] = y;
        pVX[i] = (float) Math.cos(angle) * speed;
        pVY[i] = (float) Math.sin(angle) * speed - 25.0F;
        pSize[i] = 1.4F + random.nextFloat() * 1.8F;
        pColor[i] = color;
        pBorn[i] = System.currentTimeMillis();
    }

    private void renderParticles(float alpha) {
        long nanos = System.nanoTime();
        float dt = fxLastNanos == 0L ? 0.016F : Math.min(0.05F, (nanos - fxLastNanos) / 1.0e9F);
        fxLastNanos = nanos;
        long now = System.currentTimeMillis();
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pBorn[i] == 0L) {
                continue;
            }
            float t = (now - pBorn[i]) / 650.0F;
            if (t >= 1.0F) {
                pBorn[i] = 0L;
                continue;
            }
            pVY[i] += 140.0F * dt;
            pVX[i] *= (float) Math.pow(0.04D, dt);
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            float a = (1.0F - t) * alpha;
            float sz = pSize[i] * (1.0F - t * 0.5F);
            HudStyle.glow(pX[i] - sz * 2.5F, pY[i] - sz * 2.5F, sz * 5.0F, sz * 5.0F, HudStyle.withAlpha(pColor[i], 70.0F, a));
            Render2D.rect(pX[i] - sz * 0.5F, pY[i] - sz * 0.5F, sz, sz, sz * 0.5F, HudStyle.withAlpha(pColor[i], 255.0F, a));
        }
    }

    private void spawnPopup(String text, boolean heal) {
        int i = popHead;
        popHead = (popHead + 1) % MAX_POPUPS;
        popText[i] = text;
        popBorn[i] = System.currentTimeMillis();
        popDrift[i] = (random.nextFloat() - 0.5F) * 10.0F;
        popHeal[i] = heal;
    }

    /** Всплывающие цифры урона над правым верхним углом. */
    private void renderPopups(float right, float y, float alpha) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < MAX_POPUPS; i++) {
            if (popText[i] == null) {
                continue;
            }
            float t = (now - popBorn[i]) / 950.0F;
            if (t >= 1.0F) {
                popText[i] = null;
                continue;
            }
            float pop = HudStyle.easeOutBack(Math.min(1.0F, t * 4.0F));
            float size = 5.5F + 2.0F * pop * (1.0F - t * 0.4F);
            float rise = HudStyle.easeOut(t) * 14.0F;
            float a = (t < 0.7F ? 1.0F : 1.0F - (t - 0.7F) / 0.3F) * alpha;
            int color = popHeal[i] ? HudStyle.good(255.0F, a) : HudStyle.bad(255.0F, a);
            float tw = Render2D.textWidth(HudStyle.NUM, popText[i], size);
            float px = right - tw - 22.0F + popDrift[i];
            float py = y - rise - size;
            HudStyle.glow(px - 6.0F, py - 5.0F, tw + 12.0F, size + 10.0F, HudStyle.withAlpha(color, 60.0F, a));
            Render2D.text(HudStyle.NUM, popText[i], px + 0.5F, py + 0.5F, size, HudStyle.black(160.0F, a));
            Render2D.text(HudStyle.NUM, popText[i], px, py, size, color);
        }
    }

    private static String formatDamage(float value) {
        int tenths = Math.max(1, Math.round(value * 10.0F));
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : tenths / 10 + "." + tenths % 10;
    }

    private void renderHead(LivingEntity target, float x, float y, float size, float alpha) {
        if (target instanceof AbstractClientPlayer player) {
            String texture = player.getSkin().body().texturePath().toString();
            int color = HudStyle.white(255.0F, alpha);
            if ((color >>> 24) > 3) {
                boolean base = renderSkinPart(texture, x, y, size, 8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F, color);
                boolean overlay = renderSkinPart(texture, x, y, size, 40.0F / 64.0F, 8.0F / 64.0F, 48.0F / 64.0F, 16.0F / 64.0F, color);
                if (!base && !overlay) {
                    Render2D.image(texture, x, y, size, 5.0F, color);
                }
            }
            return;
        }
        String targetName = target.getName().getString();
        String letter = !targetName.isEmpty() ? targetName.substring(0, 1).toUpperCase() : "?";
        Render2D.gradientRect(x, y, size, size, 5.0F, 5.0F, 5.0F, 5.0F,
                HudStyle.accent(200.0F, alpha), HudStyle.accent2(200.0F, alpha),
                HudStyle.accent2(200.0F, alpha), HudStyle.accent(200.0F, alpha));
        float letterSize = 12.0F;
        HudStyle.textCentered(TITLE_FONT, letter, x + size * 0.5F, y + (size - letterSize) * 0.5F + 0.5F, letterSize, HudStyle.white(255.0F, alpha));
    }

    /** «Призрачная» полоса урона: задерживается и плавно догоняет текущее HP. */
    private float updateTrail(LivingEntity target, float progress) {
        long now = System.currentTimeMillis();
        float delta = Math.min(0.1F, Math.max(0.0F, (now - trailLastMs) / 1000.0F));
        trailLastMs = now;
        if (target.getId() != trailTargetId || progress >= trailProgress) {
            trailTargetId = target.getId();
            trailProgress = progress;
            trailHoldUntil = now + 280L;
            return trailProgress;
        }
        if (now >= trailHoldUntil) {
            trailProgress = smooth(trailProgress, progress, delta, 3.5F);
            if (Math.abs(trailProgress - progress) < 0.002F) {
                trailProgress = progress;
            }
        }
        return trailProgress;
    }

    private String distanceText(LivingEntity target) {
        if (mc.player == null || target == mc.player) {
            return "you";
        }
        int tenths = Math.max(0, Math.round(mc.player.distanceTo(target) * 10.0F));
        return tenths / 10 + "." + tenths % 10 + "m";
    }

    private HealthState animatedHealth(LivingEntity target, float health, float progress) {
        boolean newTarget = target.getId() != animatedTargetId;
        boolean fullHealthCorrection = progress >= 0.995F && barAnimation.get() < 0.95F;
        if (newTarget || fullHealthCorrection) {
            animatedTargetId = target.getId();
            healthAnimation.set(health);
            barAnimation.set(progress);
            return new HealthState(health, progress);
        }

        healthAnimation.update();
        barAnimation.update();
        healthAnimation.run(health, 0.34F, Easings.EXPO_OUT, true);
        barAnimation.run(progress, 0.34F, Easings.EXPO_OUT, true);
        return new HealthState(Math.max(0.0F, healthAnimation.get()), clamp(barAnimation.get(), 0.0F, 1.0F));
    }

    private AbsorptionState animatedAbsorption(LivingEntity target, float absorption, float maxHealth) {
        boolean newTarget = target.getId() != animatedAbsorptionTargetId;
        boolean hasAbsorption = absorption > 0.05F;
        if (newTarget) {
            animatedAbsorptionTargetId = target.getId();
            absorptionAnimation.set(absorption);
            absorptionTextAnimation.set(hasAbsorption ? 1.0F : 0.0F);
        } else {
            absorptionAnimation.update();
            absorptionTextAnimation.update();
            absorptionAnimation.run(absorption, hasAbsorption ? 0.28F : 0.34F, Easings.EXPO_OUT, true);
            absorptionTextAnimation.run(hasAbsorption ? 1.0F : 0.0F, hasAbsorption ? 0.20F : 0.32F, hasAbsorption ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        }

        float animatedAbsorption = Math.max(0.0F, absorptionAnimation.get());
        float textAlpha = clamp(absorptionTextAnimation.get(), 0.0F, 1.0F);
        float textAmount = hasAbsorption ? animatedAbsorption : 0.0F;
        float textYOffset = hasAbsorption ? 0.0F : -(1.0F - textAlpha) * 6.0F;
        float progress = clamp(animatedAbsorption / Math.max(1.0F, maxHealth), 0.0F, 1.0F);
        float barAlpha = Math.max(textAlpha, progress > 0.001F ? 1.0F : 0.0F);
        return new AbsorptionState(textAmount, progress, textAlpha, textYOffset, barAlpha);
    }

    private boolean renderSkinPart(String texture, float x, float y, float size, float u0, float v0, float u1, float v1, int color) {
        if (texture == null || texture.isBlank() || color >>> 24 == 0) {
            return false;
        }
        Render2D.imageUvNearest(texture, x, y, size, size, 5.0F, 1.0F, u0, v0, u1, v1, color);
        return true;
    }

    private float displayHealth(LivingEntity entity) {
        Float espHealth = resolveEspHealth(entity, false);
        if (espHealth != null) {
            return Math.max(0.0F, espHealth);
        }
        return localHealthTracker.resolveDisplayHealth(entity, false);
    }

    private String formatWholeHealth(float health) {
        if (!Float.isFinite(health)) {
            return "0";
        }
        return Integer.toString(Math.max(0, Math.round(health)));
    }

    private Float resolveEspHealth(LivingEntity entity, boolean includeAbsorption) {
        return ESP.resolveHudHealth(entity, includeAbsorption);
    }

    private LivingEntity target() {
        if (AuraModule.target != null && AuraModule.target.isAlive()) {
            return AuraModule.target;
        }
        Entity crosshair = mc.crosshairPickEntity;
        if (crosshair instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        return null;
    }

    private record TargetHudState(LivingEntity target, float alpha, float x, float y, float width, float height) {
    }

    private record HealthState(float health, float progress) {
    }

    private record AbsorptionState(float textAmount, float progress, float textAlpha, float textYOffset, float barAlpha) {
    }
}
