package com.local.sandclock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The ant farm as pixels (plain Java, no Android, so it can be tested on a desktop).
 *
 * A side view through glass: sky above, soil below. Through the day the ants dig a tunnel
 * network (a new one each day, seeded by the date): one grain every SECONDS_PER_GRAIN seconds,
 * carried up and dropped by the nest entrance, where the grains pile into an anthill (the sand
 * physics of GrainGrid). So the tunnels and the hill both show how much of the day has gone.
 * The clock is dug out underground as rooms. One ant walks the surface as the seconds hand,
 * one digs at the tunnel's end, and one carries dirt up the main shaft. Before midnight rain
 * washes the hill down and the tunnels fill in again.
 *
 * Power: like the ocean, still things are composed into `base` and only rectangles around
 * moving things are redrawn. The hill only changes every few seconds, one grain at a time.
 */
final class AntScene {
    static final int DAY_MS = 86_400_000;
    static final int GRAINS_PER_DAY = 86_400 / Stats.SECONDS_PER_GRAIN;
    private static final int DIG_IN_MS = 3500;
    static final int RAIN_MS = 8000;      // midnight with the rain set to 0 minutes
    static final long DEMO_MS = 100_000;  // "Preview end of day": 5 minutes of rain at 3x

    final int cols, rows, day, unit;
    final int ground;     // first soil row (the grass line); the sky (and hill) is above it
    final int bedRows;    // bedrock at the very bottom (grows over two weeks)
    final int[] px;
    private final int[] base;
    private final Prefs prefs;
    private final Stats stats;
    private boolean recording;
    private final Random rnd;

    // ---- the hill (physics above the ground) ----
    final GrainGrid hill;
    private final int entrance;           // nest entrance column
    private final float swayAmp;          // cells, around the entrance
    private int placed;                   // grains dropped on the hill so far today

    // ---- the tunnels (fixed plan for the day) ----
    private final int[] order;            // cells in the order they are dug
    private int nOrder;
    private final boolean[] dug;
    private int shown;                    // cells dug so far (as shown)
    private final int[] shaft;            // main shaft path (for the carrying ant), cell indexes
    private int nShaft;
    private final byte[] room;            // extras' chambers: 1 queen, 2 eggs, 3 food (0 none)
    private final int[][] rooms = new int[3][];   // {cx, cy, rx, ry}

    // ---- clock text underground ----
    private List<ClockText.Slot> slots = new ArrayList<>();
    private final byte[] text;            // 1 wall/edge
    private final boolean[] hollow;       // a letter's inside, dug out
    private String shownKey = "";
    final int[] textPx;
    boolean textChanged = true;
    private final int textTop, textBottom;

    // ---- sky ----
    private final int[] skyCol;           // sun/moon colour over an empty sky cell, 0 none
    private int[] skyCells = new int[0];
    int nSky;
    private int skyKey = -1;
    private final int[] noteCol;          // the hourly line
    private int[] noteCells = new int[0];
    private int nNote, noteHour = -1;
    private long noteUntil;

    // ---- timing ----
    private long digInStart = -1, rainStart = -1, demoStart = -1;
    private int demoFrom;
    private boolean raining;
    private long lastTick = -1, lastWall;
    private int glint = -1;

    // rain drops
    private static final int MAX_DROPS = 90;
    private final float[] dX = new float[MAX_DROPS], dY = new float[MAX_DROPS];
    private int nDrops;

    // ---- dirty rectangles ----
    private int[] prev = new int[64], cur = new int[64];
    private int nPrev, nCur;
    final int[] dirty = new int[4 * 80];
    int nDirty;
    boolean fullDirty = true;

