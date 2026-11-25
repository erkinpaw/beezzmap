package land.beezz.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BeezzMap: M opens the live world map of the BeezzLand server, the same one as play.beezzland.com/map.html: Pl3xMap's
 * tiles painted the site's way, the public places, everyone online with their heads, the fog of war. On BeezzLand itself
 * the map also shows where you are, live from the game.
 */
public final class BeezzMap implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("BeezzMap");
    static final String VERSION = FabricLoader.getInstance().getModContainer("beezzmap")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    public static KeyMapping openKey, minimapKey, zoomInKey, zoomOutKey;

    /** Kept between games in config/beezzmap.json. */
    static final class Config {
        String theme = "beezz";
        boolean fog = true;
        boolean showPlaces = true, showMarks = true, showPlayers = true;
        boolean minimap = true;
        int minimapSize = 1; // 0 small, 1 medium, 2 large
        double minimapZoom = 1; // window pixels per block
    }

    static Config config = new Config();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("beezzmap.json");
    }

    @Override
    public void onInitializeClient() {
        try {
            if (Files.exists(configFile())) config = GSON.fromJson(Files.readString(configFile()), Config.class);
            if (config == null) config = new Config();
        } catch (Exception e) {
            LOG.warn("BeezzMap: config unreadable, using defaults", e);
        }
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("beezzmap", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.beezzmap.open", InputConstants.KEY_M, cat));
        minimapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.beezzmap.minimap", InputConstants.KEY_N, cat));
        zoomInKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.beezzmap.zoomin", InputConstants.KEY_EQUALS, cat));
        zoomOutKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.beezzmap.zoomout", InputConstants.KEY_MINUS, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.gui.screen() == null) mc.gui.setScreen(MapScreen.open(mc));
            }
            while (minimapKey.consumeClick()) {
                config.minimap = !config.minimap;
                save();
            }
            while (zoomInKey.consumeClick()) Minimap.zoom(1.5);
            while (zoomOutKey.consumeClick()) Minimap.zoom(1 / 1.5);
            if (Minimap.visible(mc) && mc.gui.screen() == null) Data.tick(worldName(mc)); // the minimap keeps the players fresh too
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("beezzmap", "minimap"), (g, delta) -> Minimap.draw(g));
    }

    static void save() {
        try {
            Files.writeString(configFile(), GSON.toJson(config));
        } catch (Exception e) {
            LOG.warn("BeezzMap: config not saved", e);
        }
    }

    /** Playing on BeezzLand right now: then the map shows the player's own live position. */
    static boolean onBeezzLand(Minecraft mc) {
        if (Boolean.getBoolean("beezzmap.pretend")) return true; // the game test, in a local world
        ServerData s = mc.getCurrentServer();
        return s != null && s.ip != null && s.ip.toLowerCase().contains("beezzland");
    }

    /** The server's name of the world the player is in (Paper names its dimensions after the world folders). */
    static String worldName(Minecraft mc) {
        if (mc.level == null) return null;
        String path = mc.level.dimension().identifier().getPath();
        return switch (path) {
            case "overworld" -> "world";
            case "the_nether" -> "world_nether";
            case "the_end" -> "world_the_end";
            default -> path;
        };
    }
}
