package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.settings.impl.*;
import releon.ru.utils.render.IncludeDefaults;
import com.google.gson.JsonObject;
import java.util.*;
import java.awt.Color;
public final class NewHudSettings {
    public enum Kind {
        WATERMARK("WaterMark", "Ватермарк", .02, .02), EFFECTS("Potions", "Эффекты", .02, .15),
        COOLDOWNS("Cooldowns", "Перезарядки", .02, .52), BINDS("HotKeys", "Бинды", .98, .15),
        STAFF("StaffList", "Персонал", .98, .43), TARGET("TargetHUD", "Цель", .5, .76),
        COORDS("Info", "Координаты", .02, .96), SPEED("BPS", "Скорость", .25, .96),
        ARMOR("Armor", "Броня", .98, .96), INVENTORY("Inventory", "Инвентарь", .5, .5),
        KEYS("Keystrokes", "Клавиши", .5, .96), NOTIFICATIONS("Notifications", "Уведомления", .98, .76),
        MUSIC("Music", "Музыка", .02, .78);
        public final String id, label;
        public final double x, y;
        Kind(String id, String label, double x, double y) { this.id=id;this.label=label;this.x=x;this.y=y; }
        public static Kind find(String id) { for (Kind kind:values()) if(kind.id.equals(id))return kind;throw new IllegalArgumentException(id); }
    }
    public enum Part {
        TITLE("Текст заголовка", 0xFFF1EEE6, 8), HEADER_ICON("Иконка заголовка", 0xFF6FD8C0, 9),
        TEXT("Основной текст",0xFFE4E2DC,8), VALUE("Значение",0xFFA9B5AF,7),
        ICON("Иконка информации",0xFF6FD8C0,9), ACCENT("Полоска прогресса",0xFF6FD8C0,1),
        POSITIVE("Включено / в сети",0xFF8DC9AD,7), NEGATIVE("Выключено",0xFFD28C9A,7),
        AWAY("Ваниш",0xFFD3BA89,7), SPECTATOR("Спекает",0xFFADA4D9,7),
        MAXIMUM("Максимальное здоровье",0xFF9299AE,6),
        SERVER("Адрес сервера",0xFFDADDEA,8), PROFILE("Имя Discord",0xFFDADDEA,8),
        FPS("Счётчик FPS",0xFFDADDEA,8), PING("Пинг",0xFFDADDEA,8),
        GOLD("Полоска поглощения",0xFFE2C36F,2), HIGHLIGHT("Новый предмет",0xFFC5D4FF,1);
        final String label; final int color; final double size;
        Part(String label,int color,double size){this.label=label;this.color=color;this.size=size;}
    }