    AntScene(int cols, int rows, int day, Prefs prefs, Stats stats, int bedRows) {
        this.cols = cols;
        this.rows = rows;
        this.day = day;
        this.prefs = prefs;
        this.stats = stats;
        this.bedRows = bedRows;
        unit = Math.max(1, Math.round(rows / 135f));
        ground = Math.round(rows * 0.41f);
        px = new int[cols * rows];
        base = new int[cols * rows];
        textPx = new int[cols * rows];
        text = new byte[cols * rows];
        hollow = new boolean[cols * rows];
        dug = new boolean[cols * rows];
        room = new byte[cols * rows];
        skyCol = new int[cols * rows];
        noteCol = new int[cols * rows];
        rnd = new Random(day * 31L + System.nanoTime());

        // The clock sits underground, in the middle of the soil.
        int soilH = rows - bedRows - ground;
        int st = Math.max(1, Math.round(rows * 0.15f / PixelFont.H));
        int mid = ground + Math.round(soilH * 0.55f);
        textTop = mid - PixelFont.H * st;
        textBottom = mid + Math.round(rows * 0.06f) + PixelFont.H * Math.max(1, Math.round(rows * 0.055f / PixelFont.H));

        // Nest entrance: the setting (or a different spot each day for "Random").
        Random r = new Random(day * 7919L + 41);
        int pos = prefs.dropPos == Prefs.DROP_RANDOM
                ? GrainGrid.MIN_DROP + r.nextInt(GrainGrid.MAX_DROP - GrainGrid.MIN_DROP + 1) : prefs.dropPos;
        entrance = Math.round(pos / 100f * (cols - 1));
        swayAmp = Math.max(0.12f, Prefs.SWAY_AMP[prefs.sway]) * cols;
        hill = new GrainGrid(cols, ground, day, 0.99f);

        // Two-week chambers in the deep corners, dug out as the fortnight goes on.
        int deep = rows - bedRows - 4 * unit;
        rooms[0] = new int[]{Math.round(cols * 0.11f), deep - 5 * unit, 13 * unit, 5 * unit}; // queen
        rooms[1] = new int[]{Math.round(cols * 0.89f), deep - 5 * unit, 12 * unit, 5 * unit}; // eggs
        rooms[2] = new int[]{Math.round(cols * 0.5f), deep - 3 * unit, 14 * unit, 3 * unit};  // food
        order = new int[GRAINS_PER_DAY + 4096];
        shaft = new int[rows * 4];
        planTunnels(r);
    }

    void setRecording(boolean on) { recording = on && stats != null; }

    // ---- the day's tunnels ----

    private boolean reserved(int x, int y) {
        if (x < 2 || x >= cols - 2 || y < ground || y >= rows - bedRows - 2) return true;
        // Keep clear of the clock (with a margin) and of the extras' chambers.
        if (y >= textTop - 3 * unit && y <= textBottom + 3 * unit && x > cols * 0.13f && x < cols * 0.87f) return true;
        for (int[] rm : rooms) {
            float ex = (x - rm[0]) / (float) (rm[2] + 3 * unit), ey = (y - rm[1]) / (float) (rm[3] + 3 * unit);
            if (ex * ex + ey * ey < 1f) return true;
        }
        return false;
    }

    private void dig(int x, int y) {
        if (nOrder >= order.length || reserved(x, y)) return;
        int i = y * cols + x;
        if (dug[i]) return;
        dug[i] = true; // marks "planned"; reset below so the shown state starts empty
        order[nOrder++] = i;
    }

    /** A tunnel step: a small round brush (3 cells wide on a TV). */
    private void carve(float fx, float fy) {
        int rr = Math.max(1, unit / 2);
        int cx = Math.round(fx), cy = Math.round(fy);
        for (int dy = -rr; dy <= rr; dy++) for (int dx = -rr; dx <= rr; dx++) {
            if (dx * dx + dy * dy <= rr * rr) dig(cx + dx, cy + dy);
        }
    }

    private void chamber(float fx, float fy, int rx, int ry) {
        for (int dy = -ry; dy <= ry; dy++) for (int dx = -rx; dx <= rx; dx++) {
            float e = dx * dx / (float) (rx * rx) + dy * dy / (float) (ry * ry);
            if (e <= 1f) dig(Math.round(fx) + dx, Math.round(fy) + dy);
        }
    }

    /** A random free spot in the soil for a digger to head for. */
    private float[] goal(Random r) {
        for (int k = 0; k < 200; k++) {
            int x = 4 + r.nextInt(cols - 8), y = ground + 4 + r.nextInt(Math.max(1, rows - bedRows - ground - 8));
            if (!reserved(x, y)) return new float[]{x, y};
        }
        return new float[]{entrance, ground + 6};
    }

