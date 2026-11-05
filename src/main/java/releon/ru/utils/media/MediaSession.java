package releon.ru.utils.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Windows system media session (GSMTC: Spotify, browsers, Yandex Music...) read by a helper PowerShell
 * process that prints one JSON line per change and accepts toggle/next/prev on stdin. It exits by itself
 * when the game dies (stdin closes). All pipe I/O stays off the render thread.
 */
public final class MediaSession {
    public record Track(String title, String artist, String app, boolean playing, long position, long duration, long updated) {
        /** Position extrapolated from the last report while playing. */
        public long position(long now) {
            long p = playing ? position + Math.max(0, now - updated) : position;
            return duration > 0 ? Math.min(p, duration) : Math.max(0, p);
        }
    }

    private static final Identifier COVER = Identifier.fromNamespaceAndPath("include", "new_hud_media_cover");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Include-Media-Input");
        t.setDaemon(true);
        return t;
    });
    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference='SilentlyContinue'",
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "Add-Type -AssemblyName System.Drawing",
            "$asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0]",
            "$asRead=[System.IO.WindowsRuntimeStreamExtensions].GetMethods()|?{$_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1}",
            "function Await($op,[Type]$type){$t=$asTask.MakeGenericMethod($type).Invoke($null,@($op));if($t.Wait(3000)){$t.Result}else{$null}}",
            "$null=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]",
            "$null=[Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]",
            "$mgr=Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
            "$in=New-Object System.IO.StreamReader([Console]::OpenStandardInput());$read=$in.ReadLineAsync();$lastKey='';$lastLine=''",
            "$paused=$null",
            "function Resume-CosmeticsMedia{if($script:paused){try{if($script:paused.GetPlaybackInfo().PlaybackStatus -eq 'Paused'){$null=Await ($script:paused.TryPlayAsync()) ([bool])}}finally{$script:paused=$null}}}",
            "try{while($true){",
            " if($read.IsCompleted){",
            "  $cmd=$read.Result;if($cmd -eq $null){break};$read=$in.ReadLineAsync()",
            "  if($cmd -eq 'resume-cosmetics'){Resume-CosmeticsMedia}else{",
            "   $s=$mgr.GetCurrentSession();if($s){",
            "    if($cmd -eq 'pause-cosmetics'){if(-not $paused -and $s.GetPlaybackInfo().PlaybackStatus -eq 'Playing' -and (Await ($s.TryPauseAsync()) ([bool]))){$paused=$s}}",
            "    else{$op=switch($cmd){'toggle'{$s.TryTogglePlayPauseAsync()}'next'{$s.TrySkipNextAsync()}'prev'{$s.TrySkipPreviousAsync()}};if($op){$null=Await $op ([bool])}}",
            "   }}",
            " }",
            " $s=$mgr.GetCurrentSession()",
            " if(-not $s){$line='{\"state\":\"none\"}';$lastKey=''}else{",
            "  $p=Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
            "  $tl=$s.GetTimelineProperties();$pb=$s.GetPlaybackInfo()",
            "  $o=[ordered]@{state='ok';title=[string]$p.Title;artist=[string]$p.Artist;app=[string]$s.SourceAppUserModelId;playing=($pb.PlaybackStatus -eq 'Playing');position=[long]$tl.Position.TotalMilliseconds;duration=[long]($tl.EndTime-$tl.StartTime).TotalMilliseconds;updated=[long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()}",
            "  $key=$o.title+'|'+$o.artist",
            "  if($key -ne $lastKey -and $o.title){$lastKey=$key;$o.cover='';if($p.Thumbnail){try{",
            "   $st=Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])",
            // PowerShell 5.1 sees the stream as a bare __ComObject; a reflective call lets the CLR cast it to IInputStream.
            "   $ms=New-Object System.IO.MemoryStream;$asRead.Invoke($null,@($st)).CopyTo($ms);$ms.Position=0;$img=[System.Drawing.Image]::FromStream($ms)",
            "   $side=[Math]::Min($img.Width,$img.Height);$bmp=New-Object System.Drawing.Bitmap 96,96;$gr=[System.Drawing.Graphics]::FromImage($bmp);$gr.InterpolationMode='HighQualityBicubic'",
            "   $gr.DrawImage($img,(New-Object System.Drawing.Rectangle 0,0,96,96),(New-Object System.Drawing.Rectangle ([int](($img.Width-$side)/2)),([int](($img.Height-$side)/2)),$side,$side),[System.Drawing.GraphicsUnit]::Pixel)",
            "   $ms=New-Object System.IO.MemoryStream;$bmp.Save($ms,[System.Drawing.Imaging.ImageFormat]::Png);$o.cover=[Convert]::ToBase64String($ms.ToArray())}catch{}}}",
            "  $line=$o|ConvertTo-Json -Compress}",
            " if($line -ne $lastLine){[Console]::Out.WriteLine($line);[Console]::Out.Flush();$lastLine=$line}",
            " $null=$read.Wait(400)}}finally{Resume-CosmeticsMedia}");

    private static volatile Track track;
    private static volatile Identifier cover;
    private static Process process;
    private static Writer input;
    private static long startedAt;

    private MediaSession() {}

    /** Current track (null when nothing plays); lazily starts the helper on first use. */
    public static Track current() {
        prepare();
        return track;
    }

    public static Identifier cover() { return cover; }

    private static final java.util.concurrent.atomic.AtomicBoolean preparing = new java.util.concurrent.atomic.AtomicBoolean();
    public static void prepare() {
        if (preparing.compareAndSet(false,true)) IO.execute(() -> {try{ensure();}finally{preparing.set(false);}});
    }

    /** Sends a command to the Windows media helper. */
    public static void command(String command) {
        IO.execute(() -> {
            if ("pause-cosmetics".equals(command)) ensure();
            Writer writer;
            synchronized (MediaSession.class) { writer = input; }
            if (writer == null) return;
            try { writer.write(command + "\n"); writer.flush(); }
            catch (Exception e) { LoggerFactory.getLogger("Include/Media").debug("Media command failed: {}", e.toString()); }
        });
    }

    private static synchronized void ensure() {
        if (process != null && process.isAlive()) return;
        long now = System.currentTimeMillis();
        if (now - startedAt < 10000 || !System.getProperty("os.name", "").startsWith("Windows")) return;
        startedAt = now;
        track = null;
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            Process owned = process;
            Thread reader = new Thread(() -> read(owned), "Include-Media-Reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            process = null;
            input = null;
            LoggerFactory.getLogger("Include/Media").debug("Media helper unavailable: {}", e.toString());
        }
    }

    private static void read(Process owned) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(owned.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                line = line.strip();
                if (!line.startsWith("{")) continue;
                try { accept(JsonParser.parseString(line).getAsJsonObject()); }
                catch (Exception e) { LoggerFactory.getLogger("Include/Media").debug("Bad media line: {}", e.toString()); }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("Include/Media").debug("Media helper stopped: {}", e.toString());
        }
        track = null;
    }

    private static void accept(JsonObject o) throws Exception {
        if (!"ok".equals(string(o, "state")) || string(o, "title").isBlank()) { track = null; return; }
        track = new Track(string(o, "title"), string(o, "artist"), string(o, "app"), o.has("playing") && o.get("playing").getAsBoolean(),
                number(o, "position"), number(o, "duration"), o.has("updated") && number(o, "updated") > 0 ? number(o, "updated") : System.currentTimeMillis());
        if (!o.has("cover")) return;
        String data = string(o, "cover");
        if (data.isEmpty()) { cover = null; return; }
        NativeImage image = NativeImage.read(Base64.getDecoder().decode(data));
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().getTextureManager().register(COVER, new DynamicTexture(() -> "new_hud_media_cover", image));
            cover = COVER;
        });
    }

    private static String string(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
    private static long number(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0; }
}
