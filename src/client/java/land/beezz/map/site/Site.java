package land.beezz.map.site;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import land.beezz.map.data.Marker;
import land.beezz.map.data.Storage;
import land.beezz.map.gui.Paper;
import land.beezz.map.gui.T;
import land.beezz.map.map.MapManager;
import land.beezz.map.map.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BeezzLand's own map, play.beezzland.com/map.html, inside the mod: on BeezzLand the map and the minimap show the site's
 * tiles (the whole explored world, rendered by Pl3xMap on the server), the public places as markers with their
 * screenshots, everyone online who isn't hidden with /map hide, and the fog of war. The player's own markers can be
 * sent to the site's map (an admin checks them there first, as with markers added on the site).
 */
public final class Site {
    /** The map folder every BeezzLand address shares (play, play1, play2…): one map, one set of markers. */
    public static final String WORLD_ID = "server_beezzland";

    private Site() {}

    // ------------------------------------------------------------------ where we are

    public static boolean isBeezzAddress(String ip) {
        return ip != null && ip.toLowerCase(java.util.Locale.ROOT).contains("beezzland");
    }

    /** Playing on BeezzLand right now (the game test pretends with -Dbeezzmap.pretend). */
    public static boolean playing(Minecraft mc) {
        if (Boolean.getBoolean("beezzmap.pretend")) return true;
        ServerData s = mc.getCurrentServer();
        return s != null && isBeezzAddress(s.ip);
    }

    public static boolean isBeezz(MapSession s) {
        return s != null && WORLD_ID.equals(s.meta.id);
    }

    /** The server's name of a dimension (Paper names the vanilla ones after the world folders). */
    public static String world(String dim) {
        if (dim == null) return null;
        return switch (dim) {
            case "minecraft:overworld" -> "world";
            case "minecraft:the_nether" -> "world_nether";
            case "minecraft:the_end" -> "world_the_end";
            // BeezzWorlds makes the farm worlds as minecraft:farm, Pl3xMap and the site name them by folder
            case "minecraft:farm" -> "world_minecraft_farm";
            case "minecraft:farm_nether" -> "world_minecraft_farm_nether";
            default -> dim.substring(dim.indexOf(':') + 1);
        };
    }

    public static String dim(String world) {
        return switch (world) {
            case "world" -> "minecraft:overworld";
            case "world_nether" -> "minecraft:the_nether";
            case "world_the_end" -> "minecraft:the_end";
            case "world_minecraft_farm" -> "minecraft:farm";
            case "world_minecraft_farm_nether" -> "minecraft:farm_nether";
            default -> "minecraft:" + world;
        };
    }

    /** Does the site draw this dimension at all? */
    public static boolean hasWorld(String dim) {
        // the known worlds count before settings.json has come: else the first frames show the mod's own picture, unfogged
        return Data.KNOWN.contains(world(dim)) || Data.world(world(dim)) != null;
    }

    /** The site's tile colours to match the design: parchment for the papyrus one, the site's own look otherwise. */
    public static String tileTheme() {
        return "papyrus".equals(Paper.theme().id()) ? "papyrus" : "beezz";
    }

    /** Keeps the site's data fresh while something shows it. */
    public static void tick(String dim) {
        Data.tick(world(dim));
    }

    /** At the start of every frame that draws the site's map. */
    public static void frame() {
        Tiles.pump();
        Icons.pump();
        Icons.trimFog();
        glide();
    }

    // ------------------------------------------------------------------ the public places, as markers

    private static List<Data.Point> madeFrom;
    private static List<Marker> markers = List.of();

    public static List<Marker> markers() {
        List<Data.Point> pts = Data.points;
        if (pts != madeFrom) {
            List<Marker> out = new ArrayList<>();
            for (Data.Point p : pts) {
                Marker m = new Marker();
                m.id = "site:" + p.id();
                m.site = true;
                m.name = p.name();
                m.description = p.description();
                m.icon = p.icon().isEmpty() ? "pin" : p.icon();
                m.color = p.color();
                m.dim = dim(p.world());
                m.x = p.x();
                m.y = p.y();
                m.z = p.z();
                m.by = p.by();
                m.status = p.status();
                m.created = p.created();
                m.screenshots = new ArrayList<>(p.shots());
                out.add(m);
            }
            markers = List.copyOf(out);
            madeFrom = pts;
        }
        return markers;
    }

