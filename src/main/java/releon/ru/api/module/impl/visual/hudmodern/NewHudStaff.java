package releon.ru.api.module.impl.visual.hudmodern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import releon.ru.utils.repository.staff.StaffUtils;
import java.util.*;
import java.util.regex.Pattern;

public final class NewHudStaff {
    public enum Status { ACTIVE, VANISH, GM3 }
    public record Row(String name,String rankLabel,Status status,PlayerInfo info) {}
    private static final Pattern ROLE=Pattern.compile("(?iu).*(admin|moder|helper|owner|support|куратор|админ|модер|хелпер|поддержка).*" );
    private NewHudStaff() {}
    public static List<Row> rows() {
        var mc=Minecraft.getInstance();if(mc.level==null||mc.getConnection()==null)return List.of();
        Map<String,Row> found=new LinkedHashMap<>();
        for(var info:mc.getConnection().getOnlinePlayers()){
            String name=info.getProfile().name();var team=mc.level.getScoreboard().getPlayersTeam(name);
            String prefix=team==null?"":team.getPlayerPrefix().getString();
            if(!StaffUtils.isStaff(name)&&!ROLE.matcher(prefix).matches())continue;
            found.put(name.toLowerCase(Locale.ROOT),new Row(name,prefix.trim(),info.getGameMode()==GameType.SPECTATOR?Status.GM3:Status.ACTIVE,info));
        }
        for(String name:StaffUtils.getStaffNames())found.putIfAbsent(name.toLowerCase(Locale.ROOT),new Row(name,"вручную",Status.VANISH,null));
        return List.copyOf(found.values());
    }
}
