package land.beezz.map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * A rectangle of the map in window pixels (the big map: the whole window; the minimap: its square). Draws the tiles,
 * the fog and the chunk grid; the caller has already scaled the pose to window pixels.
 */
final class MapView {
    static final int MAX_OUT = 3; // Pl3xMap's zoom levels 0..3 on BeezzLand

    String world = "world";
    double cx, cz, s = 1; // the block in the middle; window pixels per block
    double x0, y0, w, h; // the rectangle, window pixels

    double sx(double x) {
        return (x - cx) * s + x0 + w / 2;
    }

    double sz(double z) {
        return (z - cz) * s + y0 + h / 2;
    }

    double bx(double px) {
        return (px - x0 - w / 2) / s + cx;
    }

    double bz(double py) {
        return (py - y0 - h / 2) / s + cz;
    }

    void zoomAt(double px, double py, double k, boolean keepCenter) {
        double bx = bx(px), bz = bz(py);
        s = Math.max(1.0 / (1 << MAX_OUT) / 2, Math.min(16, s * k));
        if (!keepCenter) {
            cx = bx - (px - x0 - w / 2) / s;
            cz = bz - (py - y0 - h / 2) / s;
        }
    }

    void drawTiles(GuiGraphicsExtractor g) {
        Data.World dw = Data.world(world);
        if (dw == null) return;
        int want = Math.max(0, Math.min(MAX_OUT, (int) Math.ceil(Math.log(1 / s) / Math.log(2) - 0.001)));
        for (int n = MAX_OUT; n > want; n--) drawLevel(g, dw, n, n == MAX_OUT); // coarse ones first: never an empty hole
        drawLevel(g, dw, want, true);
    }

    private void drawLevel(GuiGraphicsExtractor g, Data.World dw, int n, boolean load) {
        double size = Tiles.SIZE << n;
        for (int tx = (int) Math.floor(bx(x0) / size); tx <= Math.floor(bx(x0 + w) / size); tx++) {
            for (int tz = (int) Math.floor(bz(y0) / size); tz <= Math.floor(bz(y0 + h) / size); tz++) {
                Tiles.Tile t = Tiles.get(BeezzMap.config.theme, dw.name(), dw.renderer(), MAX_OUT, n, tx, tz, load);
                if (t == null || t.tex == null) continue;
                // whole pixels, or thin seams show between the tiles
                int l = (int) Math.round(sx(tx * size)), tp = (int) Math.round(sz(tz * size));
                int r = (int) Math.round(sx((tx + 1) * size)), b = (int) Math.round(sz((tz + 1) * size));
                g.blit(t.tex, l, tp, r, b, 0, 1, 0, 1);
            }
        }
    }

    /** extraOpen: chunks open right now although the fog file doesn't know it yet (players standing there). */
    void drawFog(GuiGraphicsExtractor g, int color, Iterable<double[]> extraOpen) {
        Map<String, byte[]> fog = Data.fogOf(world);
        if (fog == null) return;
        Map<String, TreeSet<Integer>> live = new HashMap<>();
        for (double[] p : extraOpen) openAround(live, (int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4);
        for (int rx = (int) Math.floor(bx(x0) / 512); rx <= Math.floor(bx(x0 + w) / 512); rx++) {
            for (int rz = (int) Math.floor(bz(y0) / 512); rz <= Math.floor(bz(y0 + h) / 512); rz++) {
                int l = (int) Math.round(sx(rx * 512.0)), t = (int) Math.round(sz(rz * 512.0));
                int r = (int) Math.round(sx((rx + 1) * 512.0)), b = (int) Math.round(sz((rz + 1) * 512.0));
                String key = rx + "_" + rz;
                byte[] bits = fog.get(key);
                TreeSet<Integer> extra = live.get(key);
                if (extra != null) {
                    bits = bits == null ? new byte[128] : bits.clone();
                    for (int i : extra) bits[i >> 3] |= (byte) (1 << (i & 7));
                    key = key + extra;
                }
                if (bits == null) g.fill(l, t, r, b, color);
                else g.blit(Icons.fog(world, BeezzMap.config.theme, key, bits, color).id(), l, t, r, b, 0, 1, 0, 1);
            }
        }
    }

    /** Chunks within 3 of this one, as region -> bit numbers. */
    private static void openAround(Map<String, TreeSet<Integer>> live, int chx, int chz) {
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dz * dz > 10) continue;
            int x = chx + dx, z = chz + dz;
            live.computeIfAbsent((x >> 5) + "_" + (z >> 5), k -> new TreeSet<>()).add((x & 31) + (z & 31) * 32);
        }
    }

    /** The mod's faint chunk grid, only up close. */
    void drawGrid(GuiGraphicsExtractor g, boolean dark) {
        if (s < 4) return;
        int c = dark ? (s >= 8 ? 0x1CFFFFFF : 0x12FFFFFF) : (s >= 8 ? 0x223A2616 : 0x163A2616);
        for (double x = Math.floor(bx(x0) / 16) * 16; x <= bx(x0 + w); x += 16) {
            int px = (int) Math.round(sx(x));
            g.fill(px, (int) y0, px + 1, (int) (y0 + h), c);
        }
        for (double z = Math.floor(bz(y0) / 16) * 16; z <= bz(y0 + h); z += 16) {
            int pz = (int) Math.round(sz(z));
            g.fill((int) x0, pz, (int) (x0 + w), pz + 1, c);
        }
    }
}
