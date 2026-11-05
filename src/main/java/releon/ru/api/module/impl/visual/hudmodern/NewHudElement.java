package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import java.util.*;
import static releon.ru.api.module.impl.visual.hudmodern.NewHudSettings.Part.*;

/** The NEW suite shares drag/config infrastructure, but never mutates the other HUD styles. */
public final class NewHudElement extends NewHudPanel {
    private static final Minecraft MC=Minecraft.getInstance();
    private final NewHudSettings.Kind kind;
    private final NewHudSettings.Style style;
    private static final class Row {
        final String id; String label="",value="",rank=""; ItemStack item=ItemStack.EMPTY; Identifier effect; PlayerSkin skin;
        NewHudSettings.Part status=VALUE; float ratio=1,shownRatio=1,alpha; boolean present;
        final NewHudNumber number=new NewHudNumber();
        Row(String id){this.id=id;}
    }
    private final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
    private final LinkedHashMap<Identifier,ItemStack> cooldownItems=new LinkedHashMap<>();
    private final ItemStack[] previousInventory=new ItemStack[27];
    private final long[] arrived=new long[27];
    private Object world;
    private LivingEntity target;
    private float targetAlpha,hp,gold,targetHeight,animatedWidth,animatedHeight,speed;
    private double lastX,lastZ;
    private final NewHudNumber healthNumber=new NewHudNumber();
    private float dt=.016f;
    private float rowAlpha=1;
    private int tick;
    private boolean panelClip;
    private String targetName="";
    private long targetNameAt;
    private final Map<NewNotices.Entry,Notice> notices=new IdentityHashMap<>();
    private final float[] keyGlow=new float[5];
    private final NewHudNumber fpsNumber=new NewHudNumber(),pingNumber=new NewHudNumber();
    private static final class Notice { float alpha,y,wrapWidth=-1; int wrapSize; boolean positioned; List<String> lines; String wrapped; }

