package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;
import java.util.ArrayDeque;
public final class NewHudInput {
    private static final ArrayDeque<Long> LEFT=new ArrayDeque<>(),RIGHT=new ArrayDeque<>();
    private NewHudInput(){}
    public static void record(int button,int action){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(action!=1||mc.player==null||mc.screen!=null)return;
        var queue=button==0?LEFT:button==1?RIGHT:null;if(queue==null)return;
        trim(queue);if(queue.size()<256)queue.addLast(System.currentTimeMillis());
    }
    private static void trim(ArrayDeque<Long> queue){long before=System.currentTimeMillis()-1000;while(!queue.isEmpty()&&queue.peekFirst()<before)queue.removeFirst();}
    public static int clicks(boolean left){var queue=left?LEFT:RIGHT;trim(queue);return queue.size();}
}
