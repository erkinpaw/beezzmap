package land.beezz.map;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The map on the whole screen. Hold a mouse button and move to drag it (WASD and the arrows move it too), the wheel and
 * +/- zoom, a click on a player keeps them in the middle, the right button opens a menu: a mark of your own here, or a
 * place for everyone suggested on the site. The map is drawn in real window pixels, like the site's canvas, so its blocks
 * stay square and sharp at every zoom; the panels use the game's own GUI scale.
 */
public final class MapScreen extends Screen {
    // [ink, soft, gold, panel, fog] for each theme: the site's colours and the parchment of the mod
    static final int[] BEEZZ = {0xFFE7EEFB, 0xFF9AA8BF, 0xFFFACC15, 0xE6141A24, 0xFF0B0F17};
    static final int[] PAPYRUS = {0xFF3A2616, 0xFF6B4A2B, 0xFFB88C2C, 0xEEF6ECD0, 0xFFDECCA0};
    private static final int GREEN = 0xFF4ADE80;

    private record Hit(int x, int y, int w, int h, Runnable action, String id) {
        boolean in(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private static final class Glide {
        double fx, fz, tx, tz;
        long t0;
    }

    /** The view stays as it was when the map is opened again. */
    static final MapView view = new MapView();
    static String follow;
    private static boolean placed;
    private static boolean settingsOpen;
    private static int placesScroll;

    private final List<Hit> hits = new ArrayList<>();
    private static final Map<String, Glide> glides = new HashMap<>();
    private static List<Data.Player> seenPlayers;
    // dragging lasts from the press to the release and reads the cursor every frame instead of trusting move events:
    // some input mods deliver them differently
    private boolean dragging;
    private double lastRawX, lastRawY, dragDist;
    private int dragButton = -1;
    /** The right-click menu: where it opened (GUI pixels) and the block under it. */
    private int menuX = -1, menuY;
    private int menuBX, menuBZ;
    private Marks.Mark menuMark;

    public MapScreen() {
        super(Component.translatable("beezzmap.title"));
    }

    static int[] theme() {
        return "papyrus".equals(BeezzMap.config.theme) ? PAPYRUS : BEEZZ;
    }

    /** Opened with the key: on the server, it starts where you are. */
    static MapScreen open(Minecraft mc) {
        boolean here = BeezzMap.onBeezzLand(mc) && mc.player != null;
        if (!placed) {
            view.s = here ? 1 : 0.5;
            placed = true;
        }
        if (here && follow == null) {
            String w = BeezzMap.worldName(mc);
            if (Data.KNOWN.contains(w)) setWorld(w);
            view.cx = mc.player.getX();
            view.cz = mc.player.getZ();
        }
        return new MapScreen();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the map updates live; in single player the game keeps going too
    }

    private static int gs() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, theme()[4]);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();
        Data.tick(view.world);
        Tiles.pump();
        Icons.pump();
        Icons.trimFog();
        hits.clear();
        int[] th = theme();
        if (Data.world(view.world) == null && !Data.worlds.isEmpty()) setWorld(Data.worlds.get(0).name());
        glide();
        dragStep(mc, win);
        keysStep(mc);
        if (follow != null) {
            double[] p = playerPos(follow);
            if (p == null) follow = null;
            else {
                view.cx += (p[0] - view.cx) * .2;
                view.cz += (p[1] - view.cz) * .2;
            }
        }
        String me = BeezzMap.onBeezzLand(mc) && mc.player != null ? mc.player.getName().getString() : null;
        boolean meHere = me != null && view.world.equals(BeezzMap.worldName(mc));

        // ---- the map, in window pixels
        view.x0 = 0;
        view.y0 = 0;
        view.w = win.getWidth();
        view.h = win.getHeight();
        int gs = gs();
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        view.drawTiles(g);
        if (BeezzMap.config.fog) {
            List<double[]> open = new ArrayList<>();
            for (Data.Player p : Data.players) if (p.world().equals(view.world)) open.add(new double[] {p.x(), p.z()});
            if (meHere) open.add(new double[] {mc.player.getX(), mc.player.getZ()});
            view.drawFog(g, th[4], open);
        }
        view.drawGrid(g, th == BEEZZ);
        g.pose().popMatrix();

