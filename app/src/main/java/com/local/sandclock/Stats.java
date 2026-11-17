package com.local.sandclock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * What the screensaver has shown, counted only while it is actually the screensaver (not the
 * clock or the settings preview).
 *
 * Lifetime numbers never reset; the scenes mention them now and then. The "period" numbers
 * drive the extras that build up in the scenes (barnacles, calf, chest, trophies, bedrock,
 * fossils, constellation) and start again every two weeks, on every other Monday.
 * Kept in memory; saved every 10 minutes and when the screensaver stops.
 */
final class Stats {
    static final int PERIOD_DAYS = 14;
    /** Animals, in the order OceanScene spawns them. */
    static final int FISH = 0, TURTLE = 1, RAY = 2, JELLY = 3, ANGLER = 4, CRAB = 5, N_ANIMALS = 6;
    static final String[] ANIMALS = {"FISH SCHOOLS", "TURTLES", "RAYS", "JELLYFISH", "ANGLERFISH", "CRABS"};
    /** Ant farm: one grain dug every this many seconds (see AntScene). */
    static final int SECONDS_PER_GRAIN = 6;
    /** One lap across the screen, as distance swum (a whale is ~1/15 of the width: ~400 m). */
    private static final double KM_PER_LAP = 0.4;
    /** Hours of a scene in a period for its extras to be fully grown (about a week of use). */
    static final float FULL_HOURS = 50f;

    private static Stats instance;

    private final SharedPreferences sp;
    private long lastSave;
    private boolean dirty;

    // Lifetime. ("sand" is the ant farm: it was the sand clock, and keeps its counts.)
    long oceanMs, sandMs, grains, laps, spouts, whirlpools;
    long gardenMs, bees, ladybugLaps, leaves;
    long kampungMs, buildings, biawaks, trains;
    final long[] animals = new long[N_ANIMALS];
    int fullMoons, streak, bestStreak, daysShown;
    long lastDay = -1, lastMoonDay = -1;

    // This period (two weeks).
    long period = -1;
    long pOceanMs, pSandMs, pGrains, pLaps;
    long pGardenMs;
    int pGardenDays;
    long pLastGardenDay = -1;
    long pKampungMs, pBuildings;
    int pKampungDays;
    long pLastKampungDay = -1;
    final int[] pAnimals = new int[N_ANIMALS];
    /** Animals in the order they earned a trophy this period (each digit an animal). */
    String pTrophies = "";
    int pOceanDays, pSandDays;
    long pLastOceanDay = -1, pLastSandDay = -1;

    // The period before, for the look back on the first day of a new one.
    long lastPLaps = -1, lastPGrains = -1, lastPOceanMs, lastPSandMs;
    long lastPBees = -1, pBees;
    long lastPBuildings = -1;

    private Stats(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("sandclock_stats", Context.MODE_PRIVATE);
        oceanMs = sp.getLong("oceanMs", 0);
        sandMs = sp.getLong("sandMs", 0);
        grains = sp.getLong("grains", 0);
        laps = sp.getLong("laps", 0);
        spouts = sp.getLong("spouts", 0);
        whirlpools = sp.getLong("whirlpools", 0);
        gardenMs = sp.getLong("gardenMs", 0);
        bees = sp.getLong("bees", 0);
        ladybugLaps = sp.getLong("ladybugLaps", 0);
        leaves = sp.getLong("leaves", 0);
        pGardenMs = sp.getLong("pGardenMs", 0);
        pGardenDays = sp.getInt("pGardenDays", 0);
        pLastGardenDay = sp.getLong("pLastGardenDay", -1);
        pBees = sp.getLong("pBees", 0);
        lastPBees = sp.getLong("lastPBees", -1);
        kampungMs = sp.getLong("kampungMs", 0);
        buildings = sp.getLong("buildings", 0);
        biawaks = sp.getLong("biawaks", 0);
        trains = sp.getLong("trains", 0);
        pKampungMs = sp.getLong("pKampungMs", 0);
        pBuildings = sp.getLong("pBuildings", 0);
        pKampungDays = sp.getInt("pKampungDays", 0);
        pLastKampungDay = sp.getLong("pLastKampungDay", -1);
        lastPBuildings = sp.getLong("lastPBuildings", -1);
        for (int i = 0; i < N_ANIMALS; i++) {
            animals[i] = sp.getLong("animal" + i, 0);
            pAnimals[i] = sp.getInt("pAnimal" + i, 0);
        }
        fullMoons = sp.getInt("fullMoons", 0);
        streak = sp.getInt("streak", 0);
        bestStreak = sp.getInt("bestStreak", 0);
        daysShown = sp.getInt("daysShown", 0);
        lastDay = sp.getLong("lastDay", -1);
        lastMoonDay = sp.getLong("lastMoonDay", -1);
        period = sp.getLong("period", -1);
        pOceanMs = sp.getLong("pOceanMs", 0);
        pSandMs = sp.getLong("pSandMs", 0);
        pGrains = sp.getLong("pGrains", 0);
        pLaps = sp.getLong("pLaps", 0);
        pTrophies = sp.getString("pTrophies", "");
        pOceanDays = sp.getInt("pOceanDays", 0);
        pSandDays = sp.getInt("pSandDays", 0);
        pLastOceanDay = sp.getLong("pLastOceanDay", -1);
        pLastSandDay = sp.getLong("pLastSandDay", -1);
        lastPLaps = sp.getLong("lastPLaps", -1);
        lastPGrains = sp.getLong("lastPGrains", -1);
        lastPOceanMs = sp.getLong("lastPOceanMs", 0);
        lastPSandMs = sp.getLong("lastPSandMs", 0);
    }