    public NewHudElement(NewHudSettings.Kind kind,NewHudSettings.Style style){super(kind.id,style.x,style.y);this.kind=kind;this.style=style;Arrays.fill(previousInventory,ItemStack.EMPTY);}
    private NewHudSettings settings(){return NewHudRenderer.getInstance().settings;}
    private float approach(float value,float to){float next=value+(to-value)*(1-(float)Math.exp(-settings().animationSpeed.getFloat()*Math.min(.1f,dt)));return Math.abs(next-to)<.01f?to:next;}
    private Row row(String id,String label,String value){Row r=rows.computeIfAbsent(id,Row::new);r.label=label;r.value=value;r.present=true;return r;}
    @Override public void tick(){
        if(MC.player==null||MC.level==null)return;
        if(world!=MC.level){world=MC.level;tick=0;rows.clear();effectTotals.clear();cooldownItems.clear();Arrays.fill(previousInventory,ItemStack.EMPTY);Arrays.fill(arrived,0);lastX=MC.player.getX();lastZ=MC.player.getZ();target=null;targetAlpha=0;}
        tick++;
        boolean staffRefresh=kind!=NewHudSettings.Kind.STAFF||tick%10==0;
        if(staffRefresh)for(Row row:rows.values())row.present=false;
        switch(kind){
            case EFFECTS->effects(); case COOLDOWNS->cooldowns();case BINDS->binds();case STAFF->{if(staffRefresh)staff();}
            case INVENTORY->inventoryTick();case WATERMARK->releon.ru.utils.media.DiscordHudProfile.request();case SPEED->{double dx=MC.player.getX()-lastX,dz=MC.player.getZ()-lastZ;speed=approach(speed,(float)Math.hypot(dx,dz)*20);lastX=MC.player.getX();lastZ=MC.player.getZ();}
            default->{}
        }
    }
    private void effects(){
        for(var effect:MC.player.getActiveEffects()){
            String id=BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
            String label=HudLocalization.game(HudLocalization.Part.POTIONS_NAMES,effect.getEffect().value().getDescriptionId());
            if(effect.getAmplifier()>0)label+=" "+roman(effect.getAmplifier()+1);
            Row r=row(id,label,effect.isInfiniteDuration()?"∞":clock(effect.getDuration()/20));
            r.effect=net.minecraft.client.gui.Gui.getMobEffectSprite(effect.getEffect());
            int total=Math.max(effect.getDuration(),effectTotals.getOrDefault(id,0));effectTotals.put(id,total);
            r.ratio=effect.isInfiniteDuration()?1:effect.getDuration()/(float)Math.max(1,total);
        }
        effectTotals.keySet().removeIf(key->!rows.containsKey(key)||!rows.get(key).present);
    }
    private final Map<String,Integer> effectTotals=new HashMap<>();
    private void cooldowns(){
        var accessor=(ItemCooldownsAccessor)MC.player.getCooldowns();
        for(int i=0;i<MC.player.getInventory().getContainerSize();i++)rememberCooldown(accessor,MC.player.getInventory().getItem(i));
        rememberCooldown(accessor,MC.player.getMainHandItem());rememberCooldown(accessor,MC.player.getOffhandItem());
        for(var entry:accessor.releon$getCooldowns().entrySet()){
            Identifier id=entry.getKey();var timer=(ItemCooldownInstanceAccessor)entry.getValue();int remaining=timer.releon$getEndTime()-accessor.releon$getTickCount();if(remaining<=0)continue;
            ItemStack stack=cooldownItems.get(id);
            if(stack==null){var item=BuiltInRegistries.ITEM.getValue(id);if(item!=null){ItemStack candidate=item.getDefaultInstance();if(!candidate.isEmpty()&&id.equals(accessor.releon$getCooldownGroup(candidate))){stack=candidate;cooldownItems.put(id,stack);}}}
            if(stack!=null&&(stack.is(net.minecraft.world.item.Items.FIREWORK_ROCKET)||stack.is(net.minecraft.world.item.Items.HONEY_BLOCK)||stack.is(net.minecraft.world.item.Items.SLIME_BLOCK)))continue;
            String label=stack==null?id.toString():stack.getHoverName().getString();
            Row r=row(id.toString(),label,NewHudDraw.fmt(remaining/20f,1)+HudLocalization.ui("s","с","с"));r.item=stack==null?ItemStack.EMPTY:stack;r.ratio=remaining/(float)Math.max(1,timer.releon$getEndTime()-timer.releon$getStartTime());
        }
    }
    private void rememberCooldown(ItemCooldownsAccessor accessor,ItemStack stack){if(stack.isEmpty())return;Identifier group=accessor.releon$getCooldownGroup(stack);cooldownItems.put(group,stack.copy());if(cooldownItems.size()>128)cooldownItems.remove(cooldownItems.keySet().iterator().next());}
    private void binds(){
        for(var module:Manager.getModules().getModules()){
            if(!module.isEnabled()||module.isHidden()||!module.getBind().isBound()||module.getName().equalsIgnoreCase("ClickGui"))continue;
            row(module.getName(),module.getName(),module.getBind().getDisplayName());
        }
    }
    private void staff(){
        for(var staff:NewHudStaff.rows()){
            NewHudSettings.Part status=switch(staff.status()){case GM3->SPECTATOR;case VANISH->AWAY;default->POSITIVE;};
            String player=staff.name();
            Row r=row(player.toLowerCase(Locale.ROOT),player,status==SPECTATOR?HudLocalization.ui("Spectator","Спектейтор","Спостерігач"):status==AWAY?HudLocalization.ui("Vanish","Ваниш","Ваніш"):HudLocalization.ui("Online","Онлайн","Онлайн"));
            r.rank=staff.rankLabel();r.status=status;
            // Vanished staff have no tab entry: keep the last real skin, else fall back to the default skin instead of the placeholder.
            r.skin=staff.info()!=null?staff.info().getSkin():r.skin!=null?r.skin:net.minecraft.client.resources.DefaultPlayerSkin.get(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(player));
        }
    }
    private void inventoryTick(){
        for(int i=0;i<27;i++){
            ItemStack now=MC.player.getInventory().getItem(i+9),previous=previousInventory[i];
            if(!now.isEmpty()&&(previous.isEmpty()||!ItemStack.isSameItemSameComponents(now,previous)||now.getCount()>previous.getCount())){
                if(tick>1)arrived[i]=System.currentTimeMillis();
            }
            if(!ItemStack.matches(now,previous))previousInventory[i]=now.copy();
        }
    }
    @Override public void frame(float delta){
        dt=Math.max(.001f,Math.min(.1f,delta));
        var iterator=rows.values().iterator();while(iterator.hasNext()){Row r=iterator.next();r.alpha=approach(r.alpha,r.present?1:0);r.shownRatio=approach(r.shownRatio,r.ratio);if(!r.present&&r.alpha<.01f)iterator.remove();}
        if(kind==NewHudSettings.Kind.TARGET){
            LivingEntity next=NewHudRenderer.getInstance().target();if(next==null&&chatOpen())next=MC.player;
            if(next!=null&&next.isAlive()){if(next!=target){target=next;hp=target.getHealth();gold=target.getAbsorptionAmount();healthNumber.reset();}hp=approach(hp,target.getHealth());gold=approach(gold,target.getAbsorptionAmount());}
            targetAlpha=approach(targetAlpha,next!=null&&next.isAlive()?1:0);if(targetAlpha<.005f)target=null;
        }
    }
    @Override public void render(GuiGraphics g,float delta){
        if(MC.player==null)return;
        rowAlpha=1;
        try { switch(kind){
            case EFFECTS,COOLDOWNS,BINDS,STAFF->list(g);
            case TARGET->target(g);case INVENTORY->inventory(g);case WATERMARK->watermark(g);
            case COORDS,SPEED->information(g);case ARMOR->armor(g);case KEYS->keys(g);case NOTIFICATIONS->notifications(g);case MUSIC->music(g);
        }} finally { endPanel(g); rowAlpha=1; }
    }
    private float contentAlpha(){return rowAlpha*smooth((HudPaint.opacity()-.15f)/.85f);}
    private int color(NewHudSettings.Part part){return HudPaint.tint(PrismDraw.withAlpha(style.color(part),contentAlpha()));}
    private int size(NewHudSettings.Part part){return Math.max(4,Math.round(style.size(part)));}
    private HudFont font(NewHudSettings.Part part,boolean bold){return HudFont.of(size(part),bold?FontType.MANROPE_BOLD:FontType.MANROPE);}
    private float textWidth(String text,NewHudSettings.Part part,boolean bold){return font(part,bold).getStringWidth(text);}
    private void text(GuiGraphics g,String text,float px,float top,float h,NewHudSettings.Part part,boolean bold){var font=font(part,bold);font.drawString(g,text,px,font.centeredY(top,h),color(part));}
    private int noticeTextSize(){return (int)Math.max(4,Math.round(style.size(TEXT)*settings().notificationTextScale.getFloat()));}
    private HudFont noticeFont(){return HudFont.of(noticeTextSize(),FontType.MANROPE);}
    private float noticeTextWidth(String text){return noticeFont().getStringWidth(text);}
    private void noticeText(GuiGraphics g,String text,float px,float top,float h){var font=noticeFont();font.drawString(g,text,px,font.centeredY(top,h),color(TEXT));}
    private void icon(GuiGraphics g,String key,float px,float py,NewHudSettings.Part part){NewHudIcons.draw(g,key,px,py,style.size(part),PrismDraw.withAlpha(style.color(part),contentAlpha()));}
    private void rect(GuiGraphics g,float px,float py,float w,float h,float radius,int color){if(w>0&&h>0)NewHudDraw.roundedRect(g,px,py,w,h,radius,color);}
    private void divider(GuiGraphics g,float px,float py,float w,float h){rect(g,px,py,w,h,Math.min(w,h)/2,HudPaint.tint(style.separator.getValue().getRGB()));}
    private void bounds(float w,float h){animatedWidth=animatedWidth==0?w:approach(animatedWidth,w);animatedHeight=animatedHeight==0?h:approach(animatedHeight,h);setBounds(x,y,animatedWidth,animatedHeight);resolvePosition();}
    private void endPanel(GuiGraphics g){if(panelClip){PrismDraw.flush(g);g.disableScissor();panelClip=false;}}
    private void clip(GuiGraphics g,float px,float py,float w,float h){
        PrismDraw.flush(g);
        float s=renderScale;
        int left=(int)Math.ceil(px*s),top=(int)Math.ceil(py*s);
        int right=(int)Math.floor((px+w)*s),bottom=(int)Math.floor((py+h)*s);
        if(right<=left||bottom<=top){g.enableScissor(left,top,left,top);return;}
        g.enableScissor(left,top,right,bottom);
    }
    private static float smooth(float value){float t=Mth.clamp(value,0,1);return t*t*(3-2*t);}
    private void panel(GuiGraphics g,float px,float py,float w,float h){
        endPanel(g);
        PrismDraw.flush(g);g.nextStratum();
        if(settings().blur.getValue()){
            NewHudDraw.blurPanel(g,px,py,w,h,5,
                    PrismDraw.withAlpha(0xFFFFFFFF,style.opacity.getFloat()/100*rowAlpha));
        }
        PrismDraw.flush(g);g.nextStratum();
        rect(g,px,py,w,h,5,HudPaint.tint(PrismDraw.withAlpha(style.background.getValue().getRGB(),style.opacity.getFloat()/100*rowAlpha)));
        if(chatOpen()&&settings().shimmer.getValue()){
            float wave=(float)(.5+.5*Math.sin(System.currentTimeMillis()/1700.0));
            rect(g,px+.5f,py+.5f,w-1,h-1,4.5f,HudPaint.tint(PrismDraw.withAlpha(style.color(ICON),(.025f+.045f*wave)*rowAlpha)));
        }
        PrismDraw.flush(g);g.nextStratum();
        clip(g,px,py,w,h);panelClip=true;
    }
    private float headerHeightNew(){return Math.max(22,Math.max(style.size(TITLE),style.size(HEADER_ICON))+10);}
    private String title(){return switch(kind){case INVENTORY->HudLocalization.ui("Inventory","Инвентарь","Інвентар");default->HudLocalization.title(name);};}
    private String headerIcon(){return switch(kind){case EFFECTS->"bubbles";case COOLDOWNS->"cycle";case BINDS->"keyboard";case STAFF->"users";case INVENTORY->"bag";default->"spark";};}
    private void header(GuiGraphics g,int count){
        float h=headerHeightNew(),is=style.size(HEADER_ICON);icon(g,headerIcon(),x+7,y+(h-is)/2,HEADER_ICON);text(g,title(),x+12+is,y,h,TITLE,true);
        divider(g,x+6,y+h-.5f,width-12,.5f);
    }
    private void list(GuiGraphics g){
        float rowHeight=Math.max(kind==NewHudSettings.Kind.STAFF?17:18,Math.max(style.size(TEXT),style.size(VALUE))+8),is=style.size(ICON);
        if(kind==NewHudSettings.Kind.STAFF){is=style.portraitSize.getFloat();for(Row r:rows.values())rowHeight=Math.max(rowHeight,style.size(TEXT)+style.size(r.status)+6);}
        rowHeight=Math.max(rowHeight,is+6);
        float natural=kind==NewHudSettings.Kind.EFFECTS?128:kind==NewHudSettings.Kind.COOLDOWNS?132:kind==NewHudSettings.Kind.STAFF?104:90;
        float count=0;int active=0;
        for(Row r:rows.values()){natural=Math.max(natural,kind==NewHudSettings.Kind.STAFF?is+14+Math.max(textWidth(r.label,TEXT,false)+(r.rank.isEmpty()?0:textWidth(r.rank,VALUE,false)+8),textWidth(r.value,r.status,false)):(kind==NewHudSettings.Kind.BINDS?36:kind==NewHudSettings.Kind.EFFECTS?30+is:18+is)+textWidth(r.label,TEXT,false)+textWidth(r.value,VALUE,false));count+=r.alpha;if(r.present)active++;}
        if(rows.isEmpty()&&!chatOpen()){setBounds(x,y,0,0);animatedHeight=0;return;}
        natural=Math.max(natural,22+style.size(HEADER_ICON)+textWidth(title(),TITLE,true));
        bounds(Math.max(natural,natural*style.width.getFloat()),headerHeightNew()+4+rowHeight*count+3);panel(g,x,y,width,height);header(g,active);
        float cy=y+headerHeightNew()+3;
        for(Row r:rows.values()){
            if(r.alpha<.01f)continue;
            float slot=rowHeight*r.alpha;clip(g,x+3,cy,width-6,Math.max(0,Math.min(slot,y+height-3-cy)));
            try {
            rowAlpha=smooth((r.alpha-.3f)/.7f);float yy=cy+2*(1-r.alpha),valueX=x+width-textWidth(r.value,kind==NewHudSettings.Kind.STAFF?r.status:VALUE,false)-6;
            if(kind==NewHudSettings.Kind.BINDS){
                float chipW=Math.max(17,textWidth(r.value,VALUE,false)+8);valueX=x+width-chipW-6;
                rect(g,x+5,yy+rowHeight/2-3,1,6,.5f,color(ICON));text(g,r.label,x+10,yy,rowHeight,TEXT,false);
                rect(g,valueX,yy+2,chipW,rowHeight-4,3,HudPaint.tint(PrismDraw.withAlpha(style.color(ICON),.09f*rowAlpha)));
                float keyW=textWidth(r.value,VALUE,false);text(g,r.value,valueX+(chipW-keyW)/2,yy,rowHeight-3,VALUE,false);
                rect(g,valueX+(chipW-Math.min(keyW+2,chipW-6))/2,yy+rowHeight-5,Math.min(keyW+2,chipW-6),1,.5f,color(ICON));
            }else if(kind==NewHudSettings.Kind.STAFF){
                face(g,r.skin,x+5,yy+(rowHeight-is)/2,is);
                float line=(rowHeight-3)/2; text(g,r.label,x+9+is,yy+1,line,TEXT,false);text(g,r.value,x+9+is,yy+line+1,line,r.status,false);
                if(!r.rank.isEmpty())text(g,r.rank,x+width-6-textWidth(r.rank,VALUE,false),yy+1,line,VALUE,false);
            }else{
                float textH=rowHeight-4,ix=x+5,iy=yy+(textH-is)/2;
                if(r.effect!=null){if(rowAlpha>.8f)effectIcon(g,r.effect,ix,iy,is);}
                else if(!r.item.isEmpty()){if(rowAlpha>.8f)drawItem(g,r.item,ix,iy,is);}
                else icon(g,"clock",ix,iy,ICON);
                text(g,r.label,x+9+is,yy,textH,TEXT,false);
                r.number.draw(g,r.value,valueX,font(VALUE,false).centeredY(yy,textH),font(VALUE,false),PrismDraw.withAlpha(style.color(VALUE),contentAlpha()),dt);
                float bx=x+9+is,bw=width-(bx-x)-6;
                rect(g,bx,yy+rowHeight-3,bw,.6f,.3f,HudPaint.tint(PrismDraw.withAlpha(style.color(ACCENT),.07f)));
                rect(g,bx,yy+rowHeight-3,bw*Mth.clamp(r.shownRatio,0,1),Math.max(.5f,style.size(ACCENT)),.4f,color(ACCENT));
            }
            } finally {PrismDraw.flush(g);g.disableScissor();}cy+=slot;
        }
    }
    private void target(GuiGraphics g){
        if(target==null||targetAlpha<.01f){setBounds(x,y,0,0);return;}
        String label=target instanceof net.minecraft.world.entity.player.Player player?player.getGameProfile().name():target.getName().getString();
        if(!targetName.equals(label)){targetName=label;targetNameAt=System.currentTimeMillis();}
        String shortName=label.substring(0,label.offsetByCodePoints(0,Math.min(11,label.codePointCount(0,label.length()))));
        String health=NewHudDraw.fmt(hp,1),maximum="/ "+NewHudDraw.fmt(target.getMaxHealth(),0);
        ItemStack[] armor={target.getItemBySlot(EquipmentSlot.HEAD),target.getItemBySlot(EquipmentSlot.CHEST),target.getItemBySlot(EquipmentSlot.LEGS),target.getItemBySlot(EquipmentSlot.FEET)},hands={target.getMainHandItem(),target.getOffhandItem()};
        float padX=7,padY=5,face=style.portraitSize.getFloat(),ic=style.size(ICON);
        float nameH=style.size(TEXT)+2,healthH=Math.max(ic*1.09f,style.size(VALUE)),block=nameH+2+healthH,body=Math.max(face,block);
        // Held items sit right-aligned on the name line and armor on the health line, so equipment never adds a row.
        float handCell=Math.min(nameH+1,style.equipmentSize.getFloat()*.8f),armorCell=Math.min(healthH+1,style.equipmentSize.getFloat()*.8f);
        float handsW=strip(hands,handCell),armorW=strip(armor,armorCell);
        float column=Math.max(textWidth(shortName,TEXT,true)+handsW,ic+6+textWidth(health,VALUE,true)+textWidth(maximum,MAXIMUM,false)+armorW);
        float w=Math.max(130,face+8+column+padX*2);w=Math.max(w,w*style.width.getFloat());
        float barH=Math.max(3,style.size(ACCENT)+2),goldH=Math.max(1,style.size(GOLD)),goldShow=Mth.clamp(gold,0,1);
        bounds(w,padY+body+4+barH+goldShow*(goldH+2)+padY);HudPaint.multiplyElement(targetAlpha);panel(g,x,y,width,height);
        float inner=width-padX*2,top=y+padY,textTop=top+(body-block)/2,tx=x+padX+face+8,right=x+width-padX,nameW=Math.max(0,right-handsW-tx);
        PlayerSkin skin=target instanceof AbstractClientPlayer player?player.getSkin():null;face(g,skin,x+padX,top+(body-face)/2,face);
        scrolling(g,label,tx,textTop,nameW,nameH,TEXT,true,targetNameAt);
        float pulse=1+.09f*(float)Math.pow(Math.max(0,Math.sin(System.currentTimeMillis()/190.0)),6),heartSize=ic*pulse,healthY=textTop+nameH+2;
        NewHudIcons.draw(g,"heart-pulse",tx+(ic-heartSize)/2,healthY+(healthH-heartSize)/2,heartSize,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));
        healthNumber.draw(g,health,tx+ic+3,font(VALUE,true).centeredY(healthY,healthH),font(VALUE,true),PrismDraw.withAlpha(style.color(VALUE),contentAlpha()),dt);
        text(g,maximum,tx+ic+6+textWidth(health,VALUE,true),healthY,healthH,MAXIMUM,false);
        drawStrip(g,hands,right,textTop+(nameH-handCell)/2,handCell);
        drawStrip(g,armor,right,healthY+(healthH-armorCell)/2,armorCell);
        float barY=top+body+4;rect(g,x+padX,barY,inner,barH,barH/2,HudPaint.tint(PrismDraw.withAlpha(style.color(ACCENT),.08f)));
        rect(g,x+padX,barY,inner*Mth.clamp(hp/Math.max(1,target.getMaxHealth()),0,1),barH,barH/2,color(ACCENT));
        if(gold>.01f)rect(g,x+padX,barY+barH+2,inner*Mth.clamp(gold/Math.max(1,target.getMaxHealth()),0,1),goldH,goldH/2,color(GOLD));
    }
    /** Width of a right-aligned item strip (empty slots skipped), including its gap to the text. */
    private static float strip(ItemStack[] stacks,float cell){int n=0;for(ItemStack s:stacks)if(!s.isEmpty())n++;return n==0?0:n*(cell+1.5f)+4;}
    private void drawStrip(GuiGraphics g,ItemStack[] stacks,float right,float py,float cell){float px=right-cell;for(int i=stacks.length-1;i>=0;i--)if(!stacks[i].isEmpty()){drawItem(g,stacks[i],px,py,cell,false);px-=cell+1.5f;}
    }
    private void effectIcon(GuiGraphics g,Identifier sprite,float px,float py,float size){if(HudPaint.opacity()<.75f)return;NewHudIcons.glow(g,px,py,size,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));g.pose().pushMatrix();try{g.pose().translate(px,py);g.pose().scale(size/18,size/18);g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,sprite,0,0,18,18);}finally{g.pose().popMatrix();}}
    private void drawItem(GuiGraphics g,ItemStack stack,float px,float py,float size){drawItem(g,stack,px,py,size,true);}
    private void drawItem(GuiGraphics g,ItemStack stack,float px,float py,float size,boolean glow){if(HudPaint.opacity()<.75f)return;if(glow)NewHudIcons.glow(g,px,py,size,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));g.pose().pushMatrix();try{g.pose().translate(px,py);g.pose().scale(size/16,size/16);g.renderItem(stack,0,0);}finally{g.pose().popMatrix();}}
    private void face(GuiGraphics g,PlayerSkin skin,float px,float py,float size){
        if(skin==null){rect(g,px,py,size,size,size/2,HudPaint.tint(0xFF272B3C));icon(g,"users",px+(size-style.size(ICON))/2,py+(size-style.size(ICON))/2,ICON);return;}
        NewHudIcons.glow(g,px,py,size,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));
        var texture=skin.body().texturePath();int white=HudPaint.tint(PrismDraw.withAlpha(0xFFFFFFFF,contentAlpha()));
        NewHudDraw.imageRegion(g,px,py,size,size,texture,white,size/2,.125f,.125f,.25f,.25f,false,false);
        NewHudDraw.imageRegion(g,px,py,size,size,texture,white,size/2,.625f,.125f,.75f,.25f,false,false);
    }
    private void inventory(GuiGraphics g){
        if(java.util.stream.IntStream.range(9,36).allMatch(i->MC.player.getInventory().getItem(i).isEmpty()))return;
        float cell=Math.max(14,style.equipmentSize.getFloat()+3),w=cell*9+10,h=headerHeightNew()+cell*3+7;bounds(Math.max(w,w*style.width.getFloat()),h);panel(g,x,y,width,height);header(g,-1);
        float actual=(width-12)/9,top=y+headerHeightNew()+4;
        for(int i=0;i<27;i++){
            float ix=x+6+(i%9)*actual,iy=top+(i/9)*cell;rect(g,ix,iy,actual-2,cell-2,2,HudPaint.tint(0x0EADB8EA));
            float age=(System.currentTimeMillis()-arrived[i])/(settings().highlightSeconds.getFloat()*1000);
            if(arrived[i]>0&&age<1)rect(g,ix,iy,actual-2,cell-2,2,HudPaint.tint(PrismDraw.withAlpha(style.color(HIGHLIGHT),.72f*smooth(age*settings().highlightSeconds.getFloat()/.12f)*(1-smooth(age)))));
            ItemStack stack=MC.player.getInventory().getItem(i+9);if(stack.isEmpty())continue;
            float is=Math.min(style.equipmentSize.getFloat(),cell-4);drawItem(g,stack,ix+(actual-2-is)/2,iy+(cell-2-is)/2,is,false);
            if(stack.getCount()>1){String count=Integer.toString(stack.getCount());text(g,count,ix+actual-3-textWidth(count,VALUE,false),iy+cell-size(VALUE)-4,size(VALUE)+2,VALUE,false);}
        }
    }
    private void information(GuiGraphics g){
        String[] values=kind==NewHudSettings.Kind.SPEED?new String[]{NewHudDraw.fmt(speed,2),"BPS"}:new String[]{"X",Integer.toString(Mth.floor(MC.player.getX())),"Y",Integer.toString(Mth.floor(MC.player.getY())),"Z",Integer.toString(Mth.floor(MC.player.getZ()))};
        float is=style.size(ICON),h=Math.max(19,Math.max(is,Math.max(style.size(TEXT),style.size(VALUE)))+9),w=12+is;
        for(int i=0;i<values.length;i++){var part=kind==NewHudSettings.Kind.SPEED?(i==0?VALUE:TEXT):(i%2==0?TEXT:VALUE);w+=textWidth(values[i],part,false)+(i==0?6:5);}
        bounds(Math.max(w,w*style.width.getFloat()),h);panel(g,x,y,width,height);icon(g,kind==NewHudSettings.Kind.SPEED?"speed":"pin",x+6,y+(h-is)/2,ICON);float px=x+is+12;
        for(int i=0;i<values.length;i++){var part=kind==NewHudSettings.Kind.SPEED?(i==0?VALUE:TEXT):(i%2==0?TEXT:VALUE);text(g,values[i],px,y,h,part,false);px+=textWidth(values[i],part,false)+5;}
    }
    private void armor(GuiGraphics g){
        EquipmentSlot[] slots={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};boolean percent=settings().armorMode.is(NewHudSettings.ARMOR_PERCENT);
        float is=style.equipmentSize.getFloat(),textH=style.size(VALUE)+2,cell=percent?Math.max(is,textWidth("100%",VALUE,false))+3:is+3,h=is+(percent?textH+9:12);bounds(Math.max(cell*4+7,(cell*4+7)*style.width.getFloat()),h);panel(g,x,y,width,height);
        for(int i=0;i<4;i++){ItemStack stack=MC.player.getItemBySlot(slots[i]);if(stack.isEmpty())continue;float ix=x+5+i*cell;float ratio=stack.isDamageableItem()?1-stack.getDamageValue()/(float)Math.max(1,stack.getMaxDamage()):1;
            if(percent){drawItem(g,stack,ix+(cell-3-is)/2,y+4,is);String value=Math.round(ratio*100)+"%";text(g,value,ix+(cell-3-textWidth(value,VALUE,false))/2,y+is+5,textH,VALUE,false);}
            else{drawItem(g,stack,ix,y+4,is);rect(g,ix,y+is+7,is*Mth.clamp(ratio,0,1),Math.max(.6f,style.size(ACCENT)),.3f,color(ACCENT));}}
    }
    private void watermark(GuiGraphics g){
        var profile=releon.ru.utils.media.DiscordHudProfile.current();
        String server=MC.getCurrentServer()==null?HudLocalization.ui("Singleplayer","Одиночная игра","Одиночна гра"):MC.getCurrentServer().ip;
        int ping=NewHudRenderer.ping();
        String[] labels={"include",server,profile==null?"Discord":profile.name(),MC.getFps()+" FPS",ping+" MS"};String[] icons={"logo","pin","discord","fps","signal"};
        NewHudSettings.Part[] parts={TEXT,SERVER,PROFILE,FPS,PING};
        float is=style.size(ICON),h=Math.max(20,is+8),w=10;for(int i=0;i<labels.length;i++){w+=is+3+textWidth(labels[i],parts[i],i==0)+(i<labels.length-1?8:0);h=Math.max(h,style.size(parts[i])+8);}bounds(Math.max(w,w*style.width.getFloat()),h);panel(g,x,y,width,height);
        float px=x+5;for(int i=0;i<labels.length;i++){
            if(i>0)divider(g,px-4,y+5,.65f,h-10);
            if(i==2&&profile!=null&&profile.avatar()!=null){NewHudIcons.glow(g,px,y+(h-is)/2,is,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));NewHudDraw.imageRegion(g,px,y+(h-is)/2,is,is,profile.avatar(),HudPaint.tint(0xFFFFFFFF),is/2,0,0,1,1,false,false);}
            else if(i==0){NewHudIcons.glow(g,px,y+(h-is)/2,is,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));NewHudIcons.draw(g,"logo",px,y+(h-is)/2,is,color(ICON));}
            else icon(g,icons[i],px,y+(h-is)/2,ICON);
            if(i>=3)(i==3?fpsNumber:pingNumber).draw(g,labels[i],px+is+3,font(parts[i],false).centeredY(y,h),font(parts[i],false),PrismDraw.withAlpha(style.color(parts[i]),contentAlpha()),dt);
            else text(g,labels[i],px+is+3,y,h,parts[i],i==0);px+=is+3+textWidth(labels[i],parts[i],i==0)+8;
        }
    }
    private void keys(GuiGraphics g){
        float fs=style.size(TEXT),cell=Math.max(17,fs+10),h=cell+27,w=cell*4+12;bounds(Math.max(w,w*style.width.getFloat()),h);panel(g,x,y,width,height);
        var options=MC.options;boolean[] pressed={options.keyLeft.isDown(),options.keyUp.isDown(),options.keyDown.isDown(),options.keyRight.isDown()};String[] labels={"A","W","S","D"};
        for(int i=0;i<4;i++){keyGlow[i]=approach(keyGlow[i],pressed[i]?1:0);float px=x+5+i*cell;rect(g,px,y+5,cell-3,cell-4,3,HudPaint.tint(PrismDraw.withAlpha(style.color(ICON),.035f+.195f*keyGlow[i])));text(g,labels[i],px+(cell-3-textWidth(labels[i],TEXT,false))/2,y+5,cell-4,TEXT,false);}
        keyGlow[4]=approach(keyGlow[4],options.keyJump.isDown()?1:0);rect(g,x+6,y+cell+4,width-12,8,2,HudPaint.tint(PrismDraw.withAlpha(style.color(ICON),.035f+.165f*keyGlow[4])));text(g,"SPACE",x+(width-textWidth("SPACE",VALUE,false))/2,y+cell+4,8,VALUE,false);
        String clicks=NewHudInput.clicks(true)+" CPS    "+NewHudInput.clicks(false)+" CPS";
        text(g,clicks,x+(width-textWidth(clicks,VALUE,false))/2,y+cell+14,9,VALUE,false);
    }
    private void notifications(GuiGraphics g){
        long now=System.currentTimeMillis();float duration=settings().notificationSeconds.getFloat()*1000;
        var active=NewNotices.getActive();
        for(var entry:active)if(now-entry.startTime<Math.min(duration,entry.durationMs))notices.computeIfAbsent(entry,key->new Notice());
        var iterator=notices.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();boolean alive=active.contains(entry.getKey())&&now-entry.getKey().startTime<Math.min(duration,entry.getKey().durationMs);
            entry.getValue().alpha=approach(entry.getValue().alpha,alive?1:0);
            if(!alive&&entry.getValue().alpha<.01f)iterator.remove();
        }
        List<NewNotices.Entry> entries=notices.keySet().stream().sorted(Comparator.comparingLong(e->e.startTime)).toList();
        if(entries.isEmpty()){if(chatOpen()){bounds(90,22);panel(g,x,y,width,height);text(g,HudLocalization.ui("Notifications","Уведомления","Сповіщення"),x+6,y,22,TEXT,false);}else{setBounds(x,y,0,0);animatedHeight=0;}return;}
        float iconSize=Math.max(11,style.size(ICON)),lineHeight=Math.max(10,noticeTextSize()+3),w=86,total=0;
        for(var entry:entries){
            var status=noticeStatus(entry);float badge=entry.isCustom()?0:textWidth(entry.enabled?"ON":"OFF",status,true)+9;
            float need=noticeWidth(entry)+iconSize+badge+18;
            // Item-named notices stay on one line, so the card grows to the full text (up to the screen) instead of clipping it
            w=Math.max(w,entry.richName()!=null?Math.min(need,MC.getWindow().getGuiScaledWidth()-24):Math.min(176*style.width.getFloat()*settings().notificationWidth.getFloat(),need));
        }
        for(var entry:entries){
            Notice state=notices.get(entry);float badge=entry.isCustom()?0:textWidth(entry.enabled?"ON":"OFF",noticeStatus(entry),true)+9,max=Math.max(25,w-iconSize-badge-18);
            // Wrapping measures per code point, so it is redone only when the width or text size changes.
            // Rewrapped on a width, size or text change (cooldown countdowns); an item name in a server font stays on one line
            String label=noticeLabel(entry);
            if(state.lines==null||state.wrapWidth!=max||state.wrapSize!=noticeTextSize()||!label.equals(state.wrapped)){state.lines=entry.richName()!=null?List.of(label):wrapNotice(label,max);state.wrapWidth=max;state.wrapSize=noticeTextSize();state.wrapped=label;}
            total+=(Math.max(iconSize+10,state.lines.size()*lineHeight+10)+3)*state.alpha;
        }
        bounds(w,Math.max(1,total-3));
        float offset=0;
        for(var entry:entries){
            Notice state=notices.get(entry);float h=Math.max(iconSize+10,state.lines.size()*lineHeight+10);
            if(!state.positioned){state.y=offset;state.positioned=true;}else state.y=approach(state.y,offset);
            float cy=y+state.y,nx=x+(1-smooth(state.alpha))*7,life=Mth.clamp(1-(now-entry.startTime)/Math.min(duration,entry.durationMs),0,1);
            rowAlpha=state.alpha;panel(g,nx,cy,width,h);rowAlpha=smooth((state.alpha-.2f)/.8f);
            var status=noticeStatus(entry);
            if(!entry.itemStack.isEmpty()){if(rowAlpha>.75f)drawItem(g,entry.itemStack,nx+5,cy+(h-iconSize)/2,iconSize);}
            else if(entry.effectTexture!=null){if(rowAlpha>.75f)effectIcon(g,entry.effectTexture,nx+5,cy+(h-iconSize)/2,iconSize);}
            else NewHudIcons.draw(g,entry.isCustom()?switch(entry.type){case ERROR,WARNING->"clock";case SUCCESS->"spark";default->"bubbles";}:entry.enabled?"power":"power-off",nx+5,cy+(h-style.size(ICON))/2,style.size(ICON),color(status));
            float textY=cy+(h-state.lines.size()*lineHeight)/2;
            var rich=entry.richName();
            for(String line:state.lines){if(rich!=null)richLine(g,line,rich,nx+iconSize+9,textY,lineHeight);else noticeText(g,line,nx+iconSize+9,textY,lineHeight);textY+=lineHeight;}
            if(!entry.isCustom()){String value=entry.enabled?"ON":"OFF";text(g,value,nx+width-textWidth(value,status,true)-6,cy,h,status,true);}
            offset+=(h+3)*state.alpha;
        }
    }
    private NewHudSettings.Part noticeStatus(NewNotices.Entry entry){return entry.isCustom()?switch(entry.type){case ERROR->NEGATIVE;case WARNING->AWAY;case SUCCESS->POSITIVE;default->ICON;}:entry.enabled?POSITIVE:NEGATIVE;}
    // The item name keeps its colours and obfuscated (animated) parts in the HUD font: obfuscated characters are re-rolled every frame
    private static final String OBFUSCATED="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private float noticeWidth(NewNotices.Entry entry){return noticeTextWidth(noticeLabel(entry));}
    private void richLine(GuiGraphics g,String line,net.minecraft.network.chat.Component name,float px,float top,float h){
        String plain=name.getString().replaceAll("§.","");int i=line.indexOf(plain);
        if(i<0){noticeText(g,line,px,top,h);return;}
        String before=line.substring(0,i);noticeText(g,before,px,top,h);
        var font=noticeFont();int base=color(TEXT);float[] x={px+noticeTextWidth(before)};float y=font.centeredY(top,h);
        name.visit((style,part)->{
            String s=part.replaceAll("§.","");
            if(style.isObfuscated()){StringBuilder b=new StringBuilder();var r=java.util.concurrent.ThreadLocalRandom.current();for(int k=0;k<s.length();k++)b.append(s.charAt(k)==' '?' ':OBFUSCATED.charAt(r.nextInt(OBFUSCATED.length())));s=b.toString();}
            int c=style.getColor()!=null?(base&0xFF000000)|(style.getColor().getValue()&0xFFFFFF):base;
            font.drawString(g,s,x[0],y,c);x[0]+=font.getStringWidth(s);return java.util.Optional.empty();
        },net.minecraft.network.chat.Style.EMPTY);
        noticeText(g,line.substring(i+plain.length()),x[0],top,h);
    }
    private static String noticeLabel(NewNotices.Entry entry){return (entry.isCustom()?entry.text():entry.moduleName).replaceAll("§.","");}
    private List<String> wrapNotice(String value,float maxWidth){
        List<String> result=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(int code:value.codePoints().toArray()){String next=new String(Character.toChars(code));if(code=='\n'||line.length()>0&&noticeTextWidth(line+next)>maxWidth){result.add(line.toString());line.setLength(0);if(code=='\n')continue;}line.append(next);}
        if(!line.isEmpty()||result.isEmpty())result.add(line.toString());return result;
    }
    private static final String[] MEDIA_COMMANDS={"prev","toggle","next"};
    /** prev / toggle / next hit boxes as x,y,w,h in element space, filled while drawn. */
    private final float[] mediaButtons=new float[12];
    private float mediaProgress;
    private String mediaTitle="";
    private long mediaTitleAt;
    @Override public boolean onClick(float mx,float my){
        if(kind!=NewHudSettings.Kind.MUSIC||width<=0)return false;
        for(int i=0;i<3;i++){int b=i*4;if(mediaButtons[b+2]>0&&mx>=mediaButtons[b]&&mx<=mediaButtons[b]+mediaButtons[b+2]&&my>=mediaButtons[b+1]&&my<=mediaButtons[b+1]+mediaButtons[b+3]){releon.ru.utils.media.MediaSession.command(MEDIA_COMMANDS[i]);return true;}}
        return false;
    }
    private void music(GuiGraphics g){
        var track=releon.ru.utils.media.MediaSession.current();
        Arrays.fill(mediaButtons,0);
        if(track==null&&!chatOpen()){setBounds(x,y,0,0);animatedHeight=0;return;}
        long now=System.currentTimeMillis(),position=track==null?0:track.position(now),duration=track==null?0:track.duration();
        String title=track==null?HudLocalization.ui("Nothing playing","Ничего не играет","Нічого не грає"):track.title(),artist=track==null?"":track.artist();
        if(!title.equals(mediaTitle)){mediaTitle=title;mediaTitleAt=now;}
        float pad=6,art=30,ctrl=Math.max(10,style.size(ICON)+2),timeH=size(VALUE)+2;
        float w=Math.max(150,150*style.width.getFloat());
        bounds(w,pad+art+5+ctrl+4+timeH+pad);panel(g,x,y,width,height);
        float ax=x+pad,ay=y+pad;var cover=releon.ru.utils.media.MediaSession.cover();
        if(track!=null&&cover!=null){NewHudIcons.glow(g,ax,ay,art,PrismDraw.withAlpha(style.color(ICON),contentAlpha()));NewHudDraw.imageRegion(g,ax,ay,art,art,cover,HudPaint.tint(PrismDraw.withAlpha(0xFFFFFFFF,contentAlpha())),5,0,0,1,1,false,false);}
        else{rect(g,ax,ay,art,art,5,HudPaint.tint(PrismDraw.withAlpha(style.color(ICON),.12f*contentAlpha())));float is=style.size(ICON);icon(g,"spark",ax+(art-is)/2,ay+(art-is)/2,ICON);}
        float tx=ax+art+7,tw=Math.max(0,x+width-pad-tx),titleH=style.size(TEXT)+2,artistH=artist.isEmpty()?0:style.size(VALUE)+2,ty=ay+(art-titleH-artistH)/2;
        scrolling(g,title,tx,ty,tw,titleH,TEXT,true,mediaTitleAt);
        if(!artist.isEmpty())scrolling(g,artist,tx,ty+titleH,tw,artistH,VALUE,false,mediaTitleAt);
        // Phobia media glyphs; the centre one flips between play and pause.
        float cy=ay+art+5+ctrl/2,side=ctrl*.62f,mid=ctrl*.6f,gap=ctrl*.95f,cx=x+width/2;
        float[] lefts={cx-mid/2-gap-side,cx-mid/2,cx+mid/2+gap};float[] widths={side,mid,side};String[] glyphs={"T",track!=null&&track.playing()?"U":"A","S"};
        for(int i=0;i<3;i++){
            NewHudDraw.mediaIcon(g,glyphs[i],lefts[i],cy,widths[i],color(i==1?ICON:VALUE));
            int b=i*4;mediaButtons[b]=lefts[i]-3;mediaButtons[b+1]=cy-ctrl/2-1;mediaButtons[b+2]=widths[i]+6;mediaButtons[b+3]=ctrl+2;
        }
        float ry=cy+ctrl/2+4;String passed=clock((int)(position/1000)),total=duration>0?clock((int)(duration/1000)):"--:--";
        text(g,passed,x+pad,ry,timeH,VALUE,false);text(g,total,x+width-pad-textWidth(total,VALUE,false),ry,timeH,VALUE,false);
        mediaProgress=approach(mediaProgress,duration>0?Mth.clamp(position/(float)duration,0,1):0);
        float bx=x+pad+textWidth(passed,VALUE,false)+6,bw=Math.max(0,x+width-pad-textWidth(total,VALUE,false)-6-bx),barH=Math.max(2,style.size(ACCENT)+1),barY=ry+(timeH-barH)/2;
        rect(g,bx,barY,bw,barH,barH/2,HudPaint.tint(PrismDraw.withAlpha(style.color(ACCENT),.1f*contentAlpha())));
        rect(g,bx,barY,bw*mediaProgress,barH,barH/2,color(ACCENT));
    }
    /** Clipped single line that scrolls back and forth (pausing at both ends) when wider than the viewport. */
    private void scrolling(GuiGraphics g,String value,float px,float top,float w,float h,NewHudSettings.Part part,boolean bold,long since){
        clip(g,px,top-2,w,h+4);
        try {
            float overflow=Math.max(0,textWidth(value,part,bold)-w),scroll=0;
            if(overflow>0){float travel=overflow/18,phase=(System.currentTimeMillis()-since)/1000f%(2*travel+2.4f);scroll=phase<1.2f?0:phase<1.2f+travel?(phase-1.2f)*18:phase<2.4f+travel?overflow:Math.max(0,overflow-(phase-2.4f-travel)*18);}
            text(g,value,px-scroll,top,h,part,bold);
        } finally {PrismDraw.flush(g);g.disableScissor();}
    }
    private static String clock(int seconds){return seconds/60+":"+(seconds%60<10?"0":"")+seconds%60;}
    private static String roman(int value){return switch(value){case 1->"I";case 2->"II";case 3->"III";case 4->"IV";case 5->"V";default->Integer.toString(value);};}
}
