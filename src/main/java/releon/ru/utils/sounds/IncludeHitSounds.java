package releon.ru.utils.sounds;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Small cached WAV cues from Include; audio decoding and playback stay off the game thread. */
public final class IncludeHitSounds {
    private record Pcm(AudioFormat format, byte[] bytes) {}
    private static final Map<String, Pcm> CACHE = new HashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final List<Clip> CLIPS = new CopyOnWriteArrayList<>();
    private static final ThreadPoolExecutor AUDIO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { var thread = new Thread(task, "Titanium hit sounds"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private IncludeHitSounds() {}
    public static void play(String name, float volume, float pitch) {
        if (volume <= 0 || FAILED.contains(name)) return;
        AUDIO.execute(() -> {
            Clip clip = null;
            try {
                Pcm sound = CACHE.get(name);
                if (sound == null) { sound = read(name); CACHE.put(name, sound); }
                while (CLIPS.size() >= 8) { var oldest = CLIPS.removeFirst(); oldest.stop(); oldest.close(); }
                clip = AudioSystem.getClip();
                float factor = Math.max(.5f, Math.min(2f, pitch));
                AudioFormat source = sound.format();
                AudioFormat adjusted = new AudioFormat(source.getEncoding(), source.getSampleRate() * factor,
                        source.getSampleSizeInBits(), source.getChannels(), source.getFrameSize(), source.getFrameRate() * factor, source.isBigEndian());
                clip.open(adjusted, sound.bytes(), 0, sound.bytes().length);
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    var gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), 20f * (float) Math.log10(Math.max(.0001f, volume)))));
                }
                Clip owned = clip;
                owned.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) { CLIPS.remove(owned); owned.close(); }
                });
                CLIPS.add(owned); owned.start();
            } catch (Exception failure) {
                if (clip != null) { CLIPS.remove(clip); clip.close(); }
                if (FAILED.add(name)) org.slf4j.LoggerFactory.getLogger(IncludeHitSounds.class).warn("Cannot play hit sound {}", name, failure);
            }
        });
    }
    private static Pcm read(String name) throws Exception {
        var input = IncludeHitSounds.class.getResourceAsStream("/assets/releon/sounds/include/" + name + ".wav");
        if (input == null) throw new FileNotFoundException(name);
        try (var buffered = new BufferedInputStream(input); var audio = AudioSystem.getAudioInputStream(buffered)) {
            return new Pcm(audio.getFormat(), audio.readAllBytes());
        }
    }
}
