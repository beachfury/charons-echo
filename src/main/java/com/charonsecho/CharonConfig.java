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

            // Always write the full set back so new keys appear for admins.
            p.setProperty("set-default-size", Integer.toString(setDefaultSize));
            p.setProperty("set-max-size", Integer.toString(setMaxSize));
            p.setProperty("wake-timeout-seconds", Integer.toString(wakeTimeoutSeconds));
            p.setProperty("ghost-tether-radius", Integer.toString((int) ghostTetherRadius));
            p.setProperty("toll-xp-percent", Integer.toString(tollXpPercent));
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
