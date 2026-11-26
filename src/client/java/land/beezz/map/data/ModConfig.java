package land.beezz.map.data;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Client settings, stored in {@code config/beezzmap-settings.json}. */
public class ModConfig {
    public static final float MIN_MM_ZOOM = 0.05f;
    public static final float MAX_MM_ZOOM = 16f;
    public static final int MIN_MM_SIZE = 48;
    public static final int MAX_MM_SIZE = 600;
    public static final int[] MINIMAP_SIZES = {80, 110, 140, 170, 220, 280};

    public boolean minimapEnabled = true;
    /** Minimap side in GUI pixels: any size, dragged by its corner in the layout editor. */
    public int minimapPx = 120;
    /** GUI pixels per block: any zoom (= and - keys, the wheel in the layout editor). */
    public float minimapZoomF = 1.5f;
    /** 0.2 … 1: how much of the game shows through the minimap. */
    public float minimapOpacity = 1f;
    /** 0 = top right, 1 = top left, 2 = bottom right, 3 = bottom left; used until the minimap is dragged somewhere. */
    public int minimapCorner = 0;
    /** Set once the minimap is dragged: its top left corner as a share of the free room on the screen (0…1). */
    public boolean minimapFree = false;
    public float minimapFx = 1f;
    public float minimapFy = 0f;
    public boolean minimapCoords = true;
    /** A round minimap instead of the square one. */
    public boolean minimapRound = false;
    public boolean minimapMarkers = true;
    public boolean drawingsVisible = true;
    public boolean markersVisible = true;
    public int markerSort = 0;
    public String theme = "papyrus";
    /** Shows a "Teleport" button on markers (needs command permission). */
    public boolean teleport = false;
    public boolean markerLabels = true;
    /** Caves: 0 off, 1 on, 2 by itself when the player is underground. */
    public int caveMode = 2;

    // BeezzLand: the site's map (play.beezzland.com/map.html) in the game
    public boolean siteTiles = true;
    public boolean sitePlaces = true;
    public boolean sitePlayers = true;

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            instance = Storage.readJson(file(), ModConfig.class, new ModConfig());
            instance.clamp();
        }
        return instance;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("beezzmap-settings.json");
    }

    private void clamp() {
        minimapPx = Math.max(MIN_MM_SIZE, Math.min(MAX_MM_SIZE, minimapPx));
        minimapZoomF = Math.max(MIN_MM_ZOOM, Math.min(MAX_MM_ZOOM, minimapZoomF));
        minimapOpacity = Math.max(0.2f, Math.min(1f, minimapOpacity));
        minimapCorner = Math.floorMod(minimapCorner, 4);
        minimapFx = Math.max(0, Math.min(1, minimapFx));
        minimapFy = Math.max(0, Math.min(1, minimapFy));
        caveMode = Math.floorMod(caveMode, 3);
    }

    public void save() {
        clamp();
        Storage.writeJson(file(), this);
    }

    public int minimapSize() {
        return minimapPx;
    }

    public float minimapZoom() {
        return minimapZoomF;
    }

    public void zoomMinimap(float k) {
        minimapZoomF = Math.max(MIN_MM_ZOOM, Math.min(MAX_MM_ZOOM, minimapZoomF * k));
    }
}
