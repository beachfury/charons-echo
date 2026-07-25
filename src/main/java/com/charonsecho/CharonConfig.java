package com.charonsecho;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Live settings — config/charons-echo.properties. Written with defaults on
 * first run; edit and restart (or rejoin) to apply.
 */
public final class CharonConfig {

    /** New sets default to this footprint (blocks square). */
    public static volatile int setDefaultSize = 96;
    /** Hard ceiling a set creator can ask for. */
    public static volatile int setMaxSize = 256;
    /** Seconds the body lies in state before the ghost auto-rises. */
    public static volatile int wakeTimeoutSeconds = 60;
    /** Leash radius (blocks) around the death anchor for ghosts. */
    public static volatile double ghostTetherRadius = 24.0;
    /** Charon's toll without an obol: percent of the grave's XP taken. */
    public static volatile int tollXpPercent = 50;
    /** The Broker's price for one Stygian Seed, in emeralds. */
    public static volatile int orchardSeedPrice = 32;
    /** How many orchard trees one player may have planted at once. */
    public static volatile int orchardTreeCap = 3;
    /** Loaded ticks from seedling to the small tree (48000 = 40 real minutes). */
    public static volatile int orchardStage1Ticks = 48000;
    /** Loaded ticks from small tree to the grown tree. */
    public static volatile int orchardStage2Ticks = 72000;
    /** Loaded ticks per sculk face closing over a growing fruit (5 faces). */
    public static volatile int orchardFruitFaceTicks = 2400;
    /** Loaded ticks a sealed fruit takes to ripen. */
    public static volatile int orchardFruitSealTicks = 6000;
    /** Loaded ticks a tree rests after a full harvest before budding again. */
    public static volatile int orchardDormancyTicks = 24000;
    /** Real days the mother's owner may be absent before the line dies. */
    public static volatile int motherAbsenceDays = 30;

    private CharonConfig() {}

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("charons-echo.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
            setDefaultSize = clamp(inted(p, "set-default-size", setDefaultSize), 32, 1024);
            setMaxSize = clamp(inted(p, "set-max-size", setMaxSize), setDefaultSize, 1024);
            wakeTimeoutSeconds = clamp(inted(p, "wake-timeout-seconds", wakeTimeoutSeconds), 5, 600);
            ghostTetherRadius = clamp(inted(p, "ghost-tether-radius", (int) ghostTetherRadius), 8, 256);
            tollXpPercent = clamp(inted(p, "toll-xp-percent", tollXpPercent), 0, 100);
            orchardSeedPrice = clamp(inted(p, "orchard-seed-price", orchardSeedPrice), 1, 4096);
            orchardTreeCap = clamp(inted(p, "orchard-tree-cap", orchardTreeCap), 1, 64);
            orchardStage1Ticks = clamp(inted(p, "orchard-stage1-ticks", orchardStage1Ticks), 200, 12000000);
            orchardStage2Ticks = clamp(inted(p, "orchard-stage2-ticks", orchardStage2Ticks), 200, 12000000);
            orchardFruitFaceTicks = clamp(inted(p, "orchard-fruit-face-ticks", orchardFruitFaceTicks), 100, 12000000);
            orchardFruitSealTicks = clamp(inted(p, "orchard-fruit-seal-ticks", orchardFruitSealTicks), 100, 12000000);
            orchardDormancyTicks = clamp(inted(p, "orchard-dormancy-ticks", orchardDormancyTicks), 100, 12000000);
            motherAbsenceDays = clamp(inted(p, "mother-absence-days", motherAbsenceDays), 1, 3650);

            // Always write the full set back so new keys appear for admins.
            p.setProperty("set-default-size", Integer.toString(setDefaultSize));
            p.setProperty("set-max-size", Integer.toString(setMaxSize));
            p.setProperty("wake-timeout-seconds", Integer.toString(wakeTimeoutSeconds));
            p.setProperty("ghost-tether-radius", Integer.toString((int) ghostTetherRadius));
            p.setProperty("toll-xp-percent", Integer.toString(tollXpPercent));
            p.setProperty("orchard-seed-price", Integer.toString(orchardSeedPrice));
            p.setProperty("orchard-tree-cap", Integer.toString(orchardTreeCap));
            p.setProperty("orchard-stage1-ticks", Integer.toString(orchardStage1Ticks));
            p.setProperty("orchard-stage2-ticks", Integer.toString(orchardStage2Ticks));
            p.setProperty("orchard-fruit-face-ticks", Integer.toString(orchardFruitFaceTicks));
            p.setProperty("orchard-fruit-seal-ticks", Integer.toString(orchardFruitSealTicks));
            p.setProperty("orchard-dormancy-ticks", Integer.toString(orchardDormancyTicks));
            p.setProperty("mother-absence-days", Integer.toString(motherAbsenceDays));
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Charon's Echo — edit and restart to apply");
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] config load failed (using defaults): " + e);
        }
    }

    private static int inted(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