    static Stats get(Context c) {
        if (instance == null) instance = new Stats(c);
        return instance;
    }

    void save() {
        if (!dirty) return;
        SharedPreferences.Editor e = sp.edit();
        e.putLong("oceanMs", oceanMs).putLong("sandMs", sandMs).putLong("grains", grains)
                .putLong("laps", laps).putLong("spouts", spouts).putLong("whirlpools", whirlpools)
                .putLong("gardenMs", gardenMs).putLong("bees", bees).putLong("ladybugLaps", ladybugLaps)
                .putLong("leaves", leaves).putLong("pGardenMs", pGardenMs).putInt("pGardenDays", pGardenDays)
                .putLong("pLastGardenDay", pLastGardenDay).putLong("pBees", pBees).putLong("lastPBees", lastPBees)
                .putLong("kampungMs", kampungMs).putLong("buildings", buildings).putLong("biawaks", biawaks)
                .putLong("trains", trains).putLong("pKampungMs", pKampungMs).putLong("pBuildings", pBuildings)
                .putInt("pKampungDays", pKampungDays).putLong("pLastKampungDay", pLastKampungDay)
                .putLong("lastPBuildings", lastPBuildings);
        for (int i = 0; i < N_ANIMALS; i++) {
            e.putLong("animal" + i, animals[i]).putInt("pAnimal" + i, pAnimals[i]);
        }
        e.putInt("fullMoons", fullMoons).putInt("streak", streak).putInt("bestStreak", bestStreak)
                .putInt("daysShown", daysShown).putLong("lastDay", lastDay).putLong("lastMoonDay", lastMoonDay)
                .putLong("period", period).putLong("pOceanMs", pOceanMs).putLong("pSandMs", pSandMs)
                .putLong("pGrains", pGrains).putLong("pLaps", pLaps).putString("pTrophies", pTrophies)
                .putInt("pOceanDays", pOceanDays).putInt("pSandDays", pSandDays)
                .putLong("pLastOceanDay", pLastOceanDay).putLong("pLastSandDay", pLastSandDay)
                .putLong("lastPLaps", lastPLaps).putLong("lastPGrains", lastPGrains)
                .putLong("lastPOceanMs", lastPOceanMs).putLong("lastPSandMs", lastPSandMs)
                .apply();
        dirty = false;
        lastSave = System.currentTimeMillis();
    }

    private void maybeSave() {
        dirty = true;
        if (System.currentTimeMillis() - lastSave > 10 * 60_000L) save();
    }

    // ---- days and periods ----

    /** Days since 1970 in local time. */
    static long epochDay(Calendar c) {
        long local = c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
        return local / 86_400_000L;
    }

    /** Two-week periods start on Mondays (1970-01-05 was a Monday: day 4). */
    static long periodOf(long day) { return (day - 4) / PERIOD_DAYS; }

    /** First day of a new period (the extras have just reset): look back at the last one. */
    static boolean firstDayOfPeriod(Calendar c) {
        return (epochDay(c) - 4) % PERIOD_DAYS == 0;
    }

    private void ensurePeriod(long day) {
        long p = periodOf(day);
        if (p == period) return;
        if (period >= 0 && p == period + 1) {
            lastPLaps = pLaps;
            lastPGrains = pGrains;
            lastPOceanMs = pOceanMs;
            lastPSandMs = pSandMs;
            lastPBees = pBees;
            lastPBuildings = pBuildings;
        } else {
            lastPLaps = lastPGrains = lastPBees = lastPBuildings = -1; // nothing just before: no look back
        }
        pKampungMs = pBuildings = 0;
        pKampungDays = 0;
        pLastKampungDay = -1;
        pGardenMs = pBees = 0;
        pGardenDays = 0;
        pLastGardenDay = -1;
        period = p;
        pOceanMs = pSandMs = pGrains = pLaps = 0;
        java.util.Arrays.fill(pAnimals, 0);
        pTrophies = "";
        pOceanDays = pSandDays = 0;
        pLastOceanDay = pLastSandDay = -1;
        dirty = true;
    }

