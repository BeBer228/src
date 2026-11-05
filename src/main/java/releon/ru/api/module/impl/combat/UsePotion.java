package releon.ru.api.module.impl.combat;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.ClickSlotEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.usepotion.PotionChoice;
import releon.ru.api.module.impl.combat.usepotion.UsePotionScreen;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import java.util.*;

/** One user request = at most one useItem call. All inventory actions run on the client tick. */
public final class UsePotion extends Module {
    private static final long ACTION_DELAY_MS = 100L;
    private static final int WHEEL_SLOTS = 8;
    private final BindSetting menuBind = register(new BindSetting("Кнопка меню", "ЛКМ — использовать зелье. ПКМ — выбрать взрывное зелье из инвентаря.", KeyBind.NONE));
    // Technical persistence only: these are hidden, not user-facing options.
    private final StringSetting[] savedChoices = new StringSetting[WHEEL_SLOTS];
    private final String[] loadedValues = new String[WHEEL_SLOTS];
    private final PotionChoice[] choices = new PotionChoice[WHEEL_SLOTS];
    private Object choicesWorld;
    private int selectingSlot = -1;
    private Object selectingPlayer, selectingWorld;
    private boolean bindWasDown;
    private boolean disabled;
    private Operation operation;
    private String status = "Готов к использованию";

    public UsePotion() {
        super("UsePotion", "Использование выбранных взрывных зелий", ModuleCategory.COMBAT);
        for (int i = 0; i < WHEEL_SLOTS; i++) {
            StringSetting setting = new StringSetting("UsePotionItemV2_" + i, "", "", 65536);
            setting.visibleWhen(() -> false);
            savedChoices[i] = register(setting);
        }
    }

    private void loadChoices(Minecraft mc) {
        if (mc.level == null) return;
        if (choicesWorld != mc.level) { Arrays.fill(loadedValues, null); choicesWorld = mc.level; }
        for (int i = 0; i < WHEEL_SLOTS; i++) {
            String raw = savedChoices[i].getValue();
            if (raw == null) raw = "";
            if (raw.equals(loadedValues[i])) continue;
            loadedValues[i] = raw;
            choices[i] = null;
            if (raw.isBlank()) continue;
            try {
                var ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                ItemStack decoded = ItemStack.CODEC.parse(ops, JsonParser.parseString(raw)).result().orElse(ItemStack.EMPTY);
                if (!decoded.isEmpty() && decoded.is(Items.SPLASH_POTION)) choices[i] = new PotionChoice(decoded);
            } catch (RuntimeException ignored) {
                message(mc, "Не удалось загрузить зелье сектора " + (i + 1) + ". Выберите его заново через ПКМ.");
            }
        }
    }

    public ItemStack getWheelSlotStack(int index) {
        Minecraft mc = Minecraft.getInstance(); loadChoices(mc);
        if (index < 0 || index >= WHEEL_SLOTS || choices[index] == null) return ItemStack.EMPTY;
        // Use the real matching stack when present; otherwise keep the saved full-component icon.
        PotionChoice choice = choices[index];
        if (mc.player != null) {
            for (int pass = 0; pass < 2; pass++) for (int i = pass == 0 ? 0 : 9; i < (pass == 0 ? 9 : 36); i++) {
                ItemStack stack = inv(mc, i);
                if (choice.matches(stack)) return stack.copy();
            }
            for (int i = 0; i < 36; i++) {
                BundleContents b = contents(inv(mc, i));
                if (b != null) for (ItemStack stack : b.items()) if (choice.matches(stack)) return stack.copy();
            }
        }
        return choice.icon();
    }

    public void startSelectingItem(int index) {
        Minecraft mc = Minecraft.getInstance();
        if (disabled || operation != null || index < 0 || index >= WHEEL_SLOTS) return;
        if (!baseReady(mc) || !cursor(mc).isEmpty()) { message(mc, "Закройте контейнер и освободите курсор."); return; }
        selectingSlot = index; selectingPlayer = mc.player; selectingWorld = mc.level;
        mc.setScreen(new InventoryScreen(mc.player));
        message(mc, "Сектор " + (index + 1) + ": нажмите на нужное взрывное зелье. Esc — отмена.");
    }

