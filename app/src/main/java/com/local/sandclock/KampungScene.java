package com.local.sandclock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * The kampung clock as pixels (plain Java, no Android, so it can be tested on a desktop).
 *
 * A side view of a place in Malaysia that grows over the day: a kampung of stilt houses, palms
 * and paddy at midnight, shophouses and a surau in the morning, condos and the LRT in the
 * afternoon, and by late evening the KL skyline (Menara KL, the Twin Towers, Merdeka 118). Every
 * structure is built in visible steps (posts, floor, planks, roof; or course by course under a
 * crane) and what it replaces is taken down the same way first. The whole day is planned up front
 * from the date, and everything still is a function of the time of day, so a restart rebuilds
 * the same town at the same stage. The clock is on a billboard: painted planks, then a printed
 * board, then an LED screen. A wau (kite), later the LRT train, is the seconds hand. Before
 * midnight a giant biawak strolls through and everything sinks into rubble; the next day starts
 * with one house.
 *
 * Power: everything that doesn't move is composed into `base`: all of it once a minute, and just
 * a building's box when it gains a course. Each frame only the rectangles under moving things
 * (people, the crane hooks, the train...) are restored from it and redrawn.
 */
final class KampungScene {
    static final int DAY_MIN = 1440;
    private static final int GROW_MS = 3500;
    /** Midnight with the rampage set to 0 minutes: the biawak runs through in this long. */
    static final int QUICK_MS = 8000;
    static final long DEMO_MS = 100_000;
    private static final int PLANE_MS = 40_000;
    /** Share of a build spent on the fenced plot and foundations, and when it tops out. */
    private static final float FOUND = 0.06f, TOPPED = 0.95f;
    /** Billboard: repainted (wood to print) and re-fitted (print to LED) in strips. */
    private static final int BB_PRINT_AT = 570, BB_PRINT_MIN = 30, BB_LED_AT = 1080, BB_LED_MIN = 20;

    final int cols, rows, day, unit;
    final int[] px, textPx;
    private final int[] base;
    private final byte[] mask;   // 0 sky or hills, 1 solid, 2 billboard face
    private final byte[] text;
    private final short[] tag;   // which build drew a pixel (for the bare frame at the top of the work)
    private final Prefs prefs;
    private final float S;
    private final Random rnd;
    private final Random seeded = new Random(); // re-seeded for the fixed draws (same as new Random(seed))
    final int G;                 // the ground line: buildings stand on it

    boolean textChanged = true;
    final int[] dirty = new int[4 * 120];
    int nDirty;
    boolean fullDirty = true;

    // ---- the billboard ----
    private final int fx0, fx1, fy0, fy1, mid, st, sd;
    private List<ClockText.Slot> slots = new ArrayList<>();
    private String shownKey = "";

    // ---- the plan for the day ----
    static final int HOUSE = 1, SURAU = 2, SHOPS = 3, FLATS = 4, CONDO = 5, OFFICE = 6, KLT = 7, TWIN = 8,
            M118 = 9, PADDY = 10, PALMS = 11, PARK = 12, STADIUM = 13, STALL = 14, DURIAN = 15,
            PILLAR = 16, SPAN = 17, MPILLAR = 18, MBEAM = 19;

    private static final class B {
        int id, kind, x, w, h, by, bodyH, seed, col, variant;
        float s, e, d = 1e9f, dEnd = 1e9f;
        boolean crane;
        int craneX, craneDir;
        int x0, y0, x1, y1;            // box to recompose
        // now
        int ph;                        // 0 not yet, 1 building, 2 standing, 3 coming down, 4 gone
        float p;
        int vis;                       // rows built so far (rise kinds)
        long key = Long.MIN_VALUE;
        int sink, rubble;              // the biawak
        float cp;
        // the crane this frame (see craneGeom)
        int cMx, cJy, cJl, cCj, cHx, cHy;
        boolean cLoad;
    }

    private final List<B> builds = new ArrayList<>();
    private boolean planned;
    private int hillX0 = -1, hillX1 = -1, hillH;  // Bukit Nanas, under Menara KL
    private int deckY, lrtDone = 99999, lrtFrom;
    private int monoY, monoX0 = -1, monoX1 = -1, monoDone = 99999;
    private int extras;            // two-week extras: 1 park, 2 stadium, 3 monorail, 4 rooftop lights
    private static int extrasDay = -1, extrasForDay; // the day's extras, fixed when first planned
    private final byte[] star;
    private int stormFrom = -1, stormLen;
    private int festival;
    private float hillPh1, hillPh2;

    // ---- time ----
    private float pm;              // plan minute shown (frozen during the rampage)
    private int lightMin;          // minute for sky and windows
    private int lastMinute = -1;
    private long lastWall;
    private float lastPm = -1;
    private long growStart = -1, quickStart = -1, demoStart = -1;
    private float ramp = -1;       // rampage progress 0..1, -1 none
    private boolean flat;          // the biawak has been through: all rubble
    private boolean biawakCounted;
    private float prevRamp = 0;    // ramp on the last update (0 before the first: built mid-rampage)
    /** Rampage progress when the biawak has walked off the far side (see biawakFront). */
    private static final float BW_GONE = 0.8f;
    private int rise = 420, set = 1155;
    private float dusk, storm;
    private boolean animateNow = true;

    // ---- moving things ----
    private static final int MAX_P = 500;
    private final float[] pX = new float[MAX_P], pY = new float[MAX_P], pVx = new float[MAX_P], pVy = new float[MAX_P];
    private final int[] pCol = new int[MAX_P], pLife = new int[MAX_P];
    private int nP;
    private static final int MAX_DROPS = 140;
    private final float[] dX = new float[MAX_DROPS], dY = new float[MAX_DROPS];
    private int nDrops;
    private long lastTick = -1;
    private long fwNext = -1;
    private final List<Man> men = new ArrayList<>();
    private int menMinute = -1;
    private long riderNext = -1;

    // ---- stats ----
    private Stats stats;
    private boolean recording;
    private long planeStart = -1;
    private boolean planeFromRight;
    private int planeHour = -1;
    private int[][] noteCells = new int[0][];
    private int noteX0, noteY0, noteW, noteH;
    private int noteVis;           // letters of the banner line showing (typed in, then out)

    /** Lit windows (cells), for the dim night clock. */
    int[] nightLights = new int[0];
    private int[] litTmp = new int[600];
    private int nLit;

    void setStats(Stats s, boolean record) {
        stats = s;
        recording = record && s != null;
    }

    private int[] prev = new int[64], cur = new int[64];
    private int nPrev, nCur;

    KampungScene(int cols, int rows, int day, Prefs prefs) {
        this.cols = cols;
        this.rows = rows;
        this.day = day;
        this.prefs = prefs;
        unit = Math.max(1, Math.round(rows / 135f));
        S = rows / 270f;
        int n = cols * rows;
        px = new int[n];
        base = new int[n];
        textPx = new int[n];
        mask = new byte[n];
        text = new byte[n];
        tag = new short[n];
        star = new byte[n];
        sunCells = new int[SunMoon.maxCells(rows)];
        sunColors = new int[sunCells.length];
        rnd = new Random(day * 31L + System.nanoTime());
        G = Math.round(rows * 0.80f);
        deckY = G - s(36);
        monoY = G - s(58);

        // The billboard: big enough for the widest time and date, centred, high on its legs.
        st = Math.max(1, Math.round(rows * 0.13f / PixelFont.H));
        sd = Math.max(1, Math.round(rows * 0.055f / PixelFont.H));
        int sa = Math.max(1, st / 4);
        int tw = PixelFont.width("00:00") * st;
        int aw = st + PixelFont.width("PM") * sa;
        int dw = 0;
        String[] dn = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        String[] mn = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (String a : dn) for (String b : mn) dw = Math.max(dw, PixelFont.width(a + " 28 " + b) * sd);
        int half = Math.max(tw / 2 + aw, dw / 2) + s(9);
        fx0 = cols / 2 - half;
        fx1 = cols / 2 + half;
        fy0 = Math.round(rows * 0.11f);
        mid = fy0 + s(7) + PixelFont.H * st;
        fy1 = mid + Math.round(rows * 0.06f) + PixelFont.H * sd + s(7);

        Random r = new Random(day * 7919L + 11);
        for (int k = 0; k < 60; k++) {
            int x = r.nextInt(cols), y = r.nextInt(Math.max(1, Math.round(G * 0.6f)));
            star[y * cols + x] = (byte) (1 + r.nextInt(2));
        }
        hillPh1 = r.nextFloat() * 7;
        hillPh2 = r.nextFloat() * 7;
        int[] sto = stormFor(day);
        if (sto != null) {
            stormFrom = sto[0];
            stormLen = sto[1];
        }
    }

    /**
     * Today's afternoon storm as {start minute, minutes}, or null (about a third of days). The
     * same seeded draw the kampung has always used, so the Garden can share the weather: the
     * stars and hills above take 182 draws from this seed first (each nextInt(bound) and
     * nextFloat() is one step of the generator).
     */
    static int[] stormFor(int dayKey) {
        Random r = new Random(dayKey * 7919L + 11);
        for (int k = 0; k < 182; k++) r.nextInt();
        if (r.nextFloat() >= 0.34f) return null;
        int from = 930 + r.nextInt(70);
        return new int[]{from, 10 + r.nextInt(11)};
    }

    private int s(float v) { return Math.max(1, Math.round(v * S)); }

    private static int hash(int i) {
        int h = i * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int fmod(int a, int m) {
        int r = a % m;
        return r < 0 ? r + m : r;
    }

    // =====================================================================================
    // The plan
    // =====================================================================================

    private static final int ROLE_N = 0, ROLE_U = 1, ROLE_TWIN = 2, ROLE_M118 = 3, ROLE_KLT = 4, ROLE_TREES = 5;

    private static final class Lot {
        int x0, x1, role;
        final List<B> chain = new ArrayList<>();
        Lot(int x0, int x1, int role) { this.x0 = x0; this.x1 = x1; this.role = role; }
        int w() { return x1 - x0; }
        B last() { return chain.isEmpty() ? null : chain.get(chain.size() - 1); }
    }

    private int nextId = 1;

    private B newB(int kind, int x, int w, int h, float s, float dur, int seed) {
        B b = new B();
        b.id = nextId++;
        b.kind = kind; b.x = x; b.w = w; b.h = h; b.by = G; b.bodyH = h;
        b.s = s; b.e = s + dur; b.seed = seed;
        return b;
    }

    /** How long taking a kind down takes (minutes), before the next one starts. */
    private static int demolish(int kind) {
        switch (kind) {
            case HOUSE: return 20;
            case PADDY: return 30;
            case PALMS: return 15;
            case SHOPS: return 25;
            case FLATS: return 30;
            default: return 30;
        }
    }

    /** Adds b after what stands on the lot now, if there is time to take that down first. */
    private boolean follow(Lot lot, B b, boolean force) {
        B prev = lot.last();
        if (prev != null) {
            int dem = demolish(prev.kind);
            if (b.s < prev.e + 40 + dem) {
                if (!force) return false;
                // Forced (a landmark): drop what can't finish in time.
                while (prev != null && b.s < prev.e + 40 + demolish(prev.kind) && lot.chain.size() > 1) {
                    lot.chain.remove(lot.chain.size() - 1);
                    prev = lot.last();
                }
                if (prev != null && b.s < prev.e + 40 + demolish(prev.kind)) {
                    // Pushed later: keep how long it takes (else it pops up built, or rushed).
                    float dur = b.e - b.s;
                    b.s = prev.e + 40 + demolish(prev.kind);
                    b.e = b.s + dur;
                }
            }
            if (prev != null) {
                prev.d = b.s - demolish(prev.kind) - 3;
                prev.dEnd = prev.d + demolish(prev.kind);
            }
        }
        lot.chain.add(b);
        return true;
    }

    private int capFor(Lot lot) { return G - fy1 - s(10); }

    private void plan(Calendar c) {
        planned = true;
        Random r = new Random(day * 7919L + 23);
        // Extras from this fortnight: one more per kampung day at most.
        extras = 0;
        if (stats != null) {
            stats.refresh(c);
            if (extrasDay == day) {
                extras = extrasForDay;
            } else {
                // As the stats stood when the day began: today's own showing doesn't count (so a
                // restart later today plans the same town), and the value is kept for the day.
                int days = stats.kampungDays() - (stats.pLastKampungDay == Stats.epochDay(c) ? 1 : 0);
                extras = (int) Math.min(4, Math.min(stats.kampungGrowth() * 4.6f, Math.max(0, days)));
                extrasDay = day;
                extrasForDay = extras;
            }
        }
        festival = Festivals.of(prefs, c);

        int midL = fx0 - s(4), midR = fx1 + s(4);
        boolean twinLeft = r.nextBoolean();
        int lA0 = s(3), lA1 = midL, rA0 = midR, rA1 = cols - s(3);
        List<Lot> lots = new ArrayList<>();
        // The Twin Towers on one side, Merdeka 118 and Menara KL on the other.
        int tw = s(50);
        int t0 = twinLeft ? lA0 : rA0, t1 = twinLeft ? lA1 : rA1;
        int tx = t0 + s(4) + r.nextInt(Math.max(1, t1 - t0 - tw - s(8)));
        lots.add(new Lot(tx, tx + tw, ROLE_TWIN));
        int o0 = twinLeft ? rA0 : lA0, o1 = twinLeft ? rA1 : lA1;
        int mw = s(30), kw = s(24);
        boolean mFirst = r.nextBoolean();
        int room = Math.max(1, o1 - o0 - mw - kw - s(16));
        int a = o0 + s(4) + r.nextInt(Math.max(1, room / 2));
        int aw = mFirst ? mw : kw;
        int b2 = a + aw + s(10) + r.nextInt(Math.max(1, room / 2));
        lots.add(new Lot(a, a + aw, mFirst ? ROLE_M118 : ROLE_KLT));
        lots.add(new Lot(b2, b2 + (mFirst ? kw : mw), mFirst ? ROLE_KLT : ROLE_M118));
        Collections.sort(lots, new Comparator<Lot>() {
            public int compare(Lot p, Lot q) { return p.x0 - q.x0; }
        });
        // Fill the gaps with ordinary lots.
        List<Lot> all = new ArrayList<>();
        int x = 0;
        for (int k = 0; k <= lots.size(); k++) {
            int end = k < lots.size() ? lots.get(k).x0 - s(2) : cols;
            int gx = x + (x == 0 ? s(1) : s(2));
            while (end - gx >= s(18)) {
                int w = s(24) + r.nextInt(s(14));
                if (end - gx - w < s(20)) w = end - gx;
                int role = gx + w > fx0 - s(6) && gx < fx1 + s(6) ? ROLE_U : ROLE_N;
                all.add(new Lot(gx, gx + w, role));
                gx += w + s(2);
            }
            if (end - gx >= s(8)) all.add(new Lot(gx, end, ROLE_TREES));
            if (k < lots.size()) {
                all.add(lots.get(k));
                x = lots.get(k).x1;
            }
        }
        // ---- kampung (midnight to dawn) ----
        int twinIdx = -1;
        for (int k = 0; k < all.size(); k++) if (all.get(k).role == ROLE_TWIN) twinIdx = k;
        HashSet<Lot> paddy = new HashSet<>();
        for (int k = twinIdx - 1; k <= twinIdx + 1; k++) {
            if (k < 0 || k >= all.size()) continue;
            Lot l = all.get(k);
            if (l.role == ROLE_N || l.role == ROLE_TWIN) paddy.add(l);
        }
        // The first house: nearest the middle.
        Lot first = null;
        for (Lot l : all) {
            if (paddy.contains(l) || l.role == ROLE_KLT || l.role == ROLE_TREES) continue;
            if (first == null || Math.abs((l.x0 + l.x1) / 2 - cols / 2) < Math.abs((first.x0 + first.x1) / 2 - cols / 2)) first = l;
        }
        List<Lot> houses = new ArrayList<>();
        for (Lot l : all) {
            if (l.role == ROLE_KLT) continue;
            int seed = r.nextInt();
            if (paddy.contains(l)) {
                B b = newB(PADDY, l.x0, l.w(), s(9), 60 + r.nextInt(150), 30 + r.nextInt(15), seed);
                b.variant = r.nextInt(3) == 0 ? 1 : 0; // a scarecrow
                follow(l, b, false);
            } else if (l == first || (l.role != ROLE_TREES && r.nextFloat() < 0.62f)) {
                houses.add(l);
            } else {
                B b = newB(PALMS, l.x0, l.w(), s(40), r.nextInt(300), 60 + r.nextInt(40), seed);
                follow(l, b, false);
            }
        }
        Collections.shuffle(houses, r);
        if (first != null && houses.remove(first)) houses.add(0, first);
        for (int k = 0; k < houses.size(); k++) {
            Lot l = houses.get(k);
            int w = Math.min(l.w() - s(2), s(34));
            float s0 = k == 0 ? 0 : 25 + k * (300f / houses.size()) + r.nextInt(20);
            B b = newB(HOUSE, l.x0 + (l.w() - w) / 2, w, s(35), s0, k == 0 ? 30 : 35 + r.nextInt(15), r.nextInt());
            b.variant = r.nextInt(2); // zinc or attap
            follow(l, b, false);
        }
        // ---- town (morning to early afternoon) ----
        Lot surau = null;
        for (Lot l : all) {
            if (l == first || l.role == ROLE_TWIN || l.role == ROLE_M118 || l.role == ROLE_KLT || l.role == ROLE_TREES) continue;
            if (paddy.contains(l) || l.w() < s(28)) continue;
            if (surau == null || r.nextBoolean()) surau = l;
        }
        if (surau != null) {
            int w = s(28);
            follow(surau, newB(SURAU, surau.x0 + (surau.w() - w) / 2, w, s(34), 380 + r.nextInt(40), 50, r.nextInt()), true);
        }
        for (Lot l : all) {
            if (l == surau || l.role == ROLE_KLT || l.role == ROLE_TREES) continue;
            B last = l.last();
            int kind = 0;
            float s0 = 0;
            if (l.role == ROLE_TWIN) { kind = FLATS; s0 = 640 + r.nextInt(80); }
            else if (l.role == ROLE_M118) { kind = SHOPS; s0 = 450 + r.nextInt(150); }
            else if (last != null && last.kind == PADDY) { kind = r.nextFloat() < 0.4f ? SHOPS : FLATS; s0 = 620 + r.nextInt(180); }
            else if (last != null && last.kind == HOUSE && r.nextFloat() < 0.55f) { kind = SHOPS; s0 = 420 + r.nextInt(280); }
            else if (last != null && last.kind == PALMS && r.nextFloat() < 0.45f) { kind = r.nextBoolean() ? SHOPS : FLATS; s0 = 470 + r.nextInt(260); }
            if (kind == 0) continue;
            B b;
            if (kind == SHOPS) {
                int units = Math.max(1, Math.min(4, (l.w() - s(2)) / s(12)));
                int w = units * s(12);
                b = newB(SHOPS, l.x0 + (l.w() - w) / 2, w, s(28), s0, 60 + r.nextInt(25), r.nextInt());
            } else {
                int w = Math.min(l.w() - s(4), s(40));
                b = newB(FLATS, l.x0 + (l.w() - w) / 2, w, s(38), s0, 85 + r.nextInt(30), r.nextInt());
            }
            follow(l, b, false);
        }
        // ---- city (afternoon to evening) ----
        boolean heritage = false;
        for (Lot l : all) {
            if (l == surau || l.role == ROLE_TREES) continue;
            B last = l.last();
            if (l.role == ROLE_TWIN) {
                B b = newB(TWIN, l.x0, l.w(), Math.min(s(166), G - s(14)), 1110 + r.nextInt(30), 200, r.nextInt());
                b.bodyH = b.h - s(26);
                b.crane = true;
                follow(l, b, true);
            } else if (l.role == ROLE_M118) {
                B b = newB(M118, l.x0, l.w(), Math.min(s(196), G - s(6)), 1150 + r.nextInt(20), 210, r.nextInt());
                b.bodyH = b.h - s(62);
                b.crane = true;
                follow(l, b, true);
            } else if (l.role == ROLE_KLT) {
                hillH = s(16);
                hillX0 = l.x0 - s(14);
                hillX1 = l.x1 + s(14);
                B b = newB(KLT, l.x0, l.w(), Math.min(s(150), G - hillH - s(14)), 840 + r.nextInt(40), 200, r.nextInt());
                b.by = G - hillH;
                b.bodyH = b.h - s(32);
                b.crane = true;
                follow(l, b, true);
            } else {
                if (last != null && last.kind == SHOPS && !heritage && r.nextFloat() < 0.5f) { heritage = true; continue; }
                boolean u = l.role == ROLE_U;
                if (r.nextFloat() > (u ? 0.65f : 0.8f)) continue;
                int cap = u ? capFor(l) : G - s(40);
                boolean office = r.nextFloat() < 0.4f;
                int w = Math.min(l.w() - s(4), office ? s(18) + r.nextInt(s(9)) : s(20) + r.nextInt(s(9)));
                int h = Math.min(cap, office ? s(80) + r.nextInt(s(46)) : s(70) + r.nextInt(s(42)));
                if (w < s(14) || h < s(40)) continue;
                float s0 = (u ? 800 : 780) + r.nextInt(u ? 300 : 370);
                B b = newB(office ? OFFICE : CONDO, l.x0 + (l.w() - w) / 2, w, h, s0, 110 + r.nextInt(60), r.nextInt());
                b.bodyH = h - s(office ? 10 : 8);
                b.variant = r.nextInt(3);
                b.crane = true;
                follow(l, b, false);
            }
        }
        // ---- two-week extras ----
        Random xr = new Random(day * 131L + 7);
        if (extras >= 1) convertLot(all, twinIdx, PARK, xr);
        if (extras >= 2) convertLot(all, -1, STADIUM, xr);

        // Cranes: beside the building, the jib reaching over it.
        for (Lot l : all) {
            for (B b : l.chain) {
                if (!b.crane) continue;
                boolean left = b.kind == TWIN ? (b.x + b.w / 2 > cols / 2) : r.nextBoolean();
                if (b.x - s(8) < s(2)) left = false;
                if (b.x + b.w + s(8) > cols - s(2)) left = true;
                b.craneX = left ? b.x - s(6) : b.x + b.w + s(3);
                b.craneDir = left ? 1 : -1;
            }
            builds.addAll(l.chain);
        }
        // Stalls on the verge: a mamak stall and a durian seller.
        int sx = cols / 2 + (r.nextBoolean() ? -1 : 1) * (fx1 - fx0) / 4 - s(9);
        B stall = newB(STALL, sx, s(18), s(13), 530 + r.nextInt(30), 25, r.nextInt());
        stall.by = G + s(4);
        builds.add(stall);
        int dx = r.nextBoolean() ? s(10) + r.nextInt(Math.max(1, cols / 4)) : cols - s(26) - r.nextInt(Math.max(1, cols / 4));
        B dur = newB(DURIAN, dx, s(16), s(10), 600 + r.nextInt(60), 25, r.nextInt());
        dur.by = G + s(4);
        builds.add(dur);

        // The LRT: pillars one by one, then the deck span by span; trains when it is done.
        int span = s(70);
        int off = s(10) + r.nextInt(span - s(10));
        List<Integer> pil = new ArrayList<>();
        for (int px0 = off; px0 < cols; px0 += span) pil.add(px0);
        boolean fromLeft = r.nextBoolean();
        lrtFrom = 860;
        int np = pil.size();
        for (int k = 0; k < np; k++) {
            int order = fromLeft ? k : np - 1 - k;
            B p = newB(PILLAR, pil.get(k) - s(2), s(4), G + s(2) - deckY, lrtFrom + order * 7, 14, k);
            p.by = G + s(2);
            builds.add(p);
        }
        float spanStart = lrtFrom + np * 7 + 16;
        for (int k = 0; k <= np; k++) {
            int xa = k == 0 ? -s(4) : pil.get(k - 1), xb = k == np ? cols + s(4) : pil.get(k);
            int order = fromLeft ? k : np - k;
            B sp = newB(SPAN, xa, xb - xa, s(3), spanStart + order * 6, 8, k);
            sp.by = deckY + s(3);
            builds.add(sp);
            lrtDone = Math.max(lrtDone == 99999 ? 0 : lrtDone, (int) Math.ceil(sp.e));
        }
        // Monorail (extra 3): a lower, thinner line on the Merdeka side.
        if (extras >= 3) {
            boolean leftSide = !twinLeft;
            monoX0 = leftSide ? 0 : fx1 + s(10);
            monoX1 = leftSide ? fx0 - s(10) : cols;
            int mspan = s(46);
            int k = 0;
            for (int px0 = monoX0 + s(12); px0 < monoX1 - s(6); px0 += mspan, k++) {
                B p = newB(MPILLAR, px0 - s(1), s(3), G + s(2) - monoY, 900 + k * 6, 12, k);
                p.by = G + s(2);
                builds.add(p);
            }
            B beam = newB(MBEAM, monoX0, monoX1 - monoX0, s(2), 900 + k * 6 + 10, 30, 0);
            beam.by = monoY + s(2);
            builds.add(beam);
            monoDone = (int) Math.ceil(beam.e);
        }
        // Draw order: lots (by x), the landmarks over their neighbours, then the LRT, then the verge.
        Collections.sort(builds, new Comparator<B>() {
            public int compare(B p, B q) { return layer(p) - layer(q); }
        });
        for (B b : builds) box(b);
    }

    private static int layer(B b) {
        switch (b.kind) {
            case TWIN: case M118: case KLT: return 1;
            case PILLAR: case SPAN: return 3;
            case MPILLAR: case MBEAM: return 2;
            case STALL: case DURIAN: return 4;
            default: return 0;
        }
    }

    /** Extras: a lot's last city building becomes a park or a stadium. */
    private void convertLot(List<Lot> all, int near, int kind, Random xr) {
        Lot best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int k = 0; k < all.size(); k++) {
            Lot l = all.get(k);
            B last = l.last();
            if (last == null || (last.kind != CONDO && last.kind != OFFICE)) continue;
            if (kind == STADIUM && l.w() < s(28)) continue;
            int score = (near >= 0 ? -Math.abs(k - near) * 10 : 0) + xr.nextInt(5) + l.w() / 4;
            if (score > bestScore) { bestScore = score; best = l; }
        }
        if (best == null) return;
        B last = best.last();
        B b = newB(kind, best.x0, best.w(), kind == PARK ? s(22) : s(26), last.s, kind == PARK ? 100 : 140, last.seed);
        best.chain.set(best.chain.size() - 1, b);
    }

