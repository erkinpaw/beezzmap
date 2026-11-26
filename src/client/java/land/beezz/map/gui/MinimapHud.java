package land.beezz.map.gui;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import land.beezz.map.data.ModConfig;
import land.beezz.map.map.MapLayer;
import land.beezz.map.map.MapManager;
import land.beezz.map.map.MapSession;
import land.beezz.map.site.Data;
import land.beezz.map.site.Site;

import java.util.ArrayList;
import java.util.List;

/**
 * The minimap, north up. Any size, any zoom, anywhere on the screen (the layout editor drags it), as see-through as
 * wanted. On BeezzLand it shows the site's map with the public places and the players online; in caves the floor
 * under you.
 */
public class MinimapHud implements HudElement {
    private final MapView view = new MapView();

    /** Where the minimap goes on a screen this big: {x, y, size, text height}. */
    public static int[] rect(int sw, int sh, ModConfig cfg, boolean effects) {
        int size = Math.min(cfg.minimapSize(), Math.max(ModConfig.MIN_MM_SIZE, Math.min(sw, sh) - 24));
        int textH = cfg.minimapCoords ? 13 : 0;
        int margin = 8;
        int x;
        int y;
        if (cfg.minimapFree) {
            x = 4 + Math.round(cfg.minimapFx * Math.max(0, sw - size - 8));
            y = 4 + Math.round(cfg.minimapFy * Math.max(0, sh - size - textH - 8));
        } else {
            boolean right = cfg.minimapCorner == 0 || cfg.minimapCorner == 2;
            boolean top = cfg.minimapCorner < 2;
            x = right ? sw - size - margin : margin;
            y = top ? margin : sh - size - margin - textH;
            if (top && right && effects) {
                y += 26;
            }
        }
        return new int[]{x, y, size, textH};
    }

    /** Is there a minimap to draw right now? */
    public static boolean shown(Minecraft mc) {
        return ModConfig.get().minimapEnabled && MapManager.live() != null && mc.player != null && mc.level != null
                && !(mc.gui.screen() instanceof MapScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown(mc)) {
            return;
        }
        draw(g, mc, delta.getGameTimeDeltaPartialTick(true), mc.gui.screen() instanceof MinimapLayoutScreen);
    }

    void draw(GuiGraphicsExtractor g, Minecraft mc, float pt, boolean editing) {
        ModConfig cfg = ModConfig.get();
        MapSession session = MapManager.live();
        LocalPlayer p = mc.player;
        String dim = mc.level.dimension().identifier().toString();
        double px = p.xo + (p.getX() - p.xo) * pt;
        double pz = p.zo + (p.getZ() - p.zo) * pt;
        int[] r = rect(g.guiWidth(), g.guiHeight(), cfg, !p.getActiveEffects().isEmpty());
        int x = r[0];
        int y = r[1];
        int size = r[2];
        int textH = r[3];
        int alpha = Math.round(cfg.minimapOpacity * 255);
        boolean beezz = Site.isBeezz(session);
        MapLayer caves = MapManager.caveLayer(dim);
        boolean siteMap = beezz && cfg.siteTiles && caves == null && Site.hasWorld(dim);
        if (beezz) {
            Site.frame();
        }

        boolean round = cfg.minimapRound;
        // the frame: solid when the minimap is, else a thin edge only
        if (round) {
            ring(g, x + size / 2.0, y + size / 2.0, size / 2.0, alpha >= 250 ? 3 : 1, Paper.a(Paper.INK, Math.max(alpha, 0x80)));
        } else if (alpha >= 250) {
            Paper.panel(g, x - 4, y - 4, size + 8, size + 8 + textH);
        } else {
            g.fill(x - 2, y - 2, x + size + 2, y + size + 2, Paper.a(0xFF000000, alpha / 3));
        }
        view.setRect(x, y, size, size);
        view.cx = px;
        view.cz = pz;
        view.zoom = cfg.minimapZoom();
        view.alpha = alpha;
        view.round = round;
        MapView.newFrame();

        Runnable layers = () -> drawLayers(g, cfg, session, dim, caves, siteMap, alpha, px, pz);
        if (round) {
            // GUI scissors are rectangles: the circle is drawn as one-pixel rows, each cut to the circle's width there
            double rad = size / 2.0;
            for (int row = 0; row < size; row++) {
                double dy = row + 0.5 - rad;
                int half = (int) Math.round(Math.sqrt(Math.max(0, rad * rad - dy * dy)));
                if (half <= 0) {
                    continue;
                }
                g.enableScissor(x + size / 2 - half, y + row, x + size / 2 + half, y + row + 1);
                layers.run();
                g.disableScissor();
            }
        } else {
            g.enableScissor(x, y, x + size, y + size);
            layers.run();
            g.disableScissor();
        }
        g.enableScissor(x - 2, y - 2, x + size + 2, y + size + 2);
        if (cfg.minimapMarkers && cfg.markersVisible) {
            int ms = Math.max(8, Math.min(14, size / 10));
            if (beezz && cfg.sitePlaces) {
                view.drawMarkers(g, mc.font, Site.markers(), dim, ms - 2, false, null, null, false);
            }
            view.drawMarkers(g, mc.font, session.markers, dim, ms, false, null, null, true);
        }
        if (beezz && cfg.sitePlayers) {
            view.drawPlayers(g, mc.font, dim, Math.max(6, Math.min(10, size / 14)), true, false, -1, -1, null);
        }
        g.disableScissor();
        if (!round) {
            g.outline(x - 1, y - 1, size + 2, size + 2, Paper.a(Paper.INK_SOFT, Math.max(alpha, 0x60)));
        }
        view.drawPlayer(g, px, pz, p.getYRot(), Math.max(9, Math.min(16, size / 9)));
        drawTop(g, mc, cfg, p, caves, alpha, x, y, size, textH, editing);
    }

