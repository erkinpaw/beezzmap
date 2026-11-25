package land.beezz.map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A card over the map to name a mark, pick its colour and fix its coordinates; also deletes it. */
final class MarkScreen extends Screen {
    private static final int W = 220, H = 150;
    private final MapScreen parent;
    private final Marks.Mark mark;
    private final boolean isNew;
    private EditBox name, x, y, z;
    private int color;

    MarkScreen(MapScreen parent, Marks.Mark mark, boolean isNew) {
        super(Component.translatable(isNew ? "beezzmap.mark.new" : "beezzmap.mark.edit"));
        this.parent = parent;
        this.mark = mark;
        this.isNew = isNew;
        this.color = mark.color;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    @Override
    protected void init() {
        int l = left(), t = top();
        name = new EditBox(font, l + 10, t + 30, W - 20, 18, Component.translatable("beezzmap.mark.name"));
        name.setMaxLength(40);
        name.setHint(Component.translatable("beezzmap.mark.name"));
        name.setValue(mark.name);
        addRenderableWidget(name);
        int cw = (W - 20 - 8) / 3;
        x = coord(l + 10, t + 78, cw, mark.x, "X");
        y = coord(l + 14 + cw, t + 78, cw, mark.y, "Y");
        z = coord(l + 18 + 2 * cw, t + 78, cw, mark.z, "Z");
        int bw = isNew ? (W - 24) / 2 : (W - 28) / 3;
        addRenderableWidget(Button.builder(Component.translatable("beezzmap.mark.save"), b -> save()).bounds(l + 10, t + H - 28, bw, 20).build());
        if (!isNew) addRenderableWidget(Button.builder(Component.translatable("beezzmap.mark.delete"), b -> {
            Marks.remove(mark);
            back();
        }).bounds(l + 14 + bw, t + H - 28, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> back())
                .bounds(l + W - 10 - bw, t + H - 28, bw, 20).build());
        setInitialFocus(name);
    }

    @Override
    public void tick() {
        // some mods move the starting focus away: until the player picks a field, the name is where typing goes
        if (getFocused() == null) {
            setFocused(name);
            name.setFocused(true);
        }
    }

    private EditBox coord(int bx, int by, int w, int v, String label) {
        EditBox e = new EditBox(font, bx, by, w, 18, Component.literal(label));
        e.setMaxLength(9);
        e.setValue(String.valueOf(v));
        addRenderableWidget(e);
        return e;
    }

    private static int num(EditBox e, int fallback) {
        try {
            return Integer.parseInt(e.getValue().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    void save() {
        mark.name = name.getValue().isBlank() ? Component.translatable("beezzmap.mark.default").getString() : name.getValue().trim();
        mark.x = num(x, mark.x);
        mark.y = num(y, mark.y);
        mark.z = num(z, mark.z);
        mark.color = color;
        Marks.put(mark);
        back();
    }

    private void back() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        parent.extractBackground(g, -1, -1, pt);
        parent.extractRenderState(g, -1, -1, pt); // the map stays live under the card
        g.nextStratum();
        g.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int l = left(), t = top();
        g.fill(l, t, l + W, t + H, 0xF0141A24);
        g.fill(l, t, l + W, t + 1, 0xFFFACC15);
        g.text(font, title, l + 10, t + 9, 0xFFFACC15, false);
        g.text(font, Component.translatable("beezzmap.mark.color"), l + 10, t + 54, 0xFF9AA8BF, false);
        for (int i = 0; i < Marks.COLORS.length; i++) {
            int cx = l + 52 + i * 16, cy = t + 52;
            if (Marks.COLORS[i] == color) g.fill(cx - 2, cy - 2, cx + 14, cy + 14, 0xFFFFFFFF);
            g.fill(cx, cy, cx + 12, cy + 12, 0xFF000000 | Marks.COLORS[i]);
        }
        int cw = (W - 20 - 8) / 3;
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) g.text(font, axes[i], l + 10 + i * (cw + 4), t + 69, 0xFF9AA8BF, false);
        super.extractRenderState(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        int l = left(), t = top();
        for (int i = 0; i < Marks.COLORS.length; i++) {
            int cx = l + 52 + i * 16, cy = t + 52;
            if (e.x() >= cx - 2 && e.x() < cx + 14 && e.y() >= cy - 2 && e.y() < cy + 14) {
                color = Marks.COLORS[i];
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.input() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN) {
            save();
            return true;
        }
        return super.keyPressed(e);
    }
}
