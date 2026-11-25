package land.beezz.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The map on the whole screen. Drag to move, the wheel zooms around the cursor, a click on a player (on the map or in the
 * list) keeps them in the middle. The map itself is drawn in real window pixels, like the site's canvas, so its blocks
 * stay square and sharp at every zoom; the panels use the game's own GUI scale.
 */
public final class MapScreen extends Screen {
    // [ink, soft, gold, panel, fog] for each theme: the site's colours and the parchment of the mod
    private static final int[] BEEZZ = {0xFFE7EEFB, 0xFF9AA8BF, 0xFFFACC15, 0xE6141A24, 0xFF0B0F17};
    private static final int[] PAPYRUS = {0xFF3A2616, 0xFF6B4A2B, 0xFFB88C2C, 0xEEF6ECD0, 0xFFDECCA0};
    private static final int GREEN = 0xFF4ADE80;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private static final class Glide {
        double fx, fz, tx, tz;
        long t0;
    }

    // the view stays as it was when the map is opened again
    static String world = "world";
    static double cx, cz, s = -1; // the block in the middle; window pixels per block
    static String follow;
    private static boolean placed;

    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Glide> glides = new HashMap<>();
    private List<Data.Player> seenPlayers;
    private boolean dragging;
    private double downX, downY;

    public MapScreen() {
        super(Component.translatable("beezzmap.title"));
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (!placed || s <= 0) {
            s = 1;
            if (BeezzMap.onBeezzLand(mc) && mc.player != null) {
                String w = BeezzMap.worldName(mc);
                if (Data.KNOWN.contains(w)) world = w;
                cx = mc.player.getX();
                cz = mc.player.getZ();
                s = 2;
            }
            placed = true;
        }
        if (BeezzMap.onBeezzLand(mc) && mc.player != null && follow == null) { // opened on the server: start where you are
            String w = BeezzMap.worldName(mc);
            if (Data.KNOWN.contains(w) && !w.equals(world)) {
                world = w;
                Tiles.clear();
            }
            cx = mc.player.getX();
            cz = mc.player.getZ();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the map updates live; in single player the game keeps going too
    }

    private int[] theme() {
        return "papyrus".equals(BeezzMap.config.theme) ? PAPYRUS : BEEZZ;
    }

    private int maxOut() {
        return 3; // Pl3xMap's zoom levels 0..3 on BeezzLand
    }

    private int gs() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    // window pixels <-> blocks
    private double pxW() {
        return Minecraft.getInstance().getWindow().getWidth();
    }

    private double pxH() {
        return Minecraft.getInstance().getWindow().getHeight();
    }

    private double sx(double x) {
        return (x - cx) * s + pxW() / 2;
    }

    private double sz(double z) {
        return (z - cz) * s + pxH() / 2;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, theme()[4]);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getInstance();
        Data.tick(world);
        Tiles.pump();
        Icons.pump();
        Icons.trimFog();
        hits.clear();
        int[] th = theme();
        Data.World w = Data.world(world);
        if (w == null && !Data.worlds.isEmpty()) w = Data.world(world = Data.worlds.get(0).name());
        glide();
        if (follow != null) {
            double[] p = playerPos(follow);
            if (p == null) follow = null;
            else {
                cx += (p[0] - cx) * .2;
                cz += (p[1] - cz) * .2;
            }
        }

        // ---- the map, in window pixels
        g.pose().pushMatrix();
        g.pose().scale(1f / gs(), 1f / gs());
        if (w != null) {
            int want = Math.max(0, Math.min(maxOut(), (int) Math.ceil(Math.log(1 / s) / Math.log(2) - 0.001)));
            for (int n = maxOut(); n > want; n--) drawLevel(g, w, n, n == maxOut()); // coarse ones first: never an empty hole
            drawLevel(g, w, want, true);
            if (BeezzMap.config.fog) drawFog(g, th[4]);
            drawGrid(g);
        }
        g.pose().popMatrix();

        // ---- what is on the map, in GUI pixels
        Data.Point hovered = null;
        int gs = gs();
        for (Data.Point p : Data.points) {
            if (!p.world().equals(world)) continue;
            int x = (int) Math.round(sx(p.x() + .5) / gs), y = (int) Math.round(sz(p.z() + .5) / gs);
            if (x < -60 || y < -20 || x > width + 60 || y > height + 30) continue;
            Icons.Pic ic = Icons.icon(p.icon(), p.color());
            if (ic != null) g.blit(ic.id(), x - 8, y - 8, x + 8, y + 8, 0, 1, 0, 1);
            else g.fill(x - 3, y - 3, x + 3, y + 3, th[2]);
            String name = font.plainSubstrByWidth(p.name(), 110);
            int nw = font.width(name);
            g.fill(x - nw / 2 - 2, y + 9, x + nw / 2 + 2, y + 19, th[3]);
            g.text(font, name, x - nw / 2, y + 10, th[0], false);
            if (mx >= x - 9 && mx <= x + 9 && my >= y - 9 && my <= y + 19) hovered = p;
        }
        String me = BeezzMap.onBeezzLand(mc) && mc.player != null ? mc.player.getName().getString() : null;
        Data.Player hoverPl = null;
        for (Data.Player pl : Data.players) {
            if (!pl.world().equals(world) || pl.name().equalsIgnoreCase(me)) continue;
            double[] p = playerPos(pl.name());
            int x = (int) Math.round(sx(p[0]) / gs), y = (int) Math.round(sz(p[1]) / gs);
            if (x < -40 || y < -20 || x > width + 40 || y > height + 30) continue;
            drawHead(g, pl.name(), x, y, follow != null && follow.equals(pl.name()) ? th[2] : 0xFFFFFFFF, th);
            String nick = pl.name();
            hits.add(new Hit(x - 7, y - 7, 14, 14, () -> followPlayer(nick)));
            if (mx >= x - 7 && mx <= x + 7 && my >= y - 7 && my <= y + 7) hoverPl = pl;
        }
        if (me != null && world.equals(BeezzMap.worldName(mc))) { // you, live from the game, not two seconds late
            int x = (int) Math.round(sx(mc.player.getX()) / gs), y = (int) Math.round(sz(mc.player.getZ()) / gs);
            drawHead(g, me, x, y, th[2], th);
        }

        drawPlaque(g, mx, my, th);
        drawOnline(g, mx, my, th, me);
        drawBottom(g, mx, my, th, me);

        if (hovered != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hovered.name()).withColor(0xFACC15));
            if (!hovered.description().isBlank()) lines.add(Component.literal(hovered.description()));
            lines.add(Component.literal(hovered.x() + ", " + hovered.y() + ", " + hovered.z()).withColor(0x9AA8BF));
            if (!hovered.by().isBlank()) lines.add(Component.translatable("beezzmap.by", hovered.by()).withColor(0x9AA8BF));
            g.setComponentTooltipForNextFrame(font, lines, mx, my);
        } else if (hoverPl != null) {
            g.setComponentTooltipForNextFrame(font, List.of(Component.literal(hoverPl.name()),
                    Component.literal((int) hoverPl.x() + ", " + (int) hoverPl.z()).withColor(0x9AA8BF)), mx, my);
        }
        super.extractRenderState(g, mx, my, pt);
    }

    private void drawLevel(GuiGraphicsExtractor g, Data.World w, int n, boolean load) {
        double size = Tiles.SIZE << n;
        double x0 = cx - pxW() / 2 / s, z0 = cz - pxH() / 2 / s, x1 = cx + pxW() / 2 / s, z1 = cz + pxH() / 2 / s;
        for (int tx = (int) Math.floor(x0 / size); tx <= Math.floor(x1 / size); tx++) {
            for (int tz = (int) Math.floor(z0 / size); tz <= Math.floor(z1 / size); tz++) {
                Tiles.Tile t = Tiles.get(BeezzMap.config.theme, w.name(), w.renderer(), maxOut(), n, tx, tz, load);
                if (t == null || t.tex == null) continue;
                // whole pixels, or thin seams show between the tiles
                int l = (int) Math.round(sx(tx * size)), tp = (int) Math.round(sz(tz * size));
                int r = (int) Math.round(sx((tx + 1) * size)), b = (int) Math.round(sz((tz + 1) * size));
                g.blit(t.tex, l, tp, r, b, 0, 1, 0, 1);
            }
        }
    }

    private void drawFog(GuiGraphicsExtractor g, int color) {
        Map<String, byte[]> fog = Data.fogOf(world);
        if (fog == null) return;
        // where players stand right now is open too: the fog file is rewritten only every ten minutes
        Map<String, java.util.TreeSet<Integer>> live = new HashMap<>();
        for (Data.Player p : Data.players) if (p.world().equals(world)) openAround(live, (int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4);
        Minecraft mc = Minecraft.getInstance();
        if (BeezzMap.onBeezzLand(mc) && mc.player != null && world.equals(BeezzMap.worldName(mc))) openAround(live, mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
        double x0 = cx - pxW() / 2 / s, z0 = cz - pxH() / 2 / s, x1 = cx + pxW() / 2 / s, z1 = cz + pxH() / 2 / s;
        for (int rx = (int) Math.floor(x0 / 512); rx <= Math.floor(x1 / 512); rx++) {
            for (int rz = (int) Math.floor(z0 / 512); rz <= Math.floor(z1 / 512); rz++) {
                int l = (int) Math.round(sx(rx * 512.0)), t = (int) Math.round(sz(rz * 512.0));
                int r = (int) Math.round(sx((rx + 1) * 512.0)), b = (int) Math.round(sz((rz + 1) * 512.0));
                String key = rx + "_" + rz;
                byte[] bits = fog.get(key);
                java.util.TreeSet<Integer> extra = live.get(key);
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
    private static void openAround(Map<String, java.util.TreeSet<Integer>> live, int chx, int chz) {
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx * dx + dz * dz > 10) continue;
            int x = chx + dx, z = chz + dz;
            live.computeIfAbsent((x >> 5) + "_" + (z >> 5), k -> new java.util.TreeSet<>()).add((x & 31) + (z & 31) * 32);
        }
    }

    /** The mod's faint chunk grid, only up close. */
    private void drawGrid(GuiGraphicsExtractor g) {
        if (s < 4) return;
        boolean dark = theme() == BEEZZ;
        int c = dark ? (s >= 8 ? 0x1CFFFFFF : 0x12FFFFFF) : (s >= 8 ? 0x223A2616 : 0x163A2616);
        double x0 = cx - pxW() / 2 / s, z0 = cz - pxH() / 2 / s, x1 = cx + pxW() / 2 / s, z1 = cz + pxH() / 2 / s;
        int H = (int) pxH(), W = (int) pxW();
        for (double x = Math.floor(x0 / 16) * 16; x <= x1; x += 16) {
            int px = (int) Math.round(sx(x));
            g.fill(px, 0, px + 1, H, c);
        }
        for (double z = Math.floor(z0 / 16) * 16; z <= z1; z += 16) {
            int pz = (int) Math.round(sz(z));
            g.fill(0, pz, W, pz + 1, c);
        }
    }

    private void drawHead(GuiGraphicsExtractor g, String nick, int x, int y, int ring, int[] th) {
        g.fill(x - 7, y - 7, x + 7, y + 7, ring);
        g.fill(x - 6, y - 6, x + 6, y + 6, 0xFF000000);
        Icons.Pic head = Icons.head(nick);
        if (head != null) g.blit(head.id(), x - 6, y - 6, x + 6, y + 6, 0, 1, 0, 1);
        int nw = font.width(nick);
        g.fill(x - nw / 2 - 2, y + 9, x + nw / 2 + 2, y + 19, th[3]);
        g.text(font, nick, x - nw / 2, y + 10, ring == th[2] ? th[2] : th[0], false);
    }

    /** Top left: the title and the worlds. */
    private void drawPlaque(GuiGraphicsExtractor g, int mx, int my, int[] th) {
        int x = 6, y = 6, w = 118;
        int rows = Math.max(1, Data.worlds.size());
        g.fill(x, y, x + w, y + 20 + rows * 13, th[3]);
        g.fill(x, y, x + w, y + 1, th[2]);
        g.text(font, Component.translatable("beezzmap.title"), x + 6, y + 6, th[2], false);
        int ry = y + 19;
        for (Data.World dw : Data.worlds) {
            boolean on = dw.name().equals(world), hov = mx >= x && mx < x + w && my >= ry && my < ry + 13;
            if (on) g.fill(x + 3, ry, x + w - 3, ry + 12, 0x40FACC15);
            else if (hov) g.fill(x + 3, ry, x + w - 3, ry + 12, 0x20FFFFFF);
            g.text(font, Component.translatable("beezzmap.world." + dw.name()), x + 8, ry + 2, on ? th[2] : th[0], false);
            String name = dw.name();
            hits.add(new Hit(x, ry, w, 13, () -> switchWorld(name)));
            ry += 13;
        }
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
            if (!pl.world().equals(world)) {
                String tag = switch (pl.world()) {
                    case "world" -> "☀";
                    case "world_nether", "world_minecraft_farm_nether" -> "🔥";
                    case "world_the_end" -> "✦";
                    default -> "⚒";
                };
                g.text(font, tag, x + w - 10 - font.width(tag), ry + 2, th[1], false);
            }
            String nick = pl.name();
            hits.add(new Hit(x, ry, w, 13, () -> followPlayer(nick)));
            ry += 13;
        }
        if (list.size() > shown) g.text(font, "+" + (list.size() - shown), x + 8, ry + 1, th[1], false);
    }

    /** Bottom: scale, the block under the cursor, how fresh the data is, and the switches. */
    private void drawBottom(GuiGraphicsExtractor g, int mx, int my, int[] th, String me) {
        int y = height - 20, gs = gs();
        // scale bar
        int len = 1;
        for (int v : new int[] {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000}) if (v * s / gs <= 70) len = v;
        int bar = (int) Math.round(len * s / gs);
        double bx = mx * gs, bz = my * gs;
        int wx = (int) Math.floor((bx - pxW() / 2) / s + cx), wz = (int) Math.floor((bz - pxH() / 2) / s + cz);
        Component status;
        if (Data.down) status = Component.translatable("beezzmap.down");
        else if (Data.syncedAt == 0 || Tiles.loading() > 0 && Data.blockColor == null) status = Component.translatable("beezzmap.loading");
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

        // switches, right to left
        int bx2 = width - 6;
        bx2 = button(g, mx, my, bx2, y - 2, Component.translatable("beezzmap.fog").append(BeezzMap.config.fog ? ": ✔" : ": ✖"), th, () -> {
            BeezzMap.config.fog = !BeezzMap.config.fog;
            BeezzMap.save();
        });
        bx2 = button(g, mx, my, bx2, y - 2, Component.translatable("beezzmap.theme").append(": ")
                .append(Component.translatable("beezzmap.theme." + BeezzMap.config.theme)), th, () -> {
            BeezzMap.config.theme = "papyrus".equals(BeezzMap.config.theme) ? "beezz" : "papyrus";
            BeezzMap.save();
            Tiles.clear();
        });
        Minecraft mc = Minecraft.getInstance();
        if (me != null) button(g, mx, my, bx2, y - 2, Component.translatable("beezzmap.me"), th, () -> {
            follow = null;
            String w0 = BeezzMap.worldName(mc);
            if (Data.KNOWN.contains(w0)) switchWorld(w0);
            cx = mc.player.getX();
            cz = mc.player.getZ();
            if (s < 1) s = 2;
        });
        if (follow != null) {
            Component f = Component.translatable("beezzmap.follow", follow);
            int fw = font.width(f) + 12;
            g.fill(width / 2 - fw / 2, 6, width / 2 + fw / 2, 20, th[3]);
            g.text(font, f, width / 2 - fw / 2 + 6, 9, th[2], false);
        } else {
            Component hint = Component.translatable("beezzmap.hint");
            int fw = font.width(hint) + 12;
            if (fw < width - 2 * 140) { // between the worlds and the online list
                g.fill(width / 2 - fw / 2, 6, width / 2 + fw / 2, 20, th[3]);
                g.text(font, hint, width / 2 - fw / 2 + 6, 9, th[1], false);
            }
        }
    }

    /** A button whose right edge is at x; returns where the next one ends. */
    private int button(GuiGraphicsExtractor g, int mx, int my, int x, int y, Component label, int[] th, Runnable action) {
        int w = font.width(label) + 12, l = x - w;
        boolean hov = mx >= l && mx < x && my >= y && my < y + 16;
        g.fill(l, y, x, y + 16, th[3]);
        g.fill(l, y + 15, x, y + 16, hov ? th[2] : th[1]);
        g.text(font, label, l + 6, y + 4, hov ? th[2] : th[0], false);
        hits.add(new Hit(l, y, w, 16, action));
        return l - 4;
    }

    // ------------------------------------------------------------------ players gliding between updates

    private void glide() {
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

    private double[] playerPos(String nick) {
        Glide g = glides.get(nick);
        return g == null ? null : at(g, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ actions

    private void switchWorld(String w) {
        if (w.equals(world)) return;
        world = w;
        follow = null;
        Tiles.clear();
        cx = 0;
        cz = 0;
    }

    void followPlayer(String nick) {
        for (Data.Player p : Data.players) {
            if (!p.name().equals(nick)) continue;
            if (!p.world().equals(world)) {
                world = p.world();
                Tiles.clear();
            }
            follow = nick;
            cx = p.x();
            cz = p.z();
            if (s < 1) s = 2;
            return;
        }
    }

    // ------------------------------------------------------------------ input

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
            dragging = true;
            downX = e.x();
            downY = e.y();
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = false;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging) return super.mouseDragged(e, dx, dy);
        if (follow != null && Math.hypot(e.x() - downX, e.y() - downY) > 3) follow = null;
        cx -= dx * gs() / s;
        cz -= dy * gs() / s;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (v == 0) return false;
        zoomAt(mx * gs(), my * gs(), Math.pow(1.25, v));
        return true;
    }

    /** Zoom so the block under this window pixel stays under it. */
    void zoomAt(double px, double py, double k) {
        double bx = (px - pxW() / 2) / s + cx, bz = (py - pxH() / 2) / s + cz;
        s = Math.max(1.0 / (1 << maxOut()) / 2, Math.min(16, s * k));
        if (follow == null) {
            cx = bx - (px - pxW() / 2) / s;
            cz = bz - (py - pxH() / 2) / s;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (BeezzMap.openKey.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    // for the game test
    static void view(String w, double x, double z, double scale) {
        if (!w.equals(world)) Tiles.clear();
        world = w;
        cx = x;
        cz = z;
        s = scale;
        follow = null;
        placed = true;
    }
}