    public static String pointId(Marker m) {
        return m.site ? m.id.substring(5) : m.sentId;
    }

    /** Where a marker is on the site's map page. */
    public static String link(Marker m) {
        return Api.SITE + "/map.html#w=" + world(m.dim) + "&x=" + m.x + "&z=" + m.z + "&s=3";
    }

    // ------------------------------------------------------------------ the places' screenshots

    private static final Set<String> fetching = ConcurrentHashMap.newKeySet();
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();

    /** A screenshot of a place, downloaded once into beezzmap/site-shots; null while it comes. */
    public static Path shot(String name) {
        if (!name.matches("[a-f0-9]{20}\\.(png|jpg|webp)")) return null;
        Path p = Storage.root().resolve("site-shots").resolve(name);
        if (Files.isRegularFile(p)) return p;
        if (!failed.contains(name) && fetching.add(name)) {
            Data.NET.execute(() -> {
                try {
                    byte[] b = Api.get("/map-shots/" + name);
                    if (b == null) {
                        failed.add(name);
                        return;
                    }
                    Files.createDirectories(p.getParent());
                    Path tmp = p.resolveSibling(name + ".tmp");
                    Files.write(tmp, Pictures.png(b)); // the game reads PNG only: a JPEG from the site is turned into one
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    failed.add(name);
                    land.beezz.map.BeezzMap.LOG.debug("site shot {} failed", name, e);
                } finally {
                    fetching.remove(name);
                }
            });
        }
        return null;
    }

    public static boolean shotFailed(String name) {
        return failed.contains(name);
    }

    // ------------------------------------------------------------------ the players online, gliding between updates

    private static final class Glide {
        double fx, fz, tx, tz;
        long t0;
        String world;
    }

    private static final Map<String, Glide> glides = new HashMap<>();
    private static List<Data.Player> seenPlayers;

