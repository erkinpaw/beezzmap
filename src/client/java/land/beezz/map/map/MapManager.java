package land.beezz.map.map;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import land.beezz.map.data.ModConfig;
import land.beezz.map.data.Storage;
import land.beezz.map.data.WorldMeta;
import land.beezz.map.site.Site;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Path;

/** Owns the map session of the world the player is currently in and keeps it up to date. */
public final class MapManager {
    private static final long SCAN_BUDGET_NS = 3_000_000L;

    private static MapSession live;
    private static String liveDim;
    private static final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    private static int ticks;

    // cave mode: a picture of the floor under the player's height, kept in memory, made again when they go up or down
    private static final LongLinkedOpenHashSet caveQueue = new LongLinkedOpenHashSet();
    private static MapLayer caveLayer;
    private static String caveDim;
    private static int caveY = Integer.MIN_VALUE;
    private static boolean caves;
    private static int undergroundTicks;

    private MapManager() {
    }

    public static MapSession live() {
        return live;
    }

    public static String liveDim() {
        return liveDim;
    }

    public static void onJoin(Minecraft mc) {
        closeLive();
        WorldMeta meta = resolveMeta(mc);
        live = MapSession.open(meta);
        live.live = true;
        liveDim = null;
        queue.clear();
        live.saveMeta();
        if (Site.isBeezz(live)) {
            Site.importOldMarks(live);
        }
    }

    public static void onDisconnect() {
        closeLive();
    }

    private static void closeLive() {
        if (live != null) {
            live.meta.lastPlayed = System.currentTimeMillis();
            live.saveMeta();
            live.saveMarkers();
            live.saveStrokes();
            live.close();
            live = null;
        }
        liveDim = null;
        queue.clear();
        dropCaves();
    }

    private static void dropCaves() {
        if (caveLayer != null) {
            caveLayer.close();
        }
        caveLayer = null;
        caveDim = null;
        caveY = Integer.MIN_VALUE;
        caveQueue.clear();
    }

    /** Is the cave picture what the map shows right now (on, or by itself underground)? */
    public static boolean caves() {
        return caves && caveLayer != null;
    }

    /** The cave picture of the live dimension (null when caves are off). */
    public static MapLayer caveLayer(String dim) {
        return caves && dim != null && dim.equals(caveDim) ? caveLayer : null;
    }

    public static int caveY() {
        return caveY;
    }

    /** Opens a stored world for viewing. Reuses the live session if it is the same world. */
    public static MapSession openForViewing(WorldMeta meta) {
        if (live != null && live.meta.id.equals(meta.id)) {
            return live;
        }
        return MapSession.open(meta);
    }