    /** The map itself under the markers: caves, or the mod's own picture and the site's with its fog; the drawings. */
    private void drawLayers(GuiGraphicsExtractor g, ModConfig cfg, MapSession session, String dim, MapLayer caves, boolean siteMap,
                            int alpha, double px, double pz) {
        int x = view.x;
        int y = view.y;
        int size = view.w;
        if (caves != null) {
            g.fill(x, y, x + size, y + size, Paper.a(0xFF0E0C0B, alpha));
            view.drawTiles(g, caves);
        } else {
            if (alpha >= 250) {
                view.drawBackground(g);
            }
            view.drawTiles(g, session.layer(dim), siteMap ? dim : null);
            if (siteMap) {
                view.drawSiteTiles(g, dim);
                { // the fog of war is always there, as on the site
                    List<double[]> open = new ArrayList<>();
                    open.add(new double[]{px, pz});
                    for (Data.Player pl : Site.players(dim)) {
                        open.add(new double[]{pl.x(), pl.z()});
                    }
                    view.drawSiteFog(g, dim, open);
                }
            }
        }
        if (cfg.drawingsVisible && alpha >= 250) {
            view.drawStrokes(g, session.strokes(dim), 0.75f);
        }
    }

    /** A ring of width w around a circle, drawn row by row. */
    private static void ring(GuiGraphicsExtractor g, double cx, double cy, double rad, int w, int color) {
        double out = rad + w;
        for (int row = (int) Math.floor(cy - out); row < cy + out; row++) {
            double dy = row + 0.5 - cy;
            double ho = Math.sqrt(Math.max(0, out * out - dy * dy));
            double hi = Math.abs(dy) < rad ? Math.sqrt(rad * rad - dy * dy) : 0;
            int lo = (int) Math.round(cx - ho);
            int li = (int) Math.round(cx - hi);
            int ri = (int) Math.round(cx + hi);
            int ro = (int) Math.round(cx + ho);
            if (hi == 0) {
                g.fill(lo, row, ro, row + 1, color);
            } else {
                g.fill(lo, row, li, row + 1, color);
                g.fill(ri, row, ro, row + 1, color);
            }
        }
    }

    private void drawTop(GuiGraphicsExtractor g, Minecraft mc, ModConfig cfg, LocalPlayer p, MapLayer caves, int alpha,
                         int x, int y, int size, int textH, boolean editing) {
        // north marker
        int nx = x + size / 2;
        g.fill(nx - 5, y - 6, nx + 5, y + 4, Paper.INK);
        g.fill(nx - 4, y - 5, nx + 4, y + 3, Paper.CREAM);
        g.text(mc.font, "N", nx - 2, y - 4, Paper.RED, false);
        if (caves != null) {
            String c = T.s("caves.badge", MapManager.caveY());
            int cw = mc.font.width(c);
            int bx = x + (size - cw) / 2;
            g.fill(bx - 3, y + size - 14, bx + cw + 3, y + size - 4, 0xB0000000);
            g.text(mc.font, c, bx, y + size - 13, 0xFFE0D2B0, false);
        }

        if (cfg.minimapCoords) {
            String c = p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
            int cx = x + (size - mc.font.width(c)) / 2;
            if (alpha < 250) {
                g.fill(cx - 3, y + size + 2, cx + mc.font.width(c) + 3, y + size + 12, 0x90000000);
                g.text(mc.font, c, cx, y + size + 3, 0xFFFFFFFF, false);
            } else {
                g.text(mc.font, c, cx, y + size + 3, Paper.INK, false);
            }
        }
        if (editing) {
            g.outline(x - 5, y - 5, size + 10, size + 10 + textH, Paper.GOLD_LIGHT);
            // the corner handle to resize
            g.fill(x + size - 3, y + size + textH - 3, x + size + 5, y + size + textH + 5, Paper.GOLD);
            g.outline(x + size - 3, y + size + textH - 3, 8, 8, Paper.INK);
        }
    }
}
