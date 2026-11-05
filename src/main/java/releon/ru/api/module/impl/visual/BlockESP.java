package releon.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.repository.blockesp.BlockESPConfig;
import releon.ru.utils.string.chat.ChatMessage;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class BlockESP extends Module {
    private static BlockESP instance;

    private static final int CHUNKS_PER_TICK = 6;

    private final ColorSetting color = register(new ColorSetting("Color", "Block highlight color.", new Color(127, 242, 255, 136)));
    private final NumberSetting range = register(new NumberSetting("Range", "Search radius for blocks.", 32.0, 1.0, 128.0, 1.0));
    private final BooleanSetting notifyInChat = register(new BooleanSetting("Notify", "Show found blocks in chat.", false));

    private final Set<String> blocksToHighlight = new CopyOnWriteArraySet<>();
    private final Set<Block> targetBlocks = new HashSet<>();
    private int targetHash;

    private Map<BlockPos, Entry> renderBlocks = new HashMap<>();
    private Map<BlockPos, Entry> pendingBlocks = new HashMap<>();
    private final Set<BlockPos> notifiedBlocks = new HashSet<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private int scanIndex;
    private int scanRadius;
    private int scanCenterX;
    private int scanCenterZ;

    public BlockESP() {
        super("Block ESP", "Highlights selected blocks in the world.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static BlockESP getInstance() {
        return instance;
    }

    public Set<String> getBlocksToHighlight() {
        return blocksToHighlight;
    }

    @Override
    protected void onEnable() {
        blocksToHighlight.clear();
        blocksToHighlight.addAll(BlockESPConfig.getInstance().getBlocks());
        resetScan();
    }

    @Override
    protected void onDisable() {
        renderBlocks.clear();
        pendingBlocks.clear();
        notifiedBlocks.clear();
    }

    private void resetScan() {
        renderBlocks.clear();
        pendingBlocks.clear();
        notifiedBlocks.clear();
        scanIndex = 0;
        targetHash = 0;
    }

    private void syncTargets() {
        int hash = blocksToHighlight.hashCode();
        if (hash == targetHash && hash != 0) {
            return;
        }
        targetHash = hash;
        targetBlocks.clear();
        for (String name : blocksToHighlight) {
            Identifier id = Identifier.tryParse(name);
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(targetBlocks::add);
            }
        }
        renderBlocks.entrySet().removeIf(e -> !targetBlocks.contains(e.getValue().state.getBlock()));
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            renderBlocks.clear();
            return;
        }
        syncTargets();
        if (targetBlocks.isEmpty()) {
            renderBlocks.clear();
            return;
        }

        if (scanIndex == 0) {
            BlockPos p = client.player.blockPosition();
            scanCenterX = p.getX() >> 4;
            scanCenterZ = p.getZ() >> 4;
            scanRadius = Math.max(1, (int) Math.ceil(range.getFloat() / 16.0F));
            pendingBlocks.clear();
        }

        int side = scanRadius * 2 + 1;
        int total = side * side;
        for (int i = 0; i < CHUNKS_PER_TICK && scanIndex < total; i++, scanIndex++) {
            int cx = scanCenterX + scanIndex % side - scanRadius;
            int cz = scanCenterZ + scanIndex / side - scanRadius;
            scanChunk(client, cx, cz);
        }

        if (scanIndex >= total) {
            Map<BlockPos, Entry> swap = renderBlocks;
            renderBlocks = pendingBlocks;
            pendingBlocks = swap;
            notifiedBlocks.retainAll(renderBlocks.keySet());
            scanIndex = 0;
        }
    }

    private void scanChunk(Minecraft client, int chunkX, int chunkZ) {
        LevelChunk chunk = client.level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        double maxSqr = (double) range.getFloat() * range.getFloat();

        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(s) << 4;

            double dy = Math.max(0.0D, Math.max(baseY - py, py - (baseY + 16)));
            if (dy * dy > maxSqr) {
                continue;
            }
            if (!section.maybeHas(state -> targetBlocks.contains(state.getBlock()))) {
                continue;
            }

            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                double ddy = y + 0.5D - py;
                double dy2 = ddy * ddy;
                if (dy2 > maxSqr) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    int z = baseZ + lz;
                    double ddz = z + 0.5D - pz;
                    double dyz2 = dy2 + ddz * ddz;
                    if (dyz2 > maxSqr) {
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (!targetBlocks.contains(state.getBlock())) {
                            continue;
                        }
                        int x = baseX + lx;
                        double ddx = x + 0.5D - px;
                        if (dyz2 + ddx * ddx > maxSqr) {
                            continue;
                        }
                        mutablePos.set(x, y, z);
                        BlockPos pos = mutablePos.immutable();
                        Entry old = renderBlocks.get(pos);
                        pendingBlocks.put(pos, old != null && old.state == state ? old : new Entry(state, shapeOf(client, state, pos)));
                        if (notifyInChat.getValue() && notifiedBlocks.add(pos)) {
                            notifyBlockFound(pos, state.getBlock());
                        }
                    }
                }
            }
        }
    }

    private static VoxelShape shapeOf(Minecraft client, BlockState state, BlockPos pos) {
        VoxelShape shape = state.getShape(client.level, pos);
        if (shape.isEmpty()) {
            shape = state.getCollisionShape(client.level, pos);
        }
        return shape;
    }

    @SubscribeEvent
    private void onRender3D(WorldRenderEvent event) {
        if (!isEnabled() || mc.level == null || mc.player == null || renderBlocks.isEmpty()) {
            return;
        }
        int highlightColor = color.getValue().getRGB();
        for (Map.Entry<BlockPos, Entry> e : renderBlocks.entrySet()) {
            BlockPos pos = e.getKey();
            VoxelShape shape = e.getValue().shape;
            if (shape.isEmpty() || shape == Shapes.block()) {
                AABB box = new AABB(pos);
                Render3D.drawBox(box, highlightColor, 1.0f);
                Render3D.drawBoxOverlay(box, highlightColor, 1.25f);
            } else {
                Render3D.drawShapeAlternative(pos, shape, highlightColor, 1.0f, true, false);
                Render3D.drawShapeOverlay(pos, shape, highlightColor, 1.25f);
            }
        }
    }

    private void notifyBlockFound(BlockPos pos, Block block) {
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(
                ChatMessage.blockesp().copy().append(Component.literal(" -> " + BuiltInRegistries.BLOCK.getKey(block)
                        + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())),
                false
        );
    }

    private record Entry(BlockState state, VoxelShape shape) {
    }
}
