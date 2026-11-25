package land.beezz.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the map knows about the server, kept in step with the site the same way its own page does it:
 * players every 2 s (Pl3xMap's settings.json), the public places every minute, the fog of war every 10 minutes
 * (scripts/map_fog.py rewrites it as often). Only while the map is open: a closed map asks for nothing.
 */
final class Data {
    /** The worlds the site shows, in its order. */
    static final List<String> KNOWN = List.of("world", "world_nether", "world_the_end", "world_minecraft_farm", "world_minecraft_farm_nether");

    record World(String name, String renderer) {}
    record Player(String name, String world, double x, double z) {}
    record Point(String id, String name, String description, String world, String icon, int color, int x, int y, int z, String by) {}

    static volatile List<World> worlds = List.of();
    static volatile List<Player> players = List.of();
    static volatile List<Point> points = List.of();
    /** world -> region "x_z" -> 128 bytes, a bit per chunk: someone has been there. */
    static final Map<String, Map<String, byte[]>> fog = new HashMap<>();
    /** Pl3xMap's block index -> map colour (0x000000 = nothing to paint). */
    static volatile int[] blockColor;

    static volatile long syncedAt; // when players last came in
    static volatile boolean down; // the last request failed
    static volatile int fogVersion; // grows whenever new fog arrives, so the screen rebuilds its pictures

    static final ExecutorService NET = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "BeezzMap net");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static long lastPlayers, lastPoints, lastFog;
    private static final Map<String, Long> fogAsked = new HashMap<>();

    private Data() {}

    /** Called every client tick while the map is open. */
    static void tick(String world) {
        long now = System.currentTimeMillis();
        if (busy.get()) return;
        boolean players = now - lastPlayers >= 2000, pts = now - lastPoints >= 60_000;
        Long asked = fogAsked.get(world);
        boolean fogDue = asked == null || now - asked >= 600_000;
        if (!players && !pts && !fogDue && blockColor != null) return;
        busy.set(true);
        if (players) lastPlayers = now;
        if (pts) lastPoints = now;
        if (fogDue) fogAsked.put(world, now);
        NET.execute(() -> {
            try {
                if (blockColor == null) loadPalette();
                if (players) loadSettings();
                if (pts) loadPoints();
                if (fogDue) loadFog(world);
                down = false;
            } catch (Exception e) {
                down = true;
                lastPlayers = System.currentTimeMillis(); // try again in two seconds, not every tick
                BeezzMap.LOG.debug("BeezzMap sync failed", e);
            } finally {
                busy.set(false);
            }
        });
    }

    static World world(String name) {
        for (World w : worlds) if (w.name.equals(name)) return w;
        return null;
    }

    private static void loadSettings() throws Exception {
        String s = Api.text("/map/tiles/settings.json");
        if (s == null) throw new IllegalStateException("no settings.json");
        JsonObject d = JsonParser.parseString(s).getAsJsonObject();
        List<Player> pl = new ArrayList<>();
        for (JsonElement e : arr(d, "players")) {
            JsonObject p = e.getAsJsonObject();
            if (!p.has("position") || !p.get("position").isJsonObject()) continue; // hidden with /map hide
            JsonObject pos = p.getAsJsonObject("position");
            pl.add(new Player(str(p, "name"), str(p, "world"), pos.get("x").getAsDouble(), pos.get("z").getAsDouble()));
        }
        pl.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        players = List.copyOf(pl);
        if (worlds.isEmpty()) {
            List<World> ws = new ArrayList<>();
            for (JsonElement e : arr(d, "worldSettings")) {
                JsonObject w = e.getAsJsonObject();
                String name = str(w, "name");
                JsonArray r = arr(w, "renderers");
                if (KNOWN.contains(name) && !r.isEmpty()) ws.add(new World(name, str(r.get(0).getAsJsonObject(), "label")));
            }
            ws.sort((a, b) -> KNOWN.indexOf(a.name) - KNOWN.indexOf(b.name));
            worlds = List.copyOf(ws);
        }
        syncedAt = System.currentTimeMillis();
    }

    private static void loadPoints() throws Exception {
        String s = Api.text("/api/map/points");
        if (s == null) return;
        List<Point> out = new ArrayList<>();
        for (JsonElement e : arr(JsonParser.parseString(s).getAsJsonObject(), "points")) {
            JsonObject p = e.getAsJsonObject();
            if (!"ok".equals(str(p, "status"))) continue; // a guest sees only the approved ones anyway
            out.add(new Point(str(p, "id"), str(p, "name"), str(p, "description"), str(p, "world"), str(p, "icon"),
                    p.has("color") ? p.get("color").getAsInt() : 0, num(p, "x"), num(p, "y"), num(p, "z"), str(p, "by")));
        }
        points = List.copyOf(out);
    }

    private static void loadFog(String world) throws Exception {
        String s = Api.text("/atlas/fog/" + world + ".txt");
        Map<String, byte[]> regions = new HashMap<>();
        if (s != null) {
            JsonObject r = JsonParser.parseString(s).getAsJsonObject().getAsJsonObject("regions");
            for (Map.Entry<String, JsonElement> e : r.entrySet()) regions.put(e.getKey(), Base64.getDecoder().decode(e.getValue().getAsString()));
        }
        synchronized (fog) {
            if (s == null) fog.remove(world);
            else fog.put(world, regions);
        }
        fogVersion++;
    }

    /** null: no fog known for this world (then nothing is hidden). */
    static Map<String, byte[]> fogOf(String world) {
        synchronized (fog) {
            return fog.get(world);
        }
    }

    private static void loadPalette() throws Exception {
        // the colours of blocks, shipped with the mod; Pl3xMap's own numbering of blocks, from the site
        Map<String, Integer> byName = new HashMap<>();
        try (var in = Data.class.getResourceAsStream("/assets/beezzmap/colors.txt")) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split(" ");
                int c = Integer.parseInt(parts[0], 16);
                for (int i = 1; i < parts.length; i++) byName.put("minecraft:" + parts[i], c);
            }
        }
        String s = Api.text("/map/tiles/blocks.gz");
        if (s == null) throw new IllegalStateException("no blocks.gz");
        int[] colors = new int[2048];
        java.util.Arrays.fill(colors, 0x707070);
        for (Map.Entry<String, JsonElement> e : JsonParser.parseString(s).getAsJsonObject().entrySet()) {
            Integer c = byName.get(e.getValue().getAsString());
            int i = Integer.parseInt(e.getKey());
            if (c != null && i >= 0 && i < colors.length) colors[i] = c;
        }
        blockColor = colors;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    /** Was there a player near this chunk? Unknown regions are fog. */
    static boolean seen(byte[] bits, int cx, int cz) {
        if (bits == null || cx < 0 || cz < 0 || cx >= 32 || cz >= 32) return false;
        int i = cx + cz * 32;
        return ((bits[i >> 3] >> (i & 7)) & 1) != 0;
    }
}