        // ---- what is on the map, in GUI pixels
        List<Component> tip = null;
        if (BeezzMap.config.showPlaces) {
            for (Data.Point p : Data.points) {
                if (!p.world().equals(view.world)) continue;
                int x = gx(p.x() + .5), y = gy(p.z() + .5);
                if (x < -60 || y < -20 || x > width + 60 || y > height + 30) continue;
                Icons.Pic ic = Icons.icon(p.icon(), p.color());
                if (ic != null) g.blit(ic.id(), x - 8, y - 8, x + 8, y + 8, 0, 1, 0, 1);
                else g.fill(x - 3, y - 3, x + 3, y + 3, th[2]);
                label(g, p.name(), x, y + 9, th[0], th);
                if (mx >= x - 9 && mx <= x + 9 && my >= y - 9 && my <= y + 19) {
                    tip = new ArrayList<>();
                    tip.add(Component.literal(p.name()).withColor(0xFACC15));
                    if (!p.description().isBlank()) tip.add(Component.literal(p.description()));
                    tip.add(Component.literal(p.x() + ", " + p.y() + ", " + p.z()).withColor(0x9AA8BF));
                    if (!p.by().isBlank()) tip.add(Component.translatable("beezzmap.by", p.by()).withColor(0x9AA8BF));
                }
            }
        }
        if (BeezzMap.config.showMarks) {
            for (Marks.Mark m : Marks.all()) {
                if (!m.world.equals(view.world)) continue;
                int x = gx(m.x + .5), y = gy(m.z + .5);
                if (x < -60 || y < -20 || x > width + 60 || y > height + 30) continue;
                drawMark(g, x, y, m.color, 6);
                label(g, m.name, x, y + 8, 0xFF000000 | m.color, th);
                if (mx >= x - 7 && mx <= x + 7 && my >= y - 7 && my <= y + 18) {
                    tip = List.of(Component.literal(m.name).withColor(m.color),
                            Component.literal(m.x + ", " + m.y + ", " + m.z).withColor(0x9AA8BF),
                            Component.translatable("beezzmap.mark.hint").withColor(0x9AA8BF));
                }
            }
        }
        if (BeezzMap.config.showPlayers) {
            for (Data.Player pl : Data.players) {
                if (!pl.world().equals(view.world) || pl.name().equalsIgnoreCase(me)) continue;
                double[] p = playerPos(pl.name());
                if (p == null) continue;
                int x = gx(p[0]), y = gy(p[1]);
                String nick = pl.name();
                boolean fol = nick.equals(follow);
                if (x < 8 || y < 8 || x > width - 8 || y > height - 8) { // off the screen: a head at the edge, pointing to them
                    // the edge between the panels: under the hint at the top, above the buttons at the bottom
                    int ex = Math.max(150, Math.min(width - 150, x)), ey = Math.max(36, Math.min(height - 40, y));
                    double ang = Math.atan2(y - ey, x - ex);
                    int ax = ex + (int) Math.round(Math.cos(ang) * 10), ay = ey + (int) Math.round(Math.sin(ang) * 10);
                    g.fill(ax - 2, ay - 2, ax + 2, ay + 2, th[2]);
                    drawFace(g, nick, ex, ey, 5, fol ? th[2] : 0xFFFFFFFF);
                    hits.add(new Hit(ex - 6, ey - 6, 12, 12, () -> followPlayer(nick), ""));
                    if (Math.abs(mx - ex) <= 6 && Math.abs(my - ey) <= 6) tip = List.of(Component.literal(nick),
                            Component.translatable("beezzmap.away", (int) Math.hypot(p[0] - view.cx, p[1] - view.cz)).withColor(0x9AA8BF));
                    continue;
                }
                drawFace(g, nick, x, y, 6, fol ? th[2] : 0xFFFFFFFF);
                label(g, nick, x, y + 9, fol ? th[2] : th[0], th);
                hits.add(new Hit(x - 7, y - 7, 14, 14, () -> followPlayer(nick), ""));
                if (mx >= x - 7 && mx <= x + 7 && my >= y - 7 && my <= y + 7)
                    tip = List.of(Component.literal(nick), Component.literal((int) pl.x() + ", " + (int) pl.z()).withColor(0x9AA8BF));
            }
        }
        if (meHere) { // you, live from the game, not two seconds late
            int x = gx(mc.player.getX()), y = gy(mc.player.getZ());
            arrow(g, x, y, mc.player.getYRot(), 7, th[2]);
        }

