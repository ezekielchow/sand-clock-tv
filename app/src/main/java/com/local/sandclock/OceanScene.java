package com.local.sandclock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The ocean clock as pixels (plain Java, no Android, so it can be tested on a desktop).
 *
 * The sea fills the screen over the day: empty at midnight, near the top just before the next.
 * A whale floats on the surface and is the seconds hand: it swims from one side to the other
 * each minute, turns, and at :00 blows a spout as the water steps up. The clock's characters
 * are open-top containers: they stay dry inside until the water reaches their rim, then fill,
 * and keep their water when the sea drains at midnight. Characters that change dissolve into
 * bubbles; characters that stay grow coral. Kelp grows as the water deepens, and the animals
 * change with depth: crabs in the morning shallows, fish and turtles by day, jellyfish and an
 * anglerfish in the deep evening sea.
 *
 * Power: everything that doesn't move is composed once into `base` (when the minute, level or
 * colours change). Each frame only the rectangles under moving things are restored from it and
 * redrawn, and the view uploads just those rectangles.
 */
final class OceanScene {
    static final int DAY_MS = 86_400_000;
    /** Wave lines swap once a minute with this crossfade. */
    private static final int FADE_MS = 2500;
    /** Pour-in: the tide rolls in to today's level over this long. */
    private static final int TIDE_MS = 3500;
    /** Midnight with the whirlpool off: the sea drains in this long. */
    static final int DRAIN_MS = 7000;
    /** The whale dives into the whirlpool over its last seconds. */
    private static final int DIVE_MS = 8000;

    final int cols, rows, day;
    final int unit;      // sprite scale: 2 on a 270-row grid
    final int[] px;      // what is on screen
    private final int[] base;
    private final Prefs prefs;
    private final Random rnd;

    // ---- layout (fixed for the day, seeded by the date) ----
    final int floorRow;  // the seabed's average top; the water's zero
    private final int topRow;    // the water's top at the end of the day
    /** The water's lowest level: a shallow lagoon, just deep enough for the whale to float. */
    final int emptyRow;
    private final int[] bedTop;  // first seabed row per column
    private final byte[] solid;  // seabed: 1 sand, 2 rock
    private final byte[] deco;   // starfish and shells (palette index, 0 = none)
    private final byte[] kelp;   // palette index, 0 = none
    private final int[][] stalks; // {x, maxHeight}
    private final int[] kelpBox = new int[4];
    private float kelpPhase;

    // ---- the clock text ----
    private List<ClockText.Slot> slots = new ArrayList<>();
    private final byte[] text;       // 1 = letter wall or top edge
    private final int[] innerSlot;   // index into slots, -1 = none
    private boolean[] flooded = new boolean[0];
    private final byte[] coral;
    private final Map<String, Long> born = new HashMap<>(); // slot id -> wall ms it appeared
    private String shownKey = "";
    final int[] textPx;  // the letters alone (the dim clock at night)
    boolean textChanged = true;

    // ---- water ----
    private int levelRow;
    private int[] wave, oldWave;
    private long fadeStart = -1;
    private int lastMinute = -1;
    private long tideStart = -1;
    private long drainStart = -1;
    private long demoStart = -1;
    private float demoFrom;
    private boolean whirl;
    private float whirlFrac; // 0..1 how far through the end of the day
    private int whirlX, whirlY;
    private long lastWall;

    // ---- moving things ----
    private static final int MAX_P = 300;
    private final float[] pX = new float[MAX_P], pY = new float[MAX_P], pVx = new float[MAX_P], pVy = new float[MAX_P];
    private final int[] pKind = new int[MAX_P], pWait = new int[MAX_P];
    private int nP;
    private static final int P_BUBBLE = 0, P_DROP = 1;
    private long lastTick = -1;

    private Mob swimmer, crab;
    private long nextSwimAt, nextCrabAt;
    private int whaleX, whaleY = -1000;
    private boolean whaleRight;

    // ---- stats and the extras they grow (reset every two weeks) ----
    private Stats stats;          // null: no extras (desktop tests)
    private boolean recording;    // count what is shown (only as the screensaver)
    private boolean whirlCounted;
    private final byte[] extra;   // chest and trophies on the seabed (palette index)
    private long extrasKey = -1;
    private int[] barnacles = new int[0]; // whale sprite cells {x, y}, in the order they grow
    // Message in a bottle: drifts along the surface every two hours; its line shows mid-way.
    private long bottleStart = -1;
    private int bottleHour = -1;
    private int[] noteCells = new int[0];
    private int noteX0, noteY0, noteW, noteH;
    private static final int BOTTLE_MS = 90_000;

    /** Stats for the extras (always) and whether to count what is shown (screensaver only). */
    void setStats(Stats s, boolean record) {
        stats = s;
        recording = record && s != null;
    }

    // ---- dirty rectangles ----
    private int[] prev = new int[64], cur = new int[64];
    private int nPrev, nCur;
    /** Rectangles changed by the last render, {x, y, w, h} each; full when fullDirty. */
    final int[] dirty = new int[4 * 80];
    int nDirty;
    boolean fullDirty = true;