    @SubscribeEvent
    private void onClickSlot(ClickSlotEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (selectingSlot < 0 || !(mc.screen instanceof InventoryScreen)) return;
        // Cancel the vanilla click before selecting, so the potion stays in its original slot.
        event.cancel();
        if (disabled || !baseReady(mc) || mc.player != selectingPlayer || mc.level != selectingWorld) { clearSelection(); return; }
        if (!cursor(mc).isEmpty()) { message(mc, "Сначала освободите курсор."); return; }
        int id = event.getSlotId();
        if (id < 9 || id > 44 || id >= mc.player.containerMenu.slots.size()) return;
        Slot slot = mc.player.containerMenu.getSlot(id);
        if (slot.container != mc.player.getInventory() || !slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        if (!stack.is(Items.SPLASH_POTION)) { message(mc, "Выберите именно взрывное зелье, а не питьевое/туманное или мешочек."); return; }
        try {
            PotionChoice choice = new PotionChoice(stack);
            var ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var encoded = ItemStack.CODEC.encodeStart(ops, choice.icon()).result();
            if (encoded.isEmpty()) { message(mc, "Не удалось сохранить компоненты зелья. Выбор не изменён."); return; }
            String json = encoded.get().toString();
            if (json.length() > 65536) { message(mc, "Слишком большой объём данных зелья. Выбор не изменён."); return; }
            int index = selectingSlot;
            savedChoices[index].setValue(json); loadedValues[index] = json; choices[index] = choice;
            clearSelection();
            mc.setScreen(null); // Same selection flow as AutoSwap; click did not move the item.
            message(mc, "Сектор " + (index + 1) + ": " + choice.title);
        } catch (RuntimeException ex) { message(mc, "Не удалось сохранить зелье. Выбор не изменён."); }
    }

    private void clearSelection() { selectingSlot = -1; selectingPlayer = null; selectingWorld = null; }
    public boolean busy() { return operation != null; }

    @Override public void onTick(Minecraft mc) {
        disabled = false;
        if (selectingSlot >= 0 && (!(mc.screen instanceof InventoryScreen)
                || mc.player != selectingPlayer || mc.level != selectingWorld)) clearSelection();
        if (operation != null) tickOperation(mc);
        if (mc.player == null || mc.level == null || mc.getWindow() == null) { bindWasDown = false; return; }
        boolean down = menuBind.getValue().isDown(mc.getWindow().handle());
        if (down && !bindWasDown && mc.screen == null && operation == null) mc.setScreen(new UsePotionScreen(this));
        bindWasDown = down;
    }

    @Override protected void onDisable() {
        disabled = true;
        if (operation != null) stop(Minecraft.getInstance(), "Модуль выключен. Проверьте инвентарь.");
        bindWasDown = false;
        clearSelection();
        Arrays.fill(loadedValues, null); // Re-read hidden choices after a configuration reload.
    }

    public void requestUse(int index) {
        Minecraft mc = Minecraft.getInstance();
        if (disabled) { message(mc, "Сначала включите UsePotion."); return; }
        if (operation != null) { message(mc, "Предыдущее использование ещё выполняется."); return; }
        if (!baseReady(mc) || mc.screen != null || mc.player.isUsingItem() || !mc.player.containerMenu.getCarried().isEmpty()) {
            message(mc, "Закройте контейнер, освободите курсор и закончите использование предмета."); return;
        }
        loadChoices(mc);
        if (index < 0 || index >= WHEEL_SLOTS || choices[index] == null) { message(mc, "Сектор не настроен. ПКМ по нему — выбрать зелье из инвентаря."); return; }
        PotionChoice p = choices[index];
        Operation o = new Operation(mc, p);
        // Prefer hotbar, then main inventory. Never replace a matching potion with a different type.
        for (int pass = 0; pass < 2 && o.source < 0; pass++) {
            for (int i = pass == 0 ? 0 : 9; i < (pass == 0 ? 9 : 36); i++) {
                ItemStack stack = inv(mc, i);
                if (p.matches(stack) && !mc.player.getCooldowns().isOnCooldown(stack)) {
                    o.source = i; o.potion = stack.copy(); break;
                }
            }
        }
        if (o.source < 0) {
            search: for (int i = 0; i < 36; i++) {
                BundleContents b = contents(inv(mc, i));
                if (b == null) continue;
                int j = 0;
                for (ItemStack inner : b.items()) {
                    if (p.matches(inner) && !mc.player.getCooldowns().isOnCooldown(inner)) {
                        int free = emptySlot(mc, i);
                        if (free < 0) { message(mc, "Для извлечения из мешочка нужен один свободный слот."); return; }
                        o.bundle = i; o.bundleIndex = j; o.source = free;
                        o.bundleBefore = inv(mc, i).copy(); o.potion = inner.copy();
                        o.phase = Phase.BUNDLE_SELECT; break search;
                    }
                    j++;
                }
            }
        }
        if (o.source < 0) { message(mc, "Подходящего доступного зелья нет (или действует кулдаун)."); return; }
        operation = o;
        status = "Подготовка: " + p.title;
    }

    private enum Phase {
        BUNDLE_SELECT, BUNDLE_OPEN, BUNDLE_CURSOR, BUNDLE_PARKED,
        PREPARE, SWAP_WAIT, SELECT, USE, CONSUME, RESTORE, RESTORE_WAIT,
        RETURN_PICK, RETURN_CURSOR, RETURN_INSERTED, RETURN_PARKED, DONE
    }
    private static final class Operation {
        final Object player, level, menu;
        final PotionChoice profile;
        final int selected;
        Phase phase = Phase.PREPARE;
        long due, phaseSince = System.currentTimeMillis(), started = phaseSince;
        int source = -1, work = -1, bundle = -1, bundleIndex = -1;
        ItemStack potion = ItemStack.EMPTY, workBefore = ItemStack.EMPTY;
        ItemStack bundleBefore = ItemStack.EMPTY, bundleAfter = ItemStack.EMPTY, remainder = ItemStack.EMPTY;
        boolean swapped, used, consumed;
        Operation(Minecraft mc, PotionChoice p) {
            player = mc.player; level = mc.level; menu = mc.player.inventoryMenu; profile = p;
            selected = mc.player.getInventory().getSelectedSlot();
        }
    }

    private void tickOperation(Minecraft mc) {
        Operation o = operation;
        long now = System.currentTimeMillis();
        if (!sameContext(mc, o) || mc.screen != null) { stop(mc, "Контекст изменился: операция остановлена, проверьте инвентарь."); return; }
        if (now < o.due) return;
        if (now - o.started > 25000 || now - o.phaseSince > 3500) { stop(mc, "Нет ожидаемого изменения инвентаря. Повторного броска не будет."); return; }
        try {
            switch (o.phase) {
                case BUNDLE_SELECT -> {
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.bundle), o.bundleBefore) || !inv(mc, o.source).isEmpty()) {
                        stop(mc, "Инвентарь изменился до извлечения."); return;
                    }
                    ItemStack bundle = inv(mc, o.bundle);
                    if (BundleItem.getSelectedItem(bundle) != o.bundleIndex) {
                        BundleItem.toggleSelectedItem(bundle, o.bundleIndex);
                        if (BundleItem.getSelectedItem(bundle) != o.bundleIndex) {
                            stop(mc, "Этот элемент мешочка нельзя выбрать. Переложите зелье вручную."); return;
                        }
                        mc.getConnection().send(new ServerboundSelectBundleItemPacket(menuSlot(o.bundle), o.bundleIndex));
                    }
                    o.bundleBefore = bundle.copy();
                    next(o, Phase.BUNDLE_OPEN);
                }
                case BUNDLE_OPEN -> {
                    if (!equal(inv(mc, o.bundle), o.bundleBefore) || !cursor(mc).isEmpty() || !inv(mc, o.source).isEmpty()) {
                        stop(mc, "Выбор мешочка изменился."); return;
                    }
                    click(mc, o.bundle, 1, ClickType.PICKUP);
                    next(o, Phase.BUNDLE_CURSOR);
                }
                case BUNDLE_CURSOR -> {
                    if (cursor(mc).isEmpty()) return;
                    if (!equal(cursor(mc), o.potion) || !inv(mc, o.source).isEmpty() || contents(inv(mc, o.bundle)) == null) {
                        stop(mc, "Мешочек вернул неожиданный предмет. Бросок отменён."); return;
                    }
                    o.bundleAfter = inv(mc, o.bundle).copy();
                    click(mc, o.source, 0, ClickType.PICKUP);
                    next(o, Phase.BUNDLE_PARKED);
                }
                case BUNDLE_PARKED -> {
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.source), o.potion)) return;
                    next(o, Phase.PREPARE);
                }
                case PREPARE -> {
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.source), o.potion)) { stop(mc, "Исходный слот изменился."); return; }
                    if (o.source < 9) { o.work = o.source; next(o, Phase.SELECT); return; }
                    o.work = o.selected;
                    // An empty hotbar slot avoids moving the held weapon when possible.
                    for (int i = 0; i < 9; i++) if (i != o.bundle && inv(mc, i).isEmpty()) { o.work = i; break; }
                    if (o.work == o.bundle) {
                        for (int i = 0; i < 9; i++) if (i != o.bundle) { o.work = i; break; }
                    }
                    o.workBefore = inv(mc, o.work).copy();
                    click(mc, o.source, o.work, ClickType.SWAP);
                    o.swapped = true;
                    next(o, Phase.SWAP_WAIT);
                }
                case SWAP_WAIT -> {
                    if (equal(inv(mc, o.source), o.workBefore) && equal(inv(mc, o.work), o.potion) && cursor(mc).isEmpty()) next(o, Phase.SELECT);
                }
                case SELECT -> {
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.work), o.potion)) { stop(mc, "Зелье в руке изменилось."); return; }
                    InventoryUtils.syncSelectedHotbarSlot(o.work);
                    next(o, Phase.USE);
                }
                case USE -> {
                    if (mc.player.getInventory().getSelectedSlot() != o.work || !equal(inv(mc, o.work), o.potion)
                            || !cursor(mc).isEmpty() || mc.player.isUsingItem() || mc.player.getCooldowns().isOnCooldown(inv(mc, o.work))) {
                        stop(mc, "Использование сейчас недоступно."); return;
                    }
                    // Intentionally no auto-aim: harmful potions are thrown along the player's current aim.
                    o.used = true;
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    status = "Проверка расхода: " + o.profile.title;
                    next(o, Phase.CONSUME);
                }
                case CONSUME -> {
                    ItemStack current = inv(mc, o.work);
                    o.consumed = consumedOne(o.potion, current);
                    if (o.consumed || now - o.phaseSince >= 2000) {
                        if (!o.consumed && !equal(current, o.potion)) { stop(mc, "Неожиданное изменение используемого слота."); return; }
                        o.remainder = current.copy();
                        next(o, Phase.RESTORE);
                    }
                }
                case RESTORE -> {
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.work), o.remainder)) { stop(mc, "Слот изменён до возврата."); return; }
                    if (o.swapped) {
                        if (!equal(inv(mc, o.source), o.workBefore)) { stop(mc, "Обратный обмен отменён: исходный слот изменён."); return; }
                        click(mc, o.source, o.work, ClickType.SWAP);
                        next(o, Phase.RESTORE_WAIT);
                    } else {
                        InventoryUtils.syncSelectedHotbarSlot(o.selected);
                        next(o, Phase.RETURN_PICK);
                    }
                }
                case RESTORE_WAIT -> {
                    if (equal(inv(mc, o.work), o.workBefore) && equal(inv(mc, o.source), o.remainder) && cursor(mc).isEmpty()) {
                        o.swapped = false;
                        InventoryUtils.syncSelectedHotbarSlot(o.selected);
                        next(o, Phase.RETURN_PICK);
                    }
                }
                case RETURN_PICK -> {
                    if (o.bundle < 0 || o.remainder.isEmpty()) { next(o, Phase.DONE); return; }
                    if (!cursor(mc).isEmpty() || !equal(inv(mc, o.source), o.remainder) || !equal(inv(mc, o.bundle), o.bundleAfter)) {
                        stop(mc, "Остаток оставлен в инвентаре: мешочек или слот изменились."); return;
                    }
                    click(mc, o.source, 0, ClickType.PICKUP);
                    next(o, Phase.RETURN_CURSOR);
                }
                case RETURN_CURSOR -> {
                    if (cursor(mc).isEmpty()) return;
                    if (!equal(cursor(mc), o.remainder) || !inv(mc, o.source).isEmpty() || !equal(inv(mc, o.bundle), o.bundleAfter)) {
                        stop(mc, "Возврат в мешочек отменён из-за изменения предметов."); return;
                    }
                    click(mc, o.bundle, 1, ClickType.PICKUP);
                    next(o, Phase.RETURN_INSERTED);
                }
                case RETURN_INSERTED -> {
                    if (cursor(mc).isEmpty()) { next(o, Phase.DONE); return; }
                    if (sameItem(cursor(mc), o.remainder) && cursor(mc).getCount() <= o.remainder.getCount() && inv(mc, o.source).isEmpty()) {
                        click(mc, o.source, 0, ClickType.PICKUP);
                        next(o, Phase.RETURN_PARKED);
                    } else stop(mc, "Мешочек не принял остаток; проверьте курсор.");
                }
                case RETURN_PARKED -> {
                    if (cursor(mc).isEmpty()) {
                        message(mc, "Мешочек не принял весь остаток: он оставлен в инвентаре."); next(o, Phase.DONE);
                    }
                }
                case DONE -> {
                    InventoryUtils.syncSelectedHotbarSlot(o.selected);
                    status = o.consumed ? "Использовано: " + o.profile.title : "Расход не подтверждён — без повтора";
                    message(mc, status);
                    operation = null;
                }
            }
        } catch (RuntimeException ex) {
            stop(mc, "Ошибка операции: " + ex.getClass().getSimpleName() + ". Проверьте инвентарь.");
        }
    }

    private void next(Operation o, Phase phase) {
        o.phase = phase; o.phaseSince = System.currentTimeMillis();
        o.due = o.phaseSince + ACTION_DELAY_MS;
    }

    /** Best effort only in the original menu. Never discard or clear a carried stack. */
    private void stop(Minecraft mc, String reason) {
        Operation o = operation;
        if (o != null && sameContext(mc, o)) {
            try {
                // Park only the stack this operation owns; never move unrelated cursor contents.
                if (ownsCursor(o) && !cursor(mc).isEmpty() && sameItem(cursor(mc), o.potion) && cursor(mc).getCount() <= o.potion.getCount()) {
                    int free = inv(mc, o.source).isEmpty() ? o.source : emptySlot(mc, o.bundle);
                    if (free >= 0) click(mc, free, 0, ClickType.PICKUP);
                }
                if (cursor(mc).isEmpty() && o.swapped && o.work >= 0
                        && equal(inv(mc, o.source), o.workBefore)
                        && (equal(inv(mc, o.work), o.potion) || consumedOne(o.potion, inv(mc, o.work)))) {
                    click(mc, o.source, o.work, ClickType.SWAP);
                }
                InventoryUtils.syncSelectedHotbarSlot(o.selected);
            } catch (RuntimeException ignored) { /* Leave items visible for manual recovery. */ }
        }
        operation = null; status = reason; message(mc, reason);
    }

    private static boolean ownsCursor(Operation o) {
        return o.phase == Phase.BUNDLE_CURSOR || o.phase == Phase.RETURN_CURSOR
                || o.phase == Phase.RETURN_INSERTED || o.phase == Phase.RETURN_PARKED;
    }
    private static boolean baseReady(Minecraft mc) {
        return mc.player != null && mc.player.isAlive() && mc.level != null && mc.gameMode != null && mc.getConnection() != null
                && mc.player.containerMenu == mc.player.inventoryMenu;
    }
    private static boolean sameContext(Minecraft mc, Operation o) {
        return baseReady(mc) && mc.player == o.player && mc.level == o.level && mc.player.containerMenu == o.menu;
    }
    private static ItemStack inv(Minecraft mc, int index) { return mc.player.getInventory().getItem(index); }
    private static ItemStack cursor(Minecraft mc) { return mc.player.containerMenu.getCarried(); }
    private static int menuSlot(int index) { return index < 9 ? 36 + index : index; }
    private static void click(Minecraft mc, int inventoryIndex, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, menuSlot(inventoryIndex), button, type, mc.player);
    }
    private static int emptySlot(Minecraft mc, int excluded) {
        for (int i = 9; i < 36; i++) if (i != excluded && inv(mc, i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) if (i != excluded && inv(mc, i).isEmpty()) return i;
        return -1;
    }
    private static BundleContents contents(ItemStack stack) {
        return stack.getItem() instanceof BundleItem ? stack.get(DataComponents.BUNDLE_CONTENTS) : null;
    }
    private static boolean sameItem(ItemStack a, ItemStack b) { return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b); }
    private static boolean equal(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : sameItem(a, b) && a.getCount() == b.getCount();
    }
    private static boolean consumedOne(ItemStack before, ItemStack after) {
        return before.getCount() == 1 ? after.isEmpty() : sameItem(before, after) && after.getCount() == before.getCount() - 1;
    }
    private void message(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§b[UsePotion] §f" + text), false);
    }
}
