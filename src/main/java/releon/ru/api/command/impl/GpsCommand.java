package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import releon.ru.IMinecraft;
import releon.ru.api.command.Command;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.utils.repository.gps.GpsUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class GpsCommand extends Command implements IMinecraft {
    public GpsCommand() {
        super("gps", "Показывает стрелку в направлении координат", "waypointarrow");
    }

    @Override
    public void execute(String label, String[] args) {
        if (args.length > 0 && isClearAction(args[0])) {
            GpsUtils.clear();
            logDirect("GPS стрелка убрана.", ChatFormatting.GREEN);
            return;
        }

        if (args.length != 2) {
            logDirect("Использование: .gps <x> <z>", ChatFormatting.RED);
            return;
        }

        double x;
        double z;
        try {
            x = Double.parseDouble(args[0]);
            z = Double.parseDouble(args[1]);
        } catch (NumberFormatException exception) {
            logDirect("Неверные координаты.", ChatFormatting.RED);
            return;
        }

        GpsUtils.set(x, z);
        logDirect("GPS стрелка установлена: " + (int) x + " " + (int) z, ChatFormatting.GREEN);
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            TabCompleteHelper helper = new TabCompleteHelper().append("clear");
            if (mc.player != null) {
                helper.append(String.valueOf(mc.player.blockPosition().getX()));
            }
            return helper.filterPrefix(args[0]).stream();
        }
        if (args.length == 2 && mc.player != null) {
            return new TabCompleteHelper().append(String.valueOf(mc.player.blockPosition().getZ())).filterPrefix(args[1]).stream();
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Показывает стрелку в направлении координат",
                "Использование:",
                "> gps <x> <z>",
                "> gps clear"
        );
    }

    private boolean isClearAction(String value) {
        return value.equalsIgnoreCase("clear") || value.equalsIgnoreCase("remove") || value.equalsIgnoreCase("off");
    }
}