    /** Keeps the period current for what is drawn, also when not recording. */
    void refresh(Calendar c) { ensurePeriod(epochDay(c)); }

    // ---- recording (only while the screensaver shows) ----

    void addTime(int scene, long ms, Calendar c) {
        long day = epochDay(c);
        ensurePeriod(day);
        if (day != lastDay) {
            streak = lastDay == day - 1 ? streak + 1 : 1;
            bestStreak = Math.max(bestStreak, streak);
            daysShown++;
            lastDay = day;
        }
        if (scene == SceneView.OCEAN) {
            oceanMs += ms;
            pOceanMs += ms;
            if (pLastOceanDay != day) { pOceanDays++; pLastOceanDay = day; }
        } else if (scene == SceneView.KAMPUNG) {
            kampungMs += ms;
            pKampungMs += ms;
            if (pLastKampungDay != day) { pKampungDays++; pLastKampungDay = day; }
        } else if (scene == SceneView.GARDEN) {
            gardenMs += ms;
            pGardenMs += ms;
            if (pLastGardenDay != day) { pGardenDays++; pLastGardenDay = day; }
        } else {
            sandMs += ms;
            pSandMs += ms;
            if (pLastSandDay != day) { pSandDays++; pLastSandDay = day; }
        }
        maybeSave();
    }

    void addGrain() { grains++; pGrains++; dirty = true; }
    void addLap() { laps++; pLaps++; maybeSave(); }
    void addSpout() { spouts++; dirty = true; }
    void addBee() { bees++; pBees++; dirty = true; }
    /** Kampung: a building finished while the screensaver shows. */
    void addBuilding() { buildings++; pBuildings++; dirty = true; }
    void addBiawak() { biawaks++; maybeSave(); }
    void addTrain() { trains++; dirty = true; }
    void addLadybugLap() { ladybugLaps++; dirty = true; }
    /** Garden: leaves grown (counted as the tree grows while the screensaver shows). */
    void addLeaves(int n) { leaves += n; dirty = true; }
    void addWhirlpool() { whirlpools++; maybeSave(); }

    void sawAnimal(int kind) {
        animals[kind]++;
        pAnimals[kind]++;
        // Three sightings in a period earn that animal a trophy on the seabed.
        char k = (char) ('0' + kind);
        if (pAnimals[kind] >= 3 && pTrophies.indexOf(k) < 0) pTrophies += k;
        maybeSave();
    }

    /** At night: a full moon (within about a day of it) counts once per night. */
    void noteNight(Calendar c, double moonPhase) {
        long day = epochDay(c);
        if (Math.abs(moonPhase - 0.5) < 0.034 && lastMoonDay != day && lastMoonDay != day - 1) {
            fullMoons++;
            lastMoonDay = day;
            dirty = true;
        }
    }

    // ---- what the extras show (grow slowly over the period) ----

    float oceanGrowth() { return Math.min(1f, pOceanMs / 3_600_000f / FULL_HOURS); }
    float sandGrowth() { return Math.min(1f, pSandMs / 3_600_000f / FULL_HOURS); }
    float gardenGrowth() { return Math.min(1f, pGardenMs / 3_600_000f / FULL_HOURS); }
    /** Kampung extras grow over the two weeks (a park, a stadium, the monorail...). */
    float kampungGrowth() { return Math.min(1f, pKampungMs / 3_600_000f / FULL_HOURS); }
    int kampungDays() { return pKampungDays; }
    /** Garden extras by growth: a nest, then eggs, then chicks; mushrooms (0..6) at the roots. */
    int gardenNestStage() {
        float g = gardenGrowth();
        if (pGardenDays < 2 || g < 0.2f) return 0;   // nothing yet
        if (pGardenDays < 4 || g < 0.45f) return 1;  // a nest
        if (pGardenDays < 6 || g < 0.75f) return 2;  // eggs
        return 3;                                    // chicks
    }
    int mushrooms() { return (int) Math.min(6, Math.min(gardenGrowth() * 7, pGardenDays)); }

    /** The calf joins after 30 hours of sea, and not before the fourth sea day. */
    boolean hasCalf() { return pOceanMs >= 30 * 3_600_000L && pOceanDays >= 4; }

    /** Trophies to show: at most one more per sea day. */
    int[] trophies() {
        int n = Math.min(pTrophies.length(), pOceanDays);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = pTrophies.charAt(i) - '0';
        return out;
    }

    /** Fossils in the bedrock: one per 25,000 grains this period, at most one per sand day. */
    int fossils() { return (int) Math.min(8, Math.min(pGrains / 25_000, pSandDays)); }

