package land.beezz.map.site;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Small pictures from the site, fetched once and kept: the places' icons (the game's own textures, the MapMinecraft
 * mod's markers, or a picture the author uploaded), players' heads, and the fog of war per region.
 */
public final class Icons {
    public record Pic(Identifier id, int w, int h) {}

    private static final Pattern CUSTOM = Pattern.compile("[ub]_[a-f0-9]{20}");
    private static final Pic NONE = new Pic(null, 0, 0);
    private static final Map<String, Pic> pics = new HashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> upload = new ConcurrentLinkedQueue<>();
    private static final Map<String, Pic> fogs = new HashMap<>();
    private static int ids, fogVersion = -1;

    private Icons() {}

    /** At the start of a frame, before anything is drawn: drop fog pictures that are out of date or too many. */
    public static void trimFog() {
        if (fogVersion == Data.fogVersion && fogs.size() <= 400) return; // players walking leave a trail of variants behind
        for (Pic p : fogs.values()) Minecraft.getInstance().getTextureManager().release(p.id());
        fogs.clear();
        fogVersion = Data.fogVersion;
    }

    public static void pump() {
        Runnable r;
        while ((r = upload.poll()) != null) r.run();
    }

    /** A place's icon, or null while it loads (or when it has none). */
    public static Pic icon(String icon, int color) {
        if (icon == null || icon.isEmpty()) icon = "pin";
        String ic = icon;
        if (CUSTOM.matcher(icon).matches()) return load("icon/" + icon, () -> Api.get("/map-shots/" + ic.substring(2) + ".png"), 0);
        if (!ic.matches("[a-z0-9_]{1,40}")) return null;
        return load("icon/" + icon + "/" + color, () -> {
            byte[] b = Api.get("/atlas/mc/" + ic + ".png"); // the game's texture: kept as it is
            if (b != null) return b;
            return Api.get("/atlas/" + (color != 0 ? "markers_tint/" : "markers/") + ic + ".png");
        }, color);
    }

    public static Pic head(String nick) {
        if (!nick.matches("[A-Za-z0-9_.]{1,32}")) return null;
        return load("head/" + nick.toLowerCase(), () -> Api.get("/head/" + URLEncoder.encode(nick, StandardCharsets.UTF_8) + "/32"), 0);
    }

    private interface Source {
        byte[] get() throws Exception;
    }

    private static Pic load(String key, Source src, int tint) {
        Pic p = pics.get(key);
        if (p != null) return p == NONE ? null : p;
        pics.put(key, NONE);
        Data.NET.execute(() -> {
            try {
                byte[] b = src.get();
                if (b == null) return;
                NativeImage img = NativeImage.read(b);
                if (tint != 0) { // the mod's recolouring: the light marker multiplied by the chosen colour
                    for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                        int c = img.getPixel(x, y);
                        int r = (c >> 16 & 255) * (tint >> 16 & 255) / 255, g = (c >> 8 & 255) * (tint >> 8 & 255) / 255, bl = (c & 255) * (tint & 255) / 255;
                        img.setPixel(x, y, (c & 0xFF000000) | r << 16 | g << 8 | bl);
                    }
                }
                upload.add(() -> {
                    Identifier id = Identifier.fromNamespaceAndPath("beezzmap", "pic/" + (ids++));
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "beezzmap " + key, img));
                    pics.put(key, new Pic(id, img.getWidth(), img.getHeight()));
                });
            } catch (Exception e) {
                land.beezz.map.BeezzMap.LOG.debug("picture {} failed", key, e);
            }
        });
        return null;
    }

    /**
     * The fog over one region, 32x32 (a pixel per chunk): solid where nobody has been. A chunk next to a visited one is
     * open too, as on the site: what a player saw around them, and thin trails stay visible.
     */
    public static Pic fog(String world, String theme, String region, byte[] bits, int color) {
        String key = world + "/" + theme + "/" + Integer.toHexString(color) + "/" + region;
        Pic p = fogs.get(key);
        if (p != null) return p;
        NativeImage img = new NativeImage(32, 32, true);
        for (int i = 0; i < 1024; i++) {
            int cx = i & 31, cz = i >> 5;
            boolean open = Data.seen(bits, cx, cz) || Data.seen(bits, cx - 1, cz) || Data.seen(bits, cx + 1, cz)
                    || Data.seen(bits, cx, cz - 1) || Data.seen(bits, cx, cz + 1);
            img.setPixel(cx, cz, open ? 0 : color);
        }
        Identifier id = Identifier.fromNamespaceAndPath("beezzmap", "fog/" + (ids++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "beezzmap fog " + key, img));
        p = new Pic(id, 32, 32);
        fogs.put(key, p);
        return p;
    }
}