    public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (live != null && level == Minecraft.getInstance().level) {
            long k = MapLayer.pack(chunk.getPos().x(), chunk.getPos().z());
            queue.add(k);
            if (caves) {
                caveQueue.add(k);
            }
        }
    }

    public static void tick(Minecraft mc) {
        if (live == null || mc.level == null || mc.player == null) {
            return;
        }
        ClientLevel level = mc.level;
        String dim = level.dimension().identifier().toString();
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        if (!dim.equals(liveDim)) {
            liveDim = dim;
            queue.clear();
            int r = mc.options.getEffectiveRenderDistance();
            enqueueAround(pcx, pcz, r);
        }
        ticks++;
        if (ticks % 40 == 0) {
            enqueueAround(pcx, pcz, 2);
        }

        MapLayer layer = live.layer(dim);
        long start = System.nanoTime();
        while (!queue.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            long k = queue.removeFirstLong();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            if (level.hasChunk(cx, cz)) {
                LevelChunk chunk = level.getChunk(cx, cz);
                if (!chunk.isEmpty()) {
                    ChunkScanner.scan(level, chunk, layer);
                }
            }
        }
        layer.uploadDirty();
        tickCaves(mc, level, dim, pcx, pcz);

        if (ticks % 100 == 0) {
            live.meta.lastDim = dim;
            live.meta.lastX = mc.player.getX();
            live.meta.lastZ = mc.player.getZ();
            live.meta.lastPlayed = System.currentTimeMillis();
        }
        if (ticks % 1200 == 0) {
            live.saveTiles();
            live.saveMeta();
            live.unloadUnused();
        }
    }

    private static void tickCaves(Minecraft mc, ClientLevel level, String dim, int pcx, int pcz) {
        ModConfig cfg = ModConfig.get();
        int y = mc.player.getBlockY();
        // underground: no sky light where the eyes are (trees and the open air have it) and 8+ blocks above the head
        net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
        boolean under = !level.dimensionType().hasCeiling()
                && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) <= 2
                && level.getHeight(Heightmap.Types.MOTION_BLOCKING, mc.player.getBlockX(), mc.player.getBlockZ()) - y >= 8;
        // a second of being under (or out) before the map switches, so it doesn't flicker at a cave's mouth
        undergroundTicks = under ? Math.min(20, undergroundTicks + 1) : Math.max(0, undergroundTicks - 1);
        boolean want = cfg.caveMode == 1 || cfg.caveMode == 2 && (caves ? undergroundTicks > 0 : undergroundTicks >= 20);
        if (!want) {
            if (caves) {
                caves = false;
                dropCaves();
            }
            return;
        }
        caves = true;
        int r = mc.options.getEffectiveRenderDistance();
        if (caveLayer == null || !dim.equals(caveDim) || Math.abs(y - caveY) >= 4) {
            if (caveLayer == null || !dim.equals(caveDim)) {
                dropCaves();
                caveLayer = new MapLayer(live.meta.id, dim, live.dir.resolve("caves"), true);
                caveDim = dim;
            } else {
                caveLayer.clear();
            }
            caveY = y;
            caveQueue.clear();
            spiral(caveQueue, pcx, pcz, r);
        } else if (ticks % 40 == 0) {
            spiral(caveQueue, pcx, pcz, 2);
        }
        long start = System.nanoTime();
        while (!caveQueue.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            long k = caveQueue.removeFirstLong();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            if (level.hasChunk(cx, cz)) {
                LevelChunk chunk = level.getChunk(cx, cz);
                if (!chunk.isEmpty()) {
                    ChunkScanner.scanCave(level, chunk, caveLayer, caveY);
                }
            }
        }
        caveLayer.uploadDirty();
        if (ticks % 1200 == 0) {
            caveLayer.unloadUnused();
        }
    }

    private static void spiral(LongLinkedOpenHashSet q, int cx, int cz, int r) {
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == d) {
                        q.add(MapLayer.pack(cx + x, cz + z));
                    }
                }
            }
        }
    }

    private static void enqueueAround(int cx, int cz, int r) {
        // spiral-ish order: closest chunks first
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == d) {
                        queue.add(MapLayer.pack(cx + x, cz + z));
                    }
                }
            }
        }
    }

    private static WorldMeta resolveMeta(Minecraft mc) {
        WorldMeta meta = new WorldMeta();
        IntegratedServer sp = mc.getSingleplayerServer();
        ServerData sd = mc.getCurrentServer();
        if (Site.playing(mc)) {
            // every address of BeezzLand (play, play1, play2…) is the one server: one map, one set of markers
            meta.id = Site.WORLD_ID;
            meta.name = "BeezzLand";
            meta.address = sd != null ? sd.ip : "play.beezzland.com";
            meta.type = WorldMeta.SERVER;
        } else if (sp != null) {
            Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String folder = root.getFileName() == null ? "world" : root.getFileName().toString();
            meta.id = "local_" + Storage.sanitize(folder);
            meta.name = sp.getWorldData().getLevelName();
            meta.address = folder;
            meta.type = WorldMeta.LOCAL;
        } else if (sd != null) {
            meta.id = "server_" + Storage.sanitize(sd.ip);
            meta.name = sd.name;
            meta.address = sd.ip;
            meta.type = WorldMeta.SERVER;
        } else {
            meta.id = "unknown";
            meta.name = "Unknown";
            meta.type = WorldMeta.SERVER;
        }
        WorldMeta old = Storage.readJson(Storage.worldDir(meta.id).resolve("meta.json"), WorldMeta.class, null);
        if (old != null) {
            meta.lastDim = old.lastDim;
            meta.lastX = old.lastX;
            meta.lastZ = old.lastZ;
        }
        meta.lastPlayed = System.currentTimeMillis();
        return meta;
    }
}
