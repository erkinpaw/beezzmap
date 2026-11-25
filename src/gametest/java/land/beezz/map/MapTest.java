package land.beezz.map;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Plays the mod in a real client against the live play.beezzland.com, with real mouse and keyboard input: M opens the
 * map, dragging moves it, a click on the settings switches the theme, the right button puts a mark (typed name), players
 * off the screen point from the edge, and the minimap shows in the game. The local world pretends to be BeezzLand.
 * Every view is saved to build/run/clientGameTest/screenshots.
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

    /** The cursor to (x, y) in GUI pixels. */
    private void cursor(double x, double y) {
        double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(2);
    }

    private boolean mapOpen() {
        return ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen);
    }

    private double[] at(String id) {
        double[] p = ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen m ? m.hitCenter(id) : null);
        check(p != null, "there is " + id);
        return p;
    }

    private void click(double[] p, int button) {
        cursor(p[0], p[1]);
        ctx.getInput().pressMouse(button);
        ctx.waitTicks(3);
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
            System.setProperty("beezzmap.pretend", "true"); // as if playing on BeezzLand: the map knows where you are
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 30 90 40 -30 30");
            world.getServer().runCommand("time set noon");
            ctx.waitTicks(40);

            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(mapOpen(), "M opens the map");
            for (int i = 0; i < 60 && ctx.computeOnClient(mc -> Data.worlds.isEmpty() || Data.points.isEmpty()); i++) ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> !Data.worlds.isEmpty()), "worlds from the live server: " + Data.worlds);
            check(ctx.computeOnClient(mc -> !Data.points.isEmpty()), "public places: " + Data.points.size());
            for (int i = 0; i < 40 && ctx.computeOnClient(mc -> Data.fogOf("world") == null); i++) ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> Data.fogOf("world") != null), "fog of war for the main world");
            BeezzMap.LOG.info("BeezzMap test: players online {}", Data.players);

            // a real drag with the left button: the map follows the cursor
            ctx.runOnClient(mc -> MapScreen.view("world", 0, 0, 1));
            cursor(320, 180);
            ctx.getInput().holdMouse(0);
            ctx.waitTicks(2);
            for (int i = 0; i < 5; i++) {
                ctx.getInput().moveCursor(-120, -60);
                ctx.waitTicks(2);
            }
            ctx.getInput().releaseMouse(0);
            ctx.waitTicks(3);
            double cx = ctx.computeOnClient(mc -> MapScreen.view.cx), cz = ctx.computeOnClient(mc -> MapScreen.view.cz);
            BeezzMap.LOG.info("BeezzMap test: after the drag the centre is {}, {}", cx, cz);
            check(cx > 500 && cz > 250, "dragging moves the map");
            // and with the right button, the other way
            cursor(320, 180);
            ctx.getInput().holdMouse(1);
            ctx.waitTicks(2);
            for (int i = 0; i < 5; i++) {
                ctx.getInput().moveCursor(120, 60);
                ctx.waitTicks(2);
            }
            ctx.getInput().releaseMouse(1);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> Math.abs(MapScreen.view.cx) < 50), "dragging with the right button moves it back: " + MapScreen.view.cx);

            // WASD
            ctx.getInput().holdKey(InputConstants.KEY_D);
            ctx.waitTicks(10);
            ctx.getInput().releaseKey(InputConstants.KEY_D);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> MapScreen.view.cx > 30), "D moves the map east: " + MapScreen.view.cx);

            // the settings: open, switch the theme, look, switch back
            click(at("settings"), 0);
            String before = ctx.computeOnClient(mc -> BeezzMap.config.theme);
            click(at("set:theme"), 0);
            check(!before.equals(ctx.computeOnClient(mc -> BeezzMap.config.theme)), "a click switches the theme");
            ctx.runOnClient(mc -> MapScreen.view("world", 900, -700, 0.4));
            cursor(500, 120);
            shot("05-settings-parchment");
            click(at("set:theme"), 0);
            click(at("settings"), 0);

            // the whole explored world
            cursor(560, 250);
            ctx.runOnClient(mc -> MapScreen.view("world", 0, 0, 0.12));
            shot("01-overview");
            check(ctx.computeOnClient(mc -> Tiles.ready() > 0), "tiles painted: " + Tiles.ready());

            // a mark of my own: right click near spawn, «Поставить метку здесь», a typed name, Enter
            ctx.runOnClient(mc -> MapScreen.view("world", 30, 40, 3));
            settle();
            double[] mid = ctx.computeOnClient(mc -> new double[] {mc.gui.screen().width / 2.0 + 40, mc.gui.screen().height / 2.0 - 30});
            click(mid, 1);
            shot("03-menu");
            click(at("menu:markhere"), 0);
            check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof MarkScreen), "the mark card opens");
            ctx.getInput().typeChars("Моя база");
            ctx.waitTicks(3);
            shot("04-mark-card");
            ctx.getInput().pressKey(InputConstants.KEY_RETURN);
            ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> Marks.all().stream().anyMatch(m -> m.name.equals("Моя база"))), "the mark is saved with its name");
            check(mapOpen(), "back on the map");
            cursor(560, 250);
            shot("02-spawn-with-mark");

            // players far away: heads on the edge point to them; then follow one from the list
            if (ctx.computeOnClient(mc -> !Data.players.isEmpty())) {
                ctx.runOnClient(mc -> MapScreen.view("world", 0, 0, 1.5));
                shot("06-players-on-edge");
                String first = ctx.computeOnClient(mc -> Data.players.get(0).name());
                click(at("player:" + first), 0);
                check(ctx.computeOnClient(mc -> MapScreen.follow != null), "a click in the list follows the player");
                shot("07-follow");
            }

            ctx.runOnClient(mc -> MapScreen.view("world_nether", 0, 0, 1));
            shot("08-nether");

            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "M closes the map");

            // the minimap in the game, bigger, zoomed in with =
            ctx.runOnClient(mc -> BeezzMap.config.minimapSize = 2);
            ctx.getInput().pressKey(InputConstants.KEY_EQUALS);
            settle();
            ctx.waitTicks(40);
            ctx.takeScreenshot("09-minimap");
            world.getServer().runCommand("tp @a 30 -60 40 0 30");
            ctx.waitTicks(10);
            ctx.takeScreenshot("10-arrow-yaw0-south");
            world.getServer().runCommand("tp @a 30 -60 40 -90 30");
            ctx.waitTicks(10);
            ctx.takeScreenshot("11-arrow-yaw-90-east");
            ctx.getInput().pressKey(InputConstants.KEY_N);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> !BeezzMap.config.minimap), "N hides the minimap");
            ctx.getInput().pressKey(InputConstants.KEY_N);
        }
    }
}