    OceanScene(int cols, int rows, int day, Prefs prefs) {
        this.cols = cols;
        this.rows = rows;
        this.day = day;
        this.prefs = prefs;
        unit = Math.max(1, Math.round(rows / 135f));
        px = new int[cols * rows];
        base = new int[cols * rows];
        textPx = new int[cols * rows];
        solid = new byte[cols * rows];
        deco = new byte[cols * rows];
        kelp = new byte[cols * rows];
        text = new byte[cols * rows];
        innerSlot = new int[cols * rows];
        coral = new byte[cols * rows];
        extra = new byte[cols * rows];
        bedTop = new int[cols];
        rnd = new Random(day * 31L + System.nanoTime());
        Random r = new Random(day * 7919L + 3);

        floorRow = rows - Math.round(rows * 0.07f);
        topRow = 7 * unit;
        emptyRow = floorRow - Math.round(WHALE.h * unit * 0.6f);
        // Seabed: soft dunes, then a few rocks on top.
        double l1 = 60 + r.nextInt(80), l2 = 17 + r.nextInt(14), p1 = r.nextDouble() * 7, p2 = r.nextDouble() * 7;
        for (int x = 0; x < cols; x++) {
            bedTop[x] = floorRow + (int) Math.round(2.2 * Math.sin(x / l1 * 6.283 + p1) + 0.9 * Math.sin(x / l2 * 6.283 + p2));
        }
        int nRocks = 2 + r.nextInt(3);
        for (int k = 0; k < nRocks; k++) {
            int cx = r.nextInt(cols), rw = (6 + r.nextInt(9)) * unit, rh = (3 + r.nextInt(5)) * unit;
            for (int x = cx - rw; x <= cx + rw; x++) {
                if (x < 0 || x >= cols) continue;
                double f = (x - cx) / (double) rw;
                int top = floorRow - (int) Math.round(rh * Math.sqrt(Math.max(0, 1 - f * f)));
                for (int y = top; y < bedTop[x]; y++) solid[y * cols + x] = 2;
                if (top < bedTop[x]) bedTop[x] = top;
            }
        }
        for (int x = 0; x < cols; x++) {
            for (int y = Math.max(0, bedTop[x]); y < rows; y++) if (solid[y * cols + x] == 0) solid[y * cols + x] = 1;
        }
        // Starfish and shells resting on the sand.
        int nDeco = 2 + r.nextInt(3);
        for (int k = 0; k < nDeco; k++) {
            Sprite s = r.nextBoolean() ? STAR : SHELL;
            int x = r.nextInt(cols - s.w * unit);
            int y = bedTop[x + s.w * unit / 2] - s.h * unit + unit;
            for (int sy = 0; sy < s.h * unit; sy++) for (int sx = 0; sx < s.w * unit; sx++) {
                int pi = s.at(sx / unit, sy / unit, false);
                int xx = x + sx, yy = y + sy;
                if (pi >= 0 && yy >= 0 && yy < rows) deco[yy * cols + xx] = (byte) pi;
            }
        }
        int nKelp = 5 + r.nextInt(4);
        stalks = new int[nKelp][];
        for (int k = 0; k < nKelp; k++) {
            stalks[k] = new int[]{4 + r.nextInt(cols - 8), Math.round(rows * (0.18f + 0.3f * r.nextFloat()))};
        }
        kelpPhase = r.nextFloat() * 6;
        java.util.Arrays.fill(innerSlot, -1);
        List<int[]> bc = new ArrayList<>();
        for (int y = 2; y <= 6; y++) for (int x = 0; x < WHALE.w; x++) {
            if (WHALE.r[y].charAt(x) == 'B' && WHALE.r[y - 1].charAt(x) != '.') bc.add(new int[]{x, y});
        }
        java.util.Collections.shuffle(bc, new Random(11));
        barnacles = new int[bc.size() * 2];
        for (int k = 0; k < bc.size(); k++) { barnacles[2 * k] = bc.get(k)[0]; barnacles[2 * k + 1] = bc.get(k)[1]; }
        whirlX = cols / 2;
        whirlY = floorRow - 3 * unit;
        levelRow = emptyRow;
        wave = makeWave(0);
        oldWave = wave;
    }

    // ---- palette ----

    private static final int SKY = 1, FOAM = 2, SAND0 = 3, ROCK0 = 6, KELP0 = 8, CORAL0 = 10,
            TEXT = 13, TEXT_WET = 14, W_BODY = 15, W_BELLY = 16, W_EYE = 17, W_MOUTH = 18, W_FIN = 19,
            T_SHELL = 20, T_LIGHT = 21, T_SKIN = 22, DARK = 23, R_BODY = 24, R_UNDER = 25,
            A_BODY = 26, A_EYE = 27, A_TOOTH = 28, A_STALK = 29, A_LURE = 30,
            F_BODY = 31, F_TAIL = 32, C_BODY = 33, J_BELL = 34, J_IN = 35, J_TENT = 36,
            S_STAR = 37, SH_A = 38, SH_B = 39, BUBBLE = 40, CORE = 41, BARN = 42, WOOD = 43, BAND = 44,
            LOCK = 45, COIN0 = 46, COIN1 = 47, GLASS = 48, CORK = 49, NOTE = 50, N_PAL = 51;
    private static final int RAMP = 32;
    private final int[] pal = new int[N_PAL];
    private final int[] water = new int[RAMP], rays = new int[RAMP];
    private int colorKey = Integer.MIN_VALUE;

    private boolean updateColors() {
        int key = prefs.background * 100000 + prefs.style * 10000 + prefs.theme * 1000
                + prefs.brightness * 100 + Math.round(prefs.warmth * 20);
        if (key == colorKey) return false;
        colorKey = key;
        int st = prefs.style;
        float wf = st == 0 ? 0f : st == 1 ? 0.22f : 1f;   // water: black, near-black, lit
        float sf = st < 2 ? Prefs.BACKGROUND_F[prefs.background] : 0f; // air above it
        float bf = st == 0 ? 0.55f : st == 1 ? 0.7f : 1f;  // seabed and kelp
        pal[0] = 0xFF000000;
        pal[SKY] = sf > 0 ? prefs.color(0x5E88B4, sf) : 0xFF000000;
        pal[FOAM] = prefs.color(0xCFEFFF, 0.8f);
        pal[SAND0] = prefs.color(0x9A8058, bf);
        pal[SAND0 + 1] = prefs.color(0x8A7250, bf);
        pal[SAND0 + 2] = prefs.color(0x7A6446, bf);
        pal[ROCK0] = prefs.color(0x5A5C66, bf);
        pal[ROCK0 + 1] = prefs.color(0x484A52, bf);
        pal[KELP0] = prefs.color(0x2E7A3E, bf);
        pal[KELP0 + 1] = prefs.color(0x45A055, bf);
        pal[CORAL0] = prefs.color(0xE0707E, bf);
        pal[CORAL0 + 1] = prefs.color(0xE89A50, bf);
        pal[CORAL0 + 2] = prefs.color(0xB860A8, bf);
        pal[TEXT] = prefs.textColor(1f);
        pal[W_BODY] = prefs.color(0x46628A, 1f);
        pal[W_BELLY] = prefs.color(0xB8C8D8, 1f);
        pal[W_EYE] = prefs.color(0x101418, 1f);
        pal[W_MOUTH] = prefs.color(0x26385A, 1f);
        pal[W_FIN] = prefs.color(0x3A5276, 1f);
        pal[T_SHELL] = prefs.color(0x4E7A3A, 1f);
        pal[T_LIGHT] = prefs.color(0x84A852, 1f);
        pal[T_SKIN] = prefs.color(0x9DB070, 1f);
        pal[DARK] = prefs.color(0x101010, 1f);
        pal[R_BODY] = prefs.color(0x44566A, 1f);
        pal[R_UNDER] = prefs.color(0x8A9AAA, 1f);
        pal[A_BODY] = prefs.color(0x5A4444, 1f);
        pal[A_EYE] = prefs.color(0xE0E0A0, 1f);
        pal[A_TOOTH] = prefs.color(0xF0F0F0, 1f);
        pal[A_STALK] = prefs.color(0x7A6A5A, 1f);
        pal[A_LURE] = prefs.color(0xFFF2A0, 1f);
        pal[F_BODY] = prefs.color(0xF2B840, 1f);
        pal[F_TAIL] = prefs.color(0xE08A30, 1f);
        pal[C_BODY] = prefs.color(0xD0502A, 1f);
        pal[J_BELL] = prefs.color(0xD890E0, 1f);
        pal[J_IN] = prefs.color(0xF4C8F4, 1f);
        pal[J_TENT] = prefs.color(0xA070C0, 1f);
        pal[S_STAR] = prefs.color(0xE8783A, bf);
        pal[SH_A] = prefs.color(0xE8D8C0, bf);
        pal[SH_B] = prefs.color(0xC0A080, bf);
        pal[BUBBLE] = prefs.color(0xBFE6FF, 0.9f);
        pal[CORE] = 0xFF000000;
        pal[BARN] = prefs.color(0xB4AC9C, 1f);
        pal[WOOD] = prefs.color(0x7A4E2A, bf);
        pal[BAND] = prefs.color(0x4E3218, bf);
        pal[LOCK] = prefs.color(0xE0C050, bf);
        pal[COIN0] = prefs.color(0xF2C94C, bf);
        pal[COIN1] = prefs.color(0xC9982C, bf);
        pal[GLASS] = prefs.color(0x6FB07A, 1f);
        pal[CORK] = prefs.color(0xA07850, 1f);
        pal[NOTE] = prefs.color(0xE8F4FF, 0.9f);
        for (int k = 0; k < RAMP; k++) {
            float f = k / (RAMP - 1f);
            int c = mix(0x2A6FA8, 0x061830, f);
            water[k] = prefs.color(c, wf);
            rays[k] = prefs.color(mix(c, 0x9CD0F0, 0.22f * (1 - f)), wf);
        }
        pal[TEXT_WET] = mix(pal[TEXT], water[RAMP / 3], 0.4f);
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
        return true;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- what each still cell looks like ----

    private int baseColor(int x, int y, int[] wv) {
        int i = y * cols + x;
        int h = i * 0x9E3779B1;
        byte sd = solid[i];
        if (sd == 1) return pal[SAND0 + ((h >>> 28) % 3)];
        if (sd == 2) return pal[ROCK0 + ((h >>> 31) & 1)];
        if (deco[i] != 0) return pal[deco[i]];
        if (extra[i] != 0) return pal[extra[i]];
        int surf = levelRow + wv[x];
        boolean wet = y >= surf;
        if (text[i] != 0) return wet ? pal[TEXT_WET] : pal[TEXT];
        if (kelp[i] != 0) return pal[kelp[i]];
        if (coral[i] != 0 && wet) return pal[coral[i]];
        int s = innerSlot[i];
        if (s >= 0) wet = flooded[s];
        if (!wet) return pal[SKY];
        if (y == surf) return pal[FOAM];
        // Measured from the level, not the wave, so only the rows near the surface depend on
        // the wave (the crossfade covers them) and the deep water never goes out of step.
        int dy = Math.max(0, y - levelRow);
        int k = Math.min(RAMP - 1, dy * RAMP / Math.max(1, floorRow - topRow));
        // Daylight rays: slanted bands that fade out with depth (dithered).
        if (prefs.style == 2 && dy < rows / 3) {
            int t = x + dy / 2;
            if (t % 53 < 7 && ((h >>> 24) & 0xFF) > dy * 255 * 3 / rows) return rays[k];
        }
        return water[k];
    }

    private void composeAll(int[] wv) {
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) base[y * cols + x] = baseColor(x, y, wv);
        fullDirty = true;
    }

