package land.beezz.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The minimap in the top right corner, only while playing on BeezzLand: the same tiles as the big map around you, north
 * up, with the places, your marks, the other players and your arrow. N hides it, = and - zoom it.
 */
final class Minimap {
    private static final int[] SIZES = {90, 120, 150};
    private static final MapView view = new MapView();

    private Minimap() {}

    static boolean visible(Minecraft mc) {
        return BeezzMap.config.minimap && mc.player != null && BeezzMap.onBeezzLand(mc);
    }

    static void zoom(double k) {
        BeezzMap.config.minimapZoom = Math.max(0.25, Math.min(8, BeezzMap.config.minimapZoom * k));
        BeezzMap.save();
    }

    static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc) || mc.gui.screen() instanceof MapScreen || mc.gui.screen() instanceof MarkScreen) return;
        Tiles.pump();
        Icons.pump();
        MapScreen.glide();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        int size = SIZES[Math.max(0, Math.min(2, BeezzMap.config.minimapSize))];
        int x = g.guiWidth() - size - 6, y = 6;
        int[] th = MapScreen.theme();
        String world = BeezzMap.worldName(mc);
        view.world = world;
        view.cx = mc.player.getX();
        view.cz = mc.player.getZ();
        view.s = BeezzMap.config.minimapZoom;
        view.x0 = x * gs;
        view.y0 = y * gs;
        view.w = size * gs;
        view.h = size * gs;

        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF000000);
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, th[2]);
        g.fill(x, y, x + size, y + size, th[4]);
        g.enableScissor(x, y, x + size, y + size);
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        view.drawTiles(g);
        g.pose().popMatrix();

        if (BeezzMap.config.showPlaces) for (Data.Point p : Data.points) {
            if (!p.world().equals(world)) continue;
            int px = gx(p.x() + .5, gs), py = gy(p.z() + .5, gs);
            Icons.Pic ic = Icons.icon(p.icon(), p.color());
            if (ic != null) g.blit(ic.id(), px - 5, py - 5, px + 5, py + 5, 0, 1, 0, 1);
            else g.fill(px - 2, py - 2, px + 2, py + 2, th[2]);
        }
        if (BeezzMap.config.showMarks) for (Marks.Mark m : Marks.all()) {
            if (!m.world.equals(world)) continue;
            int mx = gx(m.x + .5, gs), my = gy(m.z + .5, gs);
            // marks out of reach sit on the edge, so you can always tell which way they are
            mx = Math.max(x + 4, Math.min(x + size - 4, mx));
            my = Math.max(y + 4, Math.min(y + size - 4, my));
            MapScreen.drawMark(g, mx, my, m.color, 3);
        }
        String me = mc.player.getName().getString();
        if (BeezzMap.config.showPlayers) for (Data.Player pl : Data.players) {
            if (!pl.world().equals(world) || pl.name().equalsIgnoreCase(me)) continue;
            double[] p = MapScreen.playerPos(pl.name());
            if (p == null) continue;
            int px = Math.max(x + 5, Math.min(x + size - 5, gx(p[0], gs))), py = Math.max(y + 5, Math.min(y + size - 5, gy(p[1], gs)));
            MapScreen.drawFace(g, pl.name(), px, py, 4, 0xFFFFFFFF);
        }
        MapScreen.arrow(g, x + size / 2, y + size / 2, mc.player.getYRot(), 5, th[2]);
        g.disableScissor();

        // north, and where you are
        g.text(mc.font, "N", x + size / 2 - 2, y + 2, 0xFFFFFFFF, true);
        String c = mc.player.getBlockX() + ", " + mc.player.getBlockY() + ", " + mc.player.getBlockZ();
        int cw = mc.font.width(c);
        g.fill(x + size / 2 - cw / 2 - 3, y + size + 3, x + size / 2 + cw / 2 + 3, y + size + 14, th[3]);
        g.text(mc.font, c, x + size / 2 - cw / 2, y + size + 5, th[0], false);
    }

    private static int gx(double bx, int gs) {
        return (int) Math.round(view.sx(bx) / gs);
    }

    private static int gy(double bz, int gs) {
        return (int) Math.round(view.sz(bz) / gs);
    }
}
