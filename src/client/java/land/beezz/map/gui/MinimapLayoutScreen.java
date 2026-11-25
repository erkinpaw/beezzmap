package land.beezz.map.gui;

import com.mojang.blaze3d.platform.InputConstants;
import land.beezz.map.data.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts the minimap where you want it, over the running game: drag it anywhere, drag its corner for the size, the wheel
 * zooms it; the buttons do the same plus the opacity.
 */
public class MinimapLayoutScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final Screen parent;
    private final ModConfig cfg = ModConfig.get();
    private final List<Hit> hits = new ArrayList<>();
    private int mode; // 0 nothing, 1 moving, 2 resizing
    private double grabX;
    private double grabY;
    private int startX;
    private int startY;
    private int startSize;

    public MinimapLayoutScreen(Screen parent) {
        super(T.tr("layout.title"));
        this.parent = parent;
    }

    private int[] rect() {
        return MinimapHud.rect(width, height, cfg, minecraft.player != null && !minecraft.player.getActiveEffects().isEmpty());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        // the game stays visible: the minimap is drawn by the HUD under this screen
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        hits.clear();
        if (!cfg.minimapEnabled || !MinimapHud.shown(minecraft)) {
            cfg.minimapEnabled = true;
        }
        int[] r = rect();
        g.outline(r[0] - 5, r[1] - 5, r[2] + 10, r[2] + 10 + r[3], Paper.GOLD_LIGHT);
        int hx = r[0] + r[2] - 3;
        int hy = r[1] + r[2] + r[3] - 3;
        g.fill(hx, hy, hx + 8, hy + 8, Paper.GOLD);
        g.outline(hx, hy, 8, 8, Paper.INK);

        // the panel goes to the other half of the screen from the minimap
        int pw = Math.min(width - 16, 250);
        int ph = 118;
        int px = (width - pw) / 2;
        int py = r[1] + r[2] / 2 < height / 2 ? height - ph - 30 : 30;
        Paper.panel(g, px, py, pw, ph);
        Paper.title(g, font, T.s("layout.title"), px + pw / 2, py + 7);
        int y = py + 20;
        for (var line : font.split(T.tr("layout.hint"), pw - 16)) {
            g.text(font, line, px + 8, y, Paper.INK_SOFT, false);
            y += 10;
        }
        y = py + 46;
        y = row(g, mx, my, px, y, pw, T.s("layout.size", cfg.minimapPx), () -> resize(-20), () -> resize(20));
        y = row(g, mx, my, px, y, pw, T.s("layout.zoom", zoomText(cfg.minimapZoomF)), () -> zoom(1 / 1.25f), () -> zoom(1.25f));
        y = row(g, mx, my, px, y, pw, T.s("layout.opacity", Math.round(cfg.minimapOpacity * 100)), () -> opacity(-0.1f), () -> opacity(0.1f));
        int bw = (pw - 24) / 2;
        boolean h1 = Paper.inside(mx, my, px + 8, y + 2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("undo"), T.tr("layout.reset"), px + 8, y + 2, bw, 16, h1, true);
        hits.add(new Hit(px + 8, y + 2, bw, 16, this::reset));
        boolean h2 = Paper.inside(mx, my, px + pw - 8 - bw, y + 2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.done"), px + pw - 8 - bw, y + 2, bw, 16, h2, true);
        hits.add(new Hit(px + pw - 8 - bw, y + 2, bw, 16, this::onClose));
    }

    private int row(GuiGraphicsExtractor g, int mx, int my, int px, int y, int pw, String label, Runnable less, Runnable more) {
        g.text(font, label, px + 10, y + 4, Paper.INK, false);
        int bx = px + pw - 8 - 38;
        boolean hl = Paper.inside(mx, my, bx, y, 18, 16);
        Paper.button(g, font, Component.literal("−"), bx, y, 18, 16, hl, true);
        hits.add(new Hit(bx, y, 18, 16, less));
        boolean hm = Paper.inside(mx, my, bx + 20, y, 18, 16);
        Paper.button(g, font, Component.literal("+"), bx + 20, y, 18, 16, hm, true);
        hits.add(new Hit(bx + 20, y, 18, 16, more));
        return y + 18;
    }

    private static String zoomText(float z) {
        return z >= 1 ? String.format(Locale.ROOT, "%.1f", z) : String.format(Locale.ROOT, "1/%.0f", 1 / z);
    }

    private void resize(int d) {
        cfg.minimapPx = Math.max(ModConfig.MIN_MM_SIZE, Math.min(ModConfig.MAX_MM_SIZE, cfg.minimapPx + d));
        cfg.save();
    }

    private void zoom(float k) {
        cfg.zoomMinimap(k);
        cfg.save();
    }

    private void opacity(float d) {
        cfg.minimapOpacity = Math.max(0.2f, Math.min(1f, Math.round((cfg.minimapOpacity + d) * 10) / 10f));
        cfg.save();
    }

    private void reset() {
        ModConfig def = new ModConfig();
        cfg.minimapFree = false;
        cfg.minimapCorner = def.minimapCorner;
        cfg.minimapPx = def.minimapPx;
        cfg.minimapZoomF = def.minimapZoomF;
        cfg.minimapOpacity = def.minimapOpacity;
        cfg.save();
    }

    /** Pins the minimap where its top left corner is now (as a share of the room, so it stays put on any window). */
    private void place(int x, int y, int size) {
        int[] r = rect();
        cfg.minimapFree = true;
        cfg.minimapFx = Math.max(0, Math.min(1, (x - 4) / (float) Math.max(1, width - size - 8)));
        cfg.minimapFy = Math.max(0, Math.min(1, (y - 4) / (float) Math.max(1, height - size - r[3] - 8)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Paper.inside(e.x(), e.y(), h.x, h.y, h.w, h.h)) {
                h.action.run();
                Paper.click();
                return true;
            }
        }
        int[] r = rect();
        grabX = e.x();
        grabY = e.y();
        startX = r[0];
        startY = r[1];
        startSize = r[2];
        if (Paper.inside(e.x(), e.y(), r[0] + r[2] - 6, r[1] + r[2] + r[3] - 6, 14, 14)) {
            mode = 2;
        } else if (Paper.inside(e.x(), e.y(), r[0] - 5, r[1] - 5, r[2] + 10, r[2] + r[3] + 10)) {
            mode = 1;
        } else {
            mode = 0;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        double ox = e.x() - grabX;
        double oy = e.y() - grabY;
        if (mode == 1) {
            place((int) Math.round(startX + ox), (int) Math.round(startY + oy), startSize);
        } else if (mode == 2) {
            int size = (int) Math.round(startSize + Math.max(ox, oy));
            cfg.minimapPx = Math.max(ModConfig.MIN_MM_SIZE, Math.min(Math.min(ModConfig.MAX_MM_SIZE, Math.min(width, height) - 24), size));
            place(startX, startY, cfg.minimapPx); // the top left corner stays where it is
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (mode != 0) {
            cfg.save();
        }
        mode = 0;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        zoom((float) Math.pow(1.15, sy));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.isEscape() || e.key() == InputConstants.KEY_RETURN) {
            onClose();
            return true;
        }
        if (e.key() == InputConstants.KEY_EQUALS || e.key() == InputConstants.KEY_ADD) {
            zoom(1.25f);
            return true;
        }
        if (e.key() == InputConstants.KEY_MINUS) {
            zoom(1 / 1.25f);
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        cfg.save();
        minecraft.gui.setScreen(parent);
    }
}
