package land.beezz.map;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Opens the map in a real client with M, against the live play.beezzland.com, and waits for real data: the worlds, the
 * players online, the places, the fog and the tiles. Every view is saved to build/run/clientGameTest/screenshots.
 */
public class MapTest implements FabricClientGameTest {
    private ClientGameTestContext ctx;

    private void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("FAIL: " + what);
        BeezzMap.LOG.info("BeezzMap test PASS: {}", what);
    }

    /** Waits until the tiles in view are on screen: nothing loading for a whole second (or 30 s pass). */
    private void settle() {
        ctx.waitTicks(10);
        int calm = 0;
        for (int i = 0; i < 150 && calm < 4; i++) {
            ctx.waitTicks(5);
            calm = ctx.computeOnClient(mc -> Tiles.loading() == 0) ? calm + 1 : 0;
        }
        ctx.waitTicks(20);
    }

    /** The cursor somewhere it shows nothing: (x, y) in GUI pixels. */
    private void cursor(double x, double y) {
        double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(2);
    }

    private void shot(String name) {
        settle();
        ctx.takeScreenshot(name);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ctx = context;
        String lang = System.getenv().getOrDefault("BEEZZMAP_LANG", "ru_ru");
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(mc -> {
            mc.options.languageCode = lang;
            mc.getLanguageManager().setSelected(lang);
            mc.options.guiScale().set(3);
            mc.resizeGui();
        });
        ctx.runOnClient(mc -> mc.reloadResourcePacks());
        ctx.waitTicks(40);
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(60);
            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen), "M opens the map");

            for (int i = 0; i < 60 && ctx.computeOnClient(mc -> Data.worlds.isEmpty() || Data.points.isEmpty()); i++) ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> !Data.worlds.isEmpty()), "worlds from the live server: " + Data.worlds);
            check(ctx.computeOnClient(mc -> !Data.points.isEmpty()), "public places: " + Data.points.size());
            for (int i = 0; i < 40 && ctx.computeOnClient(mc -> Data.fogOf("world") == null); i++) ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> Data.fogOf("world") != null), "fog of war for the main world");
            BeezzMap.LOG.info("BeezzMap test: players online {}", Data.players);

            // the whole explored world
            cursor(540, 250);
            ctx.runOnClient(mc -> MapScreen.view("world", 0, 0, 0.12));
            shot("01-overview");
            check(ctx.computeOnClient(mc -> Tiles.ready() > 0), "tiles painted: " + Tiles.ready());

            // spawn up close, with its place
            ctx.runOnClient(mc -> MapScreen.view("world", -5, -14, 3));
            settle();
            cursor(ctx.computeOnClient(mc -> mc.gui.screen().width / 2.0), ctx.computeOnClient(mc -> mc.gui.screen().height / 2.0)); // the place's card
            shot("02-spawn");
            cursor(540, 250);

            // the first player online, followed
            if (ctx.computeOnClient(mc -> !Data.players.isEmpty())) {
                ctx.runOnClient(mc -> {
                    MapScreen.view("world", 0, 0, 1.5);
                    ((MapScreen) mc.gui.screen()).followPlayer(Data.players.get(0).name());
                });
                shot("03-player");
            }

            // the same without the fog, then in parchment
            ctx.runOnClient(mc -> {
                MapScreen.view("world", 500, -500, 0.35);
                BeezzMap.config.fog = false;
            });
            shot("04-no-fog");
            ctx.runOnClient(mc -> {
                BeezzMap.config.fog = true;
                BeezzMap.config.theme = "papyrus";
                Tiles.clear();
            });
            shot("05-parchment");
            ctx.runOnClient(mc -> {
                BeezzMap.config.theme = "beezz";
                MapScreen.view("world_nether", 0, 0, 1);
            });
            shot("06-nether");

            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "M closes the map");
        }
    }
}