    private void composeRect(int x0, int y0, int w, int h, int[] wv) {
        int x1 = Math.min(cols, x0 + w), y1 = Math.min(rows, y0 + h);
        for (int y = Math.max(0, y0); y < y1; y++) for (int x = Math.max(0, x0); x < x1; x++) {
            base[y * cols + x] = baseColor(x, y, wv);
        }
    }

    // ---- water level ----

    private int rowFor(float frac) {
        return emptyRow - Math.round(Math.max(0f, Math.min(1f, frac)) * (emptyRow - topRow));
    }

    private int[] makeWave(int minute) {
        Random r = new Random(day * 1441L + minute);
        double l1 = 45 + r.nextInt(60), l2 = 13 + r.nextInt(16), a1 = 0.8 + r.nextDouble() * 0.9, a2 = 0.4 + r.nextDouble() * 0.6;
        double p1 = r.nextDouble() * 7, p2 = r.nextDouble() * 7;
        int[] w = new int[cols];
        for (int x = 0; x < cols; x++) {
            w[x] = (int) Math.round(a1 * Math.sin(x / l1 * 6.283 + p1) + a2 * Math.sin(x / l2 * 6.283 + p2));
        }
        return w;
    }

    private int surfAt(int x) {
        return levelRow + wave[Math.max(0, Math.min(cols - 1, x))];
    }

    /** Pour-in: roll the tide in from empty. */
    void startTide(long t) { tideStart = t; }

    /** Midnight without the whirlpool: drain now, quickly. */
    void startDrain(long t) { if (drainStart < 0) drainStart = t; }

    boolean drained(long t) { return drainStart >= 0 && t - drainStart >= DRAIN_MS; }

    /** "Preview end of day": the 5-minute whirlpool at 3× speed. */
    void startDemo(long t, Calendar c) {
        demoStart = t;
        demoFrom = Math.max(0.35f, minuteOfDay(c) / 1440f);
    }

    static final long DEMO_MS = 100_000;

    boolean demoOver(long t) { return demoStart >= 0 && t - demoStart > DEMO_MS + 3000; }

