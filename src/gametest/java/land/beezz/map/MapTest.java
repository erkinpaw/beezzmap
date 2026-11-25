package land.beezz.map;

import com.mojang.blaze3d.platform.InputConstants;
import land.beezz.map.data.Marker;
import land.beezz.map.data.ModConfig;
import land.beezz.map.gui.MapScreen;
import land.beezz.map.gui.MinimapHud;
import land.beezz.map.gui.MinimapLayoutScreen;
import land.beezz.map.map.MapManager;
import land.beezz.map.site.Data;
import land.beezz.map.site.Site;
import land.beezz.map.site.Tiles;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * BeezzLand in a real client against the live play.beezzland.com (read only: nothing is sent, the login is stubbed),
 * with real mouse and keyboard input. The local world pretends to be BeezzLand. Screens go to
 * build/run/clientGameTest/screenshots as bz-*.
 */
public class MapTest implements FabricClientGameTest {
    private ClientGameTestContext ctx;

    private void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("FAIL: " + what);
        BeezzMap.LOG.info("BeezzMap test PASS: {}", what);
    }

    /** Waits until the site's tiles in view are on screen: nothing loading for a whole second (or 30 s pass). */
    private void settle() {
        ctx.waitTicks(10);
        int calm = 0;
        for (int i = 0; i < 150 && calm < 4; i++) {
            ctx.waitTicks(5);
            calm = ctx.computeOnClient(mc -> Tiles.loading() == 0) ? calm + 1 : 0;
        }
        ctx.waitTicks(20);
    }

    private double gs() {
        return ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
    }

    private void cursor(double x, double y) {
        double s = gs();
        ctx.getInput().setCursorPos(x * s, y * s);
        ctx.waitTicks(2);
    }

    private MapScreen map() {
        return ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen m ? m : null);
    }

    private double[] spot(String id) {
        double[] p = ctx.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen m ? m.spot(id) : null);
        check(p != null, "there is " + id);
        return p;
    }

    private void click(double[] p, int button) {
        cursor(p[0], p[1]);
        ctx.getInput().pressMouse(button);
        ctx.waitTicks(4);
    }

    private void shot(String name) {
        settle();
        ctx.takeScreenshot("bz-" + name);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ctx = context;
        System.setProperty("beezzmap.pretend", "true");
        System.setProperty("beezzmap.noLogin", "true");
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(mc -> {
            mc.options.languageCode = "ru_ru";
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.guiScale().set(3);
            mc.resizeGui();
            ModConfig c = ModConfig.get();
            c.theme = "papyrus";
            c.minimapEnabled = true;
            c.minimapFree = false;
            c.minimapPx = 120;
            c.minimapZoomF = 1.5f;
            c.minimapOpacity = 1f;
            c.caveMode = 0;
            c.save();
        });
        ctx.runOnClient(mc -> mc.reloadResourcePacks());
        ctx.waitTicks(40);
        try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
            ctx.waitTicks(60);
            check(ctx.computeOnClient(mc -> Site.isBeezz(MapManager.live())), "the world is the BeezzLand map");
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("tp @a 30 90 40 -30 30");
            world.getServer().runCommand("time set noon");
            for (int i = 0; i < 80 && ctx.computeOnClient(mc -> Data.worlds.isEmpty() || Data.points.isEmpty()); i++) ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> !Data.worlds.isEmpty()), "worlds from the live site: " + Data.worlds);
            check(ctx.computeOnClient(mc -> !Data.points.isEmpty()), "public places: " + Data.points.size());
            BeezzMap.LOG.info("BeezzMap test: players online {}", Data.players);
            settle();
            ctx.waitTicks(40);
            ctx.takeScreenshot("bz-01-minimap-site");

            // ---- the big map: the site's whole world
            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(map() != null, "M opens the map");
            ctx.runOnClient(mc -> map0(mc).setView(0, 0, 0.12f));
            cursor(400, 250);
            shot("02-overview");
            check(ctx.computeOnClient(mc -> Tiles.ready() > 0), "site tiles painted: " + Tiles.ready());

            // a real drag with the left button
            ctx.runOnClient(mc -> map0(mc).setView(0, 0, 1));
            cursor(320, 180);
            ctx.getInput().holdMouse(0);
            ctx.waitTicks(2);
            for (int i = 0; i < 5; i++) {
                ctx.getInput().moveCursor(-120, -60);
                ctx.waitTicks(2);
            }
            ctx.getInput().releaseMouse(0);
            ctx.waitTicks(3);
            double cx = ctx.computeOnClient(mc -> map0(mc).viewX());
            check(cx > 150, "dragging moves the map: " + cx);
            ctx.getInput().holdKey(InputConstants.KEY_D);
            ctx.waitTicks(10);
            ctx.getInput().releaseKey(InputConstants.KEY_D);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> map0(mc).viewX()) > cx + 20, "D moves the map east");

            // ---- the site's places in the list; one with screenshots
            ctx.runOnClient(mc -> map0(mc).openTab(MapScreen.TAB_SITE));
            ctx.waitTicks(5);
            Data.Point withShots = ctx.computeOnClient(mc -> Data.points.stream().filter(p -> !p.shots().isEmpty() && "ok".equals(p.status())).findFirst().orElse(null));
            check(withShots != null, "a public place with screenshots");
            ctx.runOnClient(mc -> map0(mc).showMarker(Site.markers().stream().filter(m -> m.id.equals("site:" + withShots.id())).findFirst().orElseThrow()));
            for (int i = 0; i < 60 && ctx.computeOnClient(mc -> Site.shot(withShots.shots().get(0)) == null); i++) ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> Site.shot(withShots.shots().get(0)) != null), "the place's screenshot came from the site");
            shot("03-site-place");
            check(ctx.computeOnClient(mc -> map0(mc).selectedMarker() != null && map0(mc).selectedMarker().site), "a site place is shown");
            click(spot("shot:0"), 0);
            ctx.waitTicks(20);
            check(ctx.computeOnClient(mc -> map0(mc).viewerOpen()), "a click opens the place's screenshot");
            ctx.takeScreenshot("bz-04-site-screenshot");
            ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitTicks(3);

            // ---- players online: the tab, and following one
            click(spot("tab:2"), 0);
            if (ctx.computeOnClient(mc -> !Data.players.isEmpty())) {
                String first = ctx.computeOnClient(mc -> Data.players.get(0).name());
                click(spot("player:" + first), 0);
                check(ctx.computeOnClient(mc -> first.equals(map0(mc).followed())), "a click in the list follows the player");
                shot("05-follow-player");
                click(spot("player:" + first), 0);
            } else {
                shot("05-players-empty");
            }

            // ---- my marker with the "send to the site" button (the login is stubbed: nothing reaches the site)
            ctx.runOnClient(mc -> {
                Marker m = new Marker();
                m.name = "Моя база";
                m.icon = "house";
                m.dim = "minecraft:overworld";
                m.x = 30;
                m.y = 70;
                m.z = 40;
                m.description = "Проверка отправки на сайт";
                MapManager.live().markers.add(m);
                map0(mc).openTab(MapScreen.TAB_MINE);
                map0(mc).showMarker(m);
                map0(mc).setView(30, 40, 3);
            });
            shot("06-my-marker");
            click(spot("button:send"), 0);
            ctx.waitTicks(10);
            check(ctx.computeOnClient(mc -> map0(mc).sendOpen()), "the send button opens the site window");
            ctx.takeScreenshot("bz-07-send-window");
            click(spot("send:button"), 0);

            // ---- the nether from the site
            ctx.runOnClient(mc -> {
                map0(mc).closePanels();
                map0(mc).showDim("minecraft:the_nether");
                map0(mc).setView(0, 0, 0.5f);
            });
            shot("08-nether");
            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "M closes the map");

            // ---- caves: underground the map shows the cave floor
            // the test world is flat: a hill of stone with a cave and some ore inside it
            world.getServer().runCommand("fill 8 -60 18 52 -51 62 stone"); // /fill takes 32768 blocks at most
            world.getServer().runCommand("fill 8 -50 18 52 -41 62 stone");
            world.getServer().runCommand("fill 12 -58 22 48 -54 58 air");
            world.getServer().runCommand("fill 20 -59 30 26 -59 36 water");
            world.getServer().runCommand("fill 36 -59 44 40 -59 48 gold_block");
            world.getServer().runCommand("fill 14 -59 50 18 -59 56 oak_planks");
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a 30 -57 40 0 30");
            ctx.runOnClient(mc -> {
                ModConfig.get().caveMode = 2;
                ModConfig.get().save();
            });
            for (int i = 0; i < 40 && !ctx.computeOnClient(mc -> MapManager.caves()); i++) ctx.waitTicks(5);
            String why = ctx.computeOnClient(mc -> "y=" + mc.player.getBlockY() + " top=" + mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, mc.player.getBlockX(), mc.player.getBlockZ())
                    + " ws=" + mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, mc.player.getBlockX(), mc.player.getBlockZ())
                    + " mode=" + ModConfig.get().caveMode + " block=" + mc.level.getBlockState(mc.player.blockPosition().above(5)));
            check(ctx.computeOnClient(mc -> MapManager.caves()), "caves switch on by themselves underground: " + why);
            ctx.waitTicks(80);
            ctx.takeScreenshot("bz-09-minimap-caves");
            ctx.getInput().pressKey(InputConstants.KEY_M);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> map0(mc).setView(30, 40, 3));
            ctx.waitTicks(10);
            ctx.takeScreenshot("bz-10-map-caves");
            ctx.getInput().pressKey(InputConstants.KEY_M);
            world.getServer().runCommand("tp @a 30 -20 40 0 30");
            for (int i = 0; i < 40 && ctx.computeOnClient(mc -> MapManager.caves()); i++) ctx.waitTicks(5);
            check(ctx.computeOnClient(mc -> !MapManager.caves()), "caves switch off above ground");

            // ---- the minimap anywhere, any size, see-through
            ctx.runOnClient(mc -> mc.gui.setScreen(new MinimapLayoutScreen(null)));
            ctx.waitTicks(10);
            int[] r = ctx.computeOnClient(mc -> MinimapHud.rect(mc.gui.screen().width, mc.gui.screen().height, ModConfig.get(), false));
            cursor(r[0] + r[2] / 2.0, r[1] + r[2] / 2.0);
            ctx.getInput().holdMouse(0);
            ctx.waitTicks(2);
            for (int i = 0; i < 6; i++) {
                ctx.getInput().moveCursor(-200, 90);
                ctx.waitTicks(2);
            }
            ctx.getInput().releaseMouse(0);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> ModConfig.get().minimapFree && ModConfig.get().minimapFx < 0.5f && ModConfig.get().minimapFy > 0.5f),
                    "dragging puts the minimap bottom left: " + ModConfig.get().minimapFx + ", " + ModConfig.get().minimapFy);
            int[] r2 = ctx.computeOnClient(mc -> MinimapHud.rect(mc.gui.screen().width, mc.gui.screen().height, ModConfig.get(), false));
            cursor(r2[0] + r2[2] + 1, r2[1] + r2[2] + r2[3] + 1);
            ctx.getInput().holdMouse(0);
            ctx.waitTicks(2);
            for (int i = 0; i < 4; i++) {
                ctx.getInput().moveCursor(60, 60);
                ctx.waitTicks(2);
            }
            ctx.getInput().releaseMouse(0);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> ModConfig.get().minimapPx > 150), "the corner makes it bigger: " + ModConfig.get().minimapPx);
            ctx.getInput().scroll(3);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                ModConfig.get().minimapOpacity = 0.6f;
                ModConfig.get().save();
            });
            ctx.waitTicks(20);
            ctx.takeScreenshot("bz-11-minimap-layout");
            ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitTicks(30);
            ctx.takeScreenshot("bz-12-minimap-moved-see-through");
            ctx.getInput().pressKey(InputConstants.KEY_N);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> !ModConfig.get().minimapEnabled), "N hides the minimap");
            ctx.getInput().pressKey(InputConstants.KEY_N);
            ctx.runOnClient(mc -> {
                ModConfig c = ModConfig.get();
                c.minimapFree = false;
                c.minimapPx = 120;
                c.minimapOpacity = 1f;
                c.caveMode = 2;
                c.save();
            });
        }
        System.clearProperty("beezzmap.pretend");
    }

    private static MapScreen map0(net.minecraft.client.Minecraft mc) {
        return (MapScreen) mc.gui.screen();
    }
}