    private static void glide() {
        List<Data.Player> now = Data.players;
        if (now == seenPlayers) return;
        long t = System.currentTimeMillis();
        Map<String, Glide> next = new HashMap<>();
        for (Data.Player p : now) {
            Glide old = glides.get(p.name()), gl = new Glide();
            if (old != null && p.world().equals(old.world)) {
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
            gl.world = p.world();
            next.put(p.name(), gl);
        }
        glides.clear();
        glides.putAll(next);
        seenPlayers = now;
    }

    private static double[] at(Glide g, long t) {
        double k = Math.min(1, (t - g.t0) / 2000.0);
        return new double[]{g.fx + (g.tx - g.fx) * k, g.fz + (g.tz - g.fz) * k};
    }

    /** Where a player is now on the map, smoothly; null if they aren't on it. */
    public static double[] playerPos(String nick) {
        Glide g = glides.get(nick);
        return g == null ? null : at(g, System.currentTimeMillis());
    }

    /** The players the site shows in this dimension (not the one playing: their own arrow is drawn instead). */
    public static List<Data.Player> players(String dim) {
        String w = world(dim);
        String me = Minecraft.getInstance().getUser().getName();
        List<Data.Player> out = new ArrayList<>();
        for (Data.Player p : Data.players) if (p.world().equals(w) && !p.name().equalsIgnoreCase(me)) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ the player's own site session

    private static final class SessionFile {
        String name;
        String token;
    }

    private static Path sessionFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("beezzmap-site-login.json");
    }

    private static SessionFile session;

    /** The site session of this player, if they have logged in from the mod; null otherwise. */
    public static String session() {
        if (session == null) session = Storage.readJson(sessionFile(), SessionFile.class, new SessionFile());
        String me = Minecraft.getInstance().getUser().getName();
        return session.token != null && me.equalsIgnoreCase(session.name) ? session.token : null;
    }

    private static void saveSession(String name, String token) {
        SessionFile f = new SessionFile();
        f.name = name;
        f.token = token;
        session = f;
        Storage.writeJson(sessionFile(), f);
    }

    public static void logout() {
        saveSession(null, null);
    }

    // ------------------------------------------------------------------ sending a marker to the site's map

    /** How sending goes; the map screen shows it in a little window. */
    public static final class Send {
        public enum State { LOGIN, CONFIRM, UPLOADING, DONE, FAILED }

        public volatile State state = State.LOGIN;
        public volatile int number; // the login request's number, the same one the bot shows
        public volatile String message = "";
        public volatile int shot, shots;
        final Marker marker;
        final List<Path> files;
        volatile boolean cancelled;
        long loginAsked;
        String request;

        Send(Marker m, List<Path> files) {
            this.marker = m;
            this.files = files;
        }

        public void cancel() {
            cancelled = true;
        }
    }

    private static Send sending;

    public static Send sending() {
        return sending;
    }

    /** Starts sending (or updating) a marker; files are its screenshots on disk. */
    public static Send send(Marker m, List<Path> files) {
        Send s = new Send(m, files);
        sending = s;
        if (session() != null) upload(s);
        else login(s);
        return s;
    }

    private static void login(Send s) {
        String me = Minecraft.getInstance().getUser().getName();
        s.state = Send.State.LOGIN;
        if (Boolean.getBoolean("beezzmap.noLogin")) { // the game test: never ask the real site to log anyone in
            fail(s, T.s("site.err.not_linked", me));
            return;
        }
        Data.NET.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("name", me);
                Api.Reply r = Api.post("/api/login", body.toString(), null);
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                if (o.has("request")) {
                    s.request = o.get("request").getAsString();
                    s.number = o.has("number") ? o.get("number").getAsInt() : 0;
                    s.loginAsked = System.currentTimeMillis();
                    s.state = Send.State.CONFIRM;
                    poll(s, me);
                } else {
                    String err = o.has("error") ? o.get("error").getAsString() : "HTTP " + r.code();
                    int wait = o.has("wait") ? o.get("wait").getAsInt() : 0;
                    fail(s, switch (err) {
                        case "not_linked" -> T.s("site.err.not_linked", me);
                        case "too_often", "too_many" -> T.s("site.err.too_often", Math.max(1, (wait + 59) / 60));
                        case "bots_down", "auth_down" -> T.s("site.err.bots_down");
                        default -> T.s("site.err.other", err);
                    });
                }
            } catch (Exception e) {
                fail(s, T.s("site.err.offline"));
            }
        });
    }

    private static void poll(Send s, String me) throws Exception {
        while (!s.cancelled) {
            Thread.sleep(2000);
            Api.Reply r = Api.ask("/api/login/status?request=" + s.request);
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            String state = o.has("state") ? o.get("state").getAsString() : "error";
            switch (state) {
                case "approved" -> {
                    if (r.cookie() == null) {
                        fail(s, T.s("site.err.other", "no session"));
                        return;
                    }
                    saveSession(me, r.cookie());
                    upload(s);
                    return;
                }
                case "rejected" -> {
                    fail(s, T.s("site.err.rejected"));
                    return;
                }
                case "expired" -> {
                    fail(s, T.s("site.err.expired"));
                    return;
                }
                case "pending" -> {
                }
                default -> {
                    if (System.currentTimeMillis() - s.loginAsked > 300_000) {
                        fail(s, T.s("site.err.expired"));
                        return;
                    }
                }
            }
        }
    }

    private static void upload(Send s) {
        s.state = Send.State.UPLOADING;
        Data.NET.execute(() -> {
            try {
                String token = session();
                List<String> names = new ArrayList<>();
                s.shots = Math.min(6, s.files.size());
                for (int i = 0; i < s.shots && !s.cancelled; i++) {
                    s.shot = i + 1;
                    Api.Reply r = Api.post("/api/map/upload", picture(s.files.get(i)), token);
                    if (r.code() == 401) {
                        relogin(s);
                        return;
                    }
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    if (!o.has("shot")) {
                        fail(s, error(o, r.code()));
                        return;
                    }
                    names.add(o.get("shot").getAsString());
                }
                if (s.cancelled) return;
                Marker m = s.marker;
                JsonObject p = new JsonObject();
                if (m.sentId != null) p.addProperty("id", m.sentId);
                p.addProperty("name", m.name.isBlank() ? T.s("marker.unnamed") : m.name);
                p.addProperty("description", m.description == null ? "" : m.description);
                p.addProperty("world", world(m.dim));
                p.addProperty("icon", T.known(m.icon) ? m.icon : "pin");
                p.addProperty("color", m.color & 0xFFFFFF);
                p.addProperty("x", m.x);
                p.addProperty("y", Math.max(-64, Math.min(320, m.y)));
                p.addProperty("z", m.z);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                names.forEach(arr::add);
                p.add("shots", arr);
                Api.Reply r = Api.post(m.sentId != null ? "/api/map/edit" : "/api/map/add", p.toString(), token);
                if (r.code() == 401) {
                    relogin(s);
                    return;
                }
                if (r.code() == 404 && m.sentId != null) { // gone from the site (deleted there): send it anew
                    m.sentId = null;
                    upload(s);
                    return;
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                if (!o.has("point")) {
                    fail(s, error(o, r.code()));
                    return;
                }
                JsonObject point = o.getAsJsonObject("point");
                String id = point.get("id").getAsString();
                boolean ok = "ok".equals(point.has("status") ? point.get("status").getAsString() : "");
                Minecraft.getInstance().execute(() -> {
                    m.sentId = id;
                    m.sentAt = System.currentTimeMillis();
                    MapSession live = MapManager.live();
                    if (live != null) live.saveMarkers();
                });
                s.message = T.s(ok ? "site.sent_ok" : "site.sent_wait");
                s.state = Send.State.DONE;
                Data.refreshPoints();
            } catch (Exception e) {
                land.beezz.map.BeezzMap.LOG.warn("BeezzMap: sending a marker failed", e);
                fail(s, T.s("site.err.offline"));
            }
        });
    }

    /** The saved session ended on the site (expired, or logged out everywhere): log in again. */
    private static void relogin(Send s) {
        logout();
        login(s);
    }

    private static String error(JsonObject o, int code) {
        String err = o.has("error") ? o.get("error").getAsString() : "HTTP " + code;
        return switch (err) {
            case "too_many" -> T.s("site.err.too_many");
            case "too_many_waiting" -> T.s("site.err.too_many_waiting");
            case "too_many_uploads" -> T.s("site.err.too_many_uploads");
            case "too_big" -> T.s("site.err.too_big");
            case "bad_world" -> T.s("site.err.bad_world");
            case "bad_coords" -> T.s("site.err.bad_coords");
            default -> T.s("site.err.other", err);
        };
    }

    private static void fail(Send s, String message) {
        s.message = message;
        s.state = Send.State.FAILED;
    }

    /** A screenshot as the site takes it: PNG under 6 MB (a big one is halved until it fits). */
    private static byte[] picture(Path file) throws Exception {
        byte[] b = Files.readAllBytes(file);
        if (b.length <= 5_800_000) return b;
        NativeImage img = NativeImage.read(b);
        try {
            while (true) {
                NativeImage half = new NativeImage(Math.max(1, img.getWidth() / 2), Math.max(1, img.getHeight() / 2), false);
                img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), half);
                img.close();
                img = half;
                Path tmp = Files.createTempFile("beezzmap", ".png");
                try {
                    img.writeToFile(tmp);
                    b = Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                if (b.length <= 5_800_000 || img.getWidth() < 200) return b;
            }
        } finally {
            img.close();
        }
    }

    /** Marks the user kept in BeezzMap 1.x (config/beezzmap/marks.json) become markers of the BeezzLand map once. */
    public static void importOldMarks(MapSession s) {
        Path old = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("beezzmap").resolve("marks.json");
        if (!Files.isRegularFile(old)) return;
        try {
            com.google.gson.JsonArray arr = JsonParser.parseString(Files.readString(old)).getAsJsonArray();
            Set<String> have = new HashSet<>();
            for (Marker m : s.markers) have.add(m.id);
            for (var e : arr) {
                JsonObject o = e.getAsJsonObject();
                Marker m = new Marker();
                m.id = o.has("id") ? o.get("id").getAsString() : m.id;
                if (have.contains(m.id)) continue;
                m.name = o.has("name") ? o.get("name").getAsString() : "";
                m.dim = dim(o.has("world") ? o.get("world").getAsString() : "world");
                m.x = o.has("x") ? o.get("x").getAsInt() : 0;
                m.y = o.has("y") ? o.get("y").getAsInt() : 64;
                m.z = o.has("z") ? o.get("z").getAsInt() : 0;
                m.color = o.has("color") ? o.get("color").getAsInt() & 0xFFFFFF : 0;
                s.markers.add(m);
            }
            s.saveMarkers();
            Files.move(old, old.resolveSibling("marks.json.imported"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            land.beezz.map.BeezzMap.LOG.warn("BeezzMap: old marks not imported", e);
        }
    }
}