    private static int minuteOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static long msOfDay(Calendar c) {
        return (minuteOfDay(c) * 60L + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
    }

    /** How full the sea is now, 0..1, and whether the whirlpool is turning. */
    private float levelFrac(Calendar c, long t, boolean vacuumAllowed) {
        long ms = msOfDay(c);
        int vm = Prefs.VACUUM_MIN[prefs.whirlpool];
        long vacFrom = DAY_MS - vm * 60_000L;
        whirl = false;
        float frac = minuteOfDay(c) / 1440f;
        if (demoStart >= 0) {
            float p = Math.min(1f, (t - demoStart) / (float) DEMO_MS);
            whirl = p < 1f;
            whirlFrac = p;
            return demoFrom * (1 - p);
        }
        if (drainStart >= 0) {
            float p = Math.min(1f, (t - drainStart) / (float) DRAIN_MS);
            whirl = p < 1f;
            whirlFrac = p;
            return 1f * (1 - p);
        }
        if (vm > 0 && ms >= vacFrom && vacuumAllowed) {
            float p = (ms - vacFrom) / (vm * 60_000f);
            whirl = true;
            whirlFrac = p;
            frac = (vacFrom / 60_000f) / 1440f * (1 - p);
        }
        if (tideStart >= 0) {
            float p = Math.min(1f, (t - tideStart) / (float) TIDE_MS);
            if (p >= 1f) tideStart = -1;
            frac *= 1 - (1 - p) * (1 - p) * (1 - p);
        }
        return frac;
    }

    /** Remaining ms of the whirlpool (for the whale's dive), or -1. */
    private long whirlLeft(Calendar c, long t) {
        if (!whirl) return -1;
        if (demoStart >= 0) return DEMO_MS - (t - demoStart);
        if (drainStart >= 0) return DRAIN_MS - (t - drainStart);
        return DAY_MS - msOfDay(c);
    }

    // ---- the text ----

    private void layoutText(Calendar c, long wall, boolean fresh) {
        List<ClockText.Slot> next = ClockText.layout(prefs, c, cols, rows);
        HashSet<String> nextIds = new HashSet<>();
        for (ClockText.Slot s : next) nextIds.add(s.id);
        Map<String, Boolean> wasFlooded = new HashMap<>();
        for (int k = 0; k < slots.size(); k++) wasFlooded.put(slots.get(k).id, flooded[k]);
        // Characters that change dissolve into bubbles (the ones under water).
        if (!fresh) {
            for (ClockText.Slot s : slots) {
                if (nextIds.contains(s.id)) continue;
                for (int k = 0; k < s.cells.length; k += 4) {
                    int i = s.cells[k], x = i % cols, y = i / cols;
                    if (y > surfAt(x) + 1) addParticle(P_BUBBLE, x, y, 0, -(0.25f + rnd.nextFloat() * 0.35f), rnd.nextInt(24));
                }
                born.remove(s.id);
            }
        }
        java.util.Arrays.fill(text, (byte) 0);
        java.util.Arrays.fill(innerSlot, -1);
        java.util.Arrays.fill(coral, (byte) 0);
        slots = next;
        flooded = new boolean[slots.size()];
        if (fresh) ages(c, wall);
        for (int k = 0; k < slots.size(); k++) {
            ClockText.Slot s = slots.get(k);
            for (int i : s.cells) text[i] = 1;
            for (int i : s.lids) text[i] = 1;
            for (int i : s.inner) innerSlot[i] = k;
            Boolean f = wasFlooded.get(s.id);
            flooded[k] = f != null ? f : levelRow < s.top;
            if (!born.containsKey(s.id)) born.put(s.id, wall);
        }
        growCoral(c, wall);
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
    }

    /** Starting up: how long each character has already been showing (up to an hour). */
    private void ages(Calendar c, long wall) {
        born.clear();
        HashSet<String> alive = new HashSet<>();
        for (ClockText.Slot s : slots) alive.add(s.id);
        for (int back = 1; back <= 60 && !alive.isEmpty(); back++) {
            Calendar b = (Calendar) c.clone();
            b.add(Calendar.MINUTE, -back);
            HashSet<String> then = ClockText.ids(prefs, b, cols, rows);
            for (java.util.Iterator<String> it = alive.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (!then.contains(id)) {
                    born.put(id, wall - (back - 1) * 60_000L - c.get(Calendar.SECOND) * 1000L);
                    it.remove();
                }
            }
        }
        for (String id : alive) born.put(id, wall - 60 * 60_000L);
    }

    /** Coral on characters that stay: full after an hour (the date: over the whole day). */
    private void growCoral(Calendar c, long wall) {
        java.util.Arrays.fill(coral, (byte) 0);
        for (ClockText.Slot s : slots) {
            float f = s.part == 'd' ? minuteOfDay(c) / 1440f
                    : Math.min(1f, (wall - born.getOrDefault(s.id, wall)) / 3_600_000f);
            if (f <= 0) continue;
            int seed = s.id.hashCode();
            for (int i : s.cells) {
                int x = i % cols, y = i / cols;
                int[] nb = {i - cols, i - 1, i + 1};
                for (int k = 0; k < 3; k++) {
                    int n = nb[k];
                    if (n < 0 || n >= text.length || text[n] != 0 || innerSlot[n] >= 0 || solid[n] != 0) continue;
                    if (k > 0 && Math.abs(n % cols - x) != 1) continue;
                    int h = (n * 0x9E3779B1) ^ seed;
                    // Sparse crust that thickens: about 60 % of the edge when fully grown.
                    if (((h >>> 20) & 0xFFF) < f * 0.6f * 4096) coral[n] = (byte) (CORAL0 + ((h >>> 8) & 0xFF) % 3);
                }
            }
        }
    }

    private void updateFlooded() {
        for (int k = 0; k < slots.size(); k++) if (levelRow < slots.get(k).top) flooded[k] = true;
    }

    // ---- kelp ----

    private void growKelp() {
        int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
        java.util.Arrays.fill(kelp, (byte) 0);
        for (int[] st : stalks) {
            int sx = st[0];
            int room = bedTop[sx] - levelRow - 4 * unit;
            int h = Math.min(st[1], room);
            if (h < 3) continue;
            for (int k = 0; k < h; k++) {
                int y = bedTop[sx] - 1 - k;
                int dx = (int) Math.round(Math.sin(k / 11.0 + kelpPhase + sx) * 3.0 * k / Math.max(1, st[1]));
                for (int w = 0; w < unit; w++) setKelp(sx + dx + w, y, KELP0);
                if (k % (5 * unit) == 2 * unit && k > 2) {
                    int side = (k / (5 * unit)) % 2 == 0 ? 1 : -1;
                    for (int l = 1; l <= 2 * unit; l++) setKelp(sx + dx + (side > 0 ? unit - 1 + l : -l), y - l / 2, KELP0 + 1);
                }
                x0 = Math.min(x0, sx + dx - 3 * unit);
                x1 = Math.max(x1, sx + dx + 4 * unit);
                y0 = Math.min(y0, y - 2 * unit);
                y1 = Math.max(y1, y);
            }
        }
        kelpBox[0] = x0; kelpBox[1] = y0; kelpBox[2] = x1 - x0 + 1; kelpBox[3] = y1 - y0 + 1;
    }

    private void setKelp(int x, int y, int c) {
        if (x < 0 || x >= cols || y < 0 || y >= rows) return;
        int i = y * cols + x;
        if (solid[i] == 0 && text[i] == 0) kelp[i] = (byte) c;
    }

    // ---- particles ----

    private void addParticle(int kind, float x, float y, float vx, float vy, int wait) {
        if (nP >= MAX_P) return;
        pKind[nP] = kind; pX[nP] = x; pY[nP] = y; pVx[nP] = vx; pVy[nP] = vy; pWait[nP] = wait;
        nP++;
    }

    private void tickParticles(int ticks) {
        for (int n = 0; n < ticks; n++) {
            for (int k = 0; k < nP; k++) {
                if (pWait[k] > 0) { pWait[k]--; continue; }
                if (pKind[k] == P_BUBBLE) {
                    pY[k] += pVy[k];
                    pX[k] += (float) Math.sin((pY[k] + k * 7) / 5.0) * 0.25f;
                } else {
                    pVy[k] += 0.05f;
                    pX[k] += pVx[k];
                    pY[k] += pVy[k];
                }
                int x = Math.round(pX[k]);
                boolean gone = x < 0 || x >= cols || pY[k] >= rows
                        || (pKind[k] == P_BUBBLE ? pY[k] <= surfAt(x) : pVy[k] > 0 && pY[k] >= surfAt(x));
                if (gone) {
                    nP--;
                    pKind[k] = pKind[nP]; pX[k] = pX[nP]; pY[k] = pY[nP]; pVx[k] = pVx[nP]; pVy[k] = pVy[nP]; pWait[k] = pWait[nP];
                    k--;
                }
            }
        }
    }

    private void spout() {
        int bx = whaleX + (whaleRight ? 19 : WHALE.w - 1 - 19) * unit + unit / 2;
        int by = whaleY + 2 * unit;
        if (recording) stats.addSpout();
        for (int k = 0; k < 40; k++) {
            float a = (rnd.nextFloat() - 0.5f);
            addParticle(P_DROP, bx, by, a * 0.35f, -(1.0f + rnd.nextFloat() * 0.7f), rnd.nextInt(14));
        }
    }

    // ---- animals ----

    private static final class Mob {
        Sprite[] frames;
        float x0, speed;   // cells per second
        long born;
        int dir, y, school;
        boolean wetOnly, bob;
        float x(long t) { return x0 + dir * speed * (t - born) / 1000f; }
    }

    private float depthFrac() {
        return (emptyRow - levelRow) / (float) (emptyRow - topRow);
    }

    private void maybeSpawn(long t) {
        if (!prefs.oceanAnimals) return;
        float df = depthFrac();
        if (swimmer == null && t >= nextSwimAt) {
            List<Integer> kinds = new ArrayList<>();
            if (df > 0.12f) kinds.add(0);
            if (df > 0.3f) { kinds.add(1); kinds.add(2); }
            if (df > 0.55f) kinds.add(3);
            if (df > 0.7f) kinds.add(4);
            if (!kinds.isEmpty()) {
                int kind = kinds.get(rnd.nextInt(kinds.size()));
                Mob m = new Mob();
                m.wetOnly = true;
                switch (kind) {
                    case 0: m.frames = new Sprite[]{FISH}; m.speed = 9; m.school = 5; break;
                    case 1: m.frames = new Sprite[]{TURTLE}; m.speed = 4; break;
                    case 2: m.frames = new Sprite[]{RAY}; m.speed = 5; break;
                    case 3: m.frames = new Sprite[]{JELLY, JELLY2}; m.speed = 1.3f; m.bob = true; break;
                    default: m.frames = new Sprite[]{ANGLER}; m.speed = 2.5f; break;
                }
                m.speed *= unit;
                int h = m.frames[0].h * unit;
                int top = levelRow + 10 * unit, bottom = floorRow - h - 4 * unit;
                if (kind == 4) top = Math.max(top, bottom - 12 * unit);
                if (m.school > 0) { top += 8 * unit; bottom -= 6 * unit; }
                if (bottom > top) {
                    if (recording) stats.sawAnimal(kind); // same order as Stats.FISH..ANGLER
                    m.y = top + rnd.nextInt(bottom - top);
                    m.dir = rnd.nextBoolean() ? 1 : -1;
                    int w = m.frames[0].w * unit + (m.school > 0 ? 26 * unit : 0);
                    m.x0 = m.dir > 0 ? -w : cols + 1;
                    m.born = t;
                    swimmer = m;
                }
            }
            if (swimmer == null) nextSwimAt = t + 5000;
        }
        if (crab == null && t >= nextCrabAt && df > 0.03f) {
            Mob m = new Mob();
            m.frames = new Sprite[]{CRAB, CRAB2};
            m.speed = 2.5f * unit;
            m.dir = rnd.nextBoolean() ? 1 : -1;
            m.x0 = m.dir > 0 ? -CRAB.w * unit : cols + 1;
            m.born = t;
            crab = m;
            if (recording) stats.sawAnimal(Stats.CRAB);
        }
    }

    private boolean offScreen(Mob m, long t) {
        float x = m.x(t);
        int w = m.frames[0].w * unit + (m.school > 0 ? 26 * unit : 0);
        return m.dir > 0 ? x > cols + w : x < -2 * w;
    }

    // ---- per frame ----

    /**
     * Brings the scene to time c (wall clock) / t (uptime ms) and redraws what moved.
     * animate = false: no animals, bubbles or sway; just the whale and the water.
     */
    void update(Calendar c, long t, boolean animate, boolean vacuumAllowed) {
        boolean recompose = updateColors();
        long wall = c.getTimeInMillis();
        int minute = minuteOfDay(c);
        boolean gap = lastWall == 0 || Math.abs(wall - lastWall) > 120_000;
        lastWall = wall;

        String key = ClockText.key(prefs, c);
        if (!key.equals(shownKey)) {
            layoutText(c, wall, shownKey.isEmpty() || gap || !animate);
            shownKey = key;
            recompose = true;
        } else if (minute != lastMinute) {
            growCoral(c, wall);
            recompose = true;
        }
        if (minute != lastMinute) {
            int[] nw = makeWave(minute);
            if (animate && lastMinute >= 0 && !gap) {
                oldWave = wave;
                fadeStart = t;
            }
            wave = nw;
            if (lastMinute >= 0 && animate && !gap && whaleY > -1000 && !whirlDiving(c, t)) spout();
            if (lastMinute >= 0 && !gap && recording) stats.addLap(); // the whale crossed once more
            lastMinute = minute;
            recompose = true;
        }

        int row = rowFor(levelFrac(c, t, vacuumAllowed));
        // A whirlpool lasts minutes (or the 7 s drain), so it is counted as it starts: the
        // once-a-minute night steps see it too.
        if (whirl && !whirlCounted && demoStart < 0 && recording) {
            whirlCounted = true;
            stats.addWhirlpool();
        }
        if (row != levelRow) {
            levelRow = row;
            updateFlooded();
            growKelp();
            recompose = true;
        }
        if (animate && kelpTick(t)) {
            int[] b0 = kelpBox.clone();
            growKelp();
            if (!recompose) {
                composeRect(b0[0], b0[1], b0[2], b0[3], wave);
                composeRect(kelpBox[0], kelpBox[1], kelpBox[2], kelpBox[3], wave);
                addCur(b0[0], b0[1], b0[2], b0[3]);
                addCur(kelpBox[0], kelpBox[1], kelpBox[2], kelpBox[3]);
            }
        }
        if (minute != lastExtrasMinute) {
            lastExtrasMinute = minute;
            if (buildExtras(c)) recompose = true;
        }
        if (recompose) composeAll(fadeStart >= 0 ? oldWave : wave);

        // Wave lines: the old ones fade out as the new ones fade in.
        if (fadeStart >= 0) {
            float a = Math.min(1f, (t - fadeStart) / (float) FADE_MS);
            int y0 = levelRow - 3, y1 = levelRow + 3;
            for (int y = Math.max(0, y0); y <= Math.min(rows - 1, y1); y++) {
                for (int x = 0; x < cols; x++) {
                    int c0 = baseColor(x, y, oldWave), c1 = baseColor(x, y, wave);
                    base[y * cols + x] = c0 == c1 ? c1 : mix(c0, c1, a);
                }
            }
            addCur(0, y0, cols, y1 - y0 + 1);
            if (a >= 1f) fadeStart = -1;
        }

        // Fixed-rate physics for the particles.
        if (lastTick < 0) lastTick = t;
        int ticks = (int) Math.min(6, (t - lastTick) / 33);
        lastTick += ticks * 33L;
        if (t - lastTick > 200) lastTick = t; // after a pause, don't race to catch up
        if (ticks > 0) tickParticles(ticks);
        if (!animate) { nP = 0; swimmer = null; crab = null; }

        render(c, t, animate);
    }

    private long lastKelpAt;
    private int lastExtrasMinute = -1;

    private boolean kelpTick(long t) {
        if (t - lastKelpAt < 2500) return false;
        lastKelpAt = t;
        kelpPhase += 0.55f;
        return true;
    }

    private boolean whirlDiving(Calendar c, long t) {
        long left = whirlLeft(c, t);
        return left >= 0 && left < DIVE_MS;
    }

    private void addCur(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(cols, x + w), y1 = Math.min(rows, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (nCur + 4 > cur.length) cur = java.util.Arrays.copyOf(cur, cur.length * 2);
        cur[nCur++] = x0; cur[nCur++] = y0; cur[nCur++] = x1 - x0; cur[nCur++] = y1 - y0;
    }

    private void render(Calendar c, long t, boolean animate) {
        long ms = msOfDay(c);
        // Where everything is this frame.
        int ww = WHALE.w * unit, wh = WHALE.h * unit;
        int minute = (int) (ms / 60_000);
        float s = (ms % 60_000) / 60_000f;
        if (!animate) s = (int) (s * 60) / 60f; // a ticking seconds hand
        whaleRight = minute % 2 == 0;
        int xl = 2, xr = cols - ww - 2;
        whaleX = Math.round(whaleRight ? xl + s * (xr - xl) : xr - s * (xr - xl));
        int cx = whaleX + ww / 2;
        int wy = surfAt(cx) - wh / 2 - unit + (animate && (t / 1600) % 2 == 0 ? unit / 2 : 0);
        int lowest = rows;
        for (int x = whaleX; x < whaleX + ww; x += 2) lowest = Math.min(lowest, bedTop[Math.max(0, Math.min(cols - 1, x))]);
        wy = Math.min(wy, lowest - wh + unit);
        boolean whaleShown = true;
        long left = whirlLeft(c, t);
        if (left >= 0 && left < DIVE_MS) {
            // Dives into the whirlpool and is gone just before it closes.
            float p = 1f - Math.max(0, left - 1000) / (float) (DIVE_MS - 1000);
            whaleX = Math.round(whaleX + (whirlX - ww / 2 - whaleX) * p);
            wy = Math.round(wy + (whirlY - wh / 2 - wy) * p);
            whaleShown = left > 1000;
        }
        whaleY = wy;

        if (animate) {
            maybeSpawn(t);
            if (swimmer != null && offScreen(swimmer, t)) { swimmer = null; nextSwimAt = t + 8000 + rnd.nextInt(22000); }
            if (crab != null && offScreen(crab, t)) { crab = null; nextCrabAt = t + 20000 + rnd.nextInt(70000); }
        }

        // Rectangles to restore: last frame's things and this frame's.
        if (crab != null) {
            int x = Math.round(crab.x(t));
            crab.y = bedTop[Math.max(0, Math.min(cols - 1, x + CRAB.w * unit / 2))] - CRAB.h * unit + unit / 2;
            addCur(x, crab.y, CRAB.w * unit, CRAB.h * unit);
        }
        if (swimmer != null) {
            Mob m = swimmer;
            int x = Math.round(m.x(t));
            int y = m.y + (m.bob ? (int) Math.round(Math.sin(t / 1400.0) * 4 * unit) : 0);
            int w = m.frames[0].w * unit, h = m.frames[0].h * unit;
            if (m.school > 0) addCur(x - (m.dir > 0 ? 26 * unit : 0), y - 8 * unit, w + 26 * unit, h + 16 * unit);
            else addCur(x, y, w, h);
        }
        if (whaleShown) addCur(whaleX, whaleY, ww, wh);
        boolean calf = whaleShown && stats != null && stats.hasCalf();
        int calfX = 0, calfY = 0, cw = WHALE.w * calfUnit(), ch = WHALE.h * calfUnit();
        if (calf) {
            // Swims just behind its mother, a little lower.
            calfX = whaleRight ? whaleX - cw - 3 * unit : whaleX + ww + 3 * unit;
            calfY = whaleY + wh / 2;
            addCur(calfX, calfY, cw, ch);
        }
        int bottleX = 0, bottleY = 0;
        boolean bottle = bottleUpdate(c, t, animate);
        float bp = bottle ? (t - bottleStart) / (float) BOTTLE_MS : 0f;
        boolean note = bottle && bp > 0.25f && bp < 0.75f;
        if (bottle) {
            bottleX = Math.round(-BOTTLE.w * unit + bp * (cols + BOTTLE.w * unit));
            bottleY = surfAt(bottleX + BOTTLE.w * unit / 2) - 2 * unit;
            addCur(bottleX, bottleY, BOTTLE.w * unit, BOTTLE.h * unit);
        }
        // The line's area is restored for one more frame after it goes, to erase it.
        if (bottle || noteShown) addCur(noteX0, noteY0, noteW, noteH);
        noteShown = note;
        if (nP > 0) {
            int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
            for (int k = 0; k < nP; k++) {
                int x = Math.round(pX[k]), y = Math.round(pY[k]);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1);
            }
            addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        }
        int wr = 0;
        if (whirl) {
            wr = Math.round((5 + 11 * Math.min(1f, whirlFrac)) * unit);
            addCur(whirlX - wr - 1, whirlY - wr / 2 - 1, 2 * wr + 3, wr + 3);
        }

        if (fullDirty) {
            System.arraycopy(base, 0, px, 0, px.length);
        } else {
            restore(prev, nPrev);
            restore(cur, nCur);
        }

        // Draw, back to front.
        if (whirl) drawWhirl(t, wr);
        if (crab != null) {
            int f = (int) (Math.abs(crab.x(t)) / (2 * unit)) % 2;
            drawSprite(crab.frames[f], Math.round(crab.x(t)), crab.y, false, false, true);
        }
        if (swimmer != null) {
            Mob m = swimmer;
            int x = Math.round(m.x(t));
            int y = m.y + (m.bob ? (int) Math.round(Math.sin(t / 1400.0) * 4 * unit) : 0);
            Sprite sp = m.frames[(int) ((t / 700) % m.frames.length)];
            if (m.school > 0) {
                int[][] off = {{0, 0}, {-11, -6}, {-9, 6}, {-22, 1}, {-19, -8}};
                for (int[] o : off) drawSprite(sp, x + o[0] * unit * m.dir, y + o[1] * unit, m.dir < 0, true, true);
            } else {
                drawSprite(sp, x, y, m.dir < 0, true, true);
            }
        }
        for (int k = 0; k < nP; k++) {
            if (pWait[k] > 0 && pKind[k] == P_BUBBLE) continue;
            int x = Math.round(pX[k]), y = Math.round(pY[k]);
            if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
            px[y * cols + x] = pal[pKind[k] == P_BUBBLE ? BUBBLE : FOAM];
        }
        if (calf) drawSprite(WHALE, calfX, calfY, !whaleRight, false, false, calfUnit());
        if (whaleShown) {
            drawSprite(WHALE, whaleX, whaleY, !whaleRight, false, false);
            drawBarnacles();
        }
        if (bottle) drawSprite(BOTTLE, bottleX, bottleY, false, false, false);
        if (note) for (int i : noteCells) px[i] = pal[NOTE];

        // Hand the changed rectangles to the view.
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

    /** True while something needs smooth frames (bubbles, spout, fades, tide, whirlpool). */
    boolean busy() {
        return nP > 0 || fadeStart >= 0 || tideStart >= 0 || whirl || drainStart >= 0;
    }

    int levelRow() { return levelRow; }

    /** A whirlpool at the seabed: turning dashed rings around a dark centre. */
    private void drawWhirl(long t, int r) {
        int phase = (int) (t / 90);
        for (int dy = -r / 2 - 1; dy <= r / 2 + 1; dy++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                float ex = dx / (float) r, ey = dy / (r / 2f);
                float d = (float) Math.sqrt(ex * ex + ey * ey);
                if (d > 1.05f) continue;
                int x = whirlX + dx, y = whirlY + dy;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                int i = y * cols + x;
                if (solid[i] != 0 || text[i] != 0) continue;
                if (d < 0.22f) { px[i] = pal[CORE]; continue; }
                // Spiral arms: angle plus distance, turning faster near the centre.
                double a = Math.atan2(ey, ex) + d * 5.0;
                int arm = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * 12 + phase * (1.4 - d)) & 3;
                if (arm == 0) px[i] = pal[FOAM];
                else if (arm == 1 && d < 0.6f) px[i] = pal[BUBBLE];
            }
        }
    }

