package releon.ru.api.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import releon.ru.api.config.exception.ConfigException;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.ModuleManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ConfigManager {
    public static final String ROOT_FOLDER    = "Titanium";
    public static final String SYSTEM_FOLDER  = "system";
    public static final String CONFIGS_FOLDER = "configs";
    public static final String CONFIG_EXTENSION = ".cfg";

    private static final long AUTO_SAVE_DELAY_MS = 1000L;

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path systemDirectory;
    private final Path configsDirectory;
    private final Path modulesFile;
    private final ModuleConfigStorage moduleStorage;

    private boolean dirty;
    private long dirtySince;

    public ConfigManager(ModuleManager moduleManager) {
        this.systemDirectory  = systemDirectory();
        this.configsDirectory = configsDirectory();
        this.modulesFile      = systemDirectory.resolve("modules" + CONFIG_EXTENSION);
        this.moduleStorage    = new ModuleConfigStorage(moduleManager);
    }


    public static Path rootDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(ROOT_FOLDER);
    }

    @Deprecated
    public static Path userConfigDirectory() {
        return rootDirectory().resolve(SYSTEM_FOLDER);
    }

    public static Path systemDirectory() {
        return rootDirectory().resolve(SYSTEM_FOLDER);
    }

    public static Path configsDirectory() {
        return rootDirectory().resolve(CONFIGS_FOLDER);
    }

    public static Path legacyDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("releon");
    }

    public void init() {
        try {
            Files.createDirectories(systemDirectory);
            Files.createDirectories(configsDirectory);
        } catch (IOException e) {
            throw new ConfigException("Failed to create config directories under: " + rootDirectory(), e);
        }
    }


    public void loadAll() {
        loadModules();
        dirty = false;
    }

    public void saveAll() {
        saveModules();
        dirty = false;
    }

    public void markDirty() {
        if (!dirty) dirtySince = System.currentTimeMillis();
        dirty = true;
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        if (dirty && System.currentTimeMillis() - dirtySince >= AUTO_SAVE_DELAY_MS) {
            saveAll();
        }
    }

    public Path saveConfig(String name) {
        String safeName = sanitizeName(name);
        Path file = configsDirectory.resolve(safeName + CONFIG_EXTENSION);
        try {
            Files.createDirectories(configsDirectory);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(moduleStorage.save(), writer);
            }
            return file;
        } catch (IOException e) {
            throw new ConfigException("Failed to save config '" + safeName + "': " + file, e);
        }
    }

    public boolean loadConfig(String name) {
        String safeName = sanitizeName(name);
        Path file = configsDirectory.resolve(safeName + CONFIG_EXTENSION);
        if (!Files.exists(file)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            moduleStorage.load(root);
            return true;
        } catch (Exception e) {
            throw new ConfigException("Failed to load config '" + safeName + "': " + file, e);
        }
    }

    public boolean deleteConfig(String name) {
        String safeName = sanitizeName(name);
        Path file = configsDirectory.resolve(safeName + CONFIG_EXTENSION);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ConfigException("Failed to delete config '" + safeName + "': " + file, e);
        }
    }

    public List<String> listConfigs() {
        if (!Files.exists(configsDirectory)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(configsDirectory)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(CONFIG_EXTENSION))
                    .map(p -> {
                        String fname = p.getFileName().toString();
                        return fname.substring(0, fname.length() - CONFIG_EXTENSION.length());
                    })
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public boolean configExists(String name) {
        return Files.exists(configsDirectory.resolve(sanitizeName(name) + CONFIG_EXTENSION));
    }

    private void loadModules() {
        Path file = resolveModulesFileForRead();
        if (!Files.exists(file)) {
            saveModules();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            moduleStorage.load(root);
        } catch (Exception e) {
            throw new ConfigException("Failed to load module config: " + file, e);
        }
    }

    private void saveModules() {
        try {
            Files.createDirectories(systemDirectory);
            try (Writer writer = Files.newBufferedWriter(modulesFile, StandardCharsets.UTF_8)) {
                gson.toJson(moduleStorage.save(), writer);
            }
        } catch (IOException e) {
            throw new ConfigException("Failed to save module config: " + modulesFile, e);
        }
    }

    private Path resolveModulesFileForRead() {
        if (Files.exists(modulesFile)) return modulesFile;
        Path legacyJson = legacyDirectory().resolve("modules.json");
        if (Files.exists(legacyJson)) return legacyJson;
        return modulesFile;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Config name must not be blank");
        }
        String safe = name.trim().replaceAll("[^\\w\\-.]", "_");
        if (safe.isBlank() || safe.equals("_")) {
            throw new IllegalArgumentException("Config name is invalid: " + name);
        }
        return safe;
    }
}