        drawPlaque(g, mx, my, th);
        drawOnline(g, mx, my, th, me);
        drawBottom(g, mx, my, th, me);
        if (settingsOpen) drawSettings(g, mx, my, th);
        if (menuX >= 0) drawMenu(g, mx, my, th, me);
        else if (tip != null && !dragging) g.setComponentTooltipForNextFrame(font, tip, mx, my);
        super.extractRenderState(g, mx, my, pt);
    }

    private int gx(double x) {
        return (int) Math.round(view.sx(x) / gs());
    }

    private int gy(double z) {
        return (int) Math.round(view.sz(z) / gs());
    }

    private void label(GuiGraphicsExtractor g, String text, int x, int y, int color, int[] th) {
        String s = font.plainSubstrByWidth(text, 110);
        int w = font.width(s);
        g.fill(x - w / 2 - 2, y, x + w / 2 + 2, y + 10, th[3]);
        g.text(font, s, x - w / 2, y + 1, color, false);
    }

    static void drawFace(GuiGraphicsExtractor g, String nick, int x, int y, int r, int ring) {
        g.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, ring);
        g.fill(x - r, y - r, x + r, y + r, 0xFF000000);
        Icons.Pic head = Icons.head(nick);
        if (head != null) g.blit(head.id(), x - r, y - r, x + r, y + r, 0, 1, 0, 1);
    }

    /** A pin: a coloured diamond with a dark edge. */
    static void drawMark(GuiGraphicsExtractor g, int x, int y, int color, int r) {
        for (int i = -r - 1; i <= r + 1; i++) {
            int half = r + 1 - Math.abs(i);
            g.fill(x - half, y + i, x + half + 1, y + i + 1, 0xFF1A1A1A);
        }
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x + half + 1, y + i + 1, 0xFF000000 | color);
        }
        g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
    }

    /** Where you look: an arrow turned by the player's yaw (0 = south, as in the game). */
    static void arrow(GuiGraphicsExtractor g, int x, int y, float yaw, int r, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate((float) Math.toRadians(yaw + 180));
        for (int i = -r; i <= r; i++) { // a triangle pointing up (north before turning)
            int half = (int) Math.round((i + r) * 0.45);
            g.fill(-half - 1, i - 1, half + 2, i + 1, 0xFF000000);
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            int half = (int) Math.round((i + r) * 0.4);
            g.fill(-half, i, half + 1, i + 1, color);
        }
        g.pose().popMatrix();
    }

    /** Top left: the title, the worlds, and the places of this world (public ones and your own marks). */
    private void drawPlaque(GuiGraphicsExtractor g, int mx, int my, int[] th) {
        int x = 6, y = 6, w = 128;
        int ry = y + 19;
        int worldsH = Math.max(1, Data.worlds.size()) * 12;
        g.fill(x, y, x + w, y + 22 + worldsH, th[3]);
        g.fill(x, y, x + w, y + 1, th[2]);
        g.text(font, Component.translatable("beezzmap.title"), x + 6, y + 6, th[2], false);
        for (Data.World dw : Data.worlds) {
            boolean on = dw.name().equals(view.world), hov = mx >= x && mx < x + w && my >= ry && my < ry + 12;
            if (on) g.fill(x + 3, ry, x + w - 3, ry + 11, 0x40FACC15);
            else if (hov) g.fill(x + 3, ry, x + w - 3, ry + 11, 0x20FFFFFF);
            g.text(font, Component.translatable("beezzmap.world." + dw.name()), x + 8, ry + 2, on ? th[2] : th[0], false);
            String name = dw.name();
            hits.add(new Hit(x, ry, w, 12, () -> setWorld(name), "world:" + name));
            ry += 12;
        }

        // places
        record Row(String name, int color, Runnable go, boolean mine) {}
        List<Row> rows = new ArrayList<>();
        for (Data.Point p : Data.points) if (p.world().equals(view.world))
            rows.add(new Row(p.name(), th[0], () -> flyTo(p.x() + .5, p.z() + .5), false));
        for (Marks.Mark m : Marks.all()) if (m.world.equals(view.world))
            rows.add(new Row(m.name, 0xFF000000 | m.color, () -> flyTo(m.x + .5, m.z + .5), true));
        int py = y + 26 + worldsH, fit = Math.max(1, (height - 60 - py) / 12);
        placesScroll = Math.max(0, Math.min(placesScroll, rows.size() - fit));
        int shown = Math.min(rows.size(), fit);
        g.fill(x, py, x + w, py + 20 + Math.max(1, shown) * 12, th[3]);
        g.fill(x, py, x + w, py + 1, th[2]);
        g.text(font, Component.translatable("beezzmap.places", rows.size()), x + 6, py + 6, th[0], false);
        int r = py + 18;
        if (rows.isEmpty()) g.text(font, Component.translatable("beezzmap.places.none"), x + 8, r + 2, th[1], false);
        for (int i = placesScroll; i < placesScroll + shown; i++) {
            Row row = rows.get(i);
            boolean hov = mx >= x && mx < x + w && my >= r && my < r + 12;
            if (hov) g.fill(x + 3, r, x + w - 3, r + 11, 0x20FFFFFF);
            if (row.mine()) g.fill(x + 8, r + 3, x + 13, r + 8, row.color());
            else g.fill(x + 8, r + 4, x + 12, r + 8, th[2]);
            g.text(font, font.plainSubstrByWidth(row.name(), w - 24), x + 17, r + 2, row.mine() ? row.color() : th[0], false);
            hits.add(new Hit(x, r, w, 12, row.go(), "place:" + row.name()));
            r += 12;
        }
        if (rows.size() > shown) g.text(font, (placesScroll + 1) + "–" + (placesScroll + shown) + " / " + rows.size(), x + w - 50, py + 6, th[1], false);
    }

    /** Top right: everyone online (Pl3xMap's list: whoever wrote /map hide is not there). */
    private void drawOnline(GuiGraphicsExtractor g, int mx, int my, int[] th, String me) {
        List<Data.Player> list = Data.players;
        int w = 128, x = width - w - 6, y = 6;
        int fit = Math.max(1, (height - 80) / 13), shown = Math.min(list.size(), fit);
        int h = 20 + Math.max(1, shown) * 13 + (list.size() > shown ? 11 : 0);
        g.fill(x, y, x + w, y + h, th[3]);
        g.fill(x, y, x + w, y + 1, th[2]);
        g.fill(x + 6, y + 8, x + 10, y + 12, list.isEmpty() ? th[1] : GREEN);
        g.text(font, Component.translatable("beezzmap.online", list.size()), x + 14, y + 6, th[0], false);
        int ry = y + 19;
        if (list.isEmpty()) g.text(font, Component.translatable("beezzmap.nobody"), x + 8, ry + 2, th[1], false);
        for (int i = 0; i < shown; i++) {
            Data.Player pl = list.get(i);
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + 13, fol = pl.name().equals(follow);
            if (fol) g.fill(x + 3, ry, x + w - 3, ry + 12, 0x40FACC15);
            else if (hov) g.fill(x + 3, ry, x + w - 3, ry + 12, 0x20FFFFFF);
            Icons.Pic head = Icons.head(pl.name());
            if (head != null) g.blit(head.id(), x + 7, ry + 1, x + 17, ry + 11, 0, 1, 0, 1);
            else g.fill(x + 7, ry + 1, x + 17, ry + 11, 0xFF303848);
            g.text(font, font.plainSubstrByWidth(pl.name(), 72), x + 21, ry + 2, fol || pl.name().equalsIgnoreCase(me) ? th[2] : th[0], false);
            if (!pl.world().equals(view.world)) {
                String tag = Component.translatable("beezzmap.world.short." + pl.world()).getString();
                g.text(font, tag, x + w - 6 - font.width(tag), ry + 2, th[1], false);
            }
            String nick = pl.name();
            hits.add(new Hit(x, ry, w, 13, () -> followPlayer(nick), "player:" + nick));
            ry += 13;
        }
        if (list.size() > shown) g.text(font, "+" + (list.size() - shown), x + 8, ry + 1, th[1], false);
    }

    /** Bottom: scale, the block under the cursor, how fresh the data is, and the buttons. */
    private void drawBottom(GuiGraphicsExtractor g, int mx, int my, int[] th, String me) {
        int y = height - 20, gs = gs();
        double s = view.s;
        int len = 1;
        for (int v : new int[] {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000}) if (v * s / gs <= 70) len = v;
        int bar = (int) Math.round(len * s / gs);
        int wx = (int) Math.floor(view.bx(mx * gs)), wz = (int) Math.floor(view.bz(my * gs));
        Component status;
        if (Data.down) status = Component.translatable("beezzmap.down");
        else if (Data.syncedAt == 0) status = Component.translatable("beezzmap.loading");
        else status = Component.translatable("beezzmap.synced", (System.currentTimeMillis() - Data.syncedAt) / 1000);
        String coords = "X " + wx + "  Z " + wz;
        int w = Math.max(bar + 60, Math.max(font.width(coords), font.width(status))) + 14;
        g.fill(6, y - 24, 6 + w, y + 14, th[3]);
        g.fill(12, y - 16, 12 + bar, y - 14, th[0]);
        g.fill(12, y - 19, 13, y - 14, th[0]);
        g.fill(11 + bar, y - 19, 12 + bar, y - 14, th[0]);
        g.text(font, Component.translatable("beezzmap.blocks", len), 16 + bar, y - 21, th[1], false);
        g.text(font, coords, 12, y - 9, th[0], false);
        g.text(font, status, 12, y + 2, Data.down ? 0xFFF87171 : th[1], false);

        int bx = width - 6;
        bx = button(g, mx, my, bx, y - 2, Component.translatable("beezzmap.settings"), th, () -> settingsOpen = !settingsOpen, "settings");
        bx = button(g, mx, my, bx, y - 2, Component.literal("+"), th, () -> view.zoomAt(view.w / 2, view.h / 2, 1.5, true), "zoomin");
        bx = button(g, mx, my, bx, y - 2, Component.literal("−"), th, () -> view.zoomAt(view.w / 2, view.h / 2, 1 / 1.5, true), "zoomout");
        Minecraft mc = Minecraft.getInstance();
        if (me != null) button(g, mx, my, bx, y - 2, Component.translatable("beezzmap.me"), th, () -> {
            follow = null;
            String w0 = BeezzMap.worldName(mc);
            if (Data.KNOWN.contains(w0)) setWorld(w0);
            view.cx = mc.player.getX();
            view.cz = mc.player.getZ();
            if (view.s < 1) view.s = 2;
        }, "me");
        Component top = follow != null ? Component.translatable("beezzmap.follow", follow) : Component.translatable("beezzmap.hint");
        int fw = font.width(top) + 12;
        if (fw < width - 2 * 140) { // between the places and the online list
            g.fill(width / 2 - fw / 2, 6, width / 2 + fw / 2, 20, th[3]);
            g.text(font, top, width / 2 - fw / 2 + 6, 9, follow != null ? th[2] : th[1], false);
        }
    }

    private void drawSettings(GuiGraphicsExtractor g, int mx, int my, int[] th) {
        BeezzMap.Config c = BeezzMap.config;
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {Component.translatable("beezzmap.theme").append(": ").append(Component.translatable("beezzmap.theme." + c.theme)), (Runnable) () -> {
            c.theme = "papyrus".equals(c.theme) ? "beezz" : "papyrus";
            Tiles.clear();
        }, "theme"});
        rows.add(new Object[] {onOff("beezzmap.fog", c.fog), (Runnable) () -> c.fog = !c.fog, "fog"});
        rows.add(new Object[] {onOff("beezzmap.show.places", c.showPlaces), (Runnable) () -> c.showPlaces = !c.showPlaces, "places"});
        rows.add(new Object[] {onOff("beezzmap.show.marks", c.showMarks), (Runnable) () -> c.showMarks = !c.showMarks, "marks"});
        rows.add(new Object[] {onOff("beezzmap.show.players", c.showPlayers), (Runnable) () -> c.showPlayers = !c.showPlayers, "players"});
        rows.add(new Object[] {onOff("beezzmap.minimap", c.minimap), (Runnable) () -> c.minimap = !c.minimap, "minimap"});
        rows.add(new Object[] {Component.translatable("beezzmap.minimap.size").append(": ").append(Component.translatable("beezzmap.minimap.size." + c.minimapSize)),
                (Runnable) () -> c.minimapSize = (c.minimapSize + 1) % 3, "minisize"});
        int w = 0;
        for (Object[] r : rows) w = Math.max(w, font.width((Component) r[0]));
        w += 20;
        int x = width - 6 - w, h = 8 + rows.size() * 14, y = height - 26 - h;
        hits.add(new Hit(x, y, w, h, () -> {}, "")); // a click on the panel between its rows doesn't close it
        g.fill(x, y, x + w, y + h, th[3]);
        g.fill(x, y, x + w, y + 1, th[2]);
        int ry = y + 4;
        for (Object[] r : rows) {
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + 14;
            if (hov) g.fill(x + 3, ry, x + w - 3, ry + 13, 0x20FFFFFF);
            g.text(font, (Component) r[0], x + 10, ry + 3, hov ? th[2] : th[0], false);
            Runnable a = (Runnable) r[1];
            hits.add(new Hit(x, ry, w, 14, () -> {
                a.run();
                BeezzMap.save();
            }, "set:" + r[2]));
            ry += 14;
        }
    }

    private static Component onOff(String key, boolean on) {
        return Component.translatable(key).append(on ? ": ✔" : ": ✖");
    }

    private void drawMenu(GuiGraphicsExtractor g, int mx, int my, int[] th, String me) {
        List<Object[]> rows = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (menuMark != null) {
            Marks.Mark m = menuMark;
            rows.add(new Object[] {Component.translatable("beezzmap.mark.edit"), (Runnable) () -> mc.gui.setScreen(new MarkScreen(this, m, false)), "edit"});
            rows.add(new Object[] {Component.translatable("beezzmap.mark.delete"), (Runnable) () -> Marks.remove(m), "delete"});
        } else {
            rows.add(new Object[] {Component.translatable("beezzmap.mark.here"), (Runnable) () -> newMark(menuBX, 64, menuBZ), "markhere"});
            if (me != null && view.world.equals(BeezzMap.worldName(mc))) rows.add(new Object[] {Component.translatable("beezzmap.mark.mine"),
                    (Runnable) () -> newMark(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()), "markme"});
            rows.add(new Object[] {Component.translatable("beezzmap.suggest"), (Runnable) () -> ConfirmLinkScreen.confirmLinkNow(this,
                    URI.create(String.format(Locale.ROOT, "%s/map.html#w=%s&x=%d&z=%d&s=3", Api.SITE, view.world, menuBX, menuBZ))), "suggest"});
        }
        int w = 0;
        for (Object[] r : rows) w = Math.max(w, font.width((Component) r[0]));
        w += 16;
        int h = 18 + rows.size() * 14;
        int x = Math.min(menuX, width - w - 4), y = Math.min(menuY, height - h - 4);
        hits.add(new Hit(x, y, w, h, () -> {}, "")); // a click on the card between its rows doesn't close it
        g.fill(x, y, x + w, y + h, 0xF0141A24);
        g.fill(x, y, x + w, y + 1, 0xFFFACC15);
        g.text(font, menuBX + ", " + menuBZ, x + 8, y + 5, 0xFF9AA8BF, false);
        int ry = y + 16;
        for (Object[] r : rows) {
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + 14;
            if (hov) g.fill(x + 2, ry, x + w - 2, ry + 13, 0x30FACC15);
            g.text(font, (Component) r[0], x + 8, ry + 3, hov ? 0xFFFACC15 : 0xFFE7EEFB, false);
            Runnable a = (Runnable) r[1];
            hits.add(new Hit(x, ry, w, 14, () -> {
                menuX = -1;
                a.run();
            }, "menu:" + r[2]));
            ry += 14;
        }
    }

    /** A button whose right edge is at x; returns where the next one ends. */
    private int button(GuiGraphicsExtractor g, int mx, int my, int x, int y, Component label, int[] th, Runnable action, String id) {
        int w = font.width(label) + 12, l = x - w;
        boolean hov = mx >= l && mx < x && my >= y && my < y + 16;
        g.fill(l, y, x, y + 16, th[3]);
        g.fill(l, y + 15, x, y + 16, hov ? th[2] : th[1]);
        g.text(font, label, l + 6, y + 4, hov ? th[2] : th[0], false);
        hits.add(new Hit(l, y, w, 16, action, id));
        return l - 4;
    }

    // ------------------------------------------------------------------ players gliding between updates

    static void glide() {
        List<Data.Player> now = Data.players;
        if (now == seenPlayers) return;
        long t = System.currentTimeMillis();
        Map<String, Glide> next = new HashMap<>();
        for (Data.Player p : now) {
            Glide old = glides.get(p.name()), gl = new Glide();
            if (old != null) {
                double[] cur = at(old, t);
                gl.fx = cur[0];
                gl.fz = cur[1];
            } else {
                gl.fx = p.x();
                gl.fz = p.z();
            }
            gl.tx = p.x();
            gl.tz = p.z();
            gl.t0 = t;
            next.put(p.name(), gl);
        }
        glides.clear();
        glides.putAll(next);
        seenPlayers = now;
    }

    private static double[] at(Glide g, long t) {
        double k = Math.min(1, (t - g.t0) / 2000.0);
        return new double[] {g.fx + (g.tx - g.fx) * k, g.fz + (g.tz - g.fz) * k};
    }

    static double[] playerPos(String nick) {
        Glide g = glides.get(nick);
        return g == null ? null : at(g, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ actions

    static void setWorld(String w) {
        if (w.equals(view.world)) return;
        view.world = w;
        follow = null;
        Tiles.clear();
        view.cx = 0;
        view.cz = 0;
    }

    void followPlayer(String nick) {
        for (Data.Player p : Data.players) {
            if (!p.name().equals(nick)) continue;
            setWorld(p.world());
            follow = nick;
            view.cx = p.x();
            view.cz = p.z();
            if (view.s < 1) view.s = 2;
            return;
        }
    }

    private void flyTo(double x, double z) {
        follow = null;
        view.cx = x;
        view.cz = z;
        if (view.s < 1.5) view.s = 3;
    }

    void newMark(int x, int y, int z) {
        Marks.Mark m = new Marks.Mark();
        m.world = view.world;
        m.x = x;
        m.y = y;
        m.z = z;
        m.color = Marks.COLORS[Marks.all().size() % Marks.COLORS.length];
        Minecraft.getInstance().gui.setScreen(new MarkScreen(this, m, true));
    }

    private Marks.Mark markAt(double mx, double my) {
        if (!BeezzMap.config.showMarks) return null;
        for (Marks.Mark m : Marks.all()) {
            if (!m.world.equals(view.world)) continue;
            if (Math.abs(gx(m.x + .5) - mx) <= 7 && Math.abs(gy(m.z + .5) - my) <= 7) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    private void dragStep(Minecraft mc, Window win) {
        if (!dragging) return;
        double rx = mc.mouseHandler.xpos(), ry = mc.mouseHandler.ypos();
        double k = (double) win.getWidth() / Math.max(1, win.getScreenWidth()); // screen coordinates -> framebuffer pixels
        double dx = (rx - lastRawX) * k, dy = (ry - lastRawY) * k;
        lastRawX = rx;
        lastRawY = ry;
        if (dx != 0 || dy != 0) {
            dragDist += Math.abs(dx) + Math.abs(dy);
            if (dragDist > 6) follow = null;
            if (follow == null) {
                view.cx -= dx / view.s;
                view.cz -= dy / view.s;
            }
        }
    }

    /** Held movement keys, from the key events (not polled: input mods may run the keyboard on their own thread). */
    private final java.util.Set<Integer> held = new java.util.HashSet<>();
    private static final java.util.Set<Integer> MOVE = java.util.Set.of(InputConstants.KEY_A, InputConstants.KEY_D, InputConstants.KEY_W,
            InputConstants.KEY_S, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN);

    private long lastKeysStep;

    private void keysStep(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastKeysStep == 0 ? 0 : Math.min(0.25, (now - lastKeysStep) / 1e9);
        lastKeysStep = now;
        if (mc.gui.screen() != this || held.isEmpty()) return;
        double step = 900 * dt / view.s; // 900 window pixels a second, whatever the frame rate
        double dx = 0, dz = 0;
        if (held.contains(InputConstants.KEY_A) || held.contains(InputConstants.KEY_LEFT)) dx -= step;
        if (held.contains(InputConstants.KEY_D) || held.contains(InputConstants.KEY_RIGHT)) dx += step;
        if (held.contains(InputConstants.KEY_W) || held.contains(InputConstants.KEY_UP)) dz -= step;
        if (held.contains(InputConstants.KEY_S) || held.contains(InputConstants.KEY_DOWN)) dz += step;
        if (dx != 0 || dz != 0) {
            follow = null;
            view.cx += dx;
            view.cz += dz;
        }
    }

    @Override
    public void tick() {
        keysStep(Minecraft.getInstance()); // also between frames: frame limiters may skip drawing while the window is idle
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (held.contains(e.input())) keysStep(Minecraft.getInstance()); // the last bit of the press counts too
        held.remove(e.input());
        return super.keyReleased(e);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.in(e.x(), e.y())) {
                    h.action().run();
                    return true;
                }
            }
        }
        if (menuX >= 0) { // a click anywhere else closes the menu
            menuX = -1;
            return true;
        }
        if (settingsOpen && e.button() == 0) settingsOpen = false;
        Minecraft mc = Minecraft.getInstance();
        dragging = true;
        dragButton = e.button();
        dragDist = 0;
        lastRawX = mc.mouseHandler.xpos();
        lastRawY = mc.mouseHandler.ypos();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        boolean click = dragging && dragDist <= 6 && e.button() == dragButton;
        if (dragging) dragStep(Minecraft.getInstance(), Minecraft.getInstance().getWindow());
        dragging = false;
        if (click && e.button() == 1) { // the right button without moving: the menu
            openMenu(e.x(), e.y());
            return true;
        }
        if (click && e.button() == 0) {
            Marks.Mark m = markAt(e.x(), e.y());
            if (m != null) Minecraft.getInstance().gui.setScreen(new MarkScreen(this, m, false));
        }
        return true;
    }

    void openMenu(double x, double y) {
        menuX = (int) x;
        menuY = (int) y;
        menuBX = (int) Math.floor(view.bx(x * gs()));
        menuBZ = (int) Math.floor(view.bz(y * gs()));
        menuMark = markAt(x, y);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return dragging; // moved in dragStep, from the cursor itself
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (v == 0) return false;
        if (mx < 140 && my > 30) { // over the places: scroll the list
            placesScroll -= (int) Math.signum(v);
            return true;
        }
        view.zoomAt(mx * gs(), my * gs(), Math.pow(1.25, v), follow != null);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (BeezzMap.openKey.matches(e)) {
            onClose();
            return true;
        }
        if (MOVE.contains(e.input())) {
            if (held.isEmpty()) lastKeysStep = System.nanoTime();
            held.add(e.input());
            return true;
        }
        if (e.input() == InputConstants.KEY_EQUALS || e.input() == 334 /* numpad + */) {
            view.zoomAt(view.w / 2, view.h / 2, 1.5, true);
            return true;
        }
        if (e.input() == InputConstants.KEY_MINUS || e.input() == 333 /* numpad - */) {
            view.zoomAt(view.w / 2, view.h / 2, 1 / 1.5, true);
            return true;
        }
        if (e.input() == InputConstants.KEY_ESCAPE && (menuX >= 0 || settingsOpen)) {
            menuX = -1;
            settingsOpen = false;
            return true;
        }
        return super.keyPressed(e);
    }

    // ------------------------------------------------------------------ for the game test

    /** The middle of the clickable thing with this id, in GUI pixels. */
    double[] hitCenter(String id) {
        for (Hit h : hits) if (h.id().equals(id)) return new double[] {h.x() + h.w() / 2.0, h.y() + h.h() / 2.0};
        return null;
    }

    static void view(String w, double x, double z, double scale) {
        setWorld(w);
        view.cx = x;
        view.cz = z;
        view.s = scale;
        follow = null;
        placed = true;
    }
}