    private void box(B b) {
        int pad = b.kind == PALMS ? s(22) : s(6); // palm fronds reach well past the lot
        b.x0 = b.x - pad;
        b.x1 = b.x + b.w + pad;
        if (b.crane) {
            b.x0 = Math.min(b.x0, b.craneX - s(4));
            b.x1 = Math.max(b.x1, b.craneX + s(6));
        }
        if (b.kind == SPAN || b.kind == MBEAM) {
            b.y0 = b.by - s(6);
            b.y1 = b.by + s(4);
        } else {
            b.y0 = Math.max(0, b.by - b.h - s(22));
            b.y1 = Math.min(rows, Math.max(b.by, G) + s(5));
        }
        if (b.kind == STALL || b.kind == DURIAN) b.y0 = b.by - b.h - s(4);
        b.x0 = Math.max(0, b.x0);
        b.x1 = Math.min(cols, b.x1);
    }

    // =====================================================================================
    // Festivals
    // =====================================================================================

    /** Fireworks windows (plan minutes) for today's festival, or null. */
    private static final int[] FW_MIDNIGHT = {0, 10}, FW_MERDEKA = {1260, 1270}, FW_DEEPAVALI = {1230, 1236};
    private static final int[] SUN_DEFAULT = {420, 1155};

    private int[] fireworks() {
        switch (festival) {
            case Festivals.NEWYEAR: case Festivals.CNY: return FW_MIDNIGHT;
            case Festivals.MERDEKA: case Festivals.MSIA: return FW_MERDEKA;
            case Festivals.DEEPAVALI: return FW_DEEPAVALI;
            default: return null;
        }
    }

    // =====================================================================================
    // Palette
    // =====================================================================================

    private static final int SKY = 1, SKY2 = 2, STAR = 3, HILL0 = 4, HILL1 = 5, GRASS = 6, GRASS2 = 7, DIRT = 8,
            DIRT2 = 9, ROAD = 10, ROADLINE = 11, KERB = 12, WATER = 13, WATER2 = 14, SOIL = 15, WOOD = 16,
            WOOD2 = 17, WOOD3 = 18, ATTAP = 19, ATTAP2 = 20, ZINC = 21, ZINC2 = 22, WHITEW = 23, DOME_G = 24,
            DOME_Y = 25, SHOP0 = 26, TILE = 31, CREAM = 32, CREAM2 = 33, CONDO0 = 34, GLASS0 = 37, GLASS1 = 38,
            MULLION = 39, STEEL = 40, STEEL2 = 41, STEEL3 = 42, CONC = 43, CONC2 = 44, CONC3 = 45, SCAF = 46,
            HOARD = 47, HOARD2 = 48, CRANE = 49, CRANE2 = 50, CABLE = 51, WIN = 52, WINLIT = 53, WINLIT2 = 54,
            TRUNK = 55, FROND = 56, FROND2 = 57, BANANA = 58, BANANA2 = 59, PADDY0 = 60, PADDY1 = 61,
            PADDYG = 62, PADDYG2 = 63, PWATER = 64, BUND = 65, RUB0 = 66, RUB1 = 67, RUB2 = 68, DUST = 69,
            BB_WOOD = 70, BB_WOOD2 = 71, BB_PAINT = 72, BB_PRINT = 73, BB_PRINTD = 74, BB_RED = 75, BB_YEL = 76,
            BB_LED = 77, BB_LEDT = 78, POLE = 79, POLE2 = 80, SKIN = 81, SKIN2 = 82, HAT_Y = 83, VEST = 84,
            BLUE = 85, PANTS = 86, WHITE = 87, CONE = 88, RED = 89, GREEN = 90, BLACK = 91, PINK = 92,
            KERBAU = 93, HORN = 94, CHICK = 95, COMB = 96, CAT = 97, BEAK = 98, BW0 = 99, BW1 = 100, BW2 = 101,
            BW3 = 102, TONGUE = 103, EYE = 104, TRAIN = 105, TRAIN2 = 106, TRAINW = 107, PLANE = 108,
            BANNER = 109, NOTE = 110, RAIN = 111, BOLT = 112, FLASH = 113, FW0 = 114, LANTERN = 119, GOLD = 120,
            FLAME = 121, FLAG_R = 122, FLAG_B = 123, XTREE = 124, SUN = 125, SUN2 = 126, MOON = 127, MOON2 = 128,
            CLOUD = 129, CLOUD2 = 130, MIST = 131, LAMP = 132, AVIATION = 133, ROOFL0 = 134, KITE_R = 137,
            KITE_Y = 138, KITE_W = 139, LEAF = 140, LEAF2 = 141, SKY3 = 142, N_PAL = 143;
    private final int[] pal = new int[N_PAL];
    private int colorKey = Integer.MIN_VALUE;

