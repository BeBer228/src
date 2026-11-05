package releon.ru.api.module.impl.misc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.screens.cosmetics.CosmeticsScreen;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.media.MediaSession;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

public final class Cosmetics extends Module {
    public enum Group {
        Weapons, Spears, Hats, Wings, Bodywear, Pets, Talismans, Sounds
    }

    public enum Audience {
        Self, Friends, Others
    }

    public record Entry(Group group, String name, Map<String, Identifier> models, String sound) {
        public Identifier weapon(String item, ItemDisplayContext context) {
            if (context != ItemDisplayContext.GUI) {
                Identifier hand = models.get(item + "_in_hand");
                if (hand != null) return hand;
            }
            return models.get(item);
        }
    }

    private record CatalogEntry(Group group, String name, Map<String, String> models, String sound) {
        Entry resolve() {
            Map<String, Identifier> ids = new HashMap<>();
            if (models != null) models.forEach((item, model) ->
                    ids.put(item, Identifier.fromNamespaceAndPath("include", "cosmetics/" + model)));
            return new Entry(group, name, Map.copyOf(ids), sound);
        }
    }

    public static final String NONE = "Off";
    private final Map<Group, List<Entry>> catalog = new EnumMap<>(Group.class);
    private final Map<Group, Map<String, Entry>> indexed = new EnumMap<>(Group.class);
    private final Map<Audience, Map<Group, ModeSetting>> selections = new EnumMap<>(Audience.class);
    private final Map<Entry, ItemStack> displayStacks = new HashMap<>();
    public final NumberSetting maceVolume = register(new NumberSetting("Mace Volume", "Volume of mace sounds", 80, 0, 100, 1));
    public final NumberSetting maceSpeed = register(new NumberSetting("Mace Speed", "Playback speed of mace sounds", 100, 50, 200, 5));
    public final BooleanSetting pauseMusic = register(new BooleanSetting("Pause Music", "Pause Windows media while a mace sound plays", false));
    private final Set<SimpleSoundInstance> musicPausingSounds = new HashSet<>();
    private CosmeticsScreen preview;

    private static Cosmetics instance;
    public Cosmetics() {
        super("Cosmetics", "Open the cosmetics collection", ModuleCategory.MISC);
        instance=this;
        List<Entry> entries = loadCatalog();
        for (Group group : Group.values()) {
            List<Entry> choices = entries.stream().filter(entry -> entry.group == group).toList();
            catalog.put(group, choices);
            Map<String, Entry> byName = new HashMap<>();
            for (Entry entry : choices) byName.put(entry.name, entry);
            indexed.put(group, byName);
        }
        for (Audience audience : Audience.values()) {
            Map<Group, ModeSetting> settings = new EnumMap<>(Group.class);
            selections.put(audience, settings);
            for (Group group : Group.values()) {
                if (group == Group.Sounds && audience != Audience.Self) continue;
                List<Entry> choices = catalog.get(group);
                String[] options = new String[choices.size() + 1];
                options[0] = NONE;
                for (int i = 0; i < choices.size(); i++) options[i + 1] = choices.get(i).name;
                ModeSetting setting = register(new ModeSetting(audience + " " + group, "", NONE, options));
                setting.setVisible(() -> false);
                settings.put(group, setting);
            }
        }
        releon.ru.utils.render.IncludeDefaults.apply("Cosmetics",getSettings(),"");
        register(new releon.ru.api.settings.impl.ButtonSetting("Коллекция", "Открыть предпросмотр.",this::openPreview));
    }