    /**
     * Digs the whole day's network in order. Each digger heads for a target spot somewhere in
     * the soil, wandering a little and turning to get round the clock and the chambers, and
     * ends in a small chamber; new diggers branch off tunnels already dug. The first one is the
     * main shaft from the entrance, down one side of the clock to the deep soil.
     * Stops at a day's worth of grains.
     */
    private void planTunnels(Random r) {
        List<float[]> heads = new ArrayList<>(); // {x, y, angle, goalX, goalY, stuck}
        float side = entrance < cols / 2 ? cols * 0.07f : cols * 0.93f;
        float[] shaftHead = new float[]{entrance, ground, (float) (Math.PI / 2), side, rows - bedRows - 10 * unit, 0};
        heads.add(shaftHead);
        int guard = 0;
        while (nOrder < GRAINS_PER_DAY && guard++ < 600_000) {
            if (heads.isEmpty() || (heads.size() < 2 && r.nextInt(400) == 0)) {
                // Branch off a tunnel already dug, toward a new spot.
                int i = order[r.nextInt(Math.max(1, nOrder))];
                float[] g = goal(r);
                float x = i % cols, y = i / (float) cols;
                heads.add(new float[]{x, y, (float) Math.atan2(g[1] - y, g[0] - x), g[0], g[1], 0});
            }
            int hi = guard % heads.size();
            float[] h = heads.get(hi);
            boolean first = h == shaftHead;
            // Steer toward the goal, with some wander.
            float want = (float) Math.atan2(h[4] - h[1], h[3] - h[0]);
            float diff = (float) Math.atan2(Math.sin(want - h[2]), Math.cos(want - h[2]));
            h[2] += Math.max(-0.12f, Math.min(0.12f, diff)) + (r.nextFloat() - 0.5f) * (first ? 0.15f : 0.4f);
            float nx = h[0] + (float) Math.cos(h[2]), ny = h[1] + (float) Math.sin(h[2]);
            if (reserved(Math.round(nx), Math.round(ny))) {
                h[2] += (r.nextBoolean() ? 1 : -1) * (float) (Math.PI / 2);
                h[5]++;
            } else {
                h[0] = nx;
                h[1] = ny;
                carve(nx, ny);
                if (first && nShaft < shaft.length) shaft[nShaft++] = Math.round(ny) * cols + Math.round(nx);
            }
            float dx = h[3] - h[0], dy = h[4] - h[1];
            if (dx * dx + dy * dy < 9 || h[5] > 40) {
                chamber(h[0], h[1], (3 + r.nextInt(4)) * unit, (1 + r.nextInt(2)) * unit + 1);
                heads.remove(hi);
            }
        }
        java.util.Arrays.fill(dug, false); // nothing is dug yet; `shown` reveals the plan
    }

    // ---- palette ----

    private static final int SKY = 1, SOIL0 = 2, AIR = 6, GRASS0 = 7, TEXT = 9, ANT = 10, CRUMB = 11,
            DROP = 12, ROCK0 = 13, BONE = 15, QUEEN = 16, EGG = 17, SEED = 18, DEW = 19, NOTE = 20,
            HILL0 = 21, N_PAL = 21 + 25 * 4;
    private final int[] pal = new int[N_PAL];
    private int colorKey = Integer.MIN_VALUE;
    private static final float[] LAYER_B = {1.00f, 0.84f, 1.10f, 0.92f, 0.78f, 1.04f};
    private static final float[] LAYER_H = {0.00f, 0.10f, -0.08f, 0.05f, 0.00f, -0.05f};

    private boolean updateColors() {
        int key = prefs.background * 100000 + prefs.style * 10000 + prefs.theme * 1000
                + prefs.brightness * 100 + Math.round(prefs.warmth * 20) + (prefs.sandLayers ? 7 : 0);
        if (key == colorKey) return false;
        colorKey = key;
        int st = prefs.style;
        // Like the sand clock: Black = the soil is dark and the air (sky, tunnels) softly lit;
        // Glowing = the soil lit and the air black.
        float soilF = st == 0 ? 0f : st == 1 ? 0.1f : 0.6f;
        int airLit = prefs.color(0x5A4A3A, Prefs.BACKGROUND_F[prefs.background]);
        pal[SKY] = st < 2 ? prefs.color(prefs.sandRgb(), Prefs.BACKGROUND_F[prefs.background]) : 0xFF000000;
        pal[AIR] = st < 2 ? airLit : prefs.color(0x140E0A, 1f);
        int sand = prefs.sandRgb();
        for (int k = 0; k < 4; k++) pal[SOIL0 + k] = prefs.color(shade(sand, 0.62f - k * 0.08f, 0.04f * k), soilF);
        float gf = st == 0 ? 0.45f : st == 1 ? 0.55f : 0.9f;
        pal[GRASS0] = prefs.color(0x3E7A34, gf);
        pal[GRASS0 + 1] = prefs.color(0x56984A, gf);
        pal[TEXT] = prefs.textColor(1f);
        pal[ANT] = prefs.color(0xA8492A, 1f);
        pal[CRUMB] = prefs.color(0xE8D8A8, 1f);
        pal[DROP] = prefs.color(0x9CC8F0, 0.9f);
        pal[ROCK0] = prefs.color(0x3A322A, st < 2 ? 0.7f : 1f);
        pal[ROCK0 + 1] = prefs.color(0x2C2620, st < 2 ? 0.7f : 1f);
        pal[BONE] = prefs.color(0xD8CCB0, 0.6f);
        pal[QUEEN] = prefs.color(0xC0603A, 1f);
        pal[EGG] = prefs.color(0xF2EEDC, 0.85f);
        pal[SEED] = prefs.color(0xC8A060, 0.9f);
        pal[DEW] = prefs.color(0xFFF4DC, 1f);
        pal[NOTE] = prefs.textColor(0.75f);
        // The hill: each hour's soil in its own tone (sand-art layers), 4 texture shades.
        float[] f = st == 0 ? new float[]{0f, 0f, 0f, 0f}
                : st == 1 ? new float[]{0.04f, 0.06f, 0.08f, 0.10f}
                : new float[]{0.43f, 0.5f, 0.55f, 0.62f};
        for (int l = 0; l < 25; l++) {
            int band = prefs.sandLayers && l > 0 ? (l - 1) % LAYER_B.length : 0;
            int tinted = shade(sand, LAYER_B[band], LAYER_H[band]);
            for (int k = 0; k < 4; k++) pal[HILL0 + l * 4 + k] = prefs.color(tinted, f[k]);
        }
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
        return true;
    }

