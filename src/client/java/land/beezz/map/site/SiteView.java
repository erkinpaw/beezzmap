package land.beezz.map.site;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * The site's map in a rectangle of window pixels (the big map: the whole window; the minimap: its square). Draws
 * Pl3xMap's tiles painted the site's way and the fog of war; the caller has already scaled the pose to window pixels.
 */
public final class SiteView {
    public static final int MAX_OUT = 3; // Pl3xMap's zoom levels 0..3 on BeezzLand

    public String world = "world";
    public double cx, cz, s = 1; // the block in the middle; window pixels per block
    public double x0, y0, w, h; // the rectangle, window pixels
    public int alpha = 255;

    public double sx(double x) {
        return (x - cx) * s + x0 + w / 2;
    }

    public double sz(double z) {
        return (z - cz) * s + y0 + h / 2;
    }

    public double bx(double px) {
        return (px - x0 - w / 2) / s + cx;
    }

    public double bz(double py) {
        return (py - y0 - h / 2) / s + cz;
    }

    private int want() {
        return Math.max(0, Math.min(MAX_OUT, (int) Math.ceil(Math.log(1 / s) / Math.log(2) - 0.001)));
    }

    public void drawTiles(GuiGraphicsExtractor g) {
        Data.World dw = Data.world(world);
        if (dw == null) return;
        int want = want();
        if (alpha >= 255) for (int n = MAX_OUT; n > want; n--) drawLevel(g, dw, n, n == MAX_OUT); // coarse ones first: never an empty hole
        drawLevel(g, dw, want, true);
    }

    /** Is the site's tile over this block painted already (then the mod's own picture under it is not needed)? */
    public boolean covers(double bx, double bz) {
        Data.World dw = Data.world(world);
        if (dw == null) return false;
        int n = want();
        double size = Tiles.SIZE << n;
        Tiles.Tile t = Tiles.get(Site.tileTheme(), dw.name(), dw.renderer(), MAX_OUT, n, (int) Math.floor(bx / size), (int) Math.floor(bz / size), false);
        return t != null && t.tex != null;
    }

    private void drawLevel(GuiGraphicsExtractor g, Data.World dw, int n, boolean load) {
        double size = Tiles.SIZE << n;
        for (int tx = (int) Math.floor(bx(x0) / size); tx <= Math.floor(bx(x0 + w) / size); tx++) {
            for (int tz = (int) Math.floor(bz(y0) / size); tz <= Math.floor(bz(y0 + h) / size); tz++) {
                Tiles.Tile t = Tiles.get(Site.tileTheme(), dw.name(), dw.renderer(), MAX_OUT, n, tx, tz, load);
                if (t == null || t.tex == null) continue;
                // whole pixels, or thin seams show between the tiles
                int l = (int) Math.round(sx(tx * size)), tp = (int) Math.round(sz(tz * size));
                int r = (int) Math.round(sx((tx + 1) * size)), b = (int) Math.round(sz((tz + 1) * size));
                pic(g, t.tex, l, tp, r, b, Tiles.SIZE);
            }
        }
    }

    private void pic(GuiGraphicsExtractor g, Identifier id, int l, int t, int r, int b, int size) {
        if (alpha >= 255) g.blit(id, l, t, r, b, 0, 1, 0, 1);
        else g.blit(RenderPipelines.GUI_TEXTURED, id, l, t, 0, 0, r - l, b - t, size, size, size, size, alpha << 24 | 0xFFFFFF);
    }

    /** extraOpen: chunks open right now although the fog file doesn't know it yet (players standing there). */
    public void drawFog(GuiGraphicsExtractor g, int color, Iterable<double[]> extraOpen) {
        Map<String, byte[]> fog = Data.fogOf(world);
        if (fog == null) return;
        if (alpha < 255) color = (((color >>> 24) * alpha / 255) << 24) | (color & 0xFFFFFF);
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
                else g.blit(Icons.fog(world, Site.tileTheme(), key, bits, color).id(), l, t, r, b, 0, 1, 0, 1);
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
}
