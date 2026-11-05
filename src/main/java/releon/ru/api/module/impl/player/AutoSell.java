package releon.ru.api.module.impl.player;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoSell extends Module {
    private final NumberSetting discount = register(new NumberSetting(
            "Скидка %", "Процент снижения минимальной найденной цены за штуку.", 30, 0, 99, 1));
    private final BooleanSetting fixedPriceMode = register(new BooleanSetting(
            "Фиксированная цена", "Продавать без поиска цены. Весь стак за указанную сумму.", false));
    private final NumberSetting fixedPrice = register(new NumberSetting(
            "Цена стака", "Цена целого стака в фиксированном режиме.", 100, 1, 1_000_000_000, 1));
    private final NumberSetting minPrice = register(new NumberSetting(
            "Минимальная цена", "Не выставлять стак дешевле этой суммы.", 1, 1, 1_000_000, 1));
    private final BooleanSetting fromChests = register(new BooleanSetting(
            "Брать из сундуков", "Самостоятельно подходить к сундукам в радиусе от точки запуска.", true));
    private final NumberSetting chestRadius = register(new NumberSetting(
            "Радиус сундуков", "Поиск в загруженной области от места включения; высота +/-4 блока.", 12, 3, 24, 1));
    private final NumberSetting actionDelay = register(new NumberSetting(
            "Задержка клика", "Минимальный интервал одиночных инвентарных действий, мс.", 350, 250, 1500, 50));
    private final NumberSetting commandDelay = register(new NumberSetting(
            "Задержка команд", "Минимальный интервал команд аукциона, мс.", 1800, 1000, 10000, 100));

    private static final Pattern MAGIC_TOKEN = Pattern.compile("(?i)[&§]k(?:[&§][0-9a-f])*[^\\s&§]+");
    private static final Pattern COLORS = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}");
    private static final Pattern NUMBER = Pattern.compile(
            "(?iu)([0-9]+(?:[., _\\u00a0\\u202f][0-9]+)*)(?:\\s*(kk|кк|k|к|m|м|млн|тыс)(?![\\p{L}]))?");
    private static final Pattern PRICE_LINE = Pattern.compile(
            "(?iu)(?:цена|стоимость|price|cost|итого|всего|total)" +
            "(?:\\s+(?:за\\s*(?:1\\s*)?(?:шт(?:уку|ука|\\.)?|предмет)|per\\s*item|each))?" +
            "\\s*[:=\\-–]?\\s*[$₽]?\\s*([0-9][0-9., _\\u00a0\\u202f]*(?:\\s*(?:kk|кк|k|к|m|м|млн|тыс)(?![\\p{L}]))?)");

    private enum State { NEXT, SWAP, SEARCH, WAIT_SEARCH, CLOSE_SEARCH, SELL, WAIT_SALE,
        PARK, FIND_CHEST, WALK, WAIT_CHEST, LOOT, FINISHED, HALTED }
    private State state = State.NEXT;
    private ItemStack current = ItemStack.EMPTY;
    private final List<ItemStack> skipped = new ArrayList<>();
    private final List<ReferencePrice> marketPrices = new ArrayList<>();
    private String query = "";
    private long salePrice;
    private int sourceSlot = -1, previousHotbar;
    private Object runPlayer, runLevel;
    private BlockPos origin, chest;
    private final Set<BlockPos> visited = new HashSet<>();
    private final ArrayDeque<BlockPos> route = new ArrayDeque<>();
    private long stateAt, nextActionAt, nextCommandAt, searchAt, lastProgressAt;
    private long saleUpdateBefore, observedUpdatesBefore;
    private volatile long inventoryUpdates, containerUpdateAt;
    private volatile String serverFailure;
    private volatile boolean watchingSale;
    private int ownMenu = -1, openPreviousMenu = -1;
    private int searchPreviousMenu = -1;
    private boolean ownInventoryScreen, ownedMovement;
    private double progressDistance = Double.MAX_VALUE;
    private int sold, skippedCount;
    private final ArrayDeque<Long> clickTimes = new ArrayDeque<>();

    private int pendingMenu = -1, pendingSlot = -1;
    private long pendingAt, pendingUpdate;
    private ItemStack pendingBefore = ItemStack.EMPTY, pendingCursor = ItemStack.EMPTY;

    public AutoSell() {
        super("AutoSell", "Продаёт инвентарь и содержимое ближайших сундуков через /ah.", ModuleCategory.PLAYER);
        fixedPrice.visibleWhen(fixedPriceMode::getValue);
        discount.visibleWhen(() -> !fixedPriceMode.getValue());
        chestRadius.visibleWhen(fromChests::getValue);
    }

    @Override
    protected void onEnable() {
        reset();
        if (mc.player != null && mc.level != null) {
            runPlayer = mc.player; runLevel = mc.level;
            origin = mc.player.blockPosition().immutable();
            previousHotbar = mc.player.getInventory().getSelectedSlot();
        }
        say("Запущен: продаются все 36 слотов основного инвентаря, кроме пропущенных. Броня и вторая рука не затрагиваются.");
    }

    @Override
    protected void onDisable() {
        stopMoving();
        if (mc.player != null && runPlayer == mc.player) selectSlot(previousHotbar);
        if (mc.player != null && runPlayer == mc.player && pendingSlot < 0
                && ownedScreen() && mc.player.containerMenu.getCarried().isEmpty()) closeOwned();
        reset();
    }

    private void reset() {
        state = State.NEXT; current = ItemStack.EMPTY; sourceSlot = -1;
        runPlayer = runLevel = null; origin = chest = null;
        skipped.clear(); marketPrices.clear(); visited.clear(); route.clear(); clickTimes.clear();
        ownMenu = openPreviousMenu = searchPreviousMenu = -1;
        pendingMenu = pendingSlot = -1; pendingBefore = pendingCursor = ItemStack.EMPTY;
        stateAt = System.currentTimeMillis(); nextActionAt = nextCommandAt = searchAt = 0;
        inventoryUpdates = containerUpdateAt = 0; serverFailure = null; watchingSale = false;
        ownInventoryScreen = ownedMovement = closeForWalk = false; sold = skippedCount = 0;
    }

    @Override
    public void onTick(Minecraft client) {
        if (state == State.HALTED || state == State.FINISHED) { stopMoving(); return; }
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.player.connection == null) {
            halt("Соединение/игрок недоступны. После подключения включи модуль заново."); return;
        }
        if (runPlayer == null) {
            runPlayer = mc.player; runLevel = mc.level;
            origin = mc.player.blockPosition().immutable();
            previousHotbar = mc.player.getInventory().getSelectedSlot();
        }
        if (runPlayer != mc.player || runLevel != mc.level || !mc.player.isAlive()) {
            halt("Смена мира, персонажа или смерть: выполнение остановлено."); return;
        }
        if (mc.player.isUsingItem()) { stopMoving(); return; }
        if (pendingSlot >= 0 && waitClick()) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            halt("На курсоре появился предмет. Автоматизация приостановлена без выбрасывания."); return;
        }
        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;
        switch (state) {
            case NEXT -> chooseNext();
            case SWAP -> finishSwap();
            case SEARCH -> startSearch();
            case WAIT_SEARCH -> readSearch();
            case CLOSE_SEARCH -> {
                if (closeOwned()) { nextActionAt = now + 350; change(State.SELL); }
            }
            case SELL -> sell();
            case WAIT_SALE -> waitSale();
            case PARK -> parkSkipped();
            case FIND_CHEST -> findChest();
            case WALK -> walkToChest();
            case WAIT_CHEST -> acceptChest();
            case LOOT -> lootChest();
            default -> { }
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive()) return;
        if (event.getPacket() instanceof ClientboundContainerSetSlotPacket
                || event.getPacket() instanceof ClientboundContainerSetContentPacket) {
            inventoryUpdates++;
            containerUpdateAt = System.currentTimeMillis();
        }
        if (watchingSale && event.getPacket() instanceof ClientboundSystemChatPacket packet) {
            String text = plain(packet.content().getString()).toLowerCase(Locale.ROOT);
            if (text.contains("слишком часто") || text.contains("подождите") || text.contains("лимит")
                    || text.contains("нельзя выстав") || text.contains("невозможно выстав")
                    || text.contains("недостаточно") || text.contains("не можете")
                    || text.contains("too many") || text.contains("cannot") || text.contains("cooldown"))
                serverFailure = text;
        }
    }

    private void chooseNext() {
        stopMoving();
        if (!inventoryOnly()) return;
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) { halt("Освободи предмет на курсоре."); return; }
        int found = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !isSkipped(stack)) { found = i; break; }
        }
        if (found < 0) {
            closeInventory();
            if (fromChests.getValue()) change(State.FIND_CHEST);
            else finish();
            return;
        }
        sourceSlot = found;
        current = mc.player.getInventory().getItem(found).copy();
        query = sanitizeAuctionQuery(current.getHoverName().getString());
        if (query.isBlank() || query.length() > 80) { skip("неподходящее имя для поиска"); return; }
        openInventory();
        if (found != 0) {
            if (!click(mc.player.inventoryMenu.containerId, inventoryMenuSlot(found), 0, ClickType.SWAP)) return;
        }
        change(State.SWAP);
    }

    private void finishSwap() {
        if (!sameStack(mc.player.getInventory().getItem(0), current)) {
            halt("Первый слот не совпадает с выбранным предметом. Продажа отменена."); return;
        }
        selectSlot(0);
        closeInventory();
        nextActionAt = System.currentTimeMillis() + 350;
        if (fixedPriceMode.getValue()) {
            salePrice = Math.max(1L, Math.round(fixedPrice.getValue()));
            change(State.SELL);
        } else change(State.SEARCH);
    }

    private void startSearch() {
        if (!inventoryOnly()) return;
        closeInventory();
        if (mc.screen != null) return;
        if (!handMatches()) { halt("Предмет в руке изменился до поиска."); return; }
        for (ReferencePrice cached : marketPrices) if (sameProduct(cached.product(), current)) {
            if (preparePrice(cached.quote())) change(State.SELL);
            return;
        }
        if (!canCommand()) return;
        searchPreviousMenu = mc.player.containerMenu.containerId;
        ownMenu = -1;
        searchAt = System.currentTimeMillis();
        observedUpdatesBefore = inventoryUpdates;
        command("ah search " + query);
        change(State.WAIT_SEARCH);
    }

    private void readSearch() {
        long now = System.currentTimeMillis();
        if (mc.screen instanceof ContainerScreen screen && isAuction(screen)
                && screen.getMenu().containerId != searchPreviousMenu) {
            ownMenu = screen.getMenu().containerId;
            if (now - searchAt < 1200 || now - containerUpdateAt < 450) {
                if (now - searchAt < 10000) return;
            }
            if (!handMatches()) { halt("Предмет изменился во время поиска."); return; }
            Quote lowest = null;
            for (Slot slot : screen.getMenu().slots) {
                ItemStack offer = slot.getItem();
                if (slot.container == mc.player.getInventory() || slot.y >= 168
                        || offer.isEmpty() || !sameProduct(offer, current) || isOwnOffer(offer)) continue;
                BigDecimal total = extractTotalPrice(offer);
                if (total == null) continue;
                Quote candidate = new Quote(total, Math.max(1, offer.getCount()));
                if (lowest == null || candidate.total().multiply(BigDecimal.valueOf(lowest.count()))
                        .compareTo(lowest.total().multiply(BigDecimal.valueOf(candidate.count()))) < 0) lowest = candidate;
            }
            if (lowest == null) { skip("нет совпадающих лотов с однозначной ценой"); return; }
            if (!preparePrice(lowest)) return;
            marketPrices.add(new ReferencePrice(current.copy(), lowest));
            change(State.CLOSE_SEARCH);
            return;
        }
        if (mc.screen != null && !(mc.screen instanceof ContainerScreen)) {
            halt("Поиск открыл неизвестное окно. Закрой его и перезапусти модуль."); return;
        }
        if (now - searchAt >= 10000) skip("аукцион не открыл выдачу за 10 секунд");
    }

    private boolean preparePrice(Quote lowest) {
        BigDecimal result = lowest.total().multiply(BigDecimal.valueOf(current.getCount()))
                .multiply(BigDecimal.valueOf(100L - Math.round(discount.getValue())))
                .divide(BigDecimal.valueOf(lowest.count()).multiply(BigDecimal.valueOf(100)), 0, RoundingMode.DOWN);
        if (result.signum() < 0 || result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            skip("цена вне допустимого диапазона"); return false;
        }
        salePrice = Math.max(Math.max(1L, Math.round(minPrice.getValue())), result.longValueExact());
        say("Лот «" + plain(current.getHoverName().getString()) + "» x" + current.getCount()
                + ": " + salePrice + " монет за стак.");
        return true;
    }

    private boolean isOwnOffer(ItemStack offer) {
        try {
            Item.TooltipContext context = Item.TooltipContext.of(mc.level);
            String playerName = mc.player.getName().getString().toLowerCase(Locale.ROOT);
            Pattern exactName = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(playerName) + "(?![a-z0-9_])");
            for (Component component : offer.getTooltipLines(context, mc.player, TooltipFlag.NORMAL)) {
                String line = plain(component.getString()).toLowerCase(Locale.ROOT);
                if ((line.contains("продавец") || line.contains("seller") || line.contains("владелец"))
                        && exactName.matcher(line).find()) return true;
            }
            return false;
        } catch (RuntimeException malformedTooltip) {
            return true;
        }
    }

    private void sell() {
        if (!inventoryOnly()) return;
        closeInventory();
        if (mc.screen != null || !canCommand()) return;
        if (!handMatches()) { halt("В руке уже другой предмет: продажа отменена."); return; }
        if (salePrice <= 0) { halt("Некорректная цена."); return; }
        serverFailure = null;
        saleUpdateBefore = inventoryUpdates;
        watchingSale = true;
        command("ah sell " + salePrice);
        change(State.WAIT_SALE);
    }

    private void waitSale() {
        if (serverFailure != null) {
            halt("Ответ сервера: " + serverFailure + ". Команда продажи не повторялась."); return;
        }
        if (mc.screen != null) {
            halt("После продажи открылось окно. Автоподтверждение и повтор продажи отключены."); return;
        }
        ItemStack after = mc.player.getInventory().getItem(0);
        boolean removed = after.isEmpty() || (ItemStack.isSameItemSameComponents(after, current)
                && after.getCount() < current.getCount());
        long elapsed = System.currentTimeMillis() - stateAt;
        if (removed && inventoryUpdates > saleUpdateBefore && elapsed >= 1000) {
            watchingSale = false; sold++;
            current = ItemStack.EMPTY; change(State.NEXT);
            nextActionAt = System.currentTimeMillis() + 350;
            return;
        }
        if (elapsed >= 12000) {
            halt("За 12 секунд не подтверждено удаление продаваемого стака. Проверь чат/лимит лотов. Повторной продажи не было.");
        }
    }

    private void skip(String reason) {
        if (!current.isEmpty() && !isSkipped(current)) { skipped.add(current.copy()); skippedCount++; }
        say("Пропуск «" + plain(current.getHoverName().getString()) + "»: " + reason + ".");
        change(State.PARK);
    }

    private void parkSkipped() {
        if (mc.screen instanceof ContainerScreen) {
            if (!ownedScreen()) { halt("Чужой контейнер при пропуске предмета."); return; }
            if (!closeOwned()) return;
            nextActionAt = System.currentTimeMillis() + 350; return;
        }
        if (!inventoryOnly()) return;
        ItemStack zero = mc.player.getInventory().getItem(0);
        if (!zero.isEmpty() && ItemStack.isSameItemSameComponents(zero, current)) {
            int empty = -1;
            if (sourceSlot >= 9 && mc.player.getInventory().getItem(sourceSlot).isEmpty()) empty = sourceSlot;
            for (int i = 35; empty < 0 && i >= 9; i--)
                if (mc.player.getInventory().getItem(i).isEmpty()) empty = i;
            if (empty >= 0) {
                openInventory();
                if (!click(mc.player.inventoryMenu.containerId, inventoryMenuSlot(empty), 0, ClickType.SWAP)) return;
            }
        }
        current = ItemStack.EMPTY;
        change(State.NEXT);
    }

    private void findChest() {
        if (!inventoryOnly()) return;
        closeInventory();
        if (freeSlots() == 0) { finishWithReason("Инвентарь занят пропущенными вещами: освободите место."); return; }
        int radius = (int) Math.round(chestRadius.getValue());
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            if (!mc.level.hasChunkAt(p) || visited.contains(p)) continue;
            BlockState block = mc.level.getBlockState(p);
            if (block.is(Blocks.CHEST) || block.is(Blocks.TRAPPED_CHEST)) candidates.add(p.immutable());
        }
        candidates.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p))));
        for (BlockPos candidate : candidates) {
            List<BlockPos> path = pathToChest(candidate, radius);
            if (path == null) { visited.add(candidate); continue; }
            chest = candidate;
            route.clear(); route.addAll(path);
            progressDistance = Double.MAX_VALUE; lastProgressAt = System.currentTimeMillis();
            change(State.WALK); return;
        }
        finish();
    }

    private void walkToChest() {
        if (mc.screen instanceof InventoryScreen && ownInventoryScreen && pendingSlot < 0) {
            if (mc.player.getInventory().getItem(0).isEmpty()) {
                closeInventory(); nextActionAt = System.currentTimeMillis() + 350; return;
            }
            for (int i = 9; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) {
                if (click(mc.player.inventoryMenu.containerId, i, 0, ClickType.SWAP)) closeForWalk = true;
                return;
            }
            halt("Нет места для освобождения руки перед открытием сундука."); return;
        }
        if (mc.screen != null) { stopMoving(); halt("Открыто постороннее окно при подходе к сундуку."); return; }
        if (chest == null || !mc.level.hasChunkAt(chest)) { abandonChest(); return; }
        BlockState block = mc.level.getBlockState(chest);
        if (!block.is(Blocks.CHEST) && !block.is(Blocks.TRAPPED_CHEST)) { abandonChest(); return; }
        Vec3 aim = Vec3.atCenterOf(chest);
        BlockHitResult hit = ray(mc.player.getEyePosition(), aim);
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(chest)
                && mc.player.getEyePosition().distanceToSqr(hit.getLocation()) <= 16) {
            stopMoving();
            lookAt(aim);
            if (angleTo(aim) > 8 || System.currentTimeMillis() < nextActionAt) return;
            if (!mc.player.getMainHandItem().isEmpty()) {
                int emptyHotbar = -1;
                for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) { emptyHotbar = i; break; }
                if (emptyHotbar < 0) {
                    int empty = -1;
                    for (int i = 9; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) { empty = i; break; }
                    if (empty < 0) { finishWithReason("Нет пустого места для безопасного открытия сундука."); return; }
                    if (mc.player.getInventory().getSelectedSlot() != 0) {
                        selectSlot(0);
                        nextActionAt = System.currentTimeMillis() + 300; return;
                    }
                    openInventory();
                    if (click(mc.player.inventoryMenu.containerId, empty, 0, ClickType.SWAP)) {
                        closeForWalk = true;
                    }
                    return;
                }
                selectSlot(emptyHotbar);
                nextActionAt = System.currentTimeMillis() + 300; return;
            }
            openPreviousMenu = mc.player.containerMenu.containerId; ownMenu = -1;
            observedUpdatesBefore = inventoryUpdates;
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            nextActionAt = System.currentTimeMillis() + 700;
            change(State.WAIT_CHEST);
            return;
        }
        if (route.isEmpty() || System.currentTimeMillis() - stateAt > 30000) { abandonChest(); return; }
        BlockPos waypoint = route.peekFirst();
        if (!standable(waypoint)) { abandonChest(); return; }
        Vec3 goal = new Vec3(waypoint.getX() + .5, waypoint.getY(), waypoint.getZ() + .5);
        double horizontal = Math.pow(mc.player.getX() - goal.x, 2) + Math.pow(mc.player.getZ() - goal.z, 2);
        if (horizontal < .16 && Math.abs(mc.player.getY() - goal.y) < .65) {
            route.removeFirst(); stopMoving(); progressDistance = Double.MAX_VALUE;
            lastProgressAt = System.currentTimeMillis(); return;
        }
        if (horizontal < progressDistance - .03) { progressDistance = horizontal; lastProgressAt = System.currentTimeMillis(); }
        if (System.currentTimeMillis() - lastProgressAt > 3500) { abandonChest(); return; }
        Vec3 look = new Vec3(goal.x, mc.player.getEyePosition().y, goal.z);
        lookAt(look);
        ownedMovement = true;
        mc.player.setSprinting(false);
        mc.options.keyUp.setDown(angleTo(look) < 35);
        mc.options.keyJump.setDown(waypoint.getY() > mc.player.blockPosition().getY() && mc.player.onGround());
    }

    private boolean closeForWalk;
    private void acceptChest() {
        stopMoving();
        if (mc.screen instanceof ContainerScreen screen && screen.getMenu().containerId != openPreviousMenu) {
            if (isAuction(screen)) { halt("Вместо сундука открылся аукцион: забор отменён."); return; }
            String title = plain(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
            if (title.contains("магазин") || title.contains("shop") || title.contains("меню") || title.contains("menu")) {
                halt("Сундук открыл меню магазина/плагина. Клики по нему не выполнялись."); return;
            }
            ownMenu = screen.getMenu().containerId;
            if (System.currentTimeMillis() - stateAt < 700 || inventoryUpdates <= observedUpdatesBefore) return;
            change(State.LOOT); return;
        }
        if (System.currentTimeMillis() - stateAt > 6000) {
            if (mc.screen != null) { halt("Неизвестный ответ на открытие сундука."); return; }
            say("Сундук не открылся: " + chest + ". Пропускаю без повторных кликов."); abandonChest();
        }
    }

    private void lootChest() {
        if (!(mc.screen instanceof ContainerScreen screen) || !ownedScreen()) {
            halt("Окно сундука изменилось во время забора."); return;
        }
        if (freeSlots() == 0) {
            if (closeOwned()) { chest = null; change(State.NEXT); }
            return;
        }
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory() || slot.getItem().isEmpty() || isSkipped(slot.getItem())) continue;
            if (!slot.mayPickup(mc.player) || !hasDestination(screen, slot)) continue;
            click(screen.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE);
            return;
        }
        if (chest != null) visited.add(chest);
        if (closeOwned()) { chest = null; change(State.NEXT); }
    }

    private List<BlockPos> pathToChest(BlockPos target, int radius) {
        BlockPos start = mc.player.blockPosition();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        queue.add(start); parent.put(start, null);
        while (!queue.isEmpty() && parent.size() <= 6000) {
            BlockPos at = queue.removeFirst();
            Vec3 eye = new Vec3(at.getX() + .5, at.getY() + mc.player.getEyeHeight(), at.getZ() + .5);
            BlockHitResult hit = ray(eye, Vec3.atCenterOf(target));
            if (eye.distanceToSqr(Vec3.atCenterOf(target)) <= 14 && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(target)) {
                ArrayDeque<BlockPos> path = new ArrayDeque<>();
                for (BlockPos n = at; n != null && !n.equals(start); n = parent.get(n)) path.addFirst(n);
                return new ArrayList<>(path);
            }
            for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos next = at.relative(direction);
                if (!standable(next)) {
                    if (standable(next.above()) && clear(at.above(2))) next = next.above();
                    else if (standable(next.below()) && clear(next) && clear(next.above())) next = next.below();
                    else continue;
                }
                if (Math.abs(next.getX() - origin.getX()) > radius + 2
                        || Math.abs(next.getZ() - origin.getZ()) > radius + 2
                        || Math.abs(next.getY() - origin.getY()) > 5 || parent.containsKey(next)) continue;
                parent.put(next.immutable(), at); queue.add(next.immutable());
            }
        }
        return null;
    }

    private boolean standable(BlockPos p) {
        if (!mc.level.hasChunkAt(p) || !clear(p) || !clear(p.above())) return false;
        BlockState floor = mc.level.getBlockState(p.below());
        return !floor.getCollisionShape(mc.level, p.below()).isEmpty() && !hazard(floor)
                && mc.level.getFluidState(p.below()).isEmpty();
    }
    private boolean clear(BlockPos p) {
        return mc.level.hasChunkAt(p) && !hazard(mc.level.getBlockState(p))
                && mc.level.getFluidState(p).isEmpty()
                && mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }
    private boolean hazard(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SWEET_BERRY_BUSH);
    }
    private BlockHitResult ray(Vec3 eye, Vec3 target) {
        return mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }
    private void abandonChest() {
        stopMoving(); if (chest != null) visited.add(chest); chest = null; route.clear(); change(State.FIND_CHEST);
    }
    private void stopMoving() {
        if (!ownedMovement) return;
        mc.options.keyUp.setDown(false); mc.options.keyJump.setDown(false); ownedMovement = false;
    }
    private void lookAt(Vec3 point) {
        Vec3 d = point.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        mc.player.setYRot(mc.player.getYRot() + clamp(wrap(yaw - mc.player.getYRot()), -12, 12));
        mc.player.setXRot(mc.player.getXRot() + clamp(pitch - mc.player.getXRot(), -8, 8));
    }
    private double angleTo(Vec3 point) {
        Vec3 d = point.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return Math.max(Math.abs(wrap(yaw - mc.player.getYRot())), Math.abs(pitch - mc.player.getXRot()));
    }
    private static float wrap(float f) { f %= 360; return f >= 180 ? f - 360 : f < -180 ? f + 360 : f; }
    private static float clamp(float v, float low, float high) { return Math.max(low, Math.min(high, v)); }

    private boolean click(int menuId, int slotIndex, int button, ClickType type) {
        long now = System.currentTimeMillis();
        if (pendingSlot >= 0 || now < nextActionAt || mc.player.containerMenu.containerId != menuId) return false;
        if (slotIndex < 0 || slotIndex >= mc.player.containerMenu.slots.size()) return false;
        while (!clickTimes.isEmpty() && now - clickTimes.peekFirst() >= 5000) clickTimes.removeFirst();
        int recent = 0; for (long t : clickTimes) if (now - t < 1000) recent++;
        if (recent >= 4 || clickTimes.size() >= 12) return false;
        Slot slot = mc.player.containerMenu.slots.get(slotIndex);
        pendingBefore = slot.getItem().copy(); pendingCursor = mc.player.containerMenu.getCarried().copy();
        pendingMenu = menuId; pendingSlot = slotIndex; pendingAt = now; pendingUpdate = inventoryUpdates;
        clickTimes.addLast(now); nextActionAt = now + Math.round(actionDelay.getValue());
        mc.gameMode.handleInventoryMouseClick(menuId, slotIndex, button, type, mc.player);
        return true;
    }

    private boolean waitClick() {
        if (mc.player.containerMenu.containerId != pendingMenu || pendingSlot >= mc.player.containerMenu.slots.size()) {
            halt("Контейнер сменился до завершения переноса. Повтор клика запрещён."); return true;
        }
        ItemStack after = mc.player.containerMenu.slots.get(pendingSlot).getItem();
        ItemStack cursor = mc.player.containerMenu.getCarried();
        long elapsed = System.currentTimeMillis() - pendingAt;
        boolean changed = !sameStack(after, pendingBefore) || !sameStack(cursor, pendingCursor);
        long minimum = Math.round(actionDelay.getValue()) + (inventoryUpdates > pendingUpdate ? 0 : 250);
        if (changed && elapsed >= minimum) {
            pendingSlot = pendingMenu = -1;
            if (closeForWalk) {
                closeForWalk = false; closeInventory(); nextActionAt = System.currentTimeMillis() + 350;
            }
            return false;
        }
        if (elapsed > 6000) halt("Перенос не изменил слот за 6 секунд. Проверь место и права; повтор не отправлялся.");
        return true;
    }

    private boolean hasDestination(ContainerScreen screen, Slot source) {
        ItemStack item = source.getItem();
        for (Slot s : screen.getMenu().slots) {
            if (s.container != mc.player.getInventory() || !s.mayPlace(item)) continue;
            ItemStack existing = s.getItem();
            if (existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, item)
                    && existing.getCount() < Math.min(s.getMaxStackSize(), item.getMaxStackSize()))) return true;
        }
        return false;
    }
    private boolean inventoryOnly() {
        if (mc.screen == null || mc.screen instanceof InventoryScreen) return true;
        halt("Закрой постороннее окно и перезапусти AutoSell."); return false;
    }
    private void openInventory() {
        if (mc.screen == null) { mc.setScreen(new InventoryScreen(mc.player)); ownInventoryScreen = true; }
    }
    private void closeInventory() {
        if (mc.screen instanceof InventoryScreen && pendingSlot < 0) {
            mc.player.closeContainer(); ownInventoryScreen = false;
        }
    }
    private boolean ownedScreen() {
        return (ownInventoryScreen && mc.screen instanceof InventoryScreen)
                || (mc.screen instanceof ContainerScreen screen && ownMenu >= 0 && screen.getMenu().containerId == ownMenu);
    }
    private boolean closeOwned() {
        if (mc.screen == null) { ownMenu = -1; return true; }
        if (pendingSlot >= 0 || !ownedScreen() || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        mc.player.closeContainer(); ownMenu = -1; ownInventoryScreen = false;
        return mc.screen == null;
    }
    private int freeSlots() {
        int count = 0; for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        return count;
    }
    private static int inventoryMenuSlot(int inventorySlot) { return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot; }
    private void selectSlot(int slot) {
        if (mc.player.getInventory().getSelectedSlot() != slot)
            InventoryUtils.syncSelectedHotbarSlot(slot);
    }
    private boolean handMatches() {
        return mc.player.getInventory().getSelectedSlot() == 0
                && sameStack(mc.player.getMainHandItem(), current);
    }
    private boolean isSkipped(ItemStack stack) {
        for (ItemStack skip : skipped) if (ItemStack.isSameItemSameComponents(skip, stack)) return true;
        return false;
    }
    private static boolean sameStack(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }
    private boolean sameProduct(ItemStack offer, ItemStack source) {
        if (offer.getItem() != source.getItem() || offer.getDamageValue() != source.getDamageValue()) return false;
        if (!cleanName(offer.getHoverName().getString()).equalsIgnoreCase(cleanName(source.getHoverName().getString()))) return false;
        return compatibleComponents(offer, source) && compatibleComponents(source, offer);
    }
    private static boolean compatibleComponents(ItemStack a, ItemStack b) {
        for (TypedDataComponent<?> component : a.getComponents()) {
            if (component.type() == DataComponents.LORE || component.type() == DataComponents.CUSTOM_NAME) continue;
            if (!java.util.Objects.equals(component.value(), b.get(component.type()))) return false;
        }
        return true;
    }
    private static boolean isAuction(ContainerScreen screen) {
        String title = plain(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        return title.contains("аукц") || title.contains("auction") || title.contains("поиск")
                || title.matches(".*\\bah\\b.*") || title.contains("search");
    }
    private boolean canCommand() { return System.currentTimeMillis() >= nextCommandAt; }
    private void command(String command) {
        mc.player.connection.sendCommand(command);
        nextCommandAt = System.currentTimeMillis() + Math.round(commandDelay.getValue());
    }
    private void change(State next) { state = next; stateAt = System.currentTimeMillis(); }
    private void say(String message) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[AutoSell] " + message), false);
    }
    private void halt(String reason) {
        stopMoving(); watchingSale = false; change(State.HALTED);
        say("Остановлено: " + reason + " Для продолжения выключи и включи модуль.");
    }
    private void finish() { finishWithReason("Доступные предметы и сундуки обработаны."); }
    private void finishWithReason(String reason) {
        stopMoving(); change(State.FINISHED);
        say(reason + " Выставлено стаков: " + sold + ", пропущено типов: " + skippedCount + ".");
    }

    private record Quote(BigDecimal total, int count) { }
    private record ReferencePrice(ItemStack product, Quote quote) { }

    private BigDecimal extractTotalPrice(ItemStack stack) {
        try {
            Item.TooltipContext context = mc.level == null ? Item.TooltipContext.EMPTY : Item.TooltipContext.of(mc.level);
            List<Component> lines = stack.getTooltipLines(context, mc.player, TooltipFlag.NORMAL);
            BigDecimal total = null, per = null;
            for (int i = 1; i < lines.size(); i++) {
                String line = plain(lines.get(i).getString()).toLowerCase(Locale.ROOT);
                Matcher matcher = PRICE_LINE.matcher(line);
                if (!matcher.find()) continue;
                BigDecimal value = parseMoney(matcher.group(1));
                if (value == null || value.signum() <= 0) continue;
                boolean each = line.contains("за шт") || line.contains("за 1") || line.contains("per item") || line.contains("each");
                if (each) {
                    if (per != null && per.compareTo(value) != 0) return null;
                    per = value;
                } else {
                    if (total != null && total.compareTo(value) != 0) return null;
                    total = value;
                }
            }
            if (total != null) {
                if (per != null && total.subtract(per.multiply(BigDecimal.valueOf(stack.getCount()))).abs()
                        .compareTo(BigDecimal.ONE) > 0) return null;
                return total;
            }
            return per == null ? null : per.multiply(BigDecimal.valueOf(Math.max(1, stack.getCount())));
        } catch (RuntimeException exception) { return null; }
    }

    public static String sanitizeAuctionQuery(String name) {
        String clean = cleanName(name);
        clean = BRACKETS.matcher(clean).replaceAll(" ");
        clean = clean.replaceAll("[^\\p{L}\\p{N}\\s]+", " ").trim().replaceAll("\\s+", " ");
        if (clean.equalsIgnoreCase("TNT TIER WHITE")) return "вайт";
        if (clean.equalsIgnoreCase("TNT TIER BLACK")) return "блек";
        return clean;
    }
    private static String cleanName(String name) {
        String cleaned = plain(MAGIC_TOKEN.matcher(name == null ? "" : name).replaceAll(" "));
        return cleaned.replaceAll("(?iu)\\s+\\d+\\s*шт(?:\\.|ук[аи]?)?\\s*$", "")
                .trim().replaceAll("\\s+", " ");
    }
    private static String plain(String value) {
        if (value == null) return "";
        String stripped = ChatFormatting.stripFormatting(value);
        return COLORS.matcher(stripped == null ? value : stripped).replaceAll("")
                .replace('\u00a0', ' ').replace('\u202f', ' ');
    }
    public static BigDecimal parseMoney(String token) {
        if (token == null) return null;
        Matcher m = NUMBER.matcher(token.trim());
        if (!m.matches()) return null;
        String numeric = m.group(1).replaceAll("[ _\\u00a0\\u202f]", "");
        String suffix = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        if (numeric.matches("[0-9]{1,3}(?:[.,][0-9]{3})+")) numeric = numeric.replace(".", "").replace(",", "");
        else if (numeric.matches("[0-9]+[.,][0-9]{1,2}")) numeric = numeric.replace(',', '.');
        else if (!numeric.matches("[0-9]+")) return null;
        try {
            BigDecimal value = new BigDecimal(numeric);
            long multiplier = switch (suffix) {
                case "k", "к", "тыс" -> 1000;
                case "kk", "кк", "m", "м", "млн" -> 1_000_000;
                default -> 1;
            };
            value = value.multiply(BigDecimal.valueOf(multiplier));
            return value.signum() > 0 && value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0 ? value : null;
        } catch (NumberFormatException exception) { return null; }
    }
}