    private void drawSprite(Sprite s, int x, int y, boolean flip, boolean wetOnly, boolean behindText) {
        drawSprite(s, x, y, flip, wetOnly, behindText, unit);
    }

    private int calfUnit() { return Math.max(1, unit / 2); }

    private void drawSprite(Sprite s, int x, int y, boolean flip, boolean wetOnly, boolean behindText, int u) {
        for (int sy = 0; sy < s.h * u; sy++) {
            int yy = y + sy;
            if (yy < 0 || yy >= rows) continue;
            for (int sx = 0; sx < s.w * u; sx++) {
                int xx = x + sx;
                if (xx < 0 || xx >= cols) continue;
                int pi = s.at(sx / u, sy / u, flip);
                if (pi < 0) continue;
                int i = yy * cols + xx;
                if (behindText && (text[i] != 0 || solid[i] != 0)) continue;
                if (wetOnly && yy <= surfAt(xx)) continue;
                px[i] = pal[pi];
            }
        }
    }

    // ---- extras ----

    private boolean noteShown;

    /** Barnacles on the whale's back: more the longer it has swum these two weeks. */
    private void drawBarnacles() {
        if (stats == null) return;
        int n = Math.round(stats.oceanGrowth() * Math.min(28, barnacles.length / 2));
        for (int k = 0; k < n; k++) {
            int sx = barnacles[2 * k], sy = barnacles[2 * k + 1];
            int fx = whaleRight ? sx : WHALE.w - 1 - sx;
            int x = whaleX + fx * unit + (k % unit), y = whaleY + sy * unit + ((k / 2) % unit);
            if (x >= 0 && y >= 0 && x < cols && y < rows) px[y * cols + x] = pal[BARN];
        }
    }

