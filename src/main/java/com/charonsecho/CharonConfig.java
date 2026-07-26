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
    /** Minutes of war service that earn a free resurrection. */
    public static volatile int warServiceMinutes = 15;
    /** Seconds shaved off the service clock per enemy downed. */
    public static volatile int warKillCreditSeconds = 30;
    /** Seconds ADDED to the clock when an enlisted player is downed. */
    public static volatile int warDownedPenaltySeconds = 60;
    /** Restless soldiers fielded at the front at once. */
    public static volatile int warRestlessCap = 8;
    /** Hollow Wind VEXES fielded at the front at once. */
    public static volatile int warWindCap = 3;
    /** Hollow Wind BREEZES fielded at once (loud — keep them scarce). */
    public static volatile int warBreezeCap = 1;
    /** War golem max health (vanilla is a boss-tier 100). */
    public static volatile int warGolemHealth = 40;
    /** War golem attack damage (vanilla averages ~14 with a launch). */
    public static volatile int warGolemDamage = 6;

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
            warServiceMinutes = clamp(inted(p, "war-service-minutes", warServiceMinutes), 1, 1440);
            warKillCreditSeconds = clamp(inted(p, "war-kill-credit-seconds", warKillCreditSeconds), 0, 3600);
            warDownedPenaltySeconds = clamp(inted(p, "war-downed-penalty-seconds", warDownedPenaltySeconds), 0, 3600);
            warRestlessCap = clamp(inted(p, "war-restless-cap", warRestlessCap), 0, 64);
            warWindCap = clamp(inted(p, "war-wind-cap", warWindCap), 0, 64);
            warBreezeCap = clamp(inted(p, "war-breeze-cap", warBreezeCap), 0, 64);
            warGolemHealth = clamp(inted(p, "war-golem-health", warGolemHealth), 10, 1024);
            warGolemDamage = clamp(inted(p, "war-golem-damage", warGolemDamage), 1, 100);

            // Always write the full file back — documented, sectioned, current.
            Files.createDirectories(file.getParent());
            Files.writeString(file, documentedFile());
        } catch (IOException e) {
            System.out.println("[CharonsEcho] config load failed (using defaults): " + e);
        }
    }

    /** The config file, written with a manual for every knob. */
    private static String documentedFile() {
        return """
            # ============================================================
            #  CHARON'S ECHO — edit values, then restart (or rejoin) to apply.
            #  Comments are regenerated on every start; only values persist.
            #  Time note: 20 ticks = 1 second. Tick-based clocks only run
            #  while the relevant chunk is LOADED.
            # ============================================================

            # ---- The Studio (builder sets) ----

            # Footprint (blocks square) a new builder set gets when no size is
            # given to /charon set new.  Range 32-1024.  Default 96.
            set-default-size=%d

            # The largest set a builder may request.  Range 32-1024 (never
            # below set-default-size).  Default 256.
            set-max-size=%d

            # ---- The death loop ----

            # Seconds the body lies in state (the wake screen) before the
            # ghost rises automatically.  Range 5-600.  Default 60.
            # Longer gives friends more time to lay an obol on the body.
            wake-timeout-seconds=%d

            # Leash radius (blocks) around the death site while a ghost is
            # still in the living world.  Range 8-256.  Default 24.
            ghost-tether-radius=%d

            # Charon's toll: percent of the grave's XP levels taken when a
            # dead player pays with memory instead of an obol.
            # Range 0-100.  Default 50.  0 makes death nearly free —
            # the obol and the war stop mattering.
            toll-xp-percent=%d

            # ---- The Stygian Orchard ----

            # The Broker's price for one Stygian Seed, in emeralds.
            # Range 1-4096.  Default 32.  Keep it above the cost of crafting
            # one obol — a tree pays out forever.
            orchard-seed-price=%d

            # Planted trees one player may have at once.  Range 1-64.
            # Default 3.  Raising this multiplies obol income per player.
            orchard-tree-cap=%d

            # Loaded ticks from seedling to the small tree.
            # Default 48000 (about 40 real minutes near the tree).
            orchard-stage1-ticks=%d

            # Loaded ticks from small tree to the grown tree.
            # Default 72000 (about 60 real minutes).  Stage1 + stage2 is the
            # full growing time — and the length of a vigil, for those who
            # keep one.
            orchard-stage2-ticks=%d

            # Loaded ticks for EACH of the five sculk faces to close over a
            # growing fruit.  Default 2400 (about 2 minutes per face).
            orchard-fruit-face-ticks=%d

            # Loaded ticks a fully sealed fruit takes to ripen into the
            # glowing froglight.  Default 6000 (about 5 minutes).
            orchard-fruit-seal-ticks=%d

            # Loaded ticks a tree rests after a FULL harvest before budding
            # again.  Default 24000 (about 20 minutes).  At defaults a tree
            # yields roughly one fruit batch per 35 minutes.
            orchard-dormancy-ticks=%d

            # Real-world days the mother tree's owner may stay offline before
            # the elder line dies.  Range 1-3650.  Default 30.
            mother-absence-days=%d

            # ---- The War Below the Moon ----

            # Minutes of war service that earn a free resurrection.
            # Range 1-1440.  Default 15.  Should feel slower than paying but
            # faster than re-grinding the XP toll — that is the balance point.
            war-service-minutes=%d

            # Seconds shaved off the service clock for each enemy downed.
            # Range 0-3600.  Default 30.  0 = flat sentence, fighting optional.
            war-kill-credit-seconds=%d

            # Seconds ADDED when an enlisted player is downed.  Range 0-3600.
            # Default 60.  This is what makes the war's PvP mean something.
            war-downed-penalty-seconds=%d

            # Restless soldiers (parched/bogged/stray) fielded at the front
            # at once.  Range 0-64.  Default 8.  The biggest lever on battle
            # size and mob count — lower this first if the front causes lag.
            war-restless-cap=%d

            # Hollow Wind VEXES at the front at once.  Range 0-64.  Default 3.
            # 0 removes vexes entirely.
            war-wind-cap=%d

            # Hollow Wind BREEZES at the front at once.  Range 0-64.
            # Default 1.  Breezes are loud and knock everything around —
            # keep them scarce.  0 = the Wind becomes pure vex.
            war-breeze-cap=%d

            # War golem max health.  Range 10-1024.  Default 40 (vanilla is a
            # boss-tier 100).  Two soldiers' worth of health feels right.
            war-golem-health=%d

            # War golem attack damage.  Range 1-100.  Default 6 (3 hearts;
            # vanilla averages ~14 and one-shots half the Restless).
            # The launch knockback stays — that is the golem's signature.
            war-golem-damage=%d
            """.formatted(setDefaultSize, setMaxSize, wakeTimeoutSeconds,
                (int) ghostTetherRadius, tollXpPercent, orchardSeedPrice, orchardTreeCap,
                orchardStage1Ticks, orchardStage2Ticks, orchardFruitFaceTicks,
                orchardFruitSealTicks, orchardDormancyTicks, motherAbsenceDays,
                warServiceMinutes, warKillCreditSeconds, warDownedPenaltySeconds,
                warRestlessCap, warWindCap, warBreezeCap, warGolemHealth, warGolemDamage);
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
