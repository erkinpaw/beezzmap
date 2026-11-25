package land.beezz.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The player's own marks: kept only on this computer (config/beezzmap/marks.json), seen only by them, on the big map
 * and on the minimap. A public place for everyone is suggested on the site, where it is checked first.
 */
final class Marks {
    static final int[] COLORS = {0xFACC15, 0xEF4444, 0xF97316, 0x22C55E, 0x06B6D4, 0x3B82F6, 0xA855F7, 0xEC4899, 0xFFFFFF, 0x78716C};

    static final class Mark {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = "";
        String world = "world";
        int x, y = 64, z;
        int color = COLORS[0];
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<Mark> list;

    private Marks() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("beezzmap").resolve("marks.json");
    }

    static List<Mark> all() {
        if (list == null) {
            list = new ArrayList<>();
            try {
                if (Files.exists(file())) {
                    List<Mark> read = GSON.fromJson(Files.readString(file()), new TypeToken<List<Mark>>() {}.getType());
                    if (read != null) list.addAll(read);
                }
            } catch (Exception e) {
                BeezzMap.LOG.warn("BeezzMap: marks unreadable", e);
            }
        }
        return list;
    }

    static void put(Mark m) {
        all().removeIf(o -> o.id.equals(m.id));
        all().add(m);
        save();
    }

    static void remove(Mark m) {
        all().removeIf(o -> o.id.equals(m.id));
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(list));
        } catch (Exception e) {
            BeezzMap.LOG.warn("BeezzMap: marks not saved", e);
        }
    }
}