    /**
     * The treasure chest (fills with coins over the two weeks) and the trophies of animals
     * seen often, on the seabed. Rebuilt only when they change. True if anything changed.
     */
    private boolean buildExtras(Calendar c) {
        if (stats == null) return false;
        stats.refresh(c);
        float g = stats.oceanGrowth();
        int coins = Math.round(g * 20);
        int[] tro = stats.trophies();
        long key = stats.period * 10_000 + (g > 0 ? 1000 : 0) + coins * 10 + tro.length;
        if (key == extrasKey) return false;
        extrasKey = key;
        java.util.Arrays.fill(extra, (byte) 0);
        Random r = new Random(stats.period * 977 + 5);
        int cx = Math.round(cols * (0.12f + 0.76f * r.nextFloat()));
        if (g > 0) {
            int x0 = cx - CHEST.w * unit / 2;
            int y0 = bedTop[Math.max(0, Math.min(cols - 1, cx))] - CHEST.h * unit + unit;
            stamp(CHEST, x0, y0);
            // Coins heap up inside the open chest, from the middle out, bottom row first.
            int placed = 0;
            for (int row = 2; row >= 1 && placed < coins; row--) {
                for (int k = 0; k < 10 && placed < coins; k++) {
                    int col = 1 + (k % 2 == 0 ? 5 + k / 2 : 4 - k / 2);
                    fillCell(x0 + col * unit, y0 + row * unit, (placed % 3 == 0) ? COIN1 : COIN0);
                    placed++;
                }
            }
        }
        // Trophies in a row along the seabed, kept clear of the chest.
        for (int k = 0; k < tro.length; k++) {
            Sprite s = TROPHIES[tro[k]];
            int x = Math.round(cols * (0.06f + 0.88f * (k + 0.5f) / 6f));
            if (g > 0 && Math.abs(x - cx) < 14 * unit) x += 16 * unit;
            if (x < 0 || x + s.w * unit >= cols) continue;
            int y = bedTop[x + s.w * unit / 2] - s.h * unit + unit;
            stamp(s, x, y);
        }
        return true;
    }

