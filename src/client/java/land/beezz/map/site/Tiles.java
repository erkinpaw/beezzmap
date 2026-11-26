package land.beezz.map.site;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The map's pictures. Pl3xMap renders on the server: per tile (512x512 pixels over 512<<n blocks) it keeps the block and
 * the height of every pixel (blockinfo) and its own picture (here only its shade of water tells the depth). Each tile is
 * painted here with the same formula as the site's map (atlas.js paint(), the MapMinecraft mod's ChunkScanner.papyrus),
 * so the game shows exactly what play.beezzland.com/map.html shows.
 */
public final class Tiles {
    public static final int SIZE = 512;
    private static final int MAX = 200; // textures kept; a tile is 1 MB of video memory
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "BeezzMap tiles");
        t.setDaemon(true);
        return t;
    });
    private static final int WATER = 0x4040FF;
    private static final int DEEP = 42, SHALLOW = 120; // brightness of the renderer's water at 14+ blocks deep and at the shore
    private static final int[] PARCH = {234, 216, 174};

    /** Is there a site tile there that will (or did) paint? false only when the site has nothing for that spot. */
    public static boolean missing(Tile t) {
        return t != null && t.failed && t.tex == null;
    }

    public static final class Tile {
        public volatile Identifier tex; // null until painted and uploaded
        volatile boolean failed;
    }

    private static final Map<String, Tile> tiles = new LinkedHashMap<>(256, .75f, true);
    private static final Map<String, int[]> shades = new LinkedHashMap<>(64, .75f, true); // coarse renderer pictures, RGB
    private static final ConcurrentLinkedQueue<Runnable> upload = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger loading = new AtomicInteger();
    private static int ids;

    private Tiles() {}

    /** Tiles on the video card right now (the game test waits for them). */
    public static int ready() {
        int n = 0;
        for (Tile t : tiles.values()) if (t.tex != null) n++;
        return n;
    }

    public static int loading() {
        return loading.get();
    }

    /** On the render thread: put painted pictures on the video card, a few per frame. */
    public static void pump() {
        for (int i = 0; i < 4; i++) {
            Runnable r = upload.poll();
            if (r == null) return;
            r.run();
        }
    }

    public static Tile get(String theme, String world, String renderer, int maxOut, int n, int x, int z, boolean load) {
        String key = theme + "/" + world + "/" + n + "/" + x + "_" + z;
        Tile t = tiles.get(key);
        if (t != null || !load || Data.blockColor == null) return t;
        if (loading.get() > 12) return null; // one batch at a time: the newest wishes win next frame
        Tile nt = new Tile();
        tiles.put(key, nt);
        evict();
        loading.incrementAndGet();
        POOL.execute(() -> {
            try {
                int[] argb = fetch(theme, world, renderer, maxOut, n, x, z);
                if (argb == null) {
                    nt.failed = true;
                    return;
                }
                NativeImage img = new NativeImage(SIZE, SIZE, false);
                for (int pz = 0; pz < SIZE; pz++) for (int px = 0; px < SIZE; px++) img.setPixel(px, pz, argb[pz * SIZE + px]);
                upload.add(() -> {
                    if (tiles.get(key) != nt) {
                        img.close();
                        return;
                    }
                    Identifier id = Identifier.fromNamespaceAndPath("beezzmap", "tile/" + (ids++));
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "beezzmap " + key, img));
                    nt.tex = id;
                });
            } catch (Exception e) {
                land.beezz.map.BeezzMap.LOG.debug("tile {} failed", key, e);
                // a network hiccup: forget it, so it is asked for again later
                upload.add(() -> {
                    if (tiles.get(key) == nt) tiles.remove(key);
                });
            } finally {
                loading.decrementAndGet();
            }
        });
        return nt;
    }

    private static void evict() {
        while (tiles.size() > MAX) {
            var it = tiles.entrySet().iterator();
            Tile old = it.next().getValue();
            it.remove();
            if (old.tex != null) Minecraft.getInstance().getTextureManager().release(old.tex);
        }
    }

    /** Every texture goes (the theme changed, or the world). */
    public static void clear() {
        for (Tile t : tiles.values()) if (t.tex != null) Minecraft.getInstance().getTextureManager().release(t.tex);
        tiles.clear();
    }

    // ------------------------------------------------------------------ painting

    private static int[] fetch(String theme, String world, String renderer, int maxOut, int n, int tx, int tz) throws Exception {
        String dir = "/map/tiles/" + world + "/" + n;
        byte[] info = Api.get(dir + "/blockinfo/" + tx + "_" + tz + ".pl3xmap.gz");
        int[] colors = Data.blockColor;
        if (info == null || info.length != 12 + SIZE * SIZE * 4) {
            // not rendered as blocks yet: Pl3xMap's own picture, if there is one
            byte[] png = Api.get(dir + "/" + renderer + "/" + tx + "_" + tz + ".png");
            return png == null ? null : rgb(png, true);
        }
        ByteBuffer dv = ByteBuffer.wrap(info); // big-endian, as Pl3xMap writes it
        boolean wet = false;
        for (int i = 0; i < SIZE * SIZE && !wet; i += 7) wet = colors[dv.getInt(12 + i * 4) >>> 21] == WATER;
        int[] shade = null;
        int k = 1, sx = 0, sz = 0;
        if (wet) { // the depth of water comes from a picture four times coarser: it tells it as well and weighs much less
            int m = Math.min(maxOut, n + 2);
            k = 1 << (m - n);
            shade = shade(world, renderer, m, Math.floorDiv(tx, k), Math.floorDiv(tz, k));
            int cell = SIZE / k;
            sx = (tx - Math.floorDiv(tx, k) * k) * cell;
            sz = (tz - Math.floorDiv(tz, k) * k) * cell;
        }
        return paint(theme, world, colors, dv, shade, k, sx, sz, tx, tz);
    }

    private static int[] shade(String world, String renderer, int m, int x, int z) throws Exception {
        String key = world + "/" + m + "/" + x + "_" + z;
        synchronized (shades) {
            int[] s = shades.get(key);
            if (s != null) return s;
        }
        byte[] png = Api.get("/map/tiles/" + world + "/" + m + "/" + renderer + "/" + x + "_" + z + ".png");
        int[] s = png == null ? null : rgb(png, false);
        if (s != null) synchronized (shades) {
            shades.put(key, s);
            while (shades.size() > 24) shades.remove(shades.keySet().iterator().next());
        }
        return s;
    }

    /** A PNG as ARGB pixels, SIZE x SIZE. */
    private static int[] rgb(byte[] png, boolean opaqueOnly) throws Exception {
        try (NativeImage img = NativeImage.read(png)) {
            int[] out = new int[SIZE * SIZE];
            int w = Math.min(SIZE, img.getWidth()), h = Math.min(SIZE, img.getHeight());
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) out[z * SIZE + x] = img.getPixel(x, z);
            return out;
        }
    }

    /** Brightness of the coarse picture under this pixel, smoothed the way the browser stretches it. */
    private static double shadeAt(int[] s, int k, int sx, int sz, int x, int z) {
        double fx = sx + (x + .5) / k - .5, fz = sz + (z + .5) / k - .5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double ax = fx - x0, az = fz - z0;
        double a = lum(s, x0, z0), b = lum(s, x0 + 1, z0), c = lum(s, x0, z0 + 1), d = lum(s, x0 + 1, z0 + 1);
        return (a * (1 - ax) + b * ax) * (1 - az) + (c * (1 - ax) + d * ax) * az;
    }

    private static double lum(int[] s, int x, int z) {
        x = Math.max(0, Math.min(SIZE - 1, x));
        z = Math.max(0, Math.min(SIZE - 1, z));
        int p = s[z * SIZE + x];
        return ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3.0;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int[] paint(String theme, String world, int[] colors, ByteBuffer dv, int[] shade, int k, int sx, int sz, int tx, int tz) {
        int minY = dv.getInt(8);
        boolean plain = !"papyrus".equals(theme), nether = world.contains("nether");
        int[] hs = new int[SIZE * SIZE];
        for (int i = 0; i < SIZE * SIZE; i++) hs[i] = (dv.getInt(12 + i * 4) & 0x3FF) + minY;
        int[] out = new int[SIZE * SIZE];
        for (int z = 0, i = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++, i++) {
                int v = dv.getInt(12 + i * 4);
                if (v == 0) continue; // nothing rendered here: transparent
                int rgb = colors[v >>> 21];
                double r, g, b;
                if (rgb == 0) { // nothing to paint (the End's void, air); under the Nether's roof, the dark
                    if (nether) out[i] = 0xFF2A1A18;
                    continue;
                }
                if (rgb == WATER) {
                    double kk = 0;
                    if (shade != null) kk = Math.max(0, Math.min(1, (SHALLOW - shadeAt(shade, k, sx, sz, x, z)) / (SHALLOW - DEEP)));
                    r = lerp(120, 62, kk);
                    g = lerp(160, 98, kk);
                    b = lerp(164, 114, kk);
                    int wz = tz * SIZE + z, wx = tx * SIZE + x;
                    if ((wz & 3) == 0 && ((wx + (wz >> 2) * 5) & 7) < 3) { // engraved waves
                        r *= .88;
                        g *= .88;
                        b *= .9;
                    }
                } else {
                    r = rgb >> 16 & 255;
                    g = rgb >> 8 & 255;
                    b = rgb & 255;
                    int hh = hs[i], hn = z > 0 ? hs[i - SIZE] : hh, hw = x > 0 ? hs[i - 1] : hh;
                    double sh = hh == hn ? .97 : 1 + Math.max(-4, Math.min(4, hh - hn)) * .045;
                    r *= sh;
                    g *= sh;
                    b *= sh;
                    if (!plain) { // sepia
                        double l = .3 * r + .59 * g + .11 * b;
                        r = lerp(r, l * 1.02 + 28, .42);
                        g = lerp(g, l * .9 + 16, .42);
                        b = lerp(b, l * .68 + 4, .42);
                    }
                    if (Math.floorDiv(hh, 8) != Math.floorDiv(hn, 8) || Math.floorDiv(hh, 8) != Math.floorDiv(hw, 8)) { // contour lines
                        r *= .84;
                        g *= .82;
                        b *= .8;
                    }
                }
                double soak = plain ? 0 : .14; // soaked into the parchment
                int R = clamp(lerp(r, PARCH[0], soak)), G = clamp(lerp(g, PARCH[1], soak)), B = clamp(lerp(b, PARCH[2], soak));
                out[i] = 0xFF000000 | R << 16 | G << 8 | B;
            }
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
