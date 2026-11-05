package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.api.config.ConfigManager;
import releon.ru.manager.Manager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ConfigCommand extends Command {
    public ConfigCommand() {
        super("config", "Управление конфигурациями", "cfg");
    }

    @Override
    public void execute(String label, String[] args) {
        ConfigManager configManager = getConfigManager();
        if (configManager == null) {
            logDirect("ConfigManager недоступен.", ChatFormatting.RED);
            return;
        }

        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";

        switch (action) {

            case "save" -> {
                if (args.length < 2) {
                    logDirect("Использование: .cfg save <название>", ChatFormatting.RED);
                    return;
                }
                String name = args[1];
                try {
                    boolean existed = configManager.configExists(name);
                    configManager.saveConfig(name);
                    logDirect((existed ? "Конфиг перезаписан: " : "Конфиг сохранён: ") + name, ChatFormatting.GREEN);
                } catch (IllegalArgumentException e) {
                    logDirect("Недопустимое название: " + e.getMessage(), ChatFormatting.RED);
                }
            }

            case "load" -> {
                if (args.length < 2) {
                    logDirect("Использование: .cfg load <название>", ChatFormatting.RED);
                    return;
                }
                String name = args[1];
                try {
                    boolean found = configManager.loadConfig(name);
                    if (found) {
                        logDirect("Конфиг загружен: " + name, ChatFormatting.GREEN);
                    } else {
                        logDirect("Конфиг не найден: " + name, ChatFormatting.RED);
                    }
                } catch (IllegalArgumentException e) {
                    logDirect("Недопустимое название: " + e.getMessage(), ChatFormatting.RED);
                }
            }

            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    logDirect("Использование: .cfg remove <название>", ChatFormatting.RED);
                    return;
                }
                String name = args[1];
                try {
                    boolean deleted = configManager.deleteConfig(name);
                    if (deleted) {
                        logDirect("Конфиг удалён: " + name, ChatFormatting.GREEN);
                    } else {
                        logDirect("Конфиг не найден: " + name, ChatFormatting.RED);
                    }
                } catch (IllegalArgumentException e) {
                    logDirect("Недопустимое название: " + e.getMessage(), ChatFormatting.RED);
                }
            }

            case "list" -> {
                List<String> configs = configManager.listConfigs();
                if (configs.isEmpty()) {
                    logDirect("Нет сохранённых конфигов.", ChatFormatting.YELLOW);
                    return;
                }
                CommandManager commandManager = CommandManager.getInstance();
                logDirectRaw(Component.literal(HelpCommand.getLine()));
                logDirect("Конфиги (" + configs.size() + ")");
                logDirectRaw(Component.literal(HelpCommand.getLine()));
                for (String cfg : configs) {
                    String loadCmd  = commandManager.getPrefix() + label + " load " + cfg;
                    String delCmd   = commandManager.getPrefix() + label + " remove " + cfg;
                    logDirectRaw(Component.literal("  §f" + cfg)
                            .withStyle(style -> style
                                    .withHoverEvent(new HoverEvent.ShowText(
                                            Component.literal("§aЛКМ §7— загрузить\n§cПКМ §7— удалить")))
                                    .withClickEvent(new ClickEvent.RunCommand(loadCmd)))
                            .append(Component.literal(" §8[§cX§8]")
                                    .withStyle(style -> style
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    Component.literal("Удалить конфиг " + cfg)))
                                            .withClickEvent(new ClickEvent.RunCommand(delCmd)))));
                }
                logDirectRaw(Component.literal(HelpCommand.getLine()));
            }

            case "autosave" -> {
                configManager.saveAll();
                logDirect("Конфиг принудительно сохранён.", ChatFormatting.GREEN);
            }
            case "dir" -> {
                try {
                    openDirectory(ConfigManager.configsDirectory());
                    logDirect("Папка конфигов открыта.", ChatFormatting.GREEN);
                } catch (IOException e) {
                    logDirect("Не удалось открыть папку: " + e.getMessage(), ChatFormatting.RED);
                }
            }
            case "system" -> {
                try {
                    openDirectory(ConfigManager.systemDirectory());
                    logDirect("Системная папка открыта.", ChatFormatting.GREEN);
                } catch (IOException e) {
                    logDirect("Не удалось открыть папку: " + e.getMessage(), ChatFormatting.RED);
                }
            }

            default -> printHelp(label);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return new TabCompleteHelper()
                    .append("save", "load", "remove", "list", "dir", "system", "autosave")
                    .sortAlphabetically()
                    .filterPrefix(args[0])
                    .stream();
        }

        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("load") || action.equals("remove") || action.equals("del") || action.equals("delete")) {
                ConfigManager configManager = getConfigManager();
                if (configManager != null) {
                    return new TabCompleteHelper()
                            .append(configManager.listConfigs().toArray(new String[0]))
                            .filterPrefix(args[1])
                            .stream();
                }
            }
        }

        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Управление именными конфигами.",
                "Использование:",
                "> cfg save <название>   — сохранить текущий конфиг <",
                "> cfg load <название>   — загрузить конфиг <",
                "> cfg remove <название> — удалить конфиг <",
                "> cfg list              — список конфигов <",
                "> cfg dir               — открыть папку конфигов <",
                "> cfg system            — открыть системную папку <"
        );
    }

    private void printHelp(String label) {
        logDirectRaw(Component.literal(HelpCommand.getLine()));
        logDirect("CONFIG");
        logDirectRaw(Component.literal(HelpCommand.getLine()));
        logDirect("> cfg save <название>");
        logDirect("> cfg load <название>");
        logDirect("> cfg remove <название>");
        logDirect("> cfg list");
        logDirectRaw(Component.literal(HelpCommand.getLine()));
    }

    private ConfigManager getConfigManager() {
        Manager manager = Manager.getInstance();
        return manager != null ? manager.getConfigManager() : null;
    }

    private void openDirectory(Path directory) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        ProcessBuilder builder = os.contains("win")
                ? new ProcessBuilder("explorer.exe", directory.toAbsolutePath().toString())
                : os.contains("mac")
                ? new ProcessBuilder("open", directory.toAbsolutePath().toString())
                : new ProcessBuilder("xdg-open", directory.toAbsolutePath().toString());
        builder.start();
    }
}