    public static final String ARMOR_BARS="Полоски",ARMOR_PERCENT="Проценты";
    public final MultiModeSetting components;
    public final ModeSetting editor,armorMode;
    public final BooleanSetting blur,shimmer;
    public final NumberSetting notificationSeconds,notificationWidth,notificationTextScale,highlightSeconds,animationSpeed,scale;
    private final EnumMap<Kind,Style> styles=new EnumMap<>(Kind.class);
    public NewHudSettings(NewHudRenderer owner){
        String[] names=Arrays.stream(Kind.values()).map(k->k.id).toArray(String[]::new);
        components=owner.setting(new MultiModeSetting("NEW: элементы","Элементы HUD New.",names,names));
        editor=owner.setting(new ModeSetting("NEW: настройка элемента","Масштаб и прозрачность выбранного элемента.",Kind.WATERMARK.label,Arrays.stream(Kind.values()).map(k->k.label).toArray(String[]::new)));
        scale=owner.setting(new NumberSetting("Масштаб","Общий масштаб HUD New.",.75,.35,2,.05));
        blur=owner.setting(new BooleanSetting("NEW: блюр","Размытие за панелями.",true));
        shimmer=owner.setting(new BooleanSetting("NEW: переливание в чате","Подсветка при открытом чате.",true));
        notificationSeconds=owner.setting(new NumberSetting("NEW: время уведомлений","Время показа.",1.6,.5,5,.1));
        notificationWidth=owner.setting(new NumberSetting("NEW: ширина уведомлений","Насколько широкой может стать плашка уведомления.",1,.7,2.5,.05));
        notificationTextScale=owner.setting(new NumberSetting("NEW: размер текста уведомлений","Масштаб текста уведомлений (меньше — компактнее).",1,.7,1.4,.05));
        highlightSeconds=owner.setting(new NumberSetting("NEW: время подсветки инвентаря","Подсветка новых предметов.",4.6,.5,8,.1));
        animationSpeed=owner.setting(new NumberSetting("NEW: скорость анимаций","Скорость переходов.",12,4,24,1));
        armorMode=owner.setting(new ModeSetting("NEW / Броня / Прочность","Прочность снаряжения.",ARMOR_BARS,ARMOR_BARS,ARMOR_PERCENT));
        shimmer.setVisible(false);highlightSeconds.setVisible(false);animationSpeed.setVisible(false);
        armorMode.visibleWhen(()->editor.is(Kind.ARMOR.label));
        for(Kind kind:Kind.values())styles.put(kind,new Style(owner,kind,editor));
        IncludeDefaults.apply("Interface",owner.getSettings(),"");
    }
    public Style style(Kind kind){return styles.get(kind);}
    public static final class Style {
        public final Kind kind;
        public final NumberSetting x,y,scale,width,opacity,portraitSize,equipmentSize;
        public final ColorSetting background,separator;
        private final EnumMap<Part,Integer> colors=new EnumMap<>(Part.class);
        private final EnumMap<Part,Float> sizes=new EnumMap<>(Part.class);
        Style(NewHudRenderer owner,Kind kind,ModeSetting editor){
            this.kind=kind;String prefix="NEW / "+kind.label+" / ";
            x=owner.setting(new NumberSetting("NEW "+kind.id+" X","",kind.x,0,1,.0005));
            y=owner.setting(new NumberSetting("NEW "+kind.id+" Y","",kind.y,0,1,.0005));
            scale=owner.setting(new NumberSetting(prefix+"Масштаб элемента","",1,.35,2.5,.05));
            width=owner.setting(new NumberSetting(prefix+"Ширина","",1,.6,2,.05));
            opacity=owner.setting(new NumberSetting(prefix+"Непрозрачность","",80,0,100,1));
            portraitSize=owner.setting(new NumberSetting(prefix+"Размер аватара","",kind==Kind.TARGET?24:12,6,48,.5));
            equipmentSize=owner.setting(new NumberSetting(prefix+"Размер предметов","",kind==Kind.TARGET?9:12,6,32,.5));
            background=owner.setting(new ColorSetting(prefix+"Фон","",new Color(0xFF151622,true)));
            separator=owner.setting(new ColorSetting(prefix+"Разделители","",new Color(0x38B8A5FF,true)));
            for(var setting:List.of(x,y,width,portraitSize,equipmentSize,background,separator))setting.setVisible(false);
            scale.visibleWhen(()->editor.is(kind.label));opacity.visibleWhen(()->editor.is(kind.label));
            JsonObject cfg=IncludeDefaults.settings("Interface");
            for(Part part:Part.values()){
                String label=part==Part.VALUE?switch(kind){case EFFECTS->"Время эффектов";case COOLDOWNS->"Время перезарядки";case BINDS->"Клавиша бинда";case STAFF->"Статус персонала";case TARGET->"Здоровье";case WATERMARK->"Показатели";case INVENTORY->"Количество предметов";case COORDS->"Координаты";case SPEED->"Скорость в BPS";case MUSIC->"Исполнитель и время";default->"Значение";}:part.label;
                String colorKey=prefix+label+": цвет",sizeKey=prefix+label+": размер";
                colors.put(part,cfg.has(colorKey)?cfg.getAsJsonObject(colorKey).get("color").getAsInt():part.color);
                float fallback=(float)(part==Part.VALUE&&kind==Kind.TARGET?12:part==Part.VALUE&&(kind==Kind.COORDS||kind==Kind.SPEED)?8:part==Part.TEXT&&(kind==Kind.EFFECTS||kind==Kind.COOLDOWNS)?7:part.size);
                sizes.put(part,cfg.has(sizeKey)?cfg.get(sizeKey).getAsFloat():fallback);
            }
        }
        public int color(Part part){return colors.get(part);}
        public float size(Part part){return sizes.get(part);}
    }
}