    /** Stars of the night constellation: one per 4 hours of sand, at most two per sand day. */
    int stars() { return (int) Math.min(12, Math.min(pSandMs / (4 * 3_600_000L), pSandDays * 2L)); }

    // ---- messages ----

    static String count(long n) {
        if (n < 10_000) return String.valueOf(n);
        if (n < 100_000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        if (n < 1_000_000) return (n / 1000) + "K";
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
    }

    private static String km(long lapCount) {
        double km = lapCount * KM_PER_LAP;
        return km < 10 ? String.format(Locale.US, "%.1f KM", km) : Math.round(km) + " KM";
    }

    /** A line for the message in a bottle; `pick` chooses which (e.g. the hour). Null: nothing yet. */
    String oceanMessage(Calendar c, int pick) {
        if (firstDayOfPeriod(c) && lastPLaps > 0) return "LAST 2 WEEKS: " + km(lastPLaps);
        java.util.List<String> m = new java.util.ArrayList<>();
        if (laps > 0) m.add("THE WHALE HAS SWUM " + km(laps));
        if (spouts > 0) m.add(count(spouts) + " SPOUTS SO FAR");
        int best = -1;
        for (int i = 0; i < N_ANIMALS; i++) if (animals[i] > 0 && (best < 0 || animals[i] > animals[best])) best = i;
        if (best >= 0) m.add(count(animals[best]) + " " + ANIMALS[best] + " SEEN");
        if (animals[ANGLER] > 0) m.add("ANGLERFISH SEEN " + count(animals[ANGLER]) + "X");
        if (whirlpools > 0) m.add(count(whirlpools) + " WHIRLPOOLS");
        if (oceanMs >= 60_000) m.add(hours(oceanMs) + " AT SEA");
        return m.isEmpty() ? null : m.get(Math.abs(pick) % m.size());
    }

    /** A line for the sand sky; `pick` chooses which (e.g. the hour). Null: nothing yet. */
    String sandMessage(Calendar c, int pick) {
        if (firstDayOfPeriod(c) && lastPGrains > 0) return "LAST 2 WEEKS: " + count(lastPGrains) + " GRAINS DUG";
        java.util.List<String> m = new java.util.ArrayList<>();
        if (grains > 0) m.add(count(grains) + " GRAINS DUG");
        if (sandMs >= 60_000) m.add(hours(sandMs) + " OF DIGGING");
        if (streak > 1) m.add("DAY " + streak + " IN A ROW");
        if (fullMoons > 0) m.add(fullMoons + (fullMoons == 1 ? " FULL MOON" : " FULL MOONS"));
        if (daysShown > 1) m.add(daysShown + " DAYS SHOWN");
        return m.isEmpty() ? null : m.get(Math.abs(pick) % m.size());
    }

    /** A line for the garden; `pick` chooses which (e.g. the hour). Null: nothing yet. */
    String gardenMessage(Calendar c, int pick) {
        if (firstDayOfPeriod(c) && lastPBees > 0) return "LAST 2 WEEKS: " + count(lastPBees) + " BEE VISITS";
        java.util.List<String> m = new java.util.ArrayList<>();
        if (bees > 0) m.add(count(bees) + " BEE VISITS");
        if (leaves > 0) m.add(count(leaves) + " LEAVES GROWN");
        if (ladybugLaps > 0) m.add("THE LADYBUG HAS CLIMBED " + count(ladybugLaps) + "X");
        if (gardenMs >= 60_000) m.add(hours(gardenMs) + " IN THE GARDEN");
        if (streak > 1) m.add("DAY " + streak + " IN A ROW");
        return m.isEmpty() ? null : m.get(Math.abs(pick) % m.size());
    }

    /** A line for the kampung's banner plane; `pick` chooses which. Null: nothing yet. */
    String kampungMessage(Calendar c, int pick) {
        if (firstDayOfPeriod(c) && lastPBuildings > 0) return "LAST 2 WEEKS: " + count(lastPBuildings) + " BUILDINGS";
        java.util.List<String> m = new java.util.ArrayList<>();
        if (buildings > 0) m.add(count(buildings) + " BUILDINGS BUILT");
        if (biawaks > 0) m.add("THE BIAWAK HAS COME " + count(biawaks) + (biawaks == 1 ? " TIME" : " TIMES"));
        if (trains > 0) m.add(count(trains) + " TRAINS PASSED");
        if (kampungMs >= 60_000) m.add(hours(kampungMs) + " IN THE KAMPUNG");
        if (streak > 1) m.add("DAY " + streak + " IN A ROW");
        return m.isEmpty() ? null : m.get(Math.abs(pick) % m.size());
    }

    private static String hours(long ms) {
        long h = ms / 3_600_000L;
        if (h < 1) return (ms / 60_000L) + " MIN";
        return count(h) + (h == 1 ? " HOUR" : " HOURS");
    }
}