    private static int mixRgb(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private void col(int i, int rgb, float f) { pal[i] = f <= 0.004f ? 0xFF000000 : prefs.color(rgb, f); }

    private boolean updateColors() {
        int key = prefs.background * 10000000 + prefs.style * 1000000 + prefs.theme * 100000
                + prefs.brightness * 10000 + Math.round(prefs.warmth * 20) * 300 + Math.round(dusk * 10) * 20
                + Math.round(storm * 4);
        if (key == colorKey) return false;
        colorKey = key;
        int stl = prefs.style;
        float sf = stl == 0 ? Prefs.BACKGROUND_F[prefs.background] : stl == 1 ? 0.06f : 0f;
        float night = 1f - 0.4f * dusk;
        float bf = (stl == 0 ? 0.5f : stl == 1 ? 0.75f : 1f) * night * (1 - 0.25f * storm);
        float gf = (stl == 0 ? 0.42f : stl == 1 ? 0.6f : 1f) * night * (1 - 0.25f * storm);
        float ff = stl == 0 ? 0.85f : 1f;                       // people, animals, things that move
        float glow = 4 * dusk * (1 - dusk);
        int top = mixRgb(mixRgb(0x5A8CC0, 0x10142E, dusk), 0x6A5A8A, glow * 0.6f);
        int low = mixRgb(mixRgb(0x9AC4E0, 0x1A2040, dusk), 0xE08A5A, glow * 0.85f);
        int midc = mixRgb(top, low, 0.5f);
        if (storm > 0) {
            top = mixRgb(top, 0x2E343C, storm * 0.8f);
            low = mixRgb(low, 0x48505A, storm * 0.8f);
            midc = mixRgb(top, low, 0.5f);
        }
        pal[0] = 0xFF000000;
        col(SKY, top, sf);
        col(SKY3, midc, sf);
        col(SKY2, low, sf);
        col(STAR, 0xC8CCE0, 0.45f * dusk);
        col(HILL0, mixRgb(0x5A7A7A, 0x26303A, dusk), stl == 2 ? 0.55f : gf * 0.9f);
        col(HILL1, mixRgb(0x3C6448, 0x1C2A24, dusk), stl == 2 ? 0.7f : gf);
        col(GRASS, 0x4E8A34, gf);
        col(GRASS2, 0x3A6E28, gf);
        col(DIRT, 0x8A6844, gf);
        col(DIRT2, 0x6A4E30, gf);
        col(ROAD, 0x3C3C42, gf);
        col(ROADLINE, 0xD8D0A8, gf);
        col(KERB, 0x9A9A94, gf);
        col(WATER, mixRgb(0x2E6A8A, 0x18304A, dusk), gf);
        col(WATER2, mixRgb(0x6AA6C4, 0x40586A, dusk), gf);
        col(SOIL, 0x3A2A1C, gf);
        col(WOOD, 0x8A5A32, bf);
        col(WOOD2, 0x5E3C20, bf);
        col(WOOD3, 0xB0804C, bf);
        col(ATTAP, 0xA07E46, bf);
        col(ATTAP2, 0x7A5E30, bf);
        col(ZINC, 0x9AA0A8, bf);
        col(ZINC2, 0x6E747C, bf);
        col(WHITEW, 0xE4DECC, bf);
        col(DOME_G, 0x3A9A64, bf);
        col(DOME_Y, 0xE0B040, bf);
        int[] shops = {0xD8866A, 0x86C0B0, 0xE4C470, 0x96B0DC, 0xE6A0AC};
        for (int k = 0; k < 5; k++) col(SHOP0 + k, shops[k], bf);
        col(TILE, 0xA84A30, bf);
        col(CREAM, 0xDCCCA4, bf);
        col(CREAM2, 0xA89A78, bf);
        int[] condo = {0xC8CCD0, 0xC4AE90, 0xA8BCCC};
        for (int k = 0; k < 3; k++) col(CONDO0 + k, condo[k], bf);
        col(GLASS0, 0x3A6A8A, bf);
        col(GLASS1, 0x4A8484, bf);
        col(MULLION, 0x9AB8C8, bf);
        col(STEEL, 0xC4CCD4, bf);
        col(STEEL2, 0x8A949E, bf);
        col(STEEL3, 0x4A5462, bf);
        col(CONC, 0xA8A49A, bf);
        col(CONC2, 0x7E7A72, bf);
        col(CONC3, 0x34322E, bf);
        col(SCAF, 0xD0A040, bf);
        col(HOARD, 0x3A6AB4, bf);
        col(HOARD2, 0xE8E8E0, bf);
        col(CRANE, 0xF0B420, bf);
        col(CRANE2, 0xA87A14, bf);
        col(CABLE, 0x2A2A2A, Math.max(bf, 0.6f));
        col(WIN, 0x2A3038, bf);
        col(WINLIT, 0xF4D488, stl == 0 ? 0.8f : 0.9f);
        col(WINLIT2, 0xE8B868, stl == 0 ? 0.75f : 0.85f);
        col(TRUNK, 0x8A6A44, bf);
        col(FROND, 0x4A9A34, bf);
        col(FROND2, 0x2E6E24, bf);
        col(BANANA, 0x6AB040, bf);
        col(BANANA2, 0x4A8A2C, bf);
        col(LEAF, 0x3E8A3A, bf);
        col(LEAF2, 0x2C6A2C, bf);
        col(PADDY0, 0x7CC244, bf);
        col(PADDY1, 0x58A032, bf);
        col(PADDYG, 0xE0C04A, bf);
        col(PADDYG2, 0xC0A036, bf);
        col(PWATER, mixRgb(0x7AA8C0, 0x304860, dusk), gf);
        col(BUND, 0x7A5E3A, gf);
        col(RUB0, 0x9A9488, bf);
        col(RUB1, 0x6E6A60, bf);
        col(RUB2, 0xB8A890, bf);
        col(DUST, 0xC8B89A, ff * 0.8f);
        col(BB_WOOD, 0x6E4628, Math.max(bf, 0.5f));
        col(BB_WOOD2, 0x5A3820, Math.max(bf, 0.5f));
        col(BB_PAINT, 0xF4ECD0, 1f);
        col(BB_PRINT, 0x1C3A72, stl == 0 ? 0.6f : 0.8f);
        col(BB_PRINTD, 0xF6F6EE, 1f);
        col(BB_RED, 0xD03A2A, 0.85f);
        col(BB_YEL, 0xEAC040, 0.85f);
        col(BB_LED, 0x0C0C10, 1f);
        pal[BB_LEDT] = prefs.textColor(1f);
        col(POLE, 0x8A8E96, Math.max(bf, 0.5f));
        col(POLE2, 0x4A4E56, Math.max(bf, 0.5f));
        col(SKIN, 0xD09A6A, ff);
        col(SKIN2, 0x8A5A38, ff);
        col(HAT_Y, 0xF4C420, ff);
        col(VEST, 0xF07A20, ff);
        col(BLUE, 0x3A5AA8, ff);
        col(PANTS, 0x2A2C44, ff);
        col(WHITE, 0xE8E8E4, ff);
        col(CONE, 0xDCC488, ff);
        col(RED, 0xD0302A, ff);
        col(GREEN, 0x2A9A44, ff);
        col(BLACK, 0x1A1A1E, ff);
        col(PINK, 0xE04A8A, ff);
        col(KERBAU, 0x4E4850, ff);
        col(HORN, 0xD8D0B8, ff);
        col(CHICK, 0xECE2D0, ff);
        col(COMB, 0xD82A2A, ff);
        col(CAT, 0xE09440, ff);
        col(BEAK, 0xF0B030, ff);
        col(BW0, 0x34361E, 1f);
        col(BW1, 0x5A5C36, 1f);
        col(BW2, 0xD0BC5A, 1f);
        col(BW3, 0x8A8656, 1f);
        col(TONGUE, 0xE04A6A, 1f);
        col(EYE, 0xF4E8B0, 1f);
        col(TRAIN, 0xDCE2E6, ff);
        col(TRAIN2, 0x2A5AB0, ff);
        col(TRAINW, dusk > 0.5f ? 0xF0D890 : 0x34404C, ff);
        col(PLANE, 0xE0E4E8, ff);
        col(BANNER, 0xF4E4A0, ff);
        col(NOTE, 0xEEF4E4, 0.9f);
        col(RAIN, 0x9AB0C8, 0.55f);
        col(BOLT, 0xF4F4FF, 1f);
        col(FLASH, 0x8A90A8, stl == 2 ? 0.35f : 0.6f);
        int[] fw = {0xF04A4A, 0xF4D040, 0x5AE070, 0x6AA0FF, 0xF4F4F4};
        for (int k = 0; k < 5; k++) col(FW0 + k, fw[k], 1f);
        col(LANTERN, 0xE0201A, 0.95f);
        col(GOLD, 0xF0C030, 0.95f);
        col(FLAME, 0xFFB030, 1f);
        col(FLAG_R, 0xD0201A, bf * 1.2f);
        col(FLAG_B, 0x1A2A90, bf * 1.2f);
        col(XTREE, 0x2A7A3A, bf);
        col(CLOUD, mixRgb(0x5A606A, 0x2A2E36, dusk), stl == 2 ? 0.5f : Math.max(sf, 0.2f) * 1.4f);
        col(CLOUD2, mixRgb(0x444A54, 0x22262C, dusk), stl == 2 ? 0.45f : Math.max(sf, 0.2f) * 1.2f);
        col(MIST, 0xC8D0D4, stl == 2 ? 0.5f : 0.45f);
        col(LAMP, 0xFFE0A0, 0.9f);
        col(AVIATION, 0xFF3020, 1f);
        col(ROOFL0, 0xE04AA0, 0.85f);
        col(ROOFL0 + 1, 0x4AC0E0, 0.85f);
        col(ROOFL0 + 2, 0xF0D050, 0.85f);
        col(KITE_R, 0xD8302A, ff);
        col(KITE_Y, 0xF0C840, ff);
        col(KITE_W, 0x2A4AA0, ff);
        textChanged = true;
        return true;
    }

    // =====================================================================================
    // Drawing primitives into `base` (clipped to the box being composed)
    // =====================================================================================

    private int cx0, cy0, cx1, cy1;           // compose clip
    private int lo = Integer.MIN_VALUE, hi;   // a building's built rows (before the sink offset)
    private int oy, ox;                       // sink offset (the biawak)
    private short curTag;

    private void P(int x, int y, int pi) {
        if (y < lo) return;
        x += ox;
        y += oy;
        if (y >= hi || x < cx0 || x >= cx1 || y < cy0 || y >= cy1) return;
        int i = y * cols + x;
        base[i] = pal[pi];
        mask[i] = 1;
        tag[i] = curTag;
    }

    private void R(int x, int y, int w, int h, int pi) {
        int ya = Math.max(y, lo) + oy, yb = y + h + oy;
        x += ox;
        ya = Math.max(ya, cy0);
        yb = Math.min(yb, Math.min(hi, cy1));
        int xa = Math.max(x, cx0), xb = Math.min(x + w, cx1);
        if (xa >= xb || ya >= yb) return;
        int c = pal[pi];
        for (int yy = ya; yy < yb; yy++) {
            int i = yy * cols + xa;
            for (int xx = xa; xx < xb; xx++, i++) {
                base[i] = c;
                mask[i] = 1;
                tag[i] = curTag;
            }
        }
    }

    private void L(int x0, int y0, int x1, int y1, int pi) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int n = 0; n < 2000; n++) {
            P(x0, y0, pi);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private boolean inClip(int x0, int y0, int x1, int y1) {
        return x1 > cx0 && x0 < cx1 && y1 > cy0 && y0 < cy1;
    }

    // =====================================================================================
    // Composing the still layer
    // =====================================================================================

    private void composeAll() {
        nLit = 0;
        compose(0, 0, cols, rows);
        fullDirty = true;
        // A sample of the lit windows for the dim night clock.
        int n = Math.min(40, nLit);
        nightLights = new int[n];
        for (int k = 0; k < n; k++) nightLights[k] = litTmp[(int) ((long) k * nLit / Math.max(1, n))];
    }

    private void compose(int x0, int y0, int x1, int y1) {
        cx0 = Math.max(0, x0); cy0 = Math.max(0, y0); cx1 = Math.min(cols, x1); cy1 = Math.min(rows, y1);
        if (cx0 >= cx1 || cy0 >= cy1) return;
        lo = Integer.MIN_VALUE; hi = rows; oy = 0; ox = 0; curTag = 0;
        paintSky();
        paintGround();
        paintBillboardLegs();
        for (B b : builds) drawBuild(b);
        paintDecorations();
        paintMist();
        paintBillboard();
        if (!fullDirty) addCur(cx0, cy0, cx1 - cx0, cy1 - cy0);
    }

    // ---- sky, hills, ground ----

    private int[] sunCells, sunColors;
    private int nSun, sunKey = -1, sunColorKey;
    private Calendar sunCal;

    private void paintSky() {
        int yEnd = Math.min(cy1, G);
        int b1 = Math.round(G * 0.45f), b2 = Math.round(G * 0.72f);
        boolean ev = lightMin >= set + 20 || lightMin < rise - 20;
        for (int y = cy0; y < yEnd; y++) {
            for (int x = cx0; x < cx1; x++) {
                int i = y * cols + x;
                int c;
                if (y < b1 || (y < b1 + 2 && ((x + y) & 1) == 0)) c = SKY;
                else if (y < b2 || (y < b2 + 2 && ((x + y) & 1) == 0)) c = SKY3;
                else c = SKY2;
                if (ev && star[i] != 0 && storm == 0) c = STAR;
                base[i] = pal[c];
                mask[i] = 0;
                tag[i] = 0;
            }
        }
        // Storm clouds.
        if (storm > 0) {
            Random r = seeded;
            r.setSeed(day * 53L);
            int n = Math.round(9 * storm);
            for (int k = 0; k < n; k++) {
                int cx = r.nextInt(cols), cy = s(6) + r.nextInt(s(40));
                int rw = s(30) + r.nextInt(s(30)), rh = s(8) + r.nextInt(s(6));
                for (int y = cy - rh; y <= cy + rh; y++) for (int x = cx - rw; x <= cx + rw; x++) {
                    if (x < cx0 || x >= cx1 || y < cy0 || y >= Math.min(cy1, G) || y < 0) continue;
                    float ex = (x - cx) / (float) rw, ey = (y - cy) / (float) rh;
                    float d = ex * ex + ey * ey;
                    if (d > 1 - 0.15f * ((hash(x * 7 + y * 131) >>> 24) / 255f)) continue;
                    int i = y * cols + x;
                    base[i] = pal[ey > 0.2f ? CLOUD2 : CLOUD];
                }
            }
        }
        // Sun or moon (shared with the other scenes; hidden behind storm clouds).
        if (storm < 0.5f) {
            sunMoonCells();
            for (int k = 0; k < nSun; k++) {
                int i = sunCells[k], x = i % cols, y = i / cols;
                if (x < cx0 || y < cy0 || x >= cx1 || y >= Math.min(cy1, G)) continue;
                base[i] = sunColors[k];
            }
        }
        // Distant hills.
        for (int x = cx0; x < cx1; x++) {
            int far = G - s(24) - Math.round((float) ((Math.sin(x / (cols * 0.21) + hillPh1) * 0.5 + 0.5) * s(22)
                    + Math.sin(x / (cols * 0.07) + hillPh2) * s(4)));
            int near = G - s(10) - Math.round((float) ((Math.sin(x / (cols * 0.13) + hillPh2 * 1.7) * 0.5 + 0.5) * s(14)));
            for (int y = Math.max(cy0, far); y < Math.min(cy1, G); y++) {
                int i = y * cols + x;
                base[i] = pal[y >= near ? HILL1 : HILL0];
                mask[i] = 0;
            }
        }
        // Bukit Nanas: a forested hill under Menara KL.
        if (hillX0 >= 0 && inClip(hillX0 - s(10), G - hillH - s(12), hillX1 + s(10), G)) {
            int cx = (hillX0 + hillX1) / 2, hw = (hillX1 - hillX0) / 2 + s(8);
            for (int x = cx - hw; x <= cx + hw; x++) {
                float f = (x - cx) / (float) hw;
                int top = G - Math.round(hillH * (float) Math.sqrt(Math.max(0, 1 - f * f)) * (1 + 0.15f * (1 - Math.abs(f))));
                for (int y = top; y < G; y++) P(x, y, ((hash(x * 31 + y) >>> 28) & 3) == 0 ? LEAF2 : LEAF);
                // Tree crowns along the top.
                if (Math.abs(f) < 0.95f && ((x * 7 + day) % s(7)) == 0) {
                    int rr = s(3) + (hash(x) >>> 30);
                    for (int dy = -rr; dy <= rr; dy++) for (int dx = -rr; dx <= rr; dx++) {
                        if (dx * dx + dy * dy > rr * rr) continue;
                        P(x + dx, top - rr / 2 + dy, dy < 0 ? LEAF : LEAF2);
                    }
                }
            }
            curTag = 0;
        }
    }

    /** Verge, road, a second verge, the river, the bank: changing with the town, segment by segment. */
    private void paintGround() {
        if (cy1 <= G) return;
        int seg = s(16);
        int v1 = G + s(4), r1 = G + s(16), v2 = G + s(19), w1 = G + s(33);
        for (int x = cx0; x < cx1; x++) {
            int sgi = x / seg;
            boolean paved = segDone(450, 60, sgi, x);        // the road: 07:30-08:30
            boolean walk = segDone(600, 60, sgi, x);         // the pavement: 10:00-11:00
            boolean bank = segDone(720, 60, sgi, x);         // the river wall: 12:00-13:00
            for (int y = Math.max(cy0, G); y < cy1; y++) {
                int i = y * cols + x;
                int h = hash(i);
                int c;
                if (y < v1) {
                    c = walk ? (y == G ? KERB : ((x % s(6)) == 0 ? CONC2 : CONC)) : (y < G + s(1) ? GRASS : ((h >>> 29) == 0 ? DIRT : GRASS2));
                } else if (y < r1) {
                    if (paved) {
                        boolean dash = y >= (v1 + r1) / 2 - Math.max(1, s(1) / 2) && y < (v1 + r1) / 2 + Math.max(1, s(1) / 2) + (s(1) > 1 ? 0 : 1)
                                && (x / s(8)) % 2 == 0;
                        c = dash ? ROADLINE : ROAD;
                    } else {
                        c = ((h >>> 28) % 5 == 0) ? DIRT2 : DIRT;
                        if (y == v1 + s(3) || y == r1 - s(4)) c = DIRT2; // ruts
                    }
                } else if (y < v2) {
                    c = walk ? (y == r1 ? KERB : CONC) : GRASS;
                } else if (y < w1) {
                    if (bank && (y < v2 + s(2) || y >= w1 - s(1))) c = KERB;
                    else if (!bank && y < v2 + s(1) && (h >>> 29) == 0) c = GRASS2;
                    else c = ((h >>> 24) & 0x3F) == 0 || ((x + y * 3 + day) % 37 == 0) ? WATER2 : WATER;
                } else {
                    c = y < w1 + s(2) ? GRASS2 : SOIL;
                }
                base[i] = pal[c];
                mask[i] = 1;
                tag[i] = 0;
            }
        }
        // Reeds by the river in the kampung hours; a sampan drifting, morning to noon.
        if (pm < 720) {
            for (int x = cx0; x < cx1; x++) {
                if ((hash(x * 13 + day) & 15) != 0) continue;
                int hh = s(2) + ((hash(x) >>> 28) & 1);
                for (int y = v2 - hh; y < v2 + s(1); y++) P(x, y, (y & 1) == 0 ? GRASS : GRASS2);
            }
        }
        if (pm >= 300 && pm < 720) {
            int bx = Math.round(-s(24) + (pm - 300) / 420f * (cols + s(30)));
            int by = v2 + s(6);
            R(bx, by, s(18), s(2), WOOD2);
            R(bx + s(2), by + s(2), s(14), s(1), WOOD2);
            P(bx - 1, by - 1, WOOD2);
            P(bx + s(18), by - 1, WOOD2);
            // the boatman, with a conical hat and a pole
            R(bx + s(8), by - s(5), s(2), s(5), BLUE);
            R(bx + s(8), by - s(7), s(2), s(2), SKIN);
            R(bx + s(7), by - s(8), s(4), s(1), CONE);
            L(bx + s(12), by - s(9), bx + s(15), by + s(4), WOOD);
        }
        // Street lamps, once the pavement is in: lit at night.
        int lampEvery = s(60);
        for (int x = lampEvery / 2; x < cols; x += lampEvery) {
            if (!segDone(600, 60, x / seg, x) || pm < 620) continue;
            if (!inClip(x - s(3), G - s(14), x + s(3), r1 + 1)) continue;
            R(x, G - s(12), Math.max(1, s(1)), r1 - (G - s(12)), POLE2);
            R(x - s(2), G - s(13), s(4), s(1), POLE2);
            P(x - s(2), G - s(12), lightMin >= set || lightMin < rise ? LAMP : POLE2);
            P(x + s(1), G - s(12), lightMin >= set || lightMin < rise ? LAMP : POLE2);
        }
    }

    /** Segment-by-segment work along the ground: is the segment at x done by now? */
    private boolean segDone(int from, int dur, int segIdx, int x) {
        if (pm >= from + dur) return true;
        if (pm < from) return false;
        int nseg = cols / s(16) + 1;
        boolean leftFirst = (day & 1) == 0;
        int order = leftFirst ? segIdx : nseg - 1 - segIdx;
        return order < (pm - from) / dur * nseg;
    }

    // ---- the billboard ----

    private int bbStage(int x) {
        float m = pm;
        float fw = fx1 - fx0;
        if (m < BB_PRINT_AT) return 0;
        if (m < BB_PRINT_AT + BB_PRINT_MIN) return x - fx0 < fw * (m - BB_PRINT_AT) / BB_PRINT_MIN ? 1 : 0;
        if (m < BB_LED_AT) return 1;
        if (m < BB_LED_AT + BB_LED_MIN) return x - fx0 < fw * (m - BB_LED_AT) / BB_LED_MIN ? 2 : 1;
        return 2;
    }

    private void paintBillboardLegs() {
        if (!inClip(fx0, fy1, fx1, G + s(4))) return;
        int fw = fx1 - fx0;
        for (int k = 0; k < 2; k++) {
            int x = fx0 + Math.round(fw * (k == 0 ? 0.22f : 0.78f));
            boolean steel = pm >= BB_PRINT_AT + k * 15;
            int w = s(5);
            if (steel) {
                for (int y = fy1; y < G + s(2); y++) {
                    P(x, y, POLE);
                    P(x + w - 1, y, POLE);
                    int ph = (y - fy1) % (2 * w);
                    P(x + (ph < w ? ph : 2 * w - 1 - ph), y, POLE2);
                }
            } else {
                R(x + 1, fy1, w - 2, G + s(2) - fy1, WOOD2);
                // brackets up to the board
                L(x + w / 2, fy1 + s(16), x + w / 2 + (k == 0 ? -s(14) : s(14)), fy1, WOOD2);
                L(x + w / 2, fy1 + s(16), x + w / 2 + (k == 0 ? s(14) : -s(14)), fy1, WOOD2);
            }
        }
        // A crossbeam between the legs.
        int lx0 = fx0 + Math.round(fw * 0.22f), lx1 = fx0 + Math.round(fw * 0.78f) + s(5);
        int by = fy1 + Math.round((G - fy1) * 0.42f);
        if (pm < BB_PRINT_AT + 15) {
            R(lx0, by, lx1 - lx0, s(2), WOOD2);
        } else {
            R(lx0, by, lx1 - lx0, Math.max(1, s(1)), POLE2);
        }
        // The catwalk under the printed board / screen.
        if (pm >= BB_PRINT_AT) {
            R(fx0 + s(4), fy1, fw - s(8), s(1), POLE2);
        }
    }

    private void paintBillboard() {
        if (!inClip(fx0 - s(2), fy0 - s(4), fx1 + s(2), fy1 + s(1))) return;
        int fb = s(2);
        int dc0 = BB_PAINT, dc1 = BB_PRINTD, dc2 = BB_LEDT;
        for (int y = Math.max(cy0, fy0); y < Math.min(cy1, fy1); y++) {
            for (int x = Math.max(cx0, fx0); x < Math.min(cx1, fx1); x++) {
                int i = y * cols + x;
                int stg = bbStage(x);
                boolean edge = x < fx0 + fb || x >= fx1 - fb || y < fy0 + fb || y >= fy1 - fb;
                boolean edge2 = x < fx0 + fb + s(1) || x >= fx1 - fb - s(1) || y < fy0 + fb + s(1) || y >= fy1 - fb - s(1);
                int c;
                if (text[i] != 0) {
                    c = stg == 0 ? dc0 : stg == 1 ? dc1 : dc2;
                } else if (stg == 0) {
                    c = edge ? BB_WOOD2 : (((y - fy0) % s(6)) == 0 ? BB_WOOD2 : BB_WOOD);
                } else if (stg == 1) {
                    c = edge ? BB_RED : edge2 ? BB_YEL : BB_PRINT;
                } else {
                    c = edge ? POLE2 : BB_LED;
                }
                base[i] = pal[c];
                mask[i] = 2;
                tag[i] = 0;
            }
        }
        // Lamps over the printed board at night.
        boolean dark = lightMin >= set - 10 || lightMin < rise;
        if (dark && pm >= BB_PRINT_AT + BB_PRINT_MIN && pm < BB_LED_AT) {
            int fw = fx1 - fx0;
            for (int k = 1; k <= 3; k++) {
                int x = fx0 + fw * k / 4;
                R(x - s(1), fy0 - s(3), s(3), s(1), POLE2);
                R(x, fy0 - s(2), s(1), s(2), POLE2);
                P(x, fy0 - s(3), LAMP);
            }
        }
    }

    private void layoutText(Calendar c) {
        slots = ClockText.layoutAt(prefs, c, cols, rows, mid, st, sd);
        // Clear the old text's box, then mark the new text (and grow the box to hold it).
        for (int y = tby0; y < tby1; y++) java.util.Arrays.fill(text, y * cols + tbx0, y * cols + tbx1, (byte) 0);
        int x0 = tbx0, y0 = tby0, x1 = tbx1, y1 = tby1;
        if (x0 >= x1) { x0 = cols; y0 = rows; x1 = 0; y1 = 0; }
        for (ClockText.Slot sl : slots) {
            for (int k = 0; k < 3; k++) {
                int[] cells = k == 0 ? sl.cells : k == 1 ? sl.lids : sl.inner;
                for (int i : cells) {
                    text[i] = 1;
                    int x = i % cols, y = i / cols;
                    if (x < x0) x0 = x;
                    if (x >= x1) x1 = x + 1;
                    if (y < y0) y0 = y;
                    if (y >= y1) y1 = y + 1;
                }
            }
        }
        if (x0 < x1) { tbx0 = x0; tby0 = y0; tbx1 = x1; tby1 = y1; }
        textChanged = true;
    }

    /** The text's box (cells): textPx is only ever non-zero inside it. */
    private int tbx0, tby0, tbx1, tby1;

    int textX0() { return tbx0; }
    int textY0() { return tby0; }
    int textW() { return tbx1 - tbx0; }
    int textH() { return tby1 - tby0; }

    /** Brings textPx up to date (only needed when it is shown: the night clock). */
    void fillTextPx() {
        int tc = prefs.textColor(1f);
        for (int y = tby0; y < tby1; y++) {
            for (int i = y * cols + tbx0, e = y * cols + tbx1; i < e; i++) textPx[i] = text[i] != 0 ? tc : 0;
        }
    }

    // ---- windows ----

    private boolean lit(int id) {
        int pct = ((hash(id * 31 + day) >>> 8) & 0x7FFF) % 100;
        float frac;
        int m = lightMin;
        if (m >= set - 10) {
            frac = Math.min(64, (m - (set - 10)) * 0.8f);
            if (m > 1350) frac -= (m - 1350) * 0.4f;
        } else if (m < rise + 10) {
            frac = m < 300 ? 6 : Math.min(30, 6 + (m - 300) * 0.25f);
        } else {
            frac = storm > 0.5f ? 25 : 0;
        }
        return pct < frac;
    }

    private void win(int x, int y, int w, int h, int id) {
        boolean l = lit(id);
        R(x, y, w, h, l ? ((hash(id) & 1) == 0 ? WINLIT : WINLIT2) : WIN);
        if (l && nLit < litTmp.length) {
            int X = x + ox, Y = Math.max(y, lo) + oy;
            if (X >= 0 && X < cols && Y >= 0 && Y < rows && Y < hi) litTmp[nLit++] = Y * cols + X;
        }
    }

    // ---- buildings ----

    private void drawBuild(B b) {
        if (b.ph == 0 || b.ph == 4) return;
        if (!inClip(b.x0, b.y0, b.x1, b.y1)) return;
        curTag = (short) b.id;
        oy = b.sink;
        ox = b.sink > 0 && b.cp < 1 && ((b.sink / Math.max(1, s(3))) & 1) == 1 ? 1 : 0;
        hi = b.sink > 0 ? Math.max(b.by, G) : rows;
        if (b.cp < 1) {
            switch (b.kind) {
                case HOUSE: drawHouse(b); break;
                case PADDY: drawPaddy(b); break;
                case PALMS: drawPalms(b); break;
                case PARK: drawPark(b); break;
                case STALL: drawStall(b); break;
                case DURIAN: drawDurian(b); break;
                case SPAN: drawSpan(b); break;
                case MBEAM: drawMonoBeam(b); break;
                default: drawRise(b); break;
            }
        }
        lo = Integer.MIN_VALUE; hi = rows; oy = 0; ox = 0;
        if (b.rubble > 0) drawRubble(b);
        curTag = 0;
    }

    private static boolean heavy(int kind) {
        return kind == CONDO || kind == OFFICE || kind == TWIN || kind == M118 || kind == KLT;
    }

    private boolean hoarded(int kind) {
        return kind != PILLAR && kind != MPILLAR && kind != SURAU;
    }

    /** Fraction of the structure standing (rise kinds): foundations first, topped out at TOPPED. */
    private float riseQ(B b) {
        if (b.ph == 1) return clamp01((b.p - FOUND) / (TOPPED - FOUND));
        if (b.ph == 2) return 1f;
        if (b.ph == 3) return b.p;
        return 0;
    }

    private void drawRise(B b) {
        boolean building = b.ph == 1;
        int vis = b.vis;
        if (vis > 0) {
            lo = b.by - vis;
            switch (b.kind) {
                case SURAU: shapeSurau(b); break;
                case SHOPS: shapeShops(b); break;
                case FLATS: shapeFlats(b); break;
                case CONDO: shapeCondo(b); break;
                case OFFICE: shapeOffice(b); break;
                case KLT: shapeKLT(b); break;
                case TWIN: shapeTwin(b); break;
                case M118: shapeM118(b); break;
                case STADIUM: shapeStadium(b); break;
                case PILLAR: case MPILLAR: shapePillar(b); break;
                default: break;
            }
            lo = Integer.MIN_VALUE;
            if (building && b.sink == 0) frameAndScaffold(b, vis);
        }
        if ((building || b.ph == 3) && hoarded(b.kind) && b.sink == 0) hoarding(b);
        if (building && b.sink == 0 && vis == 0 && heavy(b.kind)) {
            // Piling: a rig over the plot.
            int x = b.x + b.w / 2;
            R(x, b.by - s(34), s(2), s(34), CRANE2);
            R(x - s(1), b.by - s(34) + (int) ((b.p / FOUND) * s(20)), s(4), s(3), BLACK);
        }
        if (building && b.crane && b.sink == 0) mast(b);
    }

    private void hoarding(B b) {
        // Put up panel by panel at the start, taken down panel by panel at the end.
        float f = b.ph == 1 ? clamp01(b.p / (FOUND * 0.6f)) : clamp01(b.p / 0.15f);
        int x0 = b.x - s(2), x1 = b.x + b.w + s(2), y0 = b.by - s(6);
        int panel = s(8);
        int nPanels = (x1 - x0 + panel - 1) / panel;
        x1 = Math.min(x1, x0 + Math.round(nPanels * f) * panel);
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < b.by; y++) {
                int c = y == y0 ? HOARD2 : (((x - x0) % s(8)) == 0 ? HOARD2 : HOARD);
                P(x, y, c);
            }
        }
    }

    /** Bare concrete frame at the top of the work, and scaffolding around it. */
    private void frameAndScaffold(B b, int vis) {
        int top = b.by - vis;
        boolean hv = heavy(b.kind);
        int lag = hv ? s(14) : s(5);
        int bodyTop = b.by - b.bodyH;
        int xa = b.x - s(3), xb = b.x + b.w + s(3);
        int fl = s(4), colEvery = s(6);
        short id = (short) b.id;
        // The frame.
        for (int y = Math.max(top, bodyTop); y < Math.min(b.by, top + lag); y++) {
            if (y < cy0 || y >= cy1) continue;
            for (int x = Math.max(xa, cx0); x < Math.min(xb, cx1); x++) {
                int i = y * cols + x;
                if (tag[i] != id) continue;
                boolean slab = ((b.by - y) % fl) == 0, column = ((x - b.x) % colEvery) == 0;
                base[i] = pal[slab || column ? CONC : CONC3];
            }
        }
        if (b.kind == PILLAR || b.kind == MPILLAR) return;
        // Scaffolding: poles and ledgers over the working floors (the whole front on low buildings).
        int sy0 = top - s(3), sy1 = hv ? Math.min(b.by - s(6), top + lag + s(10)) : b.by - s(6);
        int step = s(5);
        for (int y = sy0; y < sy1; y++) {
            int ry = Math.max(y, top);
            if (ry < 0 || ry >= rows) continue;
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
            for (int x = Math.max(0, b.x - s(1)); x < Math.min(cols, b.x + b.w + s(1)); x++) {
                if (tag[ry * cols + x] == id) { mn = Math.min(mn, x); mx = Math.max(mx, x); }
            }
            if (mn > mx) continue;
            mn -= s(2);
            mx += s(2);
            boolean ledger = ((y - sy0) % step) == 0 && y >= top;
            for (int x = mn; x <= mx; x++) {
                boolean pole = ((x - mn) % step) == 0 || x == mx;
                if (y < top && !pole) continue;
                if (pole || ledger) P(x, y, SCAF);
            }
        }
        // A ladder on one side.
        int lx = ladderX(b);
        for (int y = top; y < b.by; y++) {
            P(lx, y, SCAF);
            P(lx + s(2), y, SCAF);
            if (((b.by - y) % s(2)) == 0) P(lx + 1, y, SCAF);
        }
    }

    private int ladderX(B b) {
        return b.crane && b.craneDir < 0 ? b.x - s(4) : b.x + b.w + s(2);
    }

    private int mastTop(B b) {
        int top = b.by - b.vis - s(16);
        boolean underBoard = b.craneX + s(4) > fx0 - s(2) && b.craneX < fx1 + s(2);
        if (underBoard) top = Math.max(top, fy1 + s(6));
        return Math.max(s(10), Math.min(top, b.by - s(20)));
    }

    private void mast(B b) {
        int top = mastTop(b), x = b.craneX, w = s(3);
        for (int y = top; y < b.by; y++) {
            P(x, y, CRANE);
            P(x + w, y, CRANE);
            int ph = (y - top) % (2 * w);
            P(x + (ph < w ? ph : 2 * w - ph), y, CRANE2);
        }
        R(x - s(1), b.by - s(2), w + s(3), s(2), CONC2);
    }

    // ---- the kinds ----

    private void drawHouse(B b) {
        float p = b.p;
        int x0 = b.x, w = b.w, by = b.by;
        int stilt = s(9), bodyH = s(13), roofH = s(13), ov = s(3);
        int floorY = by - stilt, wallTop = floorY - bodyH, peakY = wallTop - roofH;
        int pw = Math.max(1, s(2));
        int postW = Math.max(1, s(1.5f));
        // Posts (stilts and corner posts), rising.
        float a = clamp01(p / 0.15f);
        int postTop = by - Math.round(a * (stilt + bodyH));
        int[] posts = {x0 + s(1), x0 + w / 2, x0 + w - s(1) - postW};
        for (int px0 : posts) R(px0, postTop, postW, by - postTop, WOOD2);
        // Floor beam, left to right.
        float f = clamp01((p - 0.15f) / 0.1f);
        if (f > 0) R(x0, floorY, Math.round(w * f), s(2), WOOD2);
        // Walls plank by plank.
        float wv = clamp01((p - 0.25f) / 0.4f);
        int planks = (w + pw - 1) / pw;
        int nPl = Math.round(planks * wv);
        int doorX = x0 + Math.round(w * 0.6f), doorW = s(5);
        for (int k = 0; k < nPl; k++) {
            int x = x0 + k * pw;
            R(x, wallTop, Math.min(pw, x0 + w - x), bodyH, (k & 1) == 0 ? WOOD : WOOD3);
        }
        int planked = x0 + nPl * pw;
        int[] wins = {x0 + Math.round(w * 0.12f), x0 + Math.round(w * 0.36f)};
        for (int k = 0; k < wins.length; k++) {
            int wx = wins[k];
            if (wx + s(4) > planked) continue;
            win(wx, wallTop + s(3), s(5), s(6), b.id * 7 + k);
            R(wx - s(1), wallTop + s(3), s(1), s(6), WOOD2);
            R(wx + s(5), wallTop + s(3), s(1), s(6), WOOD2);
        }
        if (doorX + doorW <= planked) R(doorX, wallTop + s(2), doorW, bodyH - s(2), WOOD2);
        // Roof: the gable frame, then the covering from the eaves up.
        float rf = clamp01((p - 0.65f) / 0.25f);
        int cx = x0 + w / 2, hw = w / 2 + ov;
        boolean zinc = b.variant == 0;
        if (rf > 0) {
            L(cx - hw, wallTop, cx, peakY, WOOD2);
            L(cx + hw, wallTop, cx, peakY, WOOD2);
            int filled = Math.round(roofH * Math.min(1f, rf * 1.15f));
            for (int y = wallTop; y > wallTop - filled; y--) {
                float t = (y - peakY) / (float) roofH;
                int half = Math.round(hw * t);
                for (int x = cx - half; x <= cx + half; x++) {
                    int inEdge = Math.min(x - (cx - half), (cx + half) - x);
                    int thick = s(3);
                    int c;
                    if (inEdge < thick || y > wallTop - s(1)) {
                        c = zinc ? (((x + y) % 3) == 0 ? ZINC2 : ZINC) : (((x * 2 + y) % 3) == 0 ? ATTAP2 : ATTAP);
                    } else {
                        c = ((x - cx) % s(2)) == 0 ? WOOD2 : WOOD3; // the gable's decorated panel
                        if (Math.abs(x - cx) <= s(1) && y > peakY + roofH / 2 && y < wallTop - s(2)) c = WOOD2;
                    }
                    P(x, y, c);
                }
            }
        }
        // Finishing: the crossed gable tips and the stairs.
        if (p >= 0.92f) {
            L(cx, peakY, cx - s(3), peakY - s(3), WOOD2);
            L(cx, peakY, cx + s(3), peakY - s(3), WOOD2);
            int steps = Math.max(2, stilt / s(2));
            for (int k = 0; k < steps; k++) {
                R(doorX + k * s(2) - s(1), floorY + k * s(2), s(4), Math.max(1, s(0.6f)), WOOD3);
            }
            L(doorX + s(2), floorY, doorX + s(2) + steps * s(2), by, WOOD2);
        }
    }

    /** Paddy: planted column by column, green shoots growing taller, golden by late morning. */
    private void drawPaddy(B b) {
        int by = b.by;
        boolean harvest = b.ph == 3;
        // Bunds at both ends.
        R(b.x, by - s(2), s(3), s(2), BUND);
        R(b.x + b.w - s(3), by - s(2), s(3), s(2), BUND);
        int x0 = b.x + s(3), x1 = b.x + b.w - s(3);
        int span = Math.max(1, x1 - x0);
        boolean water = !(harvest && b.p < 0.3f);
        for (int x = x0; x < x1; x++) {
            float fx = (x - x0) / (float) span;
            P(x, by - 1, water ? PWATER : DIRT);
            if (s(1) > 1) P(x, by - 2, water ? PWATER : DIRT2);
            boolean planted = b.ph == 1 ? fx < b.p : !harvest || fx < b.p;
            if (!planted) {
                if (harvest && ((x - x0) % 3) == 0) P(x, by - 2 - (s(1) > 1 ? 1 : 0), PADDYG2); // stubble
                continue;
            }
            if (((x - x0) % 3) == 2) continue;
            float plantedAt = b.s + fx * (b.e - b.s);
            float age = pm - plantedAt;
            int hgt = s(2) + Math.round(s(8) * clamp01(age / 300f));
            int hh = hash(x * 17 + b.seed);
            hgt += (hh >>> 30) - 1;
            float gold = clamp01((age - 360) / 180f);
            boolean golden = ((hh >>> 8) & 0xFF) < gold * 255;
            for (int k = 0; k < hgt; k++) {
                int y = by - 2 - (s(1) > 1 ? 1 : 0) - k;
                int c = golden ? (k >= hgt - s(2) ? PADDYG : PADDYG2) : (k >= hgt - s(2) ? PADDY0 : PADDY1);
                P(x, y, c);
            }
        }
        // A scarecrow, while the field is green.
        if (b.variant == 1 && b.ph >= 1 && (b.ph != 1 || b.p > 0.5f) && !(harvest && b.p < 0.6f)) {
            int x = b.x + b.w / 2;
            R(x, by - s(16), Math.max(1, s(1)), s(15), WOOD2);
            R(x - s(4), by - s(13), s(9), Math.max(1, s(1)), WOOD2);
            R(x - s(2), by - s(13), s(5), s(5), RED);
            R(x - s(1), by - s(16), s(3), s(3), CONE);
            R(x - s(2), by - s(17), s(5), s(1), CONE);
        }
    }

    /** Coconut palms and a banana tree: planted one at a time, grown; felled one at a time. */
    private void drawPalms(B b) {
        Random r = seeded;
        r.setSeed(b.seed);
        int n = Math.max(1, Math.min(4, b.w / s(10)));
        for (int k = 0; k < n; k++) {
            int tx = b.x + s(4) + Math.round((b.w - s(8)) * (k + 0.5f) / n) + r.nextInt(s(4)) - s(2);
            boolean banana = k == n - 1 && n > 1 && r.nextBoolean();
            float lean = (r.nextFloat() - 0.5f) * s(10);
            float full = banana ? s(14) : s(28) + r.nextInt(s(12));
            float g;
            if (b.ph == 1) g = clamp01((b.p - k / (float) n) * n * 1.4f);
            else if (b.ph == 3) g = b.p > k / (float) n ? 1f : 0f;
            else g = 1f;
            if (g <= 0) continue;
            float hgt = Math.max(s(4), full * g);
            if (banana) drawBanana(tx, b.by, Math.round(hgt), g);
            else drawPalm(tx, b.by, Math.round(hgt), lean * g, g, r.nextInt());
        }
    }

    private void drawPalm(int x, int by, int h, float lean, float g, int seed) {
        int tw = Math.max(1, s(1.5f));
        int cxp = x, cyp = by;
        for (int k = 0; k <= h; k++) {
            float t = k / (float) h;
            int xx = x + Math.round(lean * t * t);
            int y = by - k;
            for (int j = 0; j < tw; j++) P(xx + j, y, (k % s(3)) == 0 ? WOOD2 : TRUNK);
            cxp = xx;
            cyp = y;
        }
        int fl = Math.round(s(14) * Math.max(0.35f, g));
        double[] ang = {-2.9, -2.4, -1.9, -1.25, -0.75, -0.25};
        for (int f = 0; f < ang.length; f++) {
            double a = ang[f] + ((seed >>> (f * 3)) & 3) * 0.06;
            float droop = 0.55f + 0.1f * (f % 2);
            for (int k = 0; k <= fl; k++) {
                float tt = k / (float) fl;
                int xx = cxp + (int) Math.round(Math.cos(a) * k);
                int yy = cyp + (int) Math.round(Math.sin(a) * k * (1 - tt) + droop * k * tt);
                P(xx, yy, FROND);
                if (k > 2 && (k & 1) == 0) P(xx, yy + 1, FROND2);
            }
        }
        if (g >= 0.8f) {
            P(cxp - 1, cyp + 1, WOOD2);
            P(cxp + 1, cyp + 2, WOOD2);
            P(cxp, cyp + 2, WOOD2);
        }
    }

    private void drawBanana(int x, int by, int h, float g) {
        int tw = s(2);
        R(x, by - h, tw, h, BANANA2);
        int cx = x + tw / 2, cy = by - h;
        int ll = Math.round(s(10) * Math.max(0.4f, g));
        double[] ang = {-2.6, -1.9, -1.3, -0.5};
        for (int f = 0; f < ang.length; f++) {
            double a = ang[f];
            for (int k = 0; k <= ll; k++) {
                float tt = k / (float) ll;
                int xx = cx + (int) Math.round(Math.cos(a) * k);
                int yy = cy + (int) Math.round(Math.sin(a) * k * (1 - tt * 0.8f) + tt * tt * s(3));
                for (int j = -s(1); j <= s(1); j++) P(xx, yy + j, j == 0 ? BANANA2 : BANANA);
            }
        }
    }

    private void shapeSurau(B b) {
        int by = b.by, x = b.x, w = b.w;
        int bodyW = w - s(6), bx = x;
        int plinth = s(2), wallH = s(13);
        R(bx, by - plinth, bodyW, plinth, CONC);
        int wt = by - plinth - wallH;
        R(bx, wt, bodyW, wallH, WHITEW);
        for (int k = 0; k < 3; k++) {
            int wx = bx + s(3) + k * (bodyW - s(6)) / 3 + s(1);
            win(wx, wt + s(4), s(3), s(6), b.id * 5 + k);
            P(wx + s(1), wt + s(3), WIN);
        }
        R(bx - s(1), wt - s(2), bodyW + s(2), s(2), DOME_G);
        // Dome and crescent.
        int r = s(7), dcx = bx + bodyW / 2, dcy = wt - s(2);
        int dome = (b.seed & 1) == 0 ? DOME_G : DOME_Y;
        for (int dy = -r - s(2); dy <= 0; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float ex = dx / (float) r, ey = dy / (float) (r + s(2));
                if (ex * ex + ey * ey > 1) continue;
                P(dcx + dx, dcy + dy, dome);
            }
        }
        R(dcx, dcy - r - s(5), Math.max(1, s(1)), s(3), GOLD);
        P(dcx + 1, dcy - r - s(6), GOLD);
        // A slim minaret beside it.
        int mx = bx + bodyW + s(2), mw = s(3), mt = by - s(30);
        R(mx, mt, mw, by - mt, WHITEW);
        R(mx - s(1), mt + s(3), mw + s(2), s(1), dome);
        R(mx, mt - s(2), mw, s(2), dome);
        P(mx + mw / 2, mt - s(3), GOLD);
    }

    private void shapeShops(B b) {
        int by = b.by;
        int uw = s(12);
        int n = Math.max(1, b.w / uw);
        int ground = s(10), upper = s(10), parapet = s(4);
        int[] signs = {RED, BB_YEL, GREEN, BLUE};
        for (int k = 0; k < n; k++) {
            int x = b.x + k * uw;
            int c = SHOP0 + Math.abs(b.seed + k * 3) % 5;
            // ground floor: the five-foot way
            R(x, by - ground, uw, ground, c);
            R(x + s(2), by - ground + s(3), uw - s(4), ground - s(3), CONC3);
            win(x + s(3), by - ground + s(4), uw - s(6), s(2), b.id * 11 + k * 2);
            R(x + s(1), by - ground, uw - s(2), s(2), signs[Math.abs(b.seed + k) % 4]);
            // upper floor
            int uy = by - ground - upper;
            R(x, uy, uw, upper, c);
            win(x + s(2), uy + s(3), s(3), s(5), b.id * 11 + k * 2 + 1);
            win(x + uw - s(5), uy + s(3), s(3), s(5), b.id * 13 + k * 2 + 1);
            R(x, uy + upper - s(1), uw, s(1), WHITEW);
            // parapet, stepped in the middle
            int py = uy - parapet;
            R(x, py + s(2), uw, parapet - s(2), c);
            R(x + s(3), py, uw - s(6), s(2), c);
            R(x, py + s(2), uw, s(1), WHITEW);
            R(x + uw - 1, py + s(2), 1, ground + upper + parapet - s(2), WHITEW);
        }
        // Tiled roof behind the parapet.
        R(b.x + s(1), by - ground - upper - parapet + s(1), b.w - s(2), s(1), TILE);
    }

    private void shapeFlats(B b) {
        int by = b.by, fl = s(7);
        int nf = Math.max(2, (b.h - s(4)) / fl);
        int roof = by - nf * fl;
        R(b.x, roof, b.w, nf * fl, CREAM);
        for (int f = 0; f < nf; f++) {
            int fy = by - (f + 1) * fl;
            for (int wx = b.x + s(2), k = 0; wx + s(4) <= b.x + b.w - s(2); wx += s(7), k++) {
                win(wx, fy + s(1), s(4), s(3), b.id * 97 + f * 13 + k);
            }
            R(b.x, fy + fl - s(2), b.w, s(1), CREAM2);
            for (int x = b.x; x < b.x + b.w; x += s(2)) P(x, fy + fl - s(3), CREAM2);
        }
        // stairwell
        int sx = b.x + b.w / 2 - s(2);
        for (int y = roof; y < by; y++) if (((y - roof) % s(3)) != 0) R(sx, y, s(4), 1, CREAM2);
        // water tank on the roof
        R(b.x + s(3), roof - s(4), s(8), s(4), BLUE);
        R(b.x, roof - s(1), b.w, s(1), CREAM2);
    }

    private void shapeCondo(B b) {
        int by = b.by, w = b.w, x = b.x;
        int body = by - b.bodyH;
        int c = CONDO0 + b.variant;
        int fl = s(4);
        R(x, body, w, b.bodyH, c);
        int core = x + w / 2 - s(1);
        for (int y = body + s(1); y < by - s(3); y += fl) {
            int f = (by - y) / fl;
            for (int wx = x + s(1); wx < x + w - s(1); wx += s(3)) {
                if (wx >= core - s(1) && wx <= core + s(2)) continue;
                win(wx, y, s(2), s(2), b.id * 1009 + f * 37 + wx);
            }
            if ((f & 1) == 0) R(x, y + s(2), w, Math.max(1, s(0.6f)), CONC2);
        }
        // lobby
        R(x + s(2), by - s(4), w - s(4), s(4), CONC3);
        // crown
        int cw = w - s(4);
        int crownH = b.h - b.bodyH;
        for (int k = 0; k < crownH; k++) {
            int y = body - 1 - k;
            int inset = b.variant == 2 ? k * w / (2 * crownH + 1) : s(2);
            int x0 = x + inset, ww = w - 2 * inset;
            if (b.variant == 1 && k > crownH / 2) { x0 = x + s(2); ww = cw / 2; }
            if (ww > 0) R(x0, y, ww, 1, (k % s(3)) == 0 ? CONC2 : c);
        }
        // Rooftop lights (extra), at night.
        if (extras >= 4 && dusk > 0.5f) R(x + s(2), body - s(1), w - s(4), s(1), ROOFL0 + Math.abs(b.seed) % 3);
    }

    private void shapeOffice(B b) {
        int by = b.by, w = b.w, x = b.x;
        int body = by - b.bodyH;
        int g = b.variant == 1 ? GLASS1 : GLASS0;
        for (int y = body; y < by; y++) {
            int f = (by - y) / s(4);
            boolean spandrel = ((by - y) % s(4)) == 0;
            for (int xx = x; xx < x + w; xx++) {
                boolean mull = ((xx - x) % s(3)) == 0;
                P(xx, y, spandrel ? STEEL2 : mull ? MULLION : g);
            }
            if (!spandrel && ((by - y) % s(4)) == 2) {
                for (int xx = x + 1; xx < x + w - 1; xx += s(3)) {
                    if (lit(b.id * 1013 + f * 41 + xx)) win(xx, y - 1, Math.max(1, s(2) - 1), s(2), b.id * 1013 + f * 41 + xx);
                }
            }
        }
        // stepped crown and a mast
        int crown = b.h - b.bodyH;
        R(x + s(2), body - s(3), w - s(4), s(3), STEEL2);
        R(x + s(4), body - s(5), w - s(8), s(2), STEEL2);
        R(x + w / 2, body - crown, Math.max(1, s(1)), crown - s(5), STEEL);
        if (extras >= 4 && dusk > 0.5f) R(x + s(2), body - s(3), w - s(4), s(1), ROOFL0 + 1);
    }

    private void shapeKLT(B b) {
        int by = b.by, cx = b.x + b.w / 2;
        int shaft = s(98), bulbH = s(20);
        int bulbBot = by - shaft;
        // shaft, flared at the foot
        for (int y = bulbBot; y < by; y++) {
            int d = by - y;
            int hw = d < s(10) ? s(2) + (s(10) - d) * s(3) / s(10) : s(2);
            for (int x = cx - hw; x <= cx + hw; x++) P(x, y, x == cx - hw ? STEEL : STEEL2);
        }
        R(cx - s(5), by - s(4), s(11), s(4), CONC);
        // the bulb: lower cone, glass band, open deck, cap
        int y = bulbBot;
        for (int k = 0; k < s(6); k++) {
            int hw = s(2) + k * s(8) / s(6);
            R(cx - hw, y - k, 2 * hw + 1, 1, STEEL2);
        }
        int band = y - s(6);
        for (int k = 0; k < s(8); k++) {
            int yy = band - k;
            for (int x = cx - s(10); x <= cx + s(10); x++) {
                boolean glass = k >= s(2) && k < s(6);
                P(x, yy, glass ? (lit(b.id * 3 + x) ? WINLIT : STEEL3) : STEEL);
            }
        }
        int deck = band - s(8);
        R(cx - s(9), deck - s(3), s(19), s(3), STEEL2);
        for (int x = cx - s(9); x <= cx + s(9); x += s(2)) P(x, deck - s(2), STEEL3);
        for (int k = 0; k < s(4); k++) {
            int hw = s(8) - k * s(5) / s(4);
            R(cx - hw, deck - s(3) - k - 1, 2 * hw + 1, 1, STEEL);
        }
        // antenna
        int at = by - b.h, ab = deck - s(7);
        R(cx, at, Math.max(1, s(1)), ab - at, STEEL);
        for (int yy = ab - s(6); yy > at + s(6); yy -= s(9)) R(cx - s(1), yy, s(3), Math.max(1, s(0.6f)), STEEL2);
        P(cx, at, (lightMin >= set || lightMin < rise) ? AVIATION : STEEL2);
    }

    private void shapeTwin(B b) {
        int by = b.by, tw = s(15), gap = s(13);
        int t1 = b.x + (b.w - (2 * tw + gap)) / 2, t2 = t1 + tw + gap;
        int pod = s(8);
        // podium (the mall at the foot)
        R(b.x, by - pod, b.w, pod, CONC);
        R(b.x + s(2), by - pod + s(2), b.w - s(4), s(2), GLASS0);
        int body = b.bodyH;
        int[] xs = {t1, t2};
        for (int k = 0; k < 2; k++) {
            int tx = xs[k];
            for (int y = by - body; y < by - pod; y++) {
                float rel = (by - y) / (float) body;
                int inset = rel < 0.6f ? 0 : rel < 0.72f ? s(1) : rel < 0.82f ? s(2) : rel < 0.9f ? s(3) : s(4);
                boolean band = ((by - y) % s(3)) == 0;
                for (int x = tx + inset; x < tx + tw - inset; x++) {
                    int c = band ? STEEL3 : ((x - tx) % 3) == 0 ? STEEL2 : STEEL;
                    if (band && lit(b.id * 7919 + k * 977 + (by - y) * 31 + x / 2)) c = WINLIT;
                    P(x, y, c);
                }
            }
            // the crown and the pinnacle
            int top = by - body;
            int cwid = tw - 2 * s(4);
            for (int k2 = 0; k2 < s(7); k2++) {
                int ww = Math.max(Math.max(1, s(2)), cwid - k2 * cwid / s(7));
                R(tx + (tw - ww) / 2, top - 1 - k2, ww, 1, k2 % 2 == 0 ? STEEL2 : STEEL);
            }
            int pin = by - b.h, mx = tx + tw / 2;
            R(mx, pin, Math.max(1, s(1)), top - s(7) - pin, STEEL);
            R(mx - s(1), top - s(10), s(3), s(2), STEEL);
            if (extras >= 4 || lightMin >= set) {
                if (lightMin >= set || lightMin < rise) P(mx, pin, AVIATION);
            }
        }
        // The skybridge goes in last: the deck, then its legs.
        boolean bridge = b.ph == 2 || (b.ph == 1 && b.p >= 0.975f) || b.ph == 3;
        if (bridge) {
            int yb = by - Math.round(body * 0.45f);
            R(t1 + tw, yb, gap, s(2), STEEL2);
            R(t1 + tw, yb, gap, 1, STEEL);
            boolean legs = b.ph != 1 || b.p >= 0.99f;
            if (legs) {
                int mxb = t1 + tw + gap / 2;
                L(mxb, yb + s(2), t1 + tw - 1, yb + s(12), STEEL2);
                L(mxb, yb + s(2), t2, yb + s(12), STEEL2);
            }
        }
    }

    private void shapeM118(B b) {
        int by = b.by, cx = b.x + b.w / 2, bw = b.w;
        int body = b.bodyH;
        int fac = s(7);
        for (int y = by - body; y < by; y++) {
            float rel = (by - y) / (float) body;
            int hw = Math.round(bw / 2f * (1 - 0.5f * (float) Math.pow(rel, 1.6)));
            int d = by - y;
            for (int x = cx - hw; x <= cx + hw; x++) {
                int u = (x - cx) + d / 2, v = (x - cx) - d / 2;
                int fu = fmod(u, fac), fv = fmod(v, fac);
                boolean line = fu == 0 || fv == 0;
                boolean shade = (fmod(u, 2 * fac) < fac) ^ (fmod(v, 2 * fac) < fac);
                int c = line ? STEEL : shade ? GLASS0 : STEEL3;
                if (!line && ((d % s(3)) == 0) && lit(b.id * 7 + d * 131 + x)) c = WINLIT2;
                P(x, y, c);
            }
        }
        // The crown narrows into the spire.
        int top = by - body;
        int hw0 = Math.round(bw / 2f * 0.5f);
        int crown = s(14);
        for (int k = 0; k < crown; k++) {
            int hw = Math.max(1, hw0 - k * hw0 / crown);
            R(cx - hw, top - 1 - k, 2 * hw + 1, 1, (k % 3) == 0 ? STEEL : STEEL2);
        }
        int spTop = by - b.h, spBot = top - crown;
        for (int y = spTop; y < spBot; y++) {
            int ww = 1 + ((spBot - y) > (spBot - spTop) * 2 / 3 ? 1 : 0) * Math.max(1, s(1));
            R(cx - ww / 2, y, ww, 1, STEEL);
        }
        if (lightMin >= set || lightMin < rise) P(cx, spTop, AVIATION);
    }

    private void shapeStadium(B b) {
        int by = b.by, h = b.h;
        for (int k = 0; k < h - s(6); k++) {
            int y = by - 1 - k;
            int inset = (h - s(6) - k) * s(4) / Math.max(1, h);
            for (int x = b.x + inset; x < b.x + b.w - inset; x++) {
                int c = ((k / s(3)) & 1) == 0 ? CONC : CONC2;
                if (k > h / 3 && k < h - s(9) && ((x - b.x) % s(4)) == 0) c = CONC3;
                P(x, y, c);
            }
        }
        R(b.x - s(1), by - h + s(4), b.w + s(2), s(2), WHITEW);
        for (int k = 0; k < 2; k++) {
            int mx = k == 0 ? b.x + s(2) : b.x + b.w - s(3);
            R(mx, by - h - s(8), Math.max(1, s(1)), s(10), POLE2);
            R(mx - s(1), by - h - s(9), s(3), s(2), lightMin >= set - 30 ? LAMP : POLE);
        }
    }

    private void shapePillar(B b) {
        int x = b.x, w = b.w, top = b.by - b.h;
        boolean mono = b.kind == MPILLAR;
        R(x, top + s(2), w, b.h - s(2), CONC);
        R(x + w - Math.max(1, s(1)), top + s(2), Math.max(1, s(1)), b.h - s(2), CONC2);
        int cap = mono ? s(6) : s(12);
        R(x + w / 2 - cap / 2, top, cap, s(2), CONC2);
    }

    private void drawSpan(B b) {
        // The girder is lifted in, then the deck and the parapet go on.
        float p = b.ph == 1 ? b.p : b.ph == 3 ? b.p : 1;
        if (p < 0.3f) return;
        int y = b.by - b.h;
        R(b.x, y + s(1), b.w, s(2), CONC2);
        if (p >= 0.65f) {
            R(b.x, y, b.w, s(1), CONC);
            for (int x = b.x; x < b.x + b.w; x += s(6)) P(x, y - 1, CONC2);
        }
    }

    private void drawMonoBeam(B b) {
        float p = b.ph == 1 ? b.p : b.ph == 3 ? b.p : 1;
        int y = b.by - b.h;
        R(b.x, y, Math.round(b.w * p), b.h, CONC2);
    }

    private void drawStall(B b) {
        float p = b.ph == 1 ? b.p : b.ph == 3 ? b.p : 1;
        int x = b.x, w = b.w, by = b.by;
        int postH = s(12);
        float a = clamp01(p / 0.3f);
        int top = by - Math.round(postH * a);
        R(x + s(1), top, Math.max(1, s(1)), by - top, POLE2);
        R(x + w - s(2), top, Math.max(1, s(1)), by - top, POLE2);
        if (p > 0.35f) R(x + s(2), by - s(5), w - s(4), s(1), WOOD3);
        if (p > 0.45f) R(x + s(3), by - s(4), w - s(6), s(4), WOOD);
        float cv = clamp01((p - 0.5f) / 0.4f);
        int cw = Math.round((w + s(2)) * cv);
        for (int xx = x - s(1); xx < x - s(1) + cw; xx++) {
            boolean red = ((xx - x) / s(3) & 1) == 0;
            R(xx, by - postH - s(2), 1, s(3), red ? RED : WHITE);
        }
        if (p > 0.92f) {
            R(x + s(4), by - s(7), s(4), s(2), POLE2); // the wok
            R(x + w - s(7), by - s(7), s(3), s(2), CONC);
        }
    }

    private void drawDurian(B b) {
        float p = b.ph == 1 ? b.p : b.ph == 3 ? b.p : 1;
        int x = b.x, w = b.w, by = b.by;
        if (p > 0) R(x + s(1), by - s(4), w - s(2), s(1), WOOD3);
        if (p > 0.15f) {
            R(x + s(2), by - s(3), Math.max(1, s(1)), s(3), WOOD2);
            R(x + w - s(3), by - s(3), Math.max(1, s(1)), s(3), WOOD2);
        }
        // umbrella
        if (p > 0.3f) {
            int ux = x + w / 2;
            R(ux, by - s(13), Math.max(1, s(1)), s(9), POLE2);
            for (int k = 0; k < s(3); k++) R(ux - s(6) + k * 2, by - s(14) + k, s(13) - k * 4, 1, (k & 1) == 0 ? GREEN : BB_YEL);
        }
        // durians, one at a time
        int n = Math.round(clamp01((p - 0.4f) / 0.55f) * 7);
        for (int k = 0; k < n; k++) {
            int dx = x + s(2) + (k % 4) * s(3), dy = by - s(6) - (k / 4) * s(2);
            R(dx, dy, s(2), s(2), (k & 1) == 0 ? PADDY1 : PADDYG2);
        }
    }

    private void drawPark(B b) {
        float p = b.ph == 1 ? b.p : b.ph == 3 ? b.p : 1;
        int by = b.by;
        // The lake: dug, then filled.
        int lx0 = b.x + s(4), lx1 = b.x + b.w - s(4);
        float dig = clamp01(p / 0.3f);
        int dw = Math.round((lx1 - lx0) * dig);
        R(lx0, by - s(2), dw, s(2), p < 0.4f ? DIRT2 : WATER);
        if (p >= 0.4f) R(lx0 + s(2), by - s(2), Math.max(0, dw - s(4)), 1, WATER2);
        // Trees, one at a time.
        int n = Math.max(2, b.w / s(12));
        int planted = Math.round(clamp01((p - 0.45f) / 0.5f) * n);
        for (int k = 0; k < planted; k++) {
            int tx = b.x + s(4) + k * (b.w - s(8)) / Math.max(1, n - 1);
            int th = s(8) + (k % 2) * s(3);
            R(tx, by - th, Math.max(1, s(1)), th, TRUNK);
            int rr = s(4);
            for (int dy = -rr; dy <= rr; dy++) for (int dx = -rr; dx <= rr; dx++) {
                if (dx * dx + dy * dy > rr * rr) continue;
                P(tx + dx, by - th - rr / 2 + dy, dy < 0 ? LEAF : LEAF2);
            }
        }
    }

    private void drawRubble(B b) {
        int x0 = b.x - s(3), x1 = b.x + b.w + s(3);
        int by = Math.max(b.by, G);
        if (b.kind == SPAN || b.kind == MBEAM) by = G;
        int hw = (x1 - x0) / 2, cx = (x0 + x1) / 2;
        int main = b.kind == HOUSE ? WOOD : b.kind == PADDY ? PADDY1 : b.kind == PALMS ? FROND2 : RUB2;
        for (int x = x0; x <= x1; x++) {
            float f = (x - cx) / (float) Math.max(1, hw);
            int hh = Math.round(b.rubble * (float) Math.sqrt(Math.max(0, 1 - f * f)));
            for (int y = by - hh; y < by; y++) {
                int h = hash(x * 131 + y * 7 + b.id);
                int c = ((h >>> 28) & 3) == 0 ? main : ((h >>> 24) & 3) == 0 ? RUB1 : ((h >>> 20) & 1) == 0 ? RUB0 : RUB2;
                P(x, y, c);
            }
        }
    }

    private int rubbleMax(B b) {
        switch (b.kind) {
            case PADDY: return 0;
            case SPAN: case MBEAM: return s(3);
            case PILLAR: case MPILLAR: return s(4);
            case STALL: case DURIAN: return s(3);
            case HOUSE: case PALMS: return s(6);
            default: return Math.min(s(16), s(5) + b.h / 6);
        }
    }

    // ---- festival decorations (few, small, put up one at a time) ----

    private void paintDecorations() {
        if (festival == Festivals.NONE || festival == Festivals.NEWYEAR) return;
        if (festival == Festivals.XMAS) {
            int x = fx0 - s(10);
            if (pm < 480 || !inClip(x - s(10), G - s(30), x + s(10), G + s(4))) return;
            float g = clamp01((pm - 480) / 30f);
            int th = Math.round(s(24) * g);
            for (int k = 0; k < th; k++) {
                int hw = (th - k) * s(9) / Math.max(1, s(24));
                R(x - hw, G - k - 1, 2 * hw + 1, 1, XTREE);
            }
            int lights = Math.round(clamp01((pm - 510) / 90f) * 14);
            Random r = seeded;
            r.setSeed(day);
            for (int k = 0; k < lights; k++) {
                int yy = G - s(3) - r.nextInt(Math.max(1, th - s(4)));
                int hw = (th - (G - yy)) * s(9) / Math.max(1, s(24));
                P(x - hw + r.nextInt(Math.max(1, 2 * hw + 1)), yy, FW0 + k % 5);
            }
            if (lights >= 14) R(x - s(1), G - th - s(2), s(3), s(2), GOLD);
            return;
        }
        int order = 0;
        for (B b : builds) {
            if (b.ph != 2 || b.sink > 0) continue;
            int k = b.kind;
            if (k != HOUSE && k != SHOPS && k != FLATS && k != CONDO && k != OFFICE && k != SURAU) continue;
            if (!inClip(b.x0, b.y0, b.x1, b.y1)) { order++; continue; }
            float up = Math.max(b.e, festival == Festivals.RAYA || festival == Festivals.DEEPAVALI ? 1020 : 420) + 8 + (order * 11) % 90;
            order++;
            if (pm < up) continue;
            int roof = k == HOUSE ? b.by - s(22) : b.by - b.h;
            if (festival == Festivals.MERDEKA || festival == Festivals.MSIA) {
                int fx = b.x + b.w / 2 + (k == HOUSE ? s(6) : 0), ft = (k == HOUSE ? b.by - s(40) : roof) - s(8);
                R(fx, ft, Math.max(1, s(1)), s(8), POLE2);
                for (int yy = 0; yy < s(5); yy++) {
                    for (int xx = 1; xx <= s(8); xx++) {
                        // Jalur Gemilang: red and white stripes, a blue canton with the yellow crescent and star
                        boolean canton = xx <= s(4) && yy < s(3);
                        boolean moon = canton && yy == s(1) && xx >= s(2) && xx <= s(3) || canton && yy == s(1) - 1 && xx == s(3);
                        int c = canton ? (moon ? GOLD : FLAG_B) : ((yy & 1) == 0 ? FLAG_R : WHITE);
                        P(fx + xx, ft + yy, c);
                    }
                }
            } else if (festival == Festivals.CNY) {
                int ly = k == HOUSE ? b.by - s(22) : k == SHOPS ? b.by - s(10) : b.by - s(6);
                for (int side = 0; side < 2; side++) {
                    int lx = side == 0 ? b.x + s(2) : b.x + b.w - s(5);
                    P(lx + s(1), ly, GOLD);
                    R(lx, ly + 1, s(3), s(3), LANTERN);
                    P(lx + s(1), ly + 1 + s(3), GOLD);
                }
            } else if (festival == Festivals.RAYA || festival == Festivals.DEEPAVALI) {
                // Oil lamps along the front (pelita on poles for Raya, little clay lamps for Deepavali).
                for (int lx = b.x + s(2); lx < b.x + b.w - s(1); lx += s(6)) {
                    if (festival == Festivals.RAYA) {
                        R(lx, G - s(6), Math.max(1, s(1)), s(6), WOOD2);
                        P(lx, G - s(7), FLAME);
                    } else {
                        R(lx, G + s(1), s(2), s(1), DIRT2);
                        P(lx, G, FLAME);
                    }
                }
                if (festival == Festivals.RAYA && k == HOUSE) {
                    int kx = b.x + b.w / 2;
                    int ky = b.by - s(22) + s(1);
                    for (int dy = 0; dy < s(4); dy++) {
                        int hw = dy < s(2) ? dy : s(4) - 1 - dy;
                        R(kx - hw, ky + dy, 2 * hw + 1, 1, (dy & 1) == 0 ? PADDY1 : BB_YEL);
                    }
                }
                if (festival == Festivals.DEEPAVALI && k == HOUSE) {
                    // A kolam on the pavement, drawn ring by ring through the morning.
                    int kx = b.x + b.w / 2, ky = G + s(2);
                    int rings = Math.round(clamp01((pm - 420) / 60f) * 3);
                    for (int rr = 1; rr <= rings; rr++) {
                        int c = FW0 + rr % 5;
                        for (int dx = -rr * s(3); dx <= rr * s(3); dx += s(1)) {
                            if (((dx / Math.max(1, s(1))) & 1) == 0) P(kx + dx, ky + ((dx & 2) == 0 ? -s(1) : s(1)) * (rr & 1), c);
                        }
                    }
                }
            }
        }
    }

    private void paintMist() {
        float m;
        int t = lightMin - rise;
        if (t < -40 || t > 60) return;
        m = t < 0 ? (t + 40) / 40f : 1f - t / 60f;
        int y0 = G - s(14), y1 = G + s(2);
        if (!inClip(0, y0, cols, y1)) return;
        int thr = Math.round(m * 70);
        for (int y = Math.max(cy0, y0); y < Math.min(cy1, y1); y++) {
            float band = 1f - Math.abs((y - (G - s(5))) / (float) s(10));
            for (int x = cx0; x < cx1; x++) {
                int i = y * cols + x;
                if (mask[i] == 2) continue;
                if (((hash(i) >>> 8) & 0xFF) < thr * band * 2.2f && ((x + y) & 1) == 0) base[i] = pal[MIST];
            }
        }
    }

    // =====================================================================================
    // Time
    // =====================================================================================

    void startGrowIn(long t) { growStart = t; }

    /** Midnight with the rampage set to 0: the biawak runs through now. */
    void startQuick(long t) { if (quickStart < 0) quickStart = t; }

    boolean quickDone(long t) { return quickStart >= 0 && t - quickStart >= QUICK_MS + 1500; }

    /** True when the biawak has been through and everything is rubble. */
    boolean flattened() { return flat; }

    /** True while dust is still in the air. */
    boolean airborne() { return nP > 0; }

    void startDemo(long t, Calendar c) { demoStart = t; }

    boolean demoOver(long t) { return demoStart >= 0 && t - demoStart > DEMO_MS + 4000; }

    private static int minuteOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static long msOfDay(Calendar c) {
        return (minuteOfDay(c) * 60L + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
    }

    private long nowMs;

    private void timeState(Calendar c, long t, boolean vacuumAllowed) {
        nowMs = c.getTimeInMillis();
        float real = msOfDay(c) / 60_000f;
        // Past midnight but this is still yesterday's town (until the view builds the new one):
        // it stays at the end of its day, not back at its start.
        if (c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) != day) real = DAY_MIN - 0.001f;
        ramp = -1;
        lightMin = minuteOfDay(c);
        if (demoStart >= 0) {
            pm = 1380;
            lightMin = 1350;
            ramp = Math.min(1f, (t - demoStart) / (float) DEMO_MS);
        } else if (quickStart >= 0) {
            pm = DAY_MIN - 0.01f;
            lightMin = 1439;
            ramp = Math.min(1f, (t - quickStart) / (float) QUICK_MS);
        } else {
            int vm = Prefs.VACUUM_MIN[prefs.kampungRampage];
            float from = DAY_MIN - vm;
            if (vm > 0 && vacuumAllowed && real >= from) {
                pm = from;
                ramp = Math.min(1f, (real - from) / vm);
            } else {
                pm = real;
            }
        }
        if (growStart >= 0 && ramp < 0) {
            float p = Math.min(1f, (t - growStart) / (float) GROW_MS);
            if (p >= 1f) growStart = -1;
            pm = pm * (1 - (1 - p) * (1 - p) * (1 - p));
        }
        if (ramp >= 0) growStart = -1; // no grow-in while the biawak is out
        // Sky
        int[] tt = prefs.city > 0 ? Sun.times(prefs.city, c) : SUN_DEFAULT;
        rise = tt[0];
        set = tt[1];
        int m = lightMin;
        float dk;
        if (m >= set + 45 || m < rise - 45) dk = 1f;
        else if (m >= set - 15) dk = (m - (set - 15)) / 60f;
        else if (m < rise + 15) dk = 1f - (m - (rise - 45)) / 60f;
        else dk = 0f;
        dusk = Math.round(dk * 10) / 10f;
        // Storm
        storm = 0;
        if (prefs.kampungWeather && stormFrom >= 0 && demoStart < 0 && quickStart < 0) {
            float into = real - stormFrom;
            if (into >= -3 && into < stormLen + 3) {
                storm = into < 0 ? (into + 3) / 3f : into > stormLen ? (stormLen + 3 - into) / 3f : 1f;
                storm = Math.round(clamp01(storm) * 4) / 4f;
            }
        }
    }

    /** The shared sun or moon for the sky's minute (once a minute, or when colours change). */
    private void sunMoonCells() {
        int key = lightMin * 4 + (prefs.sunMoon ? 1 : 0);
        if (key == sunKey && colorKey == sunColorKey) return;
        sunKey = key;
        sunColorKey = colorKey;
        if (sunCal == null) sunCal = Calendar.getInstance();
        sunCal.setTimeInMillis(nowMs);
        sunCal.set(Calendar.HOUR_OF_DAY, lightMin / 60);
        sunCal.set(Calendar.MINUTE, lightMin % 60);
        nSun = SunMoon.draw(prefs, sunCal, cols, rows, G - s(20), Math.round(rows * 0.06f), sunCells, sunColors);
    }

    /** Where each build is now; marks boxes to recompose when one gains or loses a step. */
    private boolean phases(boolean all) {
        // The biawak: its snout's position across the screen.
        float front = biawakFront();
        int dir = biawakDir();
        boolean any = false;
        for (B b : builds) {
            float p0 = pm;
            if (p0 < b.s) { b.ph = 0; b.p = 0; }
            else if (p0 < b.e) { b.ph = 1; b.p = (p0 - b.s) / (b.e - b.s); }
            else if (p0 < b.d) { b.ph = 2; b.p = 1; }
            else if (p0 < b.dEnd) { b.ph = 3; b.p = 1 - (p0 - b.d) / (b.dEnd - b.d); }
            else { b.ph = 4; b.p = 0; }
            b.vis = isRise(b.kind) ? Math.round(b.h * riseQ(b)) : 0;
            // The biawak flattens what it reaches.
            int oldSink = b.sink;
            if (ramp >= 0 && b.ph >= 1 && b.ph <= 3) {
                int ne = dir > 0 ? b.x : b.x + b.w;
                float dist = dir > 0 ? front - ne : ne - front;
                float reach = Math.max(b.kind == SPAN || b.kind == MBEAM ? s(30) : b.w * 0.6f + s(24), crumbleReach());
                b.cp = clamp01(dist / reach);
                int full = (b.kind == SPAN || b.kind == MBEAM ? G - deckY : Math.max(b.h, s(27))) + s(4);
                b.sink = Math.round(b.cp * full);
                b.rubble = Math.round(Math.min(1f, b.cp * 1.6f) * rubbleMax(b));
            } else {
                b.cp = 0;
                b.sink = 0;
                b.rubble = 0;
            }
            if (b.sink > oldSink && animateNow && b.kind != PADDY) dust(b, b.sink - oldSink);
            long key;
            if (isRise(b.kind)) key = b.ph * 100000L + b.vis * 4L + (b.ph == 1 && b.p >= 0.975f ? 1 : 0) + (b.ph == 1 && b.p >= 0.99f ? 2 : 0);
            else if (b.kind == PADDY) key = b.ph * 100000L + Math.round(b.p * b.w / Math.max(1, s(3)));
            else key = b.ph * 100000L + Math.round(b.p * 40);
            key = key * 4096 + b.sink * 64L + b.rubble;
            if (key != b.key) {
                b.key = key;
                if (!all) {
                    // The sinking building's box (the sink shows it lower, so include down to the ground).
                    compose(b.x0, b.y0, b.x1, b.y1);
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean isRise(int kind) {
        switch (kind) {
            case SURAU: case SHOPS: case FLATS: case CONDO: case OFFICE: case KLT: case TWIN: case M118:
            case STADIUM: case PILLAR: case MPILLAR:
                return true;
            default:
                return false;
        }
    }

    private static boolean countsAsBuilding(int kind) {
        return kind != PADDY && kind != PALMS && kind != PILLAR && kind != SPAN && kind != MPILLAR && kind != MBEAM;
    }

    // ---- the biawak ----

    private int bwLen() { return Math.round(rows * 0.85f); }

    private int bwH() { return Math.round(rows * 0.44f); }

    /** Cells the biawak walks in about two seconds: no building comes down faster than that. */
    private float crumbleReach() {
        float secs = demoStart >= 0 ? DEMO_MS / 1000f : quickStart >= 0 ? QUICK_MS / 1000f
                : Prefs.VACUUM_MIN[prefs.kampungRampage] * 60f;
        float travel = cols + bwLen() + s(30);
        return travel / (0.8f * secs) * 2f;
    }

    private int biawakDir() { return (day & 2) == 0 ? 1 : -1; }

    /** The snout's x (only meaningful while ramp >= 0). */
    private float biawakFront() {
        if (ramp < 0) return -1e6f;
        float rp = Math.min(1f, ramp / BW_GONE);
        float travel = cols + bwLen() + s(30);
        return biawakDir() > 0 ? -s(10) + rp * travel : cols + s(10) - rp * travel;
    }

    private void dust(B b, int amount) {
        int n = Math.min(40, Math.max(2, amount * b.w / Math.max(1, s(12))));
        for (int k = 0; k < n; k++) {
            float x = b.x + rnd.nextFloat() * b.w;
            float y = Math.max(b.by, G) - rnd.nextInt(s(8));
            addParticle(x, y, (rnd.nextFloat() - 0.5f) * 0.7f, -0.15f - rnd.nextFloat() * 0.45f,
                    rnd.nextInt(3) == 0 ? RUB0 : DUST, 25 + rnd.nextInt(30));
        }
    }

    private void addParticle(float x, float y, float vx, float vy, int col, int life) {
        if (nP >= MAX_P) return;
        pX[nP] = x; pY[nP] = y; pVx[nP] = vx; pVy[nP] = vy; pCol[nP] = col; pLife[nP] = life;
        nP++;
    }

    private void tickParticles(int ticks) {
        for (int n = 0; n < ticks; n++) {
            for (int k = 0; k < nP; k++) {
                pLife[k]--;
                boolean fw = pCol[k] >= FW0 && pCol[k] < FW0 + 5 || pCol[k] == GOLD;
                if (fw) pVy[k] += 0.015f; else { pVx[k] *= 0.97f; pVy[k] *= 0.97f; }
                pX[k] += pVx[k];
                pY[k] += pVy[k];
                if (pLife[k] <= 0 || pX[k] < -2 || pX[k] >= cols + 2 || pY[k] < 0 || pY[k] >= rows) {
                    if (pCol[k] == GOLD && pLife[k] <= 0) burst(pX[k], pY[k]); // a rocket bursts
                    nP--;
                    pX[k] = pX[nP]; pY[k] = pY[nP]; pVx[k] = pVx[nP]; pVy[k] = pVy[nP]; pCol[k] = pCol[nP]; pLife[k] = pLife[nP];
                    k--;
                }
            }
        }
    }

    private void burst(float x, float y) {
        int c = FW0 + rnd.nextInt(5);
        int n = 36;
        float sp = 1.1f * S + 0.3f;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * Math.PI / n;
            addParticle(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, c, 20 + rnd.nextInt(10));
        }
    }

    // =====================================================================================
    // Per frame
    // =====================================================================================

    void update(Calendar c, long t, boolean animate, boolean vacuumAllowed) {
        update(c, t, animate, vacuumAllowed, true);
    }

    /**
     * One step. With draw false (the night clock: only the text layer and the lit windows show)
     * the town is kept current but the picture isn't drawn; the next drawn frame is a full one.
     */
    void update(Calendar c, long t, boolean animate, boolean vacuumAllowed, boolean draw) {
        animateNow = animate;
        if (!draw) fullDirty = true; // nothing is drawn: no rects; the next drawn frame is whole
        if (!planned) plan(c);
        long wall = c.getTimeInMillis();
        int minute = minuteOfDay(c);
        boolean gap = lastWall == 0 || Math.abs(wall - lastWall) > 120_000;
        lastWall = wall;
        if (!animate) growStart = -1;

        timeState(c, t, vacuumAllowed);
        boolean recompose = updateColors();
        // Counted once, when this scene saw it set out (not when built mid-rampage, nor at night).
        if (ramp >= 0 && prevRamp < 0 && !biawakCounted && animate) {
            biawakCounted = true;
            if (recording && demoStart < 0) stats.addBiawak();
        }
        prevRamp = ramp;
        // Flat once the biawak has gone by (everything is rubble then): the rampage never quite
        // reaches 1 before midnight.
        if (ramp >= BW_GONE) flat = true;

        String key = ClockText.key(prefs, c);
        boolean textNew = !key.equals(shownKey);
        if (textNew) {
            layoutText(c);
            shownKey = key;
        }
        boolean minuteChanged = minute != lastMinute;
        if (minuteChanged) {
            // Buildings finished since the last minute (live only: not grow-in, catch-up or demo).
            boolean live = growStart < 0 && demoStart < 0 && quickStart < 0 && ramp < 0;
            if (lastMinute >= 0 && !gap && recording && live && lastPm >= 0) {
                int n = 0;
                for (B b : builds) if (countsAsBuilding(b.kind) && b.e > lastPm && b.e <= pm) n++;
                for (int k = 0; k < n; k++) stats.addBuilding();
                if (trainHand()) stats.addTrain();
            }
            if (live) lastPm = pm;
            lastMinute = minute;
            recompose = true;
        }
        if (gap || growStart >= 0) recompose = true;

        if (recompose) {
            phases(true);
            composeAll();
        } else {
            phases(false);
            if (textNew) compose(fx0, fy0, fx1, fy1);
        }

        // Particles, rain, fireworks.
        if (lastTick < 0) lastTick = t;
        int ticks = (int) Math.min(6, (t - lastTick) / 33);
        lastTick += ticks * 33L;
        if (t - lastTick > 200) lastTick = t;
        if (ticks > 0) {
            tickParticles(ticks);
            fireworksTick(t);
            rainTick(ticks);
        }
        if (!animate) { nP = 0; nDrops = 0; }
        updateMen(c, t, animate, gap);
        if (draw) {
            render(c, t, animate);
        } else {
            nCur = 0;
            nPrev = 0;
            nDirty = 0;
            for (Man m : men) m.drawn = false;
        }
    }

    private boolean trainHand() {
        return pm >= lrtDone && ramp < 0 && demoStart < 0 && quickStart < 0 && growStart < 0;
    }

    private boolean kiteHand() {
        return pm < lrtDone && ramp < 0 && demoStart < 0 && quickStart < 0 && growStart < 0;
    }

    private void fireworksTick(long t) {
        int[] w = fireworks();
        if (w == null || !animateNow || ramp >= 0 || growStart >= 0 || pm < w[0] || pm >= w[1]) return;
        if (fwNext >= 0 && t < fwNext) return;
        fwNext = t + 450 + rnd.nextInt(700);
        // Over the tall buildings (away from the billboard face).
        float x;
        int tries = 0;
        do { x = s(10) + rnd.nextFloat() * Math.max(1, cols - s(20)); } while (x > fx0 - s(6) && x < fx1 + s(6) && ++tries < 50);
        if (x > fx0 - s(6) && x < fx1 + s(6)) return; // no room beside the billboard
        float y0 = G - s(30);
        float vy = -(2.2f + rnd.nextFloat() * 0.6f) * S;
        addParticle(x, y0, (rnd.nextFloat() - 0.5f) * 0.2f, vy, GOLD, 26 + rnd.nextInt(10));
    }

    private boolean raining() { return storm >= 0.75f && animateNow; }

    private void rainTick(int ticks) {
        int want = raining() ? Math.round(MAX_DROPS * Math.min(1f, storm)) : 0;
        while (nDrops < want) {
            dX[nDrops] = rnd.nextInt(cols);
            dY[nDrops] = -rnd.nextInt(G);
            nDrops++;
        }
        for (int k = 0; k < nDrops; k++) {
            dY[k] += 3.2f * S * ticks;
            dX[k] -= 0.6f * S * ticks;
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            boolean land = y >= G || (y >= 0 && x >= 0 && x < cols && mask[y * cols + x] != 0) || x < 0;
            if (!land) continue;
            if (k < want) { dX[k] = rnd.nextInt(cols + s(20)); dY[k] = -rnd.nextInt(s(30)); }
            else { nDrops--; dX[k] = dX[nDrops]; dY[k] = dY[nDrops]; k--; }
        }
    }

    // =====================================================================================
    // Little people
    // =====================================================================================

    private static final int R_HAMMER = 1, R_CLIMB = 2, R_CARRY = 3, R_FARMER = 4, R_HAWKER = 5, R_DURIAN = 6,
            R_POLICE = 7, R_CAT = 8, R_WALKER = 9, R_CHICKEN = 10, R_KERBAU = 11, R_RIDER = 12;
    private static final int ARRIVE = 0, WORK = 1, LEAVE = 2, FLEE = 3, UP = 4, DOWN = 5;

    private static final class Man {
        String key;
        int role, state, dir = 1;
        float x, y, tx, lo, hi, timer;
        B site;
        int hat, shirt, pants;
        boolean carry, legs, gone, wanted;
        int cellX = Integer.MIN_VALUE, cellY = Integer.MIN_VALUE;
        long nextMove;
        int frame;
        // as last drawn (a still figure isn't restored or uploaded again)
        boolean drawn, lastFlip;
        String[] lastSpr;
        int lastRx, lastRy;
    }

    private int feet() { return G + s(3); }

    private void want(List<Man> wants, String key, int role, B site, float tx, float lo, float hi) {
        Man m = new Man();
        m.key = key; m.role = role; m.site = site; m.tx = tx; m.lo = lo; m.hi = hi;
        wants.add(m);
    }

    /** Once a minute: who should be out now; newcomers walk in, the rest walk off. */
    private void planMen(int minute) {
        List<Man> wants = new ArrayList<>();
        boolean day0 = lightMin >= rise - 20 && lightMin < set + 60;
        int maxSites = lightMin < rise - 30 ? 1 : 3;
        int sites = 0;
        List<B> active = new ArrayList<>();
        for (B b : builds) {
            if (b.ph != 1 || b.kind == PADDY || b.kind == PALMS || b.kind == SPAN || b.kind == MBEAM || b.kind == PILLAR || b.kind == MPILLAR) continue;
            active.add(b);
        }
        Collections.sort(active, new Comparator<B>() {
            public int compare(B p, B q) { return Float.compare(q.s, p.s); }
        });
        for (B b : active) {
            if (sites >= maxSites) break;
            sites++;
            boolean tall = isRise(b.kind);
            if (tall) {
                want(wants, "h" + b.id, R_HAMMER, b, ladderX(b), 0, 0);
                if (b.vis > s(10) && b.kind != STALL) want(wants, "c" + b.id, R_CLIMB, b, ladderX(b), 0, 0);
            } else {
                want(wants, "h" + b.id, R_HAMMER, b, b.x + b.w / 2 + s(6), 0, 0);
                want(wants, "c" + b.id, R_CARRY, b, b.x - s(6), b.x - s(8), b.x + b.w / 2);
            }
        }
        int farmers = 0;
        boolean kerbau = false;
        for (B b : builds) {
            if (b.kind == PADDY && b.ph >= 2 && b.ph <= 3 && lightMin >= 400 && lightMin < 690 && farmers < 2) {
                want(wants, "f" + b.id, R_FARMER, b, b.x + s(6) + (b.seed >>> 4 & 15) * (b.w - s(12)) / 16, b.x + s(4), b.x + b.w - s(8));
                farmers++;
                if (!kerbau && b.ph == 2 && b.w > s(24)) {
                    kerbau = true;
                    want(wants, "k" + b.id, R_KERBAU, b, b.x + b.w * 0.6f, b.x + s(4), b.x + b.w - s(16));
                }
            }
            if (b.kind == STALL && b.ph == 2 && lightMin >= 420 && lightMin < 1380) want(wants, "s", R_HAWKER, b, b.x + b.w / 2, 0, 0);
            if (b.kind == DURIAN && b.ph == 2 && lightMin >= 540 && lightMin < 1200) want(wants, "d", R_DURIAN, b, b.x + b.w + s(2), 0, 0);
        }
        // Chickens from a house, and a cat on a shophouse step.
        int chickens = 0;
        boolean cat = false;
        for (B b : builds) {
            if (b.kind == HOUSE && b.ph == 2 && chickens < 2 && lightMin >= rise && lightMin < set && pm < 900) {
                for (int k = 0; k < 2; k++) want(wants, "ch" + b.id + k, R_CHICKEN, b, b.x + b.w / 2 + (k * 2 - 1) * s(8), b.x - s(4), b.x + b.w + s(4));
                chickens += 2;
            }
            if (b.kind == SHOPS && b.ph == 2 && !cat) {
                cat = true;
                want(wants, "cat", R_CAT, b, b.x + s(4), 0, 0);
            }
        }
        boolean paved = pm >= 510;
        if (paved && lightMin >= 450 && lightMin < 1260 && pm >= 600) want(wants, "p", R_POLICE, null, cols / 2 + s(2), 0, 0);
        // Walkers in daytime: from one edge to the other, or to a door.
        if (day0 && lightMin >= 360) {
            int nw = pm < 600 ? 1 : pm < 1000 ? 2 : 3;
            for (int k = 0; k < nw; k++) {
                int slot = (minute / 4 + k * 7) % 11;
                if (slot < 6) want(wants, "w" + (minute / 4) + "_" + k, R_WALKER, null, 0, 0, 0);
            }
        }
        // Keep it to a handful.
        int people = 0, animals = 0;
        HashSet<String> keys = new HashSet<>();
        for (Man m : wants) {
            boolean animal = m.role == R_CHICKEN || m.role == R_KERBAU || m.role == R_CAT;
            if (animal ? animals >= 3 : people >= 9) continue;
            if (animal) animals++; else people++;
            keys.add(m.key);
        }
        for (Man m : men) {
            m.wanted = keys.contains(m.key);
            if (!m.wanted && m.state != LEAVE && m.state != FLEE && m.role != R_RIDER) leave(m);
            if (!m.wanted && !animateNow) m.gone = true;
        }
        for (int k = men.size() - 1; k >= 0; k--) if (men.get(k).gone) forget(men.remove(k));
        for (Man w : wants) {
            if (!keys.contains(w.key)) continue;
            boolean have = false;
            for (Man m : men) if (m.key.equals(w.key)) { have = true; m.site = w.site; break; }
            if (!have) spawn(w);
        }
    }

    /** A figure taken off: where it was drawn is restored. */
    private void forget(Man m) {
        if (m.drawn) addCur(m.lastRx - 7 * unit - 2, m.lastRy - 9 * unit, 14 * unit + 4, 11 * unit);
    }

    private void leave(Man m) {
        m.state = LEAVE;
        float doorX = -1;
        if (m.role == R_CHICKEN && m.site != null) doorX = m.site.x + Math.round(m.site.w * 0.58f);
        m.tx = doorX >= 0 ? doorX : (m.x < cols / 2 ? -s(10) : cols + s(10));
    }

    private void spawn(Man w) {
        Man m = w;
        Random r = new Random(w.key.hashCode() * 31L + day);
        // Walk in from the nearer edge, or out of a door.
        float from;
        if (m.role == R_CHICKEN && m.site != null) from = m.site.x + Math.round(m.site.w * 0.58f);
        else if (m.role == R_CAT && m.site != null) from = m.site.x + s(6);
        else if (m.role == R_FARMER) {
            float door = -1;
            for (B b : builds) if (b.kind == HOUSE && b.ph == 2 && (door < 0 || Math.abs(b.x - m.tx) < Math.abs(door - m.tx))) door = b.x + Math.round(b.w * 0.58f);
            from = door >= 0 ? door : (m.tx < cols / 2 ? -s(6) : cols + s(6));
        } else if (m.role == R_WALKER) {
            boolean left = r.nextBoolean();
            from = left ? -s(6) : cols + s(6);
            // Some go into a shop or a house, the rest walk past.
            m.tx = left ? cols + s(8) : -s(8);
            if (r.nextInt(3) == 0) {
                for (B b : builds) {
                    if (b.ph == 2 && (b.kind == SHOPS || b.kind == HOUSE || b.kind == FLATS) && r.nextInt(3) == 0) { m.tx = b.x + b.w / 2; break; }
                }
            }
        } else {
            from = m.tx < cols / 2 ? -s(6) : cols + s(6);
        }
        m.x = from;
        m.y = feet();
        m.state = ARRIVE;
        int[] shirts = {VEST, BLUE, GREEN, RED, PINK, WHITE};
        m.shirt = shirts[r.nextInt(shirts.length)];
        m.pants = r.nextBoolean() ? PANTS : BLUE;
        m.hat = 0;
        if (m.role == R_HAMMER || m.role == R_CLIMB || m.role == R_CARRY) {
            boolean kampung = m.site != null && (m.site.kind == HOUSE || m.site.kind == SURAU);
            m.hat = kampung ? 0 : HAT_Y;
            m.shirt = kampung ? shirts[r.nextInt(4)] : VEST;
            m.carry = m.role != R_HAMMER;
        }
        if (m.role == R_FARMER) { m.hat = CONE; m.shirt = r.nextBoolean() ? BLUE : GREEN; }
        if (m.role == R_POLICE) { m.hat = PANTS; m.shirt = WHITE; m.pants = PANTS; }
        if (m.role == R_HAWKER) { m.shirt = WHITE; }
        m.dir = m.tx >= m.x ? 1 : -1;
        m.wanted = true;
        if (!animateNow) {
            if (m.role == R_WALKER) return;
            m.x = m.role == R_CARRY ? m.lo : m.tx;
            m.state = WORK;
            if (m.role == R_FARMER || m.role == R_KERBAU) m.y = G - 1;
            if ((m.role == R_HAMMER || m.role == R_CLIMB) && m.site != null && isRise(m.site.kind)) m.y = workTop(m);
        }
        men.add(m);
    }

    private void updateMen(Calendar c, long t, boolean animate, boolean gap) {
        boolean show = prefs.kampungPeople && growStart < 0 && demoStart < 0 || (demoStart >= 0 && prefs.kampungPeople);
        if (!show) {
            for (Man m : men) forget(m);
            men.clear();
            return;
        }
        int minute = minuteOfDay(c);
        if (ramp >= 0) {
            float front = biawakFront();
            int dir = biawakDir();
            if (!bystanders && (quickStart >= 0 || demoStart >= 0)) {
                bystanders = true;
                Random r = new Random(day * 3L);
                for (int k = 0; k < 7; k++) {
                    Man m = new Man();
                    m.key = "b" + k;
                    m.role = R_WALKER;
                    m.x = s(20) + r.nextInt(Math.max(1, cols - s(40)));
                    m.y = feet();
                    m.state = WORK;
                    m.shirt = new int[]{VEST, BLUE, GREEN, RED, PINK, WHITE}[r.nextInt(6)];
                    m.pants = PANTS;
                    m.hat = k % 3 == 0 ? HAT_Y : 0;
                    m.dir = r.nextBoolean() ? 1 : -1;
                    men.add(m);
                }
            }
            for (Man m : men) {
                if (m.state == FLEE) continue;
                // Run when it comes close (ahead of it: away; already passed: the other way).
                float gapX = dir > 0 ? m.x - front : front - m.x;
                if (gapX > s(70) || !animate) continue;
                m.state = FLEE;
                m.y = m.role == R_RIDER ? m.y : feet();
                m.dir = dir;
                m.carry = false;
                if (dir > 0 && m.x < front - bwLen()) m.dir = -1;
                if (dir < 0 && m.x > front + bwLen()) m.dir = 1;
            }
        } else if (minute != menMinute || gap) {
            menMinute = minute;
            if (demoStart < 0 && quickStart < 0) planMen(minute);
        }
        // The delivery rider, every few minutes once the road is paved.
        if (ramp < 0 && animate && pm >= 520 && lightMin >= 420 && lightMin < 1400 && quickStart < 0 && demoStart < 0) {
            long sec = msOfDay(c) / 1000;
            if (sec % 180 == 20 && riderNext != sec) {
                riderNext = sec;
                Man m = new Man();
                m.key = "r" + sec;
                m.role = R_RIDER;
                boolean left = ((sec / 180) & 1) == 0;
                m.x = left ? -s(12) : cols + s(12);
                m.tx = left ? cols + s(14) : -s(14);
                m.dir = left ? 1 : -1;
                m.y = G + s(13);
                m.state = ARRIVE;
                m.shirt = (sec / 360) % 2 == 0 ? GREEN : PINK;
                m.pants = PANTS;
                m.hat = m.shirt;
                men.add(m);
            }
        }
        if (!animate) {
            for (Man m : men) {
                if ((m.role == R_HAMMER || m.role == R_CLIMB) && m.state == WORK && m.site != null && isRise(m.site.kind)) m.y = workTop(m);
            }
            return;
        }
        float dt = Math.min(0.5f, (t - menT) / 1000f);
        menT = t;
        if (dt <= 0) return;
        for (Man m : men) moveMan(m, t, dt);
        for (int k = men.size() - 1; k >= 0; k--) if (men.get(k).gone) forget(men.remove(k));
    }

    private long menT;
    private boolean bystanders;

    private float workTop(Man m) {
        B b = m.site;
        if (b == null) return feet();
        return b.by - b.vis;
    }

    private void moveMan(Man m, long t, float dt) {
        float walk = 2.2f * S;
        switch (m.state) {
            case ARRIVE: {
                float sp = m.role == R_RIDER ? 9f * S : m.role == R_KERBAU ? 1.2f * S : m.role == R_CHICKEN ? 1.5f * S : walk;
                if (stepTo(m, m.tx, sp, dt)) {
                    if (m.role == R_WALKER || m.role == R_RIDER) { m.gone = true; break; }
                    if (m.role == R_HAMMER || m.role == R_CLIMB) {
                        if (m.site != null && isRise(m.site.kind)) { m.state = UP; m.carry = m.role == R_CLIMB; }
                        else m.state = WORK;
                    } else {
                        m.state = WORK;
                    }
                    if (m.role == R_FARMER || m.role == R_KERBAU) m.y = G - 1; // step down into the field
                }
                break;
            }
            case UP: {
                float top = workTop(m);
                m.y -= 2f * S * dt;
                if (m.y <= top) {
                    m.y = top;
                    if (m.role == R_CLIMB) { m.carry = false; m.state = DOWN; m.timer = 1.5f; }
                    else m.state = WORK;
                }
                break;
            }
            case DOWN: {
                if (m.timer > 0) { m.timer -= dt; break; }
                m.y += 2.4f * S * dt;
                if (m.y >= feet()) {
                    m.y = feet();
                    if (m.state == DOWN && m.role == R_CLIMB && m.wanted) { m.timer = 0; m.carry = true; m.state = UP; }
                    else if (m.role == R_CLIMB) m.state = UP;
                }
                break;
            }
            case WORK: {
                if (m.site != null && (m.role == R_HAMMER) && isRise(m.site.kind)) m.y = workTop(m);
                if (t >= m.nextMove) {
                    m.frame ^= 1;
                    long period = m.role == R_HAMMER ? 450 : m.role == R_POLICE ? 900 : m.role == R_KERBAU ? 3000 : 1100;
                    m.nextMove = t + period;
                    if (m.role == R_FARMER || m.role == R_CHICKEN || m.role == R_KERBAU) {
                        // creep along the field: one cell at a time
                        if (m.x + m.dir > m.hi || m.x + m.dir < m.lo) m.dir = -m.dir;
                        if ((m.role != R_KERBAU || m.frame == 0) && (m.role != R_CHICKEN || rnd.nextInt(3) == 0)) m.x += m.dir;
                        if (m.role == R_FARMER) m.nextMove = t + 2600;
                    }
                }
                if (m.role == R_CARRY) {
                    // to and fro between the pile and the site
                    float target = m.carry ? m.hi : m.lo;
                    if (stepTo(m, target, walk * 0.8f, dt)) m.carry = !m.carry;
                }
                break;
            }
            case LEAVE: {
                if (m.y < feet() - 0.5f) { m.y = Math.min(feet(), m.y + 3f * S * dt); break; }
                m.y = m.role == R_RIDER ? m.y : feet();
                if (stepTo(m, m.tx, m.role == R_KERBAU ? 1.5f * S : walk, dt)) m.gone = true;
                break;
            }
            case FLEE: {
                m.x += m.dir * (m.role == R_RIDER ? 12f : 7f) * S * dt;
                if (m.x < -s(20) || m.x > cols + s(20)) m.gone = true;
                break;
            }
            default: break;
        }
        int cx = (int) Math.floor(m.x), cy = (int) Math.floor(m.y);
        if (cx != m.cellX || cy != m.cellY) {
            if (m.cellX != Integer.MIN_VALUE) m.legs = !m.legs;
            m.cellX = cx;
            m.cellY = cy;
        }
    }

    /** Walks toward x; true when there. */
    private boolean stepTo(Man m, float x, float speed, float dt) {
        float d = x - m.x;
        if (Math.abs(d) < 0.6f) { m.x = x; return true; }
        m.dir = d > 0 ? 1 : -1;
        m.x += m.dir * Math.min(Math.abs(d), speed * dt);
        return false;
    }

    // =====================================================================================
    // Render
    // =====================================================================================

    private void addCur(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(cols, x + w), y1 = Math.min(rows, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (nCur + 4 > cur.length) cur = java.util.Arrays.copyOf(cur, cur.length * 2);
        cur[nCur++] = x0; cur[nCur++] = y0; cur[nCur++] = x1 - x0; cur[nCur++] = y1 - y0;
    }

    private void restore(int[] r, int n) {
        for (int k = 0; k + 3 < n; k += 4) {
            int x = r[k], y = r[k + 1], w = r[k + 2], h = r[k + 3];
            for (int yy = y; yy < y + h; yy++) System.arraycopy(base, yy * cols + x, px, yy * cols + x, w);
        }
    }

    private boolean flashPrev, blinkOn;

    /** True when the rect at r[k..k+3] lies inside one of this frame's rects. */
    private boolean covered(int[] r, int k) {
        int x = r[k], y = r[k + 1], x1 = x + r[k + 2], y1 = y + r[k + 3];
        for (int j = 0; j + 3 < nCur; j += 4) {
            if (x >= cur[j] && y >= cur[j + 1] && x1 <= cur[j] + cur[j + 2] && y1 <= cur[j + 1] + cur[j + 3]) return true;
        }
        return false;
    }

    private void render(Calendar c, long t, boolean animate) {
        long ms = msOfDay(c);
        float sec = (ms % 60_000) / 60_000f;
        if (!animate) sec = (int) (sec * 60) / 60f;
        boolean even = (ms / 60_000) % 2 == 0;
        float along = even ? sec : 1 - sec;

        // The cranes (jib, trolley, hook and load).
        for (B b : builds) {
            if (b.ph != 1 || !b.crane || b.sink > 0) continue;
            craneGeom(b, t, animate);
            craneRects(b);
        }
        // The seconds hand.
        int kx = 0, ky = 0, kw = 13 * unit, kh = 11 * unit;
        boolean kite = kiteHand();
        if (kite) {
            kx = Math.round(-kw + along * (cols + kw));
            ky = Math.round(rows * 0.42f + (float) Math.sin(ms / 2300.0) * s(5));
            addCur(kx, ky, kw, kh + s(10));
        }
        boolean train = trainHand();
        int carL = s(26), nCars = 3, trainLen = nCars * (carL + s(1));
        int trX = 0;
        if (train) {
            trX = Math.round(-trainLen + along * (cols + trainLen));
            addCur(trX - 1, deckY - s(9), trainLen + 2, s(10));
        }
        // Monorail train: slow, to and fro.
        boolean mono = extras >= 3 && pm >= monoDone && ramp < 0;
        int moX = 0, moLen = s(22);
        if (mono) {
            float span = Math.max(1, monoX1 - monoX0 - moLen);
            float ph = (ms / 1000f / Math.max(1f, span / (1.5f * S))) % 2f;
            moX = monoX0 + Math.round((ph < 1 ? ph : 2 - ph) * span);
            addCur(moX - 1, monoY - s(6), moLen + 2, s(7));
        }
        // The banner plane.
        int plX = 0, plY = Math.round(rows * 0.46f);
        boolean plane = planeUpdate(c, t, animate);
        float pp = plane ? (t - planeStart) / (float) PLANE_MS : 0;
        // The banner line types in from 1/4 of the flight and un-types by 3/4.
        int vis = plane ? TypedLine.visible(noteCells.length, (t - planeStart) - PLANE_MS / 4, PLANE_MS / 2) : 0;
        int plW = s(12) + s(30);
        if (plane) {
            float bx = pp * (cols + plW);
            plX = Math.round(planeFromRight ? cols - bx : -plW + bx);
            addCur(plX - 1, plY - s(4), plW + 2, s(10));
        }
        if (vis != noteVis) addCur(noteX0, noteY0, noteW, noteH);
        noteVis = vis;
        // People: only those that moved or changed pose (with where they were).
        for (Man m : men) {
            String[] spr = sprite(m);
            int rx = Math.round(m.x), ry = Math.round(m.y);
            boolean flip = m.dir < 0;
            if (m.drawn && spr == m.lastSpr && rx == m.lastRx && ry == m.lastRy && flip == m.lastFlip) continue;
            int x0 = rx - 7 * unit - 2, y0 = ry - 9 * unit, x1 = x0 + 14 * unit + 4, y1 = y0 + 11 * unit;
            if (m.drawn) {
                x0 = Math.min(x0, m.lastRx - 7 * unit - 2);
                y0 = Math.min(y0, m.lastRy - 9 * unit);
                x1 = Math.max(x1, m.lastRx + 7 * unit + 2);
                y1 = Math.max(y1, m.lastRy + 2 * unit);
            }
            addCur(x0, y0, x1 - x0, y1 - y0);
            m.drawn = true;
            m.lastSpr = spr;
            m.lastRx = rx;
            m.lastRy = ry;
            m.lastFlip = flip;
        }
        // The biawak (until it has walked off the far side).
        boolean biawak = ramp >= 0 && ramp < BW_GONE && (biawakFront() > -s(10) - 1 || biawakDir() < 0);
        float front = biawakFront();
        int bl = bwLen(), bh = bwH();
        if (biawak) {
            int bx0 = biawakDir() > 0 ? Math.round(front) - bl - s(14) : Math.round(front) - s(14);
            addCur(bx0, G + s(6) - bh - s(10), bl + s(28), bh + s(16));
        }
        // Rooftop lights blinking (extra 4)
        boolean blink = extras >= 4 && dusk > 0.5f && ramp < 0 && ((t / 1000) & 1) == 0;
        if (blink != blinkOn) {
            // On or off: the roof rows of the lights (restored, and uploaded).
            blinkOn = blink;
            for (B b : builds) {
                if (b.ph != 2 || (b.kind != CONDO && b.kind != OFFICE && b.kind != FLATS)) continue;
                addCur(b.x + s(1), b.by - b.h - 1, b.w - s(2) - s(1) + 1, 1);
            }
        }
        // Particles
        if (nP > 0) {
            int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
            for (int k = 0; k < nP; k++) {
                int x = Math.round(pX[k]), y = Math.round(pY[k]);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1);
            }
            addCur(x0 - 1, y0 - 1, x1 - x0 + 2 + unit, y1 - y0 + 2 + unit);
        }
        if (nDrops > 0) addCur(0, 0, cols, G);
        // Lightning: one or two strikes in a storm.
        int bolt = -1;
        boolean flash = false;
        if (raining() && stormFrom >= 0) {
            float into = (ms / 60_000f) - stormFrom;
            for (int k = 0; k < 2; k++) {
                float at = stormLen * (k == 0 ? 0.3f : 0.68f);
                float dtS = (into - at) * 60f;
                if (dtS >= 0 && dtS < 0.45f) {
                    bolt = k;
                    flash = dtS < 0.2f && k == 0;
                }
            }
        }

        // Last frame's rects that one of this frame's covers (the rain's sky) are done once.
        int keptPrev = 0;
        for (int k = 0; k + 3 < nPrev; k += 4) {
            if (covered(prev, k)) continue;
            prev[keptPrev++] = prev[k]; prev[keptPrev++] = prev[k + 1]; prev[keptPrev++] = prev[k + 2]; prev[keptPrev++] = prev[k + 3];
        }
        nPrev = keptPrev;
        if (fullDirty || flash || flashPrev) {
            System.arraycopy(base, 0, px, 0, px.length);
            if (flash || flashPrev) fullDirty = true;
        } else {
            restore(prev, nPrev);
            restore(cur, nCur);
        }
        flashPrev = flash;
        if (flash) {
            int fc = pal[FLASH];
            for (int i = 0; i < px.length; i++) if (mask[i] == 0) px[i] = fc;
        }

        for (B b : builds) if (b.ph == 1 && b.crane && b.sink == 0) drawCrane(b, t, animate);
        if (kite) drawKite(kx, ky, kw);
        if (mono) drawMonoTrain(moX, moLen);
        if (train) drawTrain(trX, carL, nCars, even);
        if (blink) {
            for (B b : builds) {
                if (b.ph != 2 || (b.kind != CONDO && b.kind != OFFICE && b.kind != FLATS)) continue;
                put(b.x + s(1), b.by - b.h - 1, AVIATION);
                put(b.x + b.w - s(2), b.by - b.h - 1, AVIATION);
            }
        }
        for (Man m : men) drawMan(m, t);
        if (biawak) drawBiawak(front, t);
        for (int k = 0; k < nP; k++) putR(Math.round(pX[k]), Math.round(pY[k]), unit, unit, pCol[k]);
        if (nDrops > 0) {
            int rc = pal[RAIN];
            for (int k = 0; k < nDrops; k++) {
                int x = Math.round(dX[k]), y = Math.round(dY[k]);
                for (int j = 0; j < 2 * unit; j++) {
                    int yy = y - j, xx = x + j / 3;
                    if (xx < 0 || yy < 0 || xx >= cols || yy >= G) continue;
                    int i = yy * cols + xx;
                    if (mask[i] == 0) px[i] = rc;
                }
            }
        }
        if (bolt >= 0) drawBolt(bolt);
        if (plane) drawPlane(plX, plY);
        for (int k = 0; k < vis; k++) for (int i : noteCells[k]) px[i] = pal[NOTE];

        nDirty = 0;
        if (!fullDirty) {
            for (int k = 0; k < nPrev && nDirty < dirty.length; k++) dirty[nDirty++] = prev[k];
            for (int k = 0; k < nCur && nDirty < dirty.length; k++) dirty[nDirty++] = cur[k];
            if (nPrev + nCur > dirty.length) fullDirty = true;
        }
        int[] tmp = prev; prev = cur; cur = tmp;
        nPrev = nCur; nCur = 0;
        if (bolt >= 0) addCur(0, 0, cols, G);
    }

    private void put(int x, int y, int pi) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        int i = y * cols + x;
        if (mask[i] == 2) return;
        px[i] = pal[pi];
    }

    private void putR(int x, int y, int w, int h, int pi) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) put(xx, yy, pi);
    }

    private void line(int x0, int y0, int x1, int y1, int pi) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int n = 0; n < 3000; n++) {
            put(x0, y0, pi);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Jib (swinging a little: it looks shorter as it turns), trolley, hook and the load it lifts. */
    /** Where the crane's jib and hook are this frame (kept on b for the dirty rects and drawing). */
    private void craneGeom(B b, long t, boolean animate) {
        int top = mastTop(b), mx = b.craneX + s(1);
        int dir = b.craneDir;
        double th = animate ? 0.55 * Math.sin(t / 9000.0 + b.seed) : 0;
        float cs = (float) Math.cos(th);
        int jl = Math.round(s(36) * cs), cj = Math.round(s(12) * cs);
        int jy = top - s(2);
        // The hook's cycle: down to the ground, up with a load, across, down onto the work, back.
        int ground = b.by - s(7);
        int work = b.by - b.vis - s(2);
        int near = s(4), far = Math.max(near + s(4), Math.abs(b.x + b.w / 2 - mx));
        float cyc = animate ? ((t / 1000f + (b.seed & 63)) % 40f) / 40f : 0.9f;
        float d, hy;
        boolean load;
        if (cyc < 0.12f) { d = near; hy = lerp(jy + s(4), ground, cyc / 0.12f); load = false; }
        else if (cyc < 0.16f) { d = near; hy = ground; load = true; }
        else if (cyc < 0.4f) { d = near; hy = lerp(ground, jy + s(5), (cyc - 0.16f) / 0.24f); load = true; }
        else if (cyc < 0.55f) { d = lerp(near, far, (cyc - 0.4f) / 0.15f); hy = jy + s(5); load = true; }
        else if (cyc < 0.7f) { d = far; hy = lerp(jy + s(5), work - s(1), (cyc - 0.55f) / 0.15f); load = true; }
        else if (cyc < 0.75f) { d = far; hy = work - s(1); load = false; }
        else if (cyc < 0.85f) { d = far; hy = lerp(work - s(1), jy + s(4), (cyc - 0.75f) / 0.1f); load = false; }
        else { d = lerp(far, near, (cyc - 0.85f) / 0.15f); hy = jy + s(4); load = false; }
        b.cMx = mx;
        b.cJy = jy;
        b.cJl = jl;
        b.cCj = cj;
        b.cHx = mx + dir * Math.round(Math.min(d, s(36)) * cs);
        b.cHy = Math.round(hy);
        b.cLoad = load;
    }

    /** Dirty rects for a crane: the jib band, and the hook's column (not the still mast). */
    private void craneRects(B b) {
        int dir = b.craneDir, mx = b.cMx, jy = b.cJy;
        int xa = Math.min(mx - dir * b.cCj, mx + dir * b.cJl), xb = Math.max(mx - dir * b.cCj, mx + dir * b.cJl);
        xa = Math.min(xa, Math.min(mx - dir * b.cCj, mx - dir * (b.cCj - s(4)))) - s(1);
        xb = Math.max(xb, Math.max(mx - dir * b.cCj, mx - dir * (b.cCj - s(4))) + s(4)) + s(1);
        int ya = jy - s(4) - 1, yb = jy + s(6);
        addCur(xa, ya, xb - xa + 1, yb - ya);
        int hy = b.cHy, hx = b.cHx;
        int ca = Math.min(jy + s(1), hy), cb = Math.max(jy + s(2), hy) + 3 + s(2);
        addCur(hx - s(5) - 1, ca, s(10) + 2, cb - ca + 1);
    }

    private void drawCrane(B b, long t, boolean animate) {
        int mx = b.cMx;
        int dir = b.craneDir;
        int jl = b.cJl, cj = b.cCj;
        int jy = b.cJy;
        // apex, jib and counter-jib
        line(mx, jy - s(4), mx, jy, CRANE2);
        line(mx, jy - s(4), mx + dir * jl, jy, CRANE2);
        line(mx, jy - s(4), mx - dir * cj, jy, CRANE2);
        for (int k = -cj; k <= jl; k++) {
            int x = mx + dir * k;
            put(x, jy, CRANE);
            put(x, jy + s(1), CRANE);
            if ((Math.abs(k) % s(3)) == 0) put(x, jy + s(1) / 2, CRANE2);
        }
        putR(Math.min(mx - dir * cj, mx - dir * (cj - s(4))), jy + s(1), s(4), s(3), CONC2); // counterweight
        putR(mx - s(1), jy + s(2), s(3), s(3), CRANE2); // cab
        if (prefs.kampungPeople) put(mx, jy + s(3), SKIN);
        int hx = b.cHx, hy = b.cHy;
        putR(hx - s(1), jy + s(1), s(3), s(1), CRANE2);
        line(hx, jy + s(2), hx, hy, CABLE);
        put(hx, hy + 1, CABLE);
        if (b.cLoad) {
            int ly = hy + 2;
            putR(hx - s(5), ly, s(10), s(2), b.kind == TWIN || b.kind == M118 || b.kind == KLT ? STEEL2 : RED);
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static final String[] KITE = {
            "....RRR......",
            "...RYYYR.....",
            "RRRYYWYYRRR..",
            ".RRYYWYYRR...",
            "...RYWYR.....",
            "....RWR......",
            "..RRRWRRR....",
            ".R...W...R...",
            "R....W....R..",
            ".....W.......",
            "......W......",
    };

    private void drawKite(int x, int y, int kw) {
        for (int r = 0; r < KITE.length; r++) {
            String row = KITE[r];
            for (int c = 0; c < row.length(); c++) {
                char ch = row.charAt(c);
                if (ch == '.') continue;
                int pi = ch == 'R' ? KITE_R : ch == 'Y' ? KITE_Y : KITE_W;
                putR(x + c * unit, y + r * unit, unit, unit, pi);
            }
        }
    }

    private void drawTrain(int x, int carL, int n, boolean right) {
        int top = deckY - s(8);
        for (int k = 0; k < n; k++) {
            int cx = x + k * (carL + s(1));
            for (int yy = 0; yy < s(8); yy++) {
                for (int xx = 0; xx < carL; xx++) {
                    boolean nose = (k == 0 && !right || k == n - 1 && right) && false;
                    int c = TRAIN;
                    if (yy >= s(2) && yy < s(4) && (xx % s(4)) != 0 && xx > s(1) && xx < carL - s(1)) c = TRAINW;
                    if (yy == s(5)) c = TRAIN2;
                    if (yy == s(8) - 1) c = POLE2;
                    boolean frontEnd = right ? (k == n - 1 && xx >= carL - s(2)) : (k == 0 && xx < s(2));
                    if (frontEnd && yy < s(2)) continue;
                    if (nose) continue;
                    put(cx + xx, top + yy, c);
                }
            }
        }
    }

    private void drawMonoTrain(int x, int len) {
        int top = monoY - s(6);
        for (int yy = 0; yy < s(5); yy++) for (int xx = 0; xx < len; xx++) {
            int c = yy >= s(1) && yy < s(3) && (xx % s(3)) != 0 ? TRAINW : yy == s(4) ? TRAIN2 : TRAIN;
            if ((xx == 0 || xx == len - 1) && yy == 0) continue;
            put(x + xx, top + yy, c);
        }
    }

    private void drawPlane(int x, int y) {
        // banner behind, plane in front (facing its way)
        int pw = s(12), bw = s(26);
        boolean right = !planeFromRight;
        int px0 = right ? x + bw + s(4) : x;
        int bx0 = right ? x : x + pw + s(4);
        putR(bx0, y - s(2), bw, s(5), BANNER);
        for (int k = 0; k < bw; k += s(4)) putR(bx0 + k, y, s(2), s(1), RED);
        line(right ? bx0 + bw : bx0 - s(4), y, right ? px0 : bx0, y, CABLE);
        putR(px0, y, pw, s(2), PLANE);
        int nose = right ? px0 + pw : px0 - 1;
        put(nose, y + 1, PLANE);
        int tail = right ? px0 : px0 + pw - s(2);
        putR(tail, y - s(3), s(2), s(3), PLANE);
        putR(px0 + pw / 2 - s(2), y - s(1), s(4), s(1), PLANE);
        putR(px0 + pw / 2 - s(1), y + s(2), s(3), s(1), POLE2);
    }

    private void drawBolt(int k) {
        Random r = seeded;
        r.setSeed(day * 13L + k);
        int x, tries = 0;
        do { x = s(10) + r.nextInt(Math.max(1, cols - s(20))); } while (x > fx0 - s(4) && x < fx1 + s(4) && ++tries < 50);
        int y = 0;
        while (y < G - s(30)) {
            int nx = x + r.nextInt(s(9)) - s(4), ny = y + s(6) + r.nextInt(s(6));
            for (int j = 0; j < Math.max(1, s(1)); j++) {
                int dx = Math.abs(nx - x), dy = -Math.abs(ny - y);
                int x0 = x + j, y0 = y, sx = x < nx ? 1 : -1, err = dx + dy;
                for (int n = 0; n < 200; n++) {
                    if (y0 >= 0 && y0 < rows && x0 >= 0 && x0 < cols && mask[y0 * cols + x0] == 0) px[y0 * cols + x0] = pal[BOLT];
                    if (x0 == nx + j && y0 == ny) break;
                    int e2 = 2 * err;
                    if (e2 >= dy) { err += dy; x0 += sx; }
                    if (e2 <= dx) { err += dx; y0 += 1; }
                }
            }
            x = nx;
            y = ny;
        }
    }

    // ---- people sprites (our own little blocky figures) ----

    private static final String[] MAN_A = {"HHH", "FFF", "SSS", "SSS", "P.P", "P.P"};
    private static final String[] MAN_B = {"HHH", "FFF", "SSS", "SSS", ".P.", ".P."};
    private static final String[] MAN_HAMMER1 = {"HHH.", "FFFS", "SSS.", "SSS.", "P.P.", "P.P."};
    private static final String[] MAN_HAMMER2 = {"HHH.", "FFF.", "SSSS", "SSS.", "P.P.", "P.P."};
    private static final String[] MAN_CARRY = {"HHH.", "FFF.", "SSSB", "SSS.", "P.P.", "P.P."};
    private static final String[] MAN_CARRY2 = {"HHH.", "FFF.", "SSSB", "SSS.", ".P..", ".P.."};
    private static final String[] MAN_PLANK = {"BBBBB", ".HHH.", ".FFF.", ".SSS.", ".SSS.", ".P.P.", ".P.P."};
    private static final String[] MAN_PLANK2 = {"BBBBB", ".HHH.", ".FFF.", ".SSS.", ".SSS.", "..P..", "..P.."};
    private static final String[] FARMER_UP = {".CCC.", "CCCCC", ".FFF.", ".SSS.", ".SSS.", ".P.P."};
    private static final String[] FARMER_BEND = {".....", "...CC", "SSSCC", "SSS.F", "P...F", "P...."};
    private static final String[] FARMER_BEND2 = {".....", "...CC", "SSSCC", "SSSF.", "P..F.", "P...."};
    private static final String[] POLICE1 = {"HHH..", "FFF..", "SSSSS", "SSS..", "P.P..", "P.P.."};
    private static final String[] POLICE2 = {"HHH.S", "FFF.S", "SSSS.", "SSS..", "P.P..", "P.P.."};
    private static final String[] RIDER = {"....HH...", ".BB.SS...", ".BB.SSF..", ".MMMMMMM.", "KKK...KKK", ".K.....K."};
    private static final String[] CHICKEN1 = {"..R", ".WW", "WWW", ".Y."};
    private static final String[] CHICKEN2 = {"...", ".WW", "WWW", ".YR"};
    private static final String[] CAT1 = {"O.O.", "OOO.", ".OOO", ".OOT"};
    private static final String[] CAT2 = {"O.O.", "OOO.", ".OOO", ".OO."};
    private static final String[] KERBAU1 = {"h.........h.", "hKKKKKKK.KKh", ".KKKKKKKKKKK", ".KKKKKKKK.K.", ".K.K..K.K...", ".K.K..K.K..."};
    private static final String[] KERBAU2 = {"............", ".KKKKKKK....", ".KKKKKKKKh.h", ".KKKKKKKKKKK", ".K.K..K.KKK.", ".K.K..K.K..."};

    private static final String[] FARMER_WALK = {".CCC.", "CCCCC", ".FFF.", ".SSS.", ".SSS.", "..P.."};

    /** The figure's sprite for its role, state and step. */
    private String[] sprite(Man m) {
        String[] spr;
        boolean walking = m.state == ARRIVE || m.state == LEAVE || m.state == FLEE
                || (m.role == R_CARRY && m.state == WORK);
        switch (m.role) {
            case R_FARMER:
                spr = m.state == WORK ? (m.frame == 0 ? FARMER_BEND : FARMER_BEND2) : FARMER_UP;
                if (walking && m.legs) spr = FARMER_WALK;
                break;
            case R_POLICE:
                spr = m.state == WORK ? (m.frame == 0 ? POLICE1 : POLICE2) : (m.legs ? MAN_B : MAN_A);
                break;
            case R_RIDER: spr = RIDER; break;
            case R_CHICKEN: spr = m.frame == 0 ? CHICKEN1 : CHICKEN2; break;
            case R_CAT: spr = m.frame == 0 ? CAT1 : CAT2; break;
            case R_KERBAU: spr = m.frame == 0 || walking ? KERBAU1 : KERBAU2; break;
            case R_HAMMER:
                spr = m.state == WORK ? (m.frame == 0 ? MAN_HAMMER1 : MAN_HAMMER2) : (m.legs ? MAN_B : MAN_A);
                break;
            default:
                if (m.carry && m.site != null && (m.site.kind == HOUSE || m.site.kind == SURAU || m.site.kind == STALL)) spr = m.legs ? MAN_PLANK2 : MAN_PLANK;
                else if (m.carry) spr = m.legs ? MAN_CARRY2 : MAN_CARRY;
                else spr = m.legs ? MAN_B : MAN_A;
                break;
        }
        if ((m.state == UP || m.state == DOWN) && m.role != R_FARMER) spr = m.legs ? (m.carry ? MAN_CARRY2 : MAN_B) : (m.carry ? MAN_CARRY : MAN_A);
        if (m.role == R_HAWKER || m.role == R_DURIAN) spr = m.state == WORK ? (m.frame == 0 ? MAN_A : MAN_HAMMER2) : (m.legs ? MAN_B : MAN_A);
        return spr;
    }

    private void drawMan(Man m, long t) {
        String[] spr = m.lastSpr != null ? m.lastSpr : sprite(m);
        int h = spr.length, w = spr[0].length();
        int x0 = Math.round(m.x) - (w * unit) / 2, y0 = Math.round(m.y) - h * unit + 1;
        boolean flip = m.dir < 0;
        for (int r = 0; r < h; r++) {
            String row = spr[r];
            for (int c = 0; c < w; c++) {
                char ch = row.charAt(flip ? w - 1 - c : c);
                if (ch == '.') continue;
                int pi;
                switch (ch) {
                    case 'H': pi = m.hat != 0 ? m.hat : BLACK; break;
                    case 'F': pi = (m.key.hashCode() & 1) == 0 ? SKIN : SKIN2; break;
                    case 'S': pi = m.shirt; break;
                    case 'P': pi = m.pants; break;
                    case 'B': pi = m.role == R_RIDER ? m.shirt : (m.site != null && m.site.kind == HOUSE ? WOOD3 : RED); break;
                    case 'C': pi = CONE; break;
                    case 'M': pi = RED; break;
                    case 'K': pi = m.role == R_KERBAU ? KERBAU : BLACK; break;
                    case 'h': pi = HORN; break;
                    case 'R': pi = COMB; break;
                    case 'W': pi = CHICK; break;
                    case 'Y': pi = BEAK; break;
                    case 'O': pi = CAT; break;
                    case 'T': pi = CAT; break;
                    default: pi = WHITE; break;
                }
                putR(x0 + c * unit, y0 + r * unit, unit, unit, pi);
            }
        }
    }

    // ---- the giant biawak (procedural: a ribbon body, four legs, a forked tongue) ----

    private int[] bwCy = new int[0], bwTh = new int[0];
    private static final float[] LEG_AT = {0.5f, 0.71f};

    private void drawBiawak(float front, long t) {
        int dir = biawakDir();
        int L = bwLen(), H = bwH();
        int ground = G + s(8);
        // Legs move with the distance walked (no gliding).
        double stepPh = front / (double) s(26) * Math.PI;
        int bob = (int) Math.round(Math.abs(Math.sin(stepPh)) * s(1.5f));
        // body ribbon: u from 0 (tail tip) to 1 (snout)
        int n = L;
        if (bwCy.length < n + 1) { bwCy = new int[n + 1]; bwTh = new int[n + 1]; }
        int[] cyArr = bwCy, thArr = bwTh;
        for (int k = 0; k <= n; k++) {
            float u = k / (float) n;
            float yc, th;
            if (u < 0.45f) {
                float a = u / 0.45f;
                // the tail: thin at the tip, swaying a little, thick where it meets the hips
                yc = 0.07f + a * 0.15f + (float) Math.sin(a * 3.0 + t / 600.0) * 0.025f * (1 - a);
                th = 0.03f + a * a * 0.2f;
            } else if (u < 0.74f) {
                float a = (u - 0.45f) / 0.29f;
                yc = 0.23f + 0.02f * (float) Math.sin(a * Math.PI);
                th = 0.23f + 0.06f * (float) Math.sin(a * Math.PI);
            } else if (u < 0.86f) {
                float a = (u - 0.74f) / 0.12f;
                yc = 0.24f + a * 0.2f;
                th = 0.2f - a * 0.07f;
            } else {
                float a = (u - 0.86f) / 0.14f;
                yc = 0.45f - a * 0.04f;
                th = 0.14f - a * 0.09f;
            }
            cyArr[k] = ground - Math.round(yc * H) - bob;
            thArr[k] = Math.max(1, Math.round(th * H));
        }
        // far legs first (darker), then the body, then the near legs
        drawLegs(front, dir, L, H, ground, stepPh, true, cyArr, thArr);
        for (int k = 0; k <= n; k++) {
            float u = k / (float) n;
            int x = dir > 0 ? Math.round(front) - L + k : Math.round(front) + L - k;
            int top = cyArr[k] - thArr[k] / 2, bot = top + thArr[k];
            for (int y = top; y < bot; y++) {
                float f = (y - top) / (float) Math.max(1, thArr[k]);
                int c;
                if (f < 0.22f) c = BW0;
                else if (f > 0.72f) c = BW3;
                else {
                    int band = (int) (u * 22);
                    int hh = hash(k * 31 + y * 7);
                    c = (band & 1) == 0 && ((hh >>> 26) & 3) == 0 && u < 0.88f ? BW2 : BW1;
                }
                put(x, y, c);
            }
            // a ridge along the back
            put(x, top - 1, BW0);
        }
        drawLegs(front, dir, L, H, ground, stepPh, false, cyArr, thArr);
        // eye, nostril and mouth
        int ek = Math.round(0.93f * n), ex = dir > 0 ? Math.round(front) - L + ek : Math.round(front) + L - ek;
        int ey = cyArr[ek] - thArr[ek] / 4;
        putR(ex - s(1), ey - s(1), s(3), s(2), EYE);
        put(ex, ey, BLACK);
        for (int k = Math.round(0.9f * n); k < n; k++) {
            int x = dir > 0 ? Math.round(front) - L + k : Math.round(front) + L - k;
            put(x, cyArr[k] + thArr[k] / 4, BW0);
        }
        // the forked tongue, flicking
        if (((t / 280) % 5) < 2) {
            int tx = dir > 0 ? Math.round(front) : Math.round(front);
            int ty = cyArr[n] + thArr[n] / 3;
            int tl = s(10);
            for (int k = 0; k < tl; k++) put(tx + dir * k, ty, TONGUE);
            for (int k = 0; k < s(3); k++) {
                put(tx + dir * (tl + k), ty - 1 - k / 2, TONGUE);
                put(tx + dir * (tl + k), ty + 1 + k / 2, TONGUE);
            }
        }
    }

    private void drawLegs(float front, int dir, int L, int H, int ground, double ph, boolean far,
                          int[] cyArr, int[] thArr) {
        float[] at = LEG_AT;
        for (int k = 0; k < 2; k++) {
            double swing = Math.sin(ph + (k == 0 ? 0 : Math.PI) + (far ? Math.PI : 0));
            int ki = Math.round(at[k] * L);
            int hx = dir > 0 ? Math.round(front) - L + ki : Math.round(front) + L - ki;
            int hy = cyArr[ki];
            int bodyBot = cyArr[ki] + thArr[ki] / 2;
            int fx = hx + dir * (int) Math.round(swing * s(9) + s(3)) + (far ? -dir * s(4) : 0);
            int lift = swing < 0 ? (int) Math.round(-swing * s(4)) : 0;
            int fy = ground - lift;
            // elbow sticks out sideways: just below the body, a little forward
            int kx = hx + dir * (s(6) + (int) Math.round(swing * s(4)));
            int ky = bodyBot + s(2);
            int c = far ? BW0 : BW1;
            int w = Math.max(3, Math.round(H * 0.075f));
            for (int j = -w / 2; j <= w / 2; j++) {
                line(hx + j, hy, kx + j, ky, c);
                line(kx + j, ky, fx + j, fy, c);
            }
            // claws
            for (int j = 0; j < 3; j++) put(fx + dir * (w / 2 + 1 + j), fy - (j == 1 ? 1 : 0), far ? BW0 : BW3);
        }
    }

    /** True while something needs smooth frames. */
    boolean busy() {
        return nP > 0 || nDrops > 0 || raining() || busyBesidesRain();
    }

    private boolean busyBesidesRain() {
        boolean crumbling = false;
        if (ramp >= 0 && ramp < 1) for (B b : builds) if (b.cp > 0 && b.cp < 1) { crumbling = true; break; }
        int[] fw = fireworks();
        boolean fireworksNow = fw != null && pm >= fw[0] && pm < fw[1] && ramp < 0;
        return growStart >= 0 || crumbling || quickStart >= 0 && ramp < 1 || demoStart >= 0 || fireworksNow;
    }

    /** Busy only because of the rain (drops fall a few cells a frame: 10-12 fps is plenty). */
    boolean rainOnly() {
        return (nDrops > 0 || raining()) && nP == 0 && !busyBesidesRain();
    }

    /** The biawak's own rampage before midnight (slow: a few cells a second; not the quick run or the demo). */
    boolean rampaging() {
        return ramp >= 0 && quickStart < 0 && demoStart < 0;
    }

    /** True while something moves slowly (people, the kite, cranes, the biawak walking). */
    boolean moving() {
        if (kiteHand() || trainHand() || !men.isEmpty() || planeStart >= 0) return true;
        if (ramp >= 0 && ramp < BW_GONE) return true; // the biawak walking
        for (B b : builds) if (b.ph == 1 && b.crane && b.sink == 0) return true;
        return false;
    }

    /** The train, the rider and the plane need 8 fps; everything else is slow. */
    boolean fastMoving() {
        if (trainHand() || planeStart >= 0) return true;
        for (Man m : men) if (m.role == R_RIDER || m.state == FLEE) return true;
        return false;
    }

    // ---- the banner plane (stats) ----

    private boolean planeUpdate(Calendar c, long t, boolean animate) {
        if (stats == null || !animate || ramp >= 0 || demoStart >= 0 || quickStart >= 0 || growStart >= 0) {
            planeStart = -1;
            return false;
        }
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (planeStart < 0 && h % 2 == 0 && m == 20 && c.get(Calendar.SECOND) < 30 && planeHour != h) {
            planeHour = h;
            String msg = stats.kampungMessage(c, h / 2);
            if (msg == null) return false;
            planeStart = t;
            planeFromRight = rnd.nextBoolean();
            int sc = Math.max(1, unit);
            int w = PixelFont.width(msg) * sc;
            int top = Math.round(rows * 0.035f);
            int left = TypedLine.centred(msg, sc, cols);
            noteCells = TypedLine.cells(msg, left, top, sc, cols, rows);
            noteX0 = left; noteY0 = top; noteW = Math.min(cols, w + sc); noteH = PixelFont.H * sc;
        }
        if (planeStart >= 0 && t - planeStart > PLANE_MS) planeStart = -1;
        return planeStart >= 0;
    }
}