    private static int shade(int rgb, float b, float warm) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * b * (1 + warm)));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * b));
        int bl = Math.min(255, Math.round((rgb & 0xFF) * b * (1 - warm)));
        return (r << 16) | (g << 8) | bl;
    }

    // ---- what each still cell looks like ----

    private int baseColor(int i) {
        int x = i % cols, y = i / cols;
        int h = i * 0x9E3779B1;
        if (y < ground) {
            if (hill.c[i] == GrainGrid.SAND) {
                if (i == glint) return pal[DEW];
                return pal[HILL0 + hill.layer[i] * 4 + ((h >>> 28) & 3)];
            }
            if (noteCol[i] != 0) return noteCol[i];
            if (skyCol[i] != 0) return skyCol[i];
            return pal[SKY];
        }
        if (y >= rows - bedRows) return bedrock(x, y - (rows - bedRows), h);
        if (text[i] != 0) return pal[TEXT];
        if (hollow[i] || dug[i]) return pal[AIR];
        byte rm = room[i];
        if (rm != 0) return roomColor(rm, x, y);
        if (y <= ground + unit) return pal[GRASS0 + ((h >>> 31) & 1)];
        // Soil strata: wavy bands that darken with depth.
        int band = Math.min(3, Math.max(0, (y - ground + (int) (3 * Math.sin(x / 37.0 + day))) * 4 / Math.max(1, rows - bedRows - ground)));
        return pal[SOIL0 + band] ^ (((h >>> 29) & 1) == 0 ? 0 : 0x00020202);
    }

    private int roomColor(byte rm, int x, int y) {
        int[] r = rooms[rm - 1];
        int dx = x - r[0], dy = y - r[1];
        if (rm == 1) { // the queen, resting in her chamber
            int qx = (dx + QUEEN_ART[0].length() * unit / 2) / unit, qy = (dy + r[3] / 2) / unit;
            if (dx + QUEEN_ART[0].length() * unit / 2 >= 0 && qx < QUEEN_ART[0].length() && dy + r[3] / 2 >= 0
                    && qy < QUEEN_ART.length && QUEEN_ART[qy].charAt(qx) == '#') return pal[QUEEN];
        }
        int hsh = (y * cols + x) * 0x9E3779B1;
        if (rm == 2 && dy >= r[3] / 3 && ((hsh >>> 27) & 7) < Math.round(8 * eggFill)) return pal[EGG];
        if (rm == 3 && dy >= 0 && ((hsh >>> 27) & 7) < Math.round(8 * seedFill)) return pal[SEED];
        return pal[AIR];
    }

    private float eggFill, seedFill;

    private int bedrock(int x, int y, int h) {
        int f = fossilAt(x, y);
        if (f != 0) return pal[BONE];
        return ((y + ((h >>> 30) & 1)) % 3 == 0) ? pal[ROCK0 + 1] : pal[ROCK0];
    }

    private static final String[][] FOSSILS = {{".##.", "#.##", ".##."}, {"#..#", "####", "#..#"}};

    private int fossilAt(int x, int y) {
        if (stats == null || bedRows < 4) return 0;
        int n = stats.fossils();
        for (int k = 0; k < n; k++) {
            int fx = Math.round(cols * (0.07f + 0.86f * (k + 0.5f) / 8f)) - 2;
            int fy = Math.max(0, (bedRows - 3) / 2 + (k % 2));
            int q = x - fx, r = y - fy;
            if (q >= 0 && q < 4 && r >= 0 && r < 3 && FOSSILS[k % 2][r].charAt(q) == '#') return 1;
        }
        return 0;
    }

    private void composeAll() {
        for (int i = 0; i < base.length; i++) base[i] = baseColor(i);
        fullDirty = true;
    }

    private void recolor(int i) { base[i] = baseColor(i); }

    // ---- time ----

    private static long msOfDay(Calendar c) {
        return ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60
                + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
    }

    private static int dueAt(long ms) {
        return (int) Math.min(GRAINS_PER_DAY, ms / 1000 / Stats.SECONDS_PER_GRAIN);
    }

    void startDigIn(long t) { digInStart = t; }

    /** Midnight with the rain set to 0: wash the day away now, quickly. */
    void startRain(long t) { if (rainStart < 0) rainStart = t; }

    boolean rained(long t) { return rainStart >= 0 && t - rainStart >= RAIN_MS; }

    void startDemo(long t, Calendar c) {
        demoStart = t;
        demoFrom = Math.max(GRAINS_PER_DAY / 3, dueAt(msOfDay(c)));
    }

    boolean demoOver(long t) { return demoStart >= 0 && t - demoStart > DEMO_MS + 3000; }

    boolean isEmpty() { return shown == 0 && hill.sandCount == 0; }

    /** How many grains are dug (and on the hill) now, and whether it is raining. */
    private int target(Calendar c, long t, boolean rainAllowed) {
        long ms = msOfDay(c);
        raining = false;
        if (demoStart >= 0) {
            float p = Math.min(1f, (t - demoStart) / (float) DEMO_MS);
            raining = p < 1f;
            return Math.round(demoFrom * (1 - p));
        }
        if (rainStart >= 0) {
            float p = Math.min(1f, (t - rainStart) / (float) RAIN_MS);
            raining = p < 1f;
            return Math.round(GRAINS_PER_DAY * (1 - p));
        }
        int due = dueAt(ms);
        int vm = Prefs.VACUUM_MIN[prefs.vacuum];
        long from = DAY_MS - vm * 60_000L;
        if (vm > 0 && rainAllowed && ms >= from) {
            float p = (ms - from) / (vm * 60_000f);
            raining = true;
            due = Math.round(dueAt(from) * (1 - p));
        }
        if (digInStart >= 0) {
            float p = Math.min(1f, (t - digInStart) / (float) DIG_IN_MS);
            if (p >= 1f) digInStart = -1;
            due = Math.round(due * (1 - (1 - p) * (1 - p) * (1 - p)));
        }
        return due;
    }

    // ---- the clock ----

    private void layoutText(Calendar c) {
        int st = Math.max(1, Math.round(rows * 0.15f / PixelFont.H));
        int sd = Math.max(1, Math.round(rows * 0.055f / PixelFont.H));
        int mid = textTop + PixelFont.H * st;
        java.util.Arrays.fill(text, (byte) 0);
        java.util.Arrays.fill(hollow, false);
        slots = ClockText.layoutAt(prefs, c, cols, rows, mid, st, sd);
        for (ClockText.Slot s : slots) {
            for (int i : s.cells) text[i] = 1;
            for (int i : s.lids) text[i] = 1;
            for (int i : s.inner) hollow[i] = true;
        }
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
    }

    // ---- extras: chambers and their contents ----

    private long extrasKey = -1;

    private boolean buildExtras(Calendar c) {
        if (stats == null) return false;
        stats.refresh(c);
        float g = stats.sandGrowth();
        int open = g >= 0.75f ? 3 : g >= 0.5f ? 2 : g >= 0.25f ? 1 : 0;
        eggFill = Math.min(1f, Math.max(0f, (g - 0.5f) * 2f));
        seedFill = Math.min(1f, Math.max(0f, (g - 0.75f) * 4f));
        long key = stats.period * 1000 + open * 100 + Math.round(eggFill * 8) * 10 + Math.round(seedFill * 8);
        if (key == extrasKey) return false;
        extrasKey = key;
        java.util.Arrays.fill(room, (byte) 0);
        for (int k = 0; k < open; k++) {
            int[] r = rooms[k];
            for (int dy = -r[3]; dy <= r[3]; dy++) for (int dx = -r[2]; dx <= r[2]; dx++) {
                if (dx * dx / (float) (r[2] * r[2]) + dy * dy / (float) (r[3] * r[3]) > 1f) continue;
                int x = r[0] + dx, y = r[1] + dy;
                if (x >= 0 && x < cols && y >= ground && y < rows - bedRows) room[y * cols + x] = (byte) (k + 1);
            }
        }
        return true;
    }

    // ---- sky: sun, moon and the hourly line ----

    private void updateSky(Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int key = prefs.sunMoon ? m + colorKey * 1441 : -2;
        if (key == skyKey) return;
        skyKey = key;
        for (int k = 0; k < nSky; k++) { skyCol[skyCells[k]] = 0; recolor(skyCells[k]); }
        markCells(skyCells, nSky);
        nSky = 0;
        if (!prefs.sunMoon) return;
        int[] t = prefs.city > 0 ? Sun.times(prefs.city, c) : new int[]{420, 1155};
        int rise = t[0], set = t[1];
        boolean sun = m >= rise && m < set;
        float p = sun ? (m - rise) / (float) Math.max(1, set - rise)
                : ((m < rise ? m + 1440 : m) - set) / (float) Math.max(1, 1440 - set + rise);
        int horizon = ground, top = Math.round(rows * 0.08f);
        int cx = Math.round(cols * (0.06f + 0.88f * p));
        int cy = Math.round(horizon - (horizon - top) * (float) Math.sin(Math.PI * p));
        int r = Math.max(3, Math.round(rows * (sun ? 0.026f : 0.022f)));
        int core = prefs.color(sun ? 0xFFD27A : 0xE8E4D0, sun ? 1f : 0.85f);
        int edge = prefs.color(sun ? 0xF0A850 : 0xC8C4B4, sun ? 1f : 0.85f);
        double phase = sun ? 0 : Sun.moonPhase(c.getTimeInMillis());
        if (skyCells.length < (2 * r + 1) * (2 * r + 1)) skyCells = new int[(2 * r + 1) * (2 * r + 1)];
        for (int dy = -r; dy <= r; dy++) {
            double w = Math.sqrt(Math.max(0, r * r - dy * dy + r * 0.8));
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > r + 0.4) continue;
                if (!sun) {
                    double term = w * Math.cos(2 * Math.PI * phase);
                    if (!(phase < 0.5 ? dx >= term : dx <= -term)) continue;
                }
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= cols || y >= ground) continue;
                int i = y * cols + x;
                skyCol[i] = d > r - 1.2 ? edge : core;
                skyCells[nSky++] = i;
                recolor(i);
            }
        }
        markCells(skyCells, nSky);
    }

    /** Sky cells of the moon (for the dim night clock), and their colours. */
    int skyCell(int k) { return skyCells[k]; }
    int skyColor(int i) { return skyCol[i]; }

    private void updateNote(Calendar c, boolean animate) {
        long now = c.getTimeInMillis();
        if (nNote > 0 && (now > noteUntil || raining)) {
            for (int k = 0; k < nNote; k++) { noteCol[noteCells[k]] = 0; recolor(noteCells[k]); }
            markCells(noteCells, nNote);
            nNote = 0;
        }
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (stats == null || nNote > 0 || raining || !animate || noteHour == h) return;
        if (c.get(Calendar.MINUTE) != 30 || c.get(Calendar.SECOND) > 20) return;
        noteHour = h;
        String msg = stats.sandMessage(c, h);
        if (msg == null) return;
        noteUntil = now + 12_000;
        int sc = unit;
        int left = Math.max(0, (cols - PixelFont.width(msg) * sc) / 2);
        int top = Math.round(rows * 0.035f);
        List<Integer> cells = new ArrayList<>();
        int x = left;
        for (int k = 0; k < msg.length(); k++) {
            for (int i : PixelFont.cells(msg.charAt(k), x, top, sc, cols, ground)) cells.add(i);
            x += PixelFont.advance(msg.charAt(k), sc);
        }
        if (noteCells.length < cells.size()) noteCells = new int[cells.size()];
        for (int i : cells) {
            noteCells[nNote++] = i;
            noteCol[i] = pal[NOTE];
            recolor(i);
        }
        markCells(noteCells, nNote);
    }

    private void markCells(int[] cells, int n) {
        if (n == 0) return;
        int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
        for (int k = 0; k < n; k++) {
            int x = cells[k] % cols, y = cells[k] / cols;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    // ---- per frame ----

    /** Column the k-th grain of the day is dropped on: around the entrance, swaying. */
    private int dropColumn(int k) {
        double amp = swayAmp * Math.max(0.25, 1 - k / (double) GRAINS_PER_DAY);
        double x = entrance + amp * Math.sin(2 * Math.PI * k / 45.0);
        return Math.max(0, Math.min(cols - 1, (int) Math.round(x)));
    }

    /**
     * Brings the scene to time c / uptime t. animate = false: no moving ants, rain or dew;
     * the tunnels and hill still follow the time.
     */
    void update(Calendar c, long t, boolean animate, boolean rainAllowed) {
        boolean recompose = updateColors();
        long wall = c.getTimeInMillis();
        boolean gap = lastWall == 0 || Math.abs(wall - lastWall) > 120_000;
        lastWall = wall;

        String key = ClockText.key(prefs, c);
        if (!key.equals(shownKey)) {
            layoutText(c);
            shownKey = key;
            recompose = true;
        }
        if (buildExtras(c)) recompose = true;

        // Tunnels and hill follow the target count.
        int want = target(c, t, rainAllowed);
        int before = shown;
        int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
        while (shown < want && shown < nOrder) {
            int i = order[shown++];
            dug[i] = true;
            if (!recompose) {
                recolor(i);
                int x = i % cols, y = i / cols;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
        }
        while (shown > want) {
            int i = order[--shown];
            dug[i] = false;
            if (!recompose) {
                recolor(i);
                int x = i % cols, y = i / cols;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
        }
        if (x1 >= 0) addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        // Grains dug as the day goes: count them (only live digging, as the screensaver). At
        // night the farm updates once a minute (10 grains); bigger jumps are catch-up or dig-in.
        if (recording && shown > before && shown - before <= 20 && digInStart < 0 && !raining && !gap) {
            for (int k = before; k < shown; k++) stats.addGrain();
        }
        while (placed < want) {
            hill.spawnLayer = GrainGrid.layerOf(placed);
            hill.dropInstant(dropColumn(placed));
            placed++;
        }
        // Rain: wash grains off the hill until it holds only what is still dug.
        if (hill.sandCount > want && placed > want) {
            int excess = hill.sandCount - want;
            for (int k = 0; k < excess * 3 && hill.sandCount > want; k++) hill.removeTop(rnd.nextInt(cols));
            if (want == 0) while (hill.sandCount > 0) { if (!hill.removeTop(rnd.nextInt(cols))) continue; }
            placed = Math.max(want, Math.min(placed, hill.sandCount));
        }

        // Physics only while grains are sliding (after the rain takes some away).
        if (lastTick < 0) lastTick = t;
        int ticks = (int) Math.min(6, (t - lastTick) / 33);
        lastTick += ticks * 33L;
        if (t - lastTick > 200) lastTick = t;
        if (hill.hasActive()) for (int k = 0; k < ticks; k++) hill.tick();

        updateSky(c);
        updateNote(c, animate);
        if (recompose) composeAll();
        else {
            // Hill cells that changed.
            int n = hill.takeChanges();
            if (n < 0) {
                for (int i = 0; i < ground * cols; i++) recolor(i);
                addCur(0, 0, cols, ground);
            } else if (n > 0) {
                int[] ch = hill.changedCells();
                int a0 = cols, b0 = rows, a1 = -1, b1 = -1;
                for (int k = 0; k < n; k++) {
                    int i = ch[k];
                    recolor(i);
                    int x = i % cols, y = i / cols;
                    a0 = Math.min(a0, x); b0 = Math.min(b0, y); a1 = Math.max(a1, x); b1 = Math.max(b1, y);
                }
                addCur(a0, b0, a1 - a0 + 1, b1 - b0 + 1);
            }
        }
        if (recompose) hill.takeChanges();

        // Dew: now and then a grain on the hill catches the light.
        if (glint >= 0) { int g = glint; glint = -1; recolor(g); addCur(g % cols, g / cols, 1, 1); }
        if (animate && !raining && rnd.nextFloat() < 0.04f) {
            int x = rnd.nextInt(cols), y = hill.freeDepth(x);
            if (y < ground && hill.c[y * cols + x] == GrainGrid.SAND) {
                glint = y * cols + x;
                recolor(glint);
                addCur(x, y, 1, 1);
            }
        }

        // Rain drops.
        int dticks = Math.max(1, ticks);
        if (raining && animate) {
            while (nDrops < MAX_DROPS * Math.min(1f, 0.3f + shownFrac())) {
                dX[nDrops] = rnd.nextInt(cols);
                dY[nDrops] = -rnd.nextInt(ground);
                nDrops++;
            }
        }
        for (int k = 0; k < nDrops; k++) {
            dY[k] += 2.2f * dticks;
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            boolean land = y >= ground || (y >= 0 && x >= 0 && x < cols && hill.c[y * cols + x] != GrainGrid.EMPTY);
            if (land) {
                if (raining && animate) { dX[k] = rnd.nextInt(cols); dY[k] = -rnd.nextInt(20); }
                else { nDrops--; dX[k] = dX[nDrops]; dY[k] = dY[nDrops]; k--; }
            }
        }
        if (!animate) nDrops = 0;

        render(c, t, animate);
    }

    private float shownFrac() { return Math.min(1f, shown / (float) Math.max(1, GRAINS_PER_DAY)); }

    private void addCur(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(cols, x + w), y1 = Math.min(rows, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (nCur + 4 > cur.length) cur = java.util.Arrays.copyOf(cur, cur.length * 2);
        cur[nCur++] = x0; cur[nCur++] = y0; cur[nCur++] = x1 - x0; cur[nCur++] = y1 - y0;
    }

    private void render(Calendar c, long t, boolean animate) {
        long ms = msOfDay(c);
        int aw = ANT_A.w * unit, ah = ANT_A.h * unit;

        // The seconds ant walks the surface (over the hill) once a minute, turning at each end.
        float s = (ms % 60_000) / 60_000f;
        if (!animate) s = (int) (s * 60) / 60f;
        boolean right = (ms / 60_000) % 2 == 0;
        int sx = Math.round(right ? 2 + s * (cols - aw - 4) : cols - aw - 2 - s * (cols - aw - 4));
        int surf = ground;
        for (int x = sx; x < sx + aw; x++) surf = Math.min(surf, hill.freeDepth(Math.max(0, Math.min(cols - 1, x))));
        int sy = surf - ah;
        addCur(sx, sy - unit, aw, ah + unit);

        // The digger at the end of today's tunnel; the carrier on the main shaft.
        int dx = -1, dy = -1, cx = -1, cy = -1;
        if (shown > 0 && shown < nOrder) {
            int i = order[shown - 1];
            dx = i % cols - aw / 2;
            dy = i / cols - ah / 2;
            addCur(dx, dy, aw, ah);
        }
        // Only along the part of the shaft dug so far (the plan interleaves several diggers).
        int open = 0;
        while (open < nShaft && dug[shaft[open]]) open++;
        if (animate && open > 4) {
            float q = (t % 40_000) / 20_000f;           // down and back up in 40 s
            float along = q <= 1f ? q : 2f - q;
            int i = shaft[Math.min(open - 1, Math.round(along * (open - 1)))];
            cx = i % cols - aw / 2;
            cy = i / cols - ah / 2;
            addCur(cx, cy - unit, aw, ah + unit);
        }
        if (nDrops > 0) addCur(0, 0, cols, ground);

        if (fullDirty) System.arraycopy(base, 0, px, 0, px.length);
        else {
            restore(prev, nPrev);
            restore(cur, nCur);
        }

        int legs = animate ? (int) ((t / 160) % 2) : 0;
        drawAnt(sx, sy, !right, legs, true, false);
        if (dx >= 0 || dy >= 0) drawAnt(dx, dy, false, (int) ((t / 300) % 2), false, true);
        if (cx >= 0) drawAnt(cx, cy, (t % 40_000) >= 20_000, legs, true, true);
        for (int k = 0; k < nDrops; k++) {
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            for (int l = 0; l < 2 * unit; l++) {
                int yy = y - l;
                if (x < 0 || x >= cols || yy < 0 || yy >= ground) continue;
                int i = yy * cols + x;
                if (hill.c[i] == GrainGrid.EMPTY) px[i] = pal[DROP];
            }
        }

        nDirty = 0;
        if (!fullDirty) {
            for (int k = 0; k < nPrev && nDirty < dirty.length; k++) dirty[nDirty++] = prev[k];
            for (int k = 0; k < nCur && nDirty < dirty.length; k++) dirty[nDirty++] = cur[k];
        }
        int[] tmp = prev; prev = cur; cur = tmp;
        nPrev = nCur; nCur = 0;
    }

    private void restore(int[] r, int n) {
        for (int k = 0; k + 3 < n; k += 4) {
            int x = r[k], y = r[k + 1], w = r[k + 2], h = r[k + 3];
            for (int yy = y; yy < y + h; yy++) System.arraycopy(base, yy * cols + x, px, yy * cols + x, w);
        }
    }

    /** An ant (optionally carrying a crumb), drawn only over air (sky, tunnels) when inside. */
    private void drawAnt(int x, int y, boolean flip, int frame, boolean crumb, boolean airOnly) {
        Sprite s = frame == 0 ? ANT_A : ANT_B;
        for (int yy = 0; yy < s.h * unit; yy++) for (int xx = 0; xx < s.w * unit; xx++) {
            int pi = s.at(xx / unit, yy / unit, flip);
            if (pi < 0) continue;
            int X = x + xx, Y = y + yy;
            if (X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
            int i = Y * cols + X;
            if (airOnly && Y >= ground && !dug[i] && !hollow[i]) continue;
            px[i] = pal[pi];
        }
        if (crumb) {
            int bx = flip ? x + unit : x + (s.w - 2) * unit;
            for (int k = 0; k < unit; k++) for (int l = 0; l < unit; l++) {
                int X = bx + k, Y = y - unit + l;
                if (X >= 0 && Y >= 0 && X < cols && Y < rows) px[Y * cols + X] = pal[CRUMB];
            }
        }
    }

    /** True while something needs smooth frames (dig-in, rain, sliding grains). */
    boolean busy() { return digInStart >= 0 || raining || nDrops > 0 || hill.hasActive(); }

    // ---- sprites (original pixel art) ----

    static final class Sprite {
        final String[] r;
        final int w, h;
        final int idx;
        Sprite(int idx, String... r) { this.idx = idx; this.r = r; w = r[0].length(); h = r.length; }
        int at(int x, int y, boolean flip) {
            return r[y].charAt(flip ? w - 1 - x : x) == '#' ? idx : -1;
        }
    }

    private static final String[] QUEEN_ART = {
            "...........#",
            "..####.###.#",
            ".###########",
            "######.####.",
            ".####.#.#.#.",
            "..#..#.#...."};
    static final Sprite ANT_A = new Sprite(ANT,
            ".......#",
            ".##.##.#",
            "########",
            "#.#..#.#");
    static final Sprite ANT_B = new Sprite(ANT,
            ".......#",
            ".##.##.#",
            "########",
            ".#.##.#.");
}
