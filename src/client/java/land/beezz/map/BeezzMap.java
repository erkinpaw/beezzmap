package land.beezz.map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import land.beezz.map.data.ModConfig;
import land.beezz.map.gui.Paper;
import land.beezz.map.gui.SettingsScreen;
import land.beezz.map.gui.T;
import land.beezz.map.gui.Theme;
import land.beezz.map.gui.TitleButton;
import land.beezz.map.gui.WorldsScreen;
import land.beezz.map.gui.MapScreen;
import land.beezz.map.gui.MinimapHud;
import land.beezz.map.map.MapManager;
import land.beezz.map.site.Site;

import java.util.List;

/**
 * BeezzMap: a world map and minimap with markers, screenshots, a ruler, drawings, caves and 8 designs (it grew out of
 * the MapMinecraft mod, github.com/erkinpaw/atlasmap). On BeezzLand it is the server's own map, the one on
 * play.beezzland.com/map.html: the whole explored world, the public places, the players online, the fog of war; and a
 * marker can go to that map for everyone.
 */
public class BeezzMap implements ClientModInitializer {
    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("BeezzMap");
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("beezzmap")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    @Override
    public void onInitializeClient() {
        ModKeys.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> MapManager.onJoin(mc)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(MapManager::onDisconnect));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapManager.onDisconnect());
        ClientChunkEvents.CHUNK_LOAD.register(MapManager::onChunkLoad);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("beezzmap", "minimap"), new MinimapHud());

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            loadTheme();
            if (screen instanceof TitleScreen) {
                addAtlasButton(screen, w);
            }
        });
    }

    private static boolean themeLoaded;

    /** Applies the saved UI design once the game directory (and so the config) is available. */
    private static void loadTheme() {
        if (!themeLoaded) {
            themeLoaded = true;
            Paper.apply(Theme.byId(ModConfig.get().theme));
        }
    }

    private void onTick(Minecraft mc) {
        loadTheme();
        MapManager.tick(mc);
        ModConfig cfg = ModConfig.get();
        while (ModKeys.OPEN_MAP.consumeClick()) {
            if (mc.gui.screen() == null) {
                MapScreen.openLive(mc);
            }
        }
        while (ModKeys.MARKER_LIST.consumeClick()) {
            if (mc.gui.screen() == null) {
                MapScreen s = MapScreen.openLive(mc);
                if (s != null) {
                    s.openMarkerList();
                }
            }
        }
        while (ModKeys.NEW_MARKER.consumeClick()) {
            if (mc.gui.screen() == null) {
                MapScreen s = MapScreen.openLive(mc);
                if (s != null) {
                    s.newMarkerAtPlayer();
                }
            }
        }
        while (ModKeys.TOGGLE_MINIMAP.consumeClick()) {
            cfg.minimapEnabled = !cfg.minimapEnabled;
            cfg.save();
        }
        while (ModKeys.MINIMAP_ZOOM_IN.consumeClick()) {
            cfg.zoomMinimap(1.25f);
            cfg.save();
        }
        while (ModKeys.MINIMAP_ZOOM_OUT.consumeClick()) {
            cfg.zoomMinimap(1 / 1.25f);
            cfg.save();
        }
        while (ModKeys.CAVES.consumeClick()) {
            cfg.caveMode = (cfg.caveMode + 1) % 3;
            cfg.save();
            if (mc.player != null) {
                mc.player.sendOverlayMessage(T.tr("caves.now", T.tr(new String[]{"off", "on", "caves.auto"}[cfg.caveMode])));
            }
        }
        // on BeezzLand the minimap shows the site's map: keep it fresh (the open map screen does it for its own view)
        if (cfg.minimapEnabled && mc.level != null && Site.isBeezz(MapManager.live()) && !(mc.gui.screen() instanceof MapScreen)) {
            Site.tick(mc.level.dimension().identifier().toString());
        }
        // in-map shortcuts are handled by the map screen itself
        ModKeys.RULER.consumeClick();
        ModKeys.EDITOR.consumeClick();
        ModKeys.TOGGLE_DRAWINGS.consumeClick();
    }

    private static void addAtlasButton(net.minecraft.client.gui.screens.Screen screen, int width) {
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        AbstractWidget anchor = null;
        for (AbstractWidget w : widgets) {
            if (w.getWidth() >= 150 && (anchor == null || w.getY() < anchor.getY())) {
                anchor = w;
            }
        }
        int x = anchor != null ? anchor.getX() + anchor.getWidth() + 4 : width / 2 + 104;
        int y = anchor != null ? anchor.getY() : 100;
        int step = 24;
        if (overlaps(widgets, x, y) || overlaps(widgets, x + step, y)) {
            x = anchor != null ? anchor.getX() - 24 : width / 2 - 124;
            step = -24;
        }
        Minecraft mc = Minecraft.getInstance();
        widgets.add(new TitleButton(x, y, "map", T.tr("atlas.button"), () -> mc.gui.setScreen(new WorldsScreen(screen))));
        widgets.add(new TitleButton(x + step, y, "gear", T.tr("tool.settings"), () -> mc.gui.setScreen(new SettingsScreen(screen))));
    }

    private static boolean overlaps(List<AbstractWidget> widgets, int x, int y) {
        for (AbstractWidget w : widgets) {
            if (x < w.getX() + w.getWidth() && x + 20 > w.getX() && y < w.getY() + w.getHeight() && y + 20 > w.getY()) {
                return true;
            }
        }
        return false;
    }
}