    private static List<Entry> loadCatalog() {
        try (var stream = Cosmetics.class.getResourceAsStream("/assets/include/cosmetics/catalog.json")) {
            if (stream == null) throw new IOException("Cosmetics catalog is missing");
            List<CatalogEntry> entries = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<List<CatalogEntry>>() {}.getType());
            return entries.stream().map(CatalogEntry::resolve).toList();
        } catch (IOException | RuntimeException ex) {
            LoggerFactory.getLogger(Cosmetics.class).error("Could not load cosmetics catalog", ex);
            return List.of();
        }
    }

    public static Cosmetics get() {
        return instance;
    }

    public List<Entry> entries(Group group) {
        return catalog.get(group);
    }

    public Entry selected(Audience audience, Group group) {
        ModeSetting setting = selections.get(audience).get(group);
        return setting == null ? null : indexed.get(group).get(setting.getValue());
    }

    public void select(Audience audience, Group group, Entry entry) {
        selections.get(audience).get(group).setValue(entry == null ? NONE : entry.name);
    }

    public Entry shown(Avatar player, Group group) {
        if (preview != null && preview.isModel(player)) return preview.shown(group);
        if (!isEnabled() || !(player instanceof Player actual)) return null;
        Audience audience = player == mc.player ? Audience.Self
                : FriendUtils.isFriend(actual) ? Audience.Friends : Audience.Others;
        return selected(audience, group);
    }

    public ItemStack displayStack(Entry entry) {
        return displayStacks.computeIfAbsent(entry, Cosmetics::createDisplayStack);
    }

    private static ItemStack createDisplayStack(Entry entry) {
        String item = entry.models.containsKey("mace") ? "mace"
                : entry.models.containsKey("diamond_spear") ? "diamond_spear" : "display";
        ItemStack stack = new ItemStack(switch (item) {
            case "mace" -> Items.MACE;
            case "diamond_spear" -> Items.DIAMOND_SPEAR;
            case "display" -> entry.group == Group.Talismans ? Items.TOTEM_OF_UNDYING : Items.PAPER;
            default -> Items.PAPER;
        });
        stack.set(DataComponents.ITEM_MODEL, entry.models.get(item));
        return stack;
    }

    public SimpleSoundInstance play(Entry entry) {
        if (entry == null || entry.sound == null || maceVolume.getValue().intValue() == 0) return null;
        var id = Identifier.fromNamespaceAndPath("include", entry.sound);
        SimpleSoundInstance sound = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), maceSpeed.getFloat() / 100f, maceVolume.getFloat() / 100f);
        if (mc.getSoundManager().play(sound) == SoundEngine.PlayResult.STARTED && pauseMusic.getValue()) {
            if (musicPausingSounds.isEmpty()) MediaSession.command("pause-cosmetics");
            musicPausingSounds.add(sound);
        }
        return sound;
    }

    private void updateMusicPause() {
        if (musicPausingSounds.isEmpty()) return;
        if (pauseMusic.getValue()) musicPausingSounds.removeIf(sound -> !mc.getSoundManager().isActive(sound));
        else musicPausingSounds.clear();
        if (musicPausingSounds.isEmpty()) MediaSession.command("resume-cosmetics");
    }

    public boolean replacesMaceHit() {
        return mc.player != null && mc.player.getMainHandItem().is(Items.MACE) && mc.player.fallDistance > 1.5
                && selected(Audience.Self, Group.Sounds) != null;
    }

    @SubscribeEvent
    private void onAttack(AttackEvent event) {
        if (replacesMaceHit()) play(selected(Audience.Self, Group.Sounds));
    }

    @Override protected void onEnable() { mc.execute(this::openPreview); }
    @Override public void onTick(net.minecraft.client.Minecraft client) { updateMusicPause(); }
    @Override protected void onDisable() {
        for (var sound:musicPausingSounds) mc.getSoundManager().stop(sound);
        musicPausingSounds.clear();
        MediaSession.command("resume-cosmetics");
    }
    private void openPreview() {
        if (mc.screen instanceof CosmeticsScreen) return;
        preview = new CosmeticsScreen(this,mc.screen);
        mc.setScreen(preview);
    }

    public void closePreview(CosmeticsScreen screen) {
        if (preview == screen) preview = null;
    }

}