    private void stamp(Sprite s, int x, int y) {
        for (int sy = 0; sy < s.h * unit; sy++) for (int sx = 0; sx < s.w * unit; sx++) {
            int pi = s.at(sx / unit, sy / unit, false);
            if (pi >= 0) fillPx(x + sx, y + sy, pi);
        }
    }

    private void fillCell(int x, int y, int pi) {
        for (int dy = 0; dy < unit; dy++) for (int dx = 0; dx < unit; dx++) fillPx(x + dx, y + dy, pi);
    }

    private void fillPx(int x, int y, int pi) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        int i = y * cols + x;
        if (solid[i] == 0) extra[i] = (byte) pi;
    }

    /**
     * Every two hours (at :15) a bottle drifts along the surface for 90 s; while it passes the
     * middle, a line about the whale's lifetime stats shows at the top of the screen. True while the bottle is out.
     */
    private boolean bottleUpdate(Calendar c, long t, boolean animate) {
        if (stats == null || !animate || whirl) { bottleStart = -1; return false; }
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (bottleStart < 0 && h % 2 == 0 && m == 15 && c.get(Calendar.SECOND) < 30 && bottleHour != h) {
            bottleHour = h;
            String msg = stats.oceanMessage(c, h / 2);
            if (msg == null) return false; // nothing to tell yet
            bottleStart = t;
            int sc = Math.max(1, unit);
            int w = PixelFont.width(msg) * sc;
            int top = Math.round(rows * 0.035f); // above the clock, clear of the digits
            int left = Math.max(0, (cols - w) / 2);
            List<Integer> cells = new ArrayList<>();
            int x = left;
            for (int k = 0; k < msg.length(); k++) {
                for (int i : PixelFont.cells(msg.charAt(k), x, top, sc, cols, rows)) cells.add(i);
                x += PixelFont.advance(msg.charAt(k), sc);
            }
            noteCells = new int[cells.size()];
            for (int k = 0; k < noteCells.length; k++) noteCells[k] = cells.get(k);
            noteX0 = left; noteY0 = top; noteW = Math.min(cols, w + sc); noteH = PixelFont.H * sc;
        }
        if (bottleStart >= 0 && t - bottleStart > BOTTLE_MS) bottleStart = -1;
        return bottleStart >= 0;
    }

    // ---- sprites (original pixel art) ----

    static final class Sprite {
        final String[] r;
        final int w, h;
        final String keys;
        final int[] idx;
        Sprite(String keys, int[] idx, String... r) {
            this.keys = keys; this.idx = idx; this.r = r; w = r[0].length(); h = r.length;
        }
        int at(int x, int y, boolean flip) {
            char ch = r[y].charAt(flip ? w - 1 - x : x);
            int k = keys.indexOf(ch);
            return k < 0 ? -1 : idx[k];
        }
    }

    static final Sprite WHALE = new Sprite("BLEMF", new int[]{W_BODY, W_BELLY, W_EYE, W_MOUTH, W_FIN},
            "BB..............................",
            "BBB.............................",
            ".BBB..........BBBBBBBB..........",
            "..BBBB....BBBBBBBBBBBBBBBB......",
            "...BBBBBBBBBBBBBBBBBBBBBBBBBB...",
            "..BBBBBBBBBBBBBBBBBBBBBBBBBBBBB.",
            ".BBB..BBBBBBBBBBBBBBBBBBBBBEBBBB",
            "BBB...BBBBBBBBBBBBBBBBBBBBBBBBBB",
            "BB.....BBBBBBBBBBBFFBBBMMMMMMMBB",
            "........LLLLLLLLLLLFFLLLLLLLLLB.",
            "..........LLLLLLLLLLFFLLLLLLL...",
            "..............LLLLLL..F.........");
    static final Sprite TURTLE = new Sprite("SsHEk", new int[]{T_SHELL, T_LIGHT, T_SKIN, DARK, T_SKIN},
            "....SSSSS.......",
            "..SSsSSsSSS.....",
            ".SSsSSSSsSSS.HH.",
            "SSSSSSSSSSSSSHEH",
            ".kkSSSSSSSSSkHHH",
            "kk.k......kk....");
    static final Sprite RAY = new Sprite("Rr", new int[]{R_BODY, R_UNDER},
            ".........RRR......",
            ".......RRRRRRRR...",
            "....RRRRRRRRRRRRRR",
            "RRRRR.rrrrrrrrrr..");
    static final Sprite ANGLER = new Sprite("AaLEt", new int[]{A_BODY, A_STALK, A_LURE, A_EYE, A_TOOTH},
            "........aaL",
            ".......a...",
            "..AAAAAA...",
            ".AAAAAAAAA.",
            "AAAAAAAEAAA",
            "AAAAAAAAtAt",
            ".AAAAAAtAt.",
            "..AAAAAA...");
    static final Sprite FISH = new Sprite("TYE", new int[]{F_TAIL, F_BODY, DARK},
            "T..YYY..",
            "TTYYYYEY",
            "T..YYY..");
    static final Sprite CRAB = new Sprite("CE", new int[]{C_BODY, DARK},
            "C.......C",
            "CC.E.E.CC",
            ".CCCCCCC.",
            "..CCCCC..",
            ".C.C.C.C.");
    static final Sprite CRAB2 = new Sprite("CE", new int[]{C_BODY, DARK},
            "C.......C",
            "CC.E.E.CC",
            ".CCCCCCC.",
            "..CCCCC..",
            "C.C...C.C");
    static final Sprite JELLY = new Sprite("Jjt", new int[]{J_BELL, J_IN, J_TENT},
            "..JJJ..",
            ".JJJJJ.",
            "JJjJjJJ",
            "JJJJJJJ",
            ".t.t.t.",
            ".t.t.t.",
            "t..t..t",
            ".t..t..");
    static final Sprite JELLY2 = new Sprite("Jjt", new int[]{J_BELL, J_IN, J_TENT},
            ".......",
            "..JJJ..",
            ".JJJJJ.",
            "JJjJjJJ",
            "JJJJJJJ",
            ".t.t.t.",
            "t..t..t",
            ".t.t.t.");
    static final Sprite CHEST = new Sprite("WbLkl", new int[]{WOOD, BAND, WOOD, LOCK, BAND},
            ".llllllllll.",
            "L..........L",
            "L..........L",
            "bbbbbbbbbbbb",
            "WbWWWWWWWWbW",
            "WbWWWkkWWWbW",
            "WbWWWWWWWWbW",
            "bbbbbbbbbbbb");
    static final Sprite BOTTLE = new Sprite("GCN", new int[]{GLASS, CORK, NOTE},
            "......C",
            ".GGGGGC",
            "GGNNGG.",
            ".GGGG..");
    /** One trophy per animal (Stats order): fish bones, turtle shell, ray, glow stone, lure, claw. */
    static final Sprite[] TROPHIES = {
            new Sprite("#", new int[]{SH_A}, "#.#.#.", "######", "#.#.#."),
            new Sprite("Ss", new int[]{T_SHELL, T_LIGHT}, ".sss.", "SsSsS", "S.S.S"),
            new Sprite("R", new int[]{R_BODY}, "..R..", ".RRR.", "RRRRR", "..R.."),
            new Sprite("J", new int[]{J_BELL}, ".JJ.", "JJJJ", ".JJ."),
            new Sprite("La", new int[]{A_LURE, A_STALK}, "L..", ".a.", "..a", "aa."),
            new Sprite("C", new int[]{C_BODY}, "CC.C", "C..C", ".CC."),
    };
    static final Sprite STAR = new Sprite("S", new int[]{S_STAR},
            "..S..", ".SSS.", "SSSSS", ".S.S.", "S...S");
    static final Sprite SHELL = new Sprite("Ww", new int[]{SH_A, SH_B},
            ".WWW.", "WwWwW", "WWWWW");
}
