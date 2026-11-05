package releon.ru.utils.render;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public final class PvpAssets {
    public static final String[] WORLD_IMAGES={"FireFlies","Star","Heart","Snow","Dollar","Bubble","Crown","Lightning","Rhomb","Triangle","Star 2"};
    public static Identifier textureFor(String name){
        String file=switch(name){case "Star"->"star";case "Heart"->"heart";case "Snow"->"snowflake";case "Dollar"->"dollar";case "Bubble"->"bubble";case "Crown"->"crown";case "Lightning"->"lighting";case "Rhomb"->"romb";case "Triangle"->"triangle";case "Star 2"->"starnew";default->"glow";};
        return Identifier.fromNamespaceAndPath("client","textures/particles/"+file+".png");
    }
    private static final Identifier RING = Identifier.fromNamespaceAndPath("releon", "pvp_ring_aura");
    private static boolean registered;
    private PvpAssets() {}
    public static GpuTextureView ring() {
        var mc = Minecraft.getInstance();
        if (!registered) {
            NativeImage image = new NativeImage(128, 128, false);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                float dx = (x - 63.5f) / 63.5f, dy = (y - 63.5f) / 63.5f;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                float outer = 1 - Mth.clamp((r - .88f) / .08f, 0, 1), inner = Mth.clamp((r - .35f) / .53f, 0, 1);
                float core = (float) Math.exp(-Math.pow((r - .86f) / .045f, 2));
                int alpha = r > 1 ? 0 : Math.round(Mth.clamp(inner * inner * outer * .65f + core * .95f, 0, 1) * 255);
                image.setPixelABGR(x, y, (alpha << 24) | 0xFFFFFF);
            }
            mc.getTextureManager().register(RING, new DynamicTexture(() -> "PVP ring aura", image));
            registered = true;
        }
        return mc.getTextureManager().getTexture(RING).getTextureView();
    }
}
