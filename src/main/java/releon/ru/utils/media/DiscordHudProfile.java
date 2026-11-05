package releon.ru.utils.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Reads only Discord's local READY profile, without OAuth or changing Rich Presence. */
public final class DiscordHudProfile {
    private static final String APPLICATION_ID="1559954186240528447";
    public record Profile(String name,Identifier avatar) {}
    private static volatile Profile profile;
    private static String avatarKey="";
    private static volatile RandomAccessFile pipe;
    private static volatile boolean pending;
    private static long requestedAt;
    private static int generation;
    private static final ScheduledExecutorService TIMEOUT=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Include-DiscordProfile-Timeout");t.setDaemon(true);return t;});
    private DiscordHudProfile(){}
    public static Profile current(){return profile;}
    public static synchronized void request(){
        long now=System.currentTimeMillis();
        // Discord ignores a second handshake from this process while Rich Presence is connected; that connection supplies READY via accept().
        if(pending||now-requestedAt<15000||!System.getProperty("os.name","").startsWith("Windows"))return;
        requestedAt=now;pending=true;int ticket=generation;
        Thread worker=new Thread(()->read(ticket),"Include-DiscordProfile");worker.setDaemon(true);worker.start();
    }
    private static void read(int ticket){
        ScheduledFuture<?> deadline=null;
        try{
            RandomAccessFile connection=null;
            for(int i=0;i<10&&connection==null;i++){
                try{connection=new RandomAccessFile("\\\\.\\pipe\\discord-ipc-"+i,"rw");}catch(FileNotFoundException unavailable){/* Try the next documented IPC endpoint. */}
            }
            if(connection==null){profile=null;return;}
            final RandomAccessFile active=connection;
            synchronized(DiscordHudProfile.class){if(ticket!=generation){active.close();return;}pipe=active;}
            deadline=TIMEOUT.schedule(()->close(active),4,TimeUnit.SECONDS);
            byte[] body=("{\"v\":1,\"client_id\":\""+APPLICATION_ID+"\"}").getBytes(StandardCharsets.UTF_8);
            active.write(ByteBuffer.allocate(body.length+8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(body.length).put(body).array());
            byte[] header=new byte[8];active.readFully(header);ByteBuffer frame=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);int opcode=frame.getInt(),length=frame.getInt();
            if(opcode!=1||length<2||length>262144)throw new IOException("Invalid Discord READY frame");
            byte[] payload=new byte[length];active.readFully(payload);
            apply(new String(payload,StandardCharsets.UTF_8),ticket);
        }catch(Exception failure){LoggerFactory.getLogger("Include/DiscordHUD").debug("Discord profile unavailable: {}",failure.toString());}
        finally{if(deadline!=null)deadline.cancel(false);RandomAccessFile active=pipe;pipe=null;if(active!=null)close(active);synchronized(DiscordHudProfile.class){pending=false;}}
    }
    /** READY payload received by the Rich Presence connection; the avatar download runs off its I/O thread. */
    static void accept(String json){
        int ticket=generation;
        Thread worker=new Thread(()->{try{apply(json,ticket);}catch(Exception failure){LoggerFactory.getLogger("Include/DiscordHUD").debug("Discord profile unavailable: {}",failure.toString());}},"Include-DiscordProfile");
        worker.setDaemon(true);worker.start();
    }
    private static void apply(String json,int ticket) throws Exception{
        {
            JsonObject data=JsonParser.parseString(json).getAsJsonObject();
            if(!"READY".equals(string(data,"evt")))throw new IOException("Discord did not return READY");
            JsonObject user=data.getAsJsonObject("data").getAsJsonObject("user");
            String id=string(user,"id"),name=string(user,"global_name");if(name.isEmpty())name=string(user,"username");
            if(name.isEmpty()||!id.matches("[0-9]+"))throw new IOException("Discord profile is incomplete");
            String avatar=string(user,"avatar"),display=name;
            if(ticket!=generation)return;
            if(profile!=null&&(id+avatar).equals(avatarKey)){profile=new Profile(display,profile.avatar());return;}
            profile=new Profile(display,null);
            if(avatar.matches("(?:a_)?[a-fA-F0-9]{32}")){
                URI uri=URI.create("https://cdn.discordapp.com/avatars/"+id+"/"+avatar+".png?size=64");
                try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()){
                    var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(response.statusCode()!=200||response.body().length>1048576)throw new IOException("Discord avatar unavailable");
                    var image=NativeImage.read(response.body());
                    Minecraft.getInstance().execute(()->{if(ticket!=generation){image.close();return;}Identifier texture=Identifier.fromNamespaceAndPath("include","new_hud_discord_avatar");Minecraft.getInstance().getTextureManager().register(texture,new DynamicTexture(()->"new_hud_discord_avatar",image));profile=new Profile(display,texture);avatarKey=id+avatar;});
                }
            }
        }
    }
    private static String string(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
    private static void close(RandomAccessFile file){try{file.close();}catch(IOException failure){LoggerFactory.getLogger("Include/DiscordHUD").debug("Discord IPC close: {}",failure.toString());}}
    // Closing a pipe with a pending synchronous read blocks on Windows, so it is closed off the caller's (render) thread.
    public static synchronized void stop(){generation++;requestedAt=0;RandomAccessFile active=pipe;pipe=null;if(active!=null)TIMEOUT.execute(()->close(active));}
}
