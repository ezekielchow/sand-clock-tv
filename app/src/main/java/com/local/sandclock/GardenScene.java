package com.local.sandclock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The garden clock as pixels (plain Java, no Android, so it can be tested on a desktop).
 *
 * A tree grows over the day: a seed at midnight, a full canopy by late evening. Its shape is
 * new each day (seeded by the date) and the whole day's tree is generated up front, every
 * pixel with the time it appears, so a restart mid-day rebuilds the same tree at the same size.
 * The clock's characters are planters drawn over the tree: flowers bud and bloom on their top
 * edges over the hour a character has shown (the date's over the whole day), and drop their
 * petals when it changes. A ladybug climbs from the root to a branch tip once a minute (the
 * seconds hand); at :00 a bee visits a flower. After sunset fireflies blink around the tree.
 * In the last minutes before midnight the leaves turn and fall: autumn, then a new seed.
 *
 * Power: everything that doesn't move is composed once into `base` (once a minute, or while the
 * tree grows in or turns in autumn, just the tree's box). Each frame only the rectangles under
 * moving things are restored from it and redrawn, and the view uploads just those rectangles.
 */
final class GardenScene {
    static final int DAY_MS = 86_400_000;
    /** Grow-in: the tree grows from seed to today's size over this long. */
    private static final int GROW_MS = 3500;
    /** Midnight with autumn off: the leaves fall in this long. */
    static final int DROP_MS = 7000;
    static final long DEMO_MS = 100_000;
    /** The hourly bee: flies between up to BEE_STOPS flowers, hovering at each. */
    private static final int BEE_FLY_MS = 1600, BEE_HOVER_MS = 1900, BEE_STOPS = 4;
    private static final int BIRD_MS = 40_000;
    /** Growth is kept in 1/10000 of a day; the tree's pixels store when they appear. */
    private static final int G_FULL = 10_000;
    private static final short NONE = Short.MAX_VALUE;
    /** Autumn is redrawn in this many steps (each step recomposes only the tree's box). */
    private static final int A_STEPS = 240;

    final int cols, rows, day;
    final int unit;      // sprite scale: 2 on a 270-row grid
    final int[] px;      // what is on screen
    private final int[] base;
    private final Prefs prefs;
    private final Random rnd;
    private final float sc; // rows / 270

    // ---- layout (fixed for the day, seeded by the date) ----
    final int groundTop;
    private final int[] groundY;   // first ground row per column
    private final byte[] blade;    // grass blade height per column
    final int baseX;               // where the trunk meets the ground
    private final byte[] star;     // evening stars

    // ---- the tree ----
    private final short[] barkT, leafT; // when each pixel appears (0..G_FULL), NONE = never
    private final byte[] bark;          // 1 light bark, 2 shaded bark
    private final byte[] leafShade;     // 0 dark, 1 mid, 2 light
    private final short[] leafCl;       // which cluster a leaf pixel belongs to (autumn turns them together)
    private int[] leafIdx = new int[0];
    private int[] clusterT = new int[0];
    final int[] treeBox = new int[4];   // x, y, w, h of the whole day's tree
    // Segments of the skeleton (screen units), for the ladybug and the nest.
    private float[] sX0, sY0, sX1, sY1;
    private int[] sT0, sT1, sPar, sDepth;
    private float[] sTh;
    private int nSeg;

    // ---- the clock text ----
    private List<ClockText.Slot> slots = new ArrayList<>();
    private final byte[] text;       // 1 = letter wall or top edge
    private final byte[] planter;    // 1 = soil inside a letter
    private final byte[] flower;     // palette index, 0 = none
    private final Map<String, Long> born = new HashMap<>();
    private final List<int[][]> spots = new ArrayList<>(); // per slot: {x, y, petal, start, stage}
    private String shownKey = "";
    final int[] textPx;  // the letters alone (the dim clock at night)
    boolean textChanged = true;

    // ---- the day ----
    private int g = -1;            // growth shown now
    private int lastMinute = -1;
    private long lastWall;
    private long growStart = -1;
    private long dropStart = -1;
    private long demoStart = -1;
    private int frozenG = -1;      // growth kept while the leaves fall at midnight
    private int aStep;             // autumn, 0..A_STEPS
    private boolean autumn;
    private final int[] sky;       // sun or moon colour, 0 = none
    private int[] skyCells = new int[0];
    private int nSky;
    private int skyKey = Integer.MIN_VALUE;
    private boolean evening;       // between sunset and sunrise: stars
    private boolean afterSunset;   // sunset to midnight: fireflies

    // ---- moving things ----
    private static final int MAX_P = 400;
    private final float[] pX = new float[MAX_P], pY = new float[MAX_P], pVx = new float[MAX_P], pVy = new float[MAX_P];
    private final int[] pCol = new int[MAX_P], pWait = new int[MAX_P];
    private int nP;
    private long lastTick = -1;

    private float[] pathX = new float[0], pathY = new float[0];
    private int nPath;
    private long beeStart = -1;
    private final int[] beeWX = new int[BEE_STOPS + 2], beeWY = new int[BEE_STOPS + 2];
    private int beeN;        // waypoints: off-screen, the flowers, off-screen
    private long beeDur;     // ms for the whole tour
    private int bugX, bugY;
    private boolean bugRight;

    // ---- stats and the extras they grow (reset every two weeks) ----
    private Stats stats;
    private boolean recording;
    private final byte[] extra;    // nest, eggs, chicks, mushrooms, seed (palette index)
    private long extrasKey = -1;
    private long birdStart = -1;
    private boolean birdFromRight;
    private int birdHour = -1;
    private int[] noteCells = new int[0];
    private int noteX0, noteY0, noteW, noteH;
    private boolean noteShown;

    void setStats(Stats s, boolean record) {
        stats = s;
        recording = record && s != null;
    }

    // ---- dirty rectangles ----
    private int[] prev = new int[64], cur = new int[64];
    private int nPrev, nCur;
    final int[] dirty = new int[4 * 80];
    int nDirty;
    boolean fullDirty = true;

    GardenScene(int cols, int rows, int day, Prefs prefs) {
        this.cols = cols;
        this.rows = rows;
        this.day = day;
        this.prefs = prefs;
        unit = Math.max(1, Math.round(rows / 135f));
        sc = rows / 270f;
        int n = cols * rows;
        px = new int[n];
        base = new int[n];
        textPx = new int[n];
        text = new byte[n];
        planter = new byte[n];
        flower = new byte[n];
        extra = new byte[n];
        sky = new int[n];
        star = new byte[n];
        barkT = new short[n];
        leafT = new short[n];
        bark = new byte[n];
        leafShade = new byte[n];
        leafCl = new short[n];
        java.util.Arrays.fill(barkT, NONE);
        java.util.Arrays.fill(leafT, NONE);
        rnd = new Random(day * 31L + System.nanoTime());
        Random r = new Random(day * 7919L + 11);

        groundTop = rows - Math.round(rows * 0.12f);
        groundY = new int[cols];
        blade = new byte[cols];
        double l1 = 70 + r.nextInt(80), p1 = r.nextDouble() * 7;
        for (int x = 0; x < cols; x++) {
            groundY[x] = groundTop + (int) Math.round(1.2 * Math.sin(x / l1 * 6.283 + p1));
            if (r.nextFloat() < 0.22f) blade[x] = (byte) (1 + r.nextInt(Math.max(1, Math.round(3 * sc))));
        }
        for (int k = 0; k < 40; k++) {
            int x = r.nextInt(cols), y = r.nextInt(Math.max(1, Math.round(groundTop * 0.55f)));
            star[y * cols + x] = (byte) (1 + r.nextInt(3));
        }
        // A new spot every day, anywhere from near the left edge to near the right (the canopy is
        // pulled back inside the screen when it would overhang).
        baseX = buildTree(r, Math.round(cols * (0.12f + 0.76f * r.nextFloat())));
    }

    // ---- the tree ----

    private final ArrayList<float[]> skel = new ArrayList<>();   // x0,y0,x1,y1,th0,th1,d0,d1,depth,parent
    private final ArrayList<float[]> clus = new ArrayList<>();   // x,y,R,seg,dist,seed
    private static final int MAX_DEPTH = 6;

    private int addSeg(float x0, float y0, float x1, float y1, float t0, float t1, float d0, float d1, int depth, int parent) {
        skel.add(new float[]{x0, y0, x1, y1, t0, t1, d0, d1, depth, parent});
        return skel.size() - 1;
    }

    /** One limb and everything that grows from it (unit: trunk length 1, y up negative). */
    private void grow(Random r, float x, float y, double a, float len, float th, int depth, int parent, float dist) {
        int seg = parent;
        float cx = x, cy = y;
        float thEnd = th * 0.74f;
        for (int k = 0; k < 3; k++) {
            a += (r.nextFloat() - 0.5f) * 0.32f;
            a += (Math.PI / 2 - a) * (depth == 0 ? 0.25 : 0.05); // gently toward the light
            float l = len / 3;
            float nx = (float) (cx + Math.cos(a) * l), ny = (float) (cy - Math.sin(a) * l);
            float t0 = th + (thEnd - th) * k / 3f, t1 = th + (thEnd - th) * (k + 1) / 3f;
            if (depth == 0 && k == 0) t0 *= 1.45f; // root flare
            seg = addSeg(cx, cy, nx, ny, t0, t1, dist, dist + l, depth, seg);
            // Foliage along the outer limbs (and a little on the inner ones, for the young tree).
            float chance = depth >= MAX_DEPTH - 2 ? 0.55f : depth >= 2 ? 0.65f : depth == 1 ? 0.35f : 0f;
            if (k >= 1 && r.nextFloat() < chance) {
                clus.add(new float[]{nx, ny, 0.09f + 0.04f * r.nextFloat(), seg, dist + l, r.nextInt()});
            }
            dist += l;
            cx = nx;
            cy = ny;
        }
        if (depth >= MAX_DEPTH || len < 0.1f) {
            clus.add(new float[]{cx, cy, 0.13f + 0.06f * r.nextFloat(), seg, dist, r.nextInt()});
            return;
        }
        int n = depth == 0 ? 3 : (r.nextFloat() < 0.6f ? 2 : 3);
        double spread = depth == 0 ? 0.6 + 0.3 * r.nextFloat() : 0.38 + 0.3 * r.nextFloat();
        for (int k = 0; k < n; k++) {
            double ca;
            float cl, ct;
            if (n == 2) {
                ca = a + (k == 0 ? -1 : 1) * spread * (0.7 + 0.6 * r.nextFloat());
                cl = len * (0.68f + 0.16f * r.nextFloat());
                ct = thEnd * 0.78f;
            } else {
                ca = a + (k - 1) * spread * (0.85 + 0.4 * r.nextFloat());
                cl = len * (k == 1 ? 0.8f : 0.68f) * (0.9f + 0.2f * r.nextFloat());
                ct = thEnd * (k == 1 ? 0.82f : 0.7f);
            }
            if (depth == 0) cl *= 1.05f;
            ca = Math.max(-0.2, Math.min(Math.PI + 0.2, ca));
            grow(r, cx, cy, ca, cl, ct, depth + 1, seg, dist);
        }
    }

    /** Generates the day's tree and gives each of its pixels the time it appears. Returns the base x. */
    private int buildTree(Random r, int bx) {
        grow(r, 0, 0, Math.PI / 2 + (r.nextFloat() - 0.5f) * 0.16f, 0.95f + 0.2f * r.nextFloat(), 0.2f, 0, -1, 0);
        // Fit: about 70 % of the screen tall, at most 60 % wide.
        float minX = 0, maxX = 0, minY = 0, maxD = 0;
        for (float[] s : skel) {
            minX = Math.min(minX, Math.min(s[0], s[2]));
            maxX = Math.max(maxX, Math.max(s[0], s[2]));
            minY = Math.min(minY, s[3]);
            maxD = Math.max(maxD, s[7]);
        }
        for (float[] c : clus) {
            minX = Math.min(minX, c[0] - c[2]);
            maxX = Math.max(maxX, c[0] + c[2]);
            minY = Math.min(minY, c[1] - c[2]);
        }
        float s = Math.min(rows * 0.7f / -minY, cols * 0.6f / (maxX - minX));
        int left = Math.round(bx + minX * s), right = Math.round(bx + maxX * s);
        if (left < 3) bx += 3 - left;
        if (right > cols - 4) bx -= right - (cols - 4);
        float rootR = Math.max(1f, skel.get(0)[4] * s / 2);
        // Bark: along each segment, appearing by its distance from the root; thicker parts
        // fill in later, so the trunk starts as a sapling and thickens.
        nSeg = skel.size();
        sX0 = new float[nSeg]; sY0 = new float[nSeg]; sX1 = new float[nSeg]; sY1 = new float[nSeg];
        sT0 = new int[nSeg]; sT1 = new int[nSeg]; sPar = new int[nSeg]; sDepth = new int[nSeg]; sTh = new float[nSeg];
        int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
        for (int k = 0; k < nSeg; k++) {
            float[] q = skel.get(k);
            float ax = bx + q[0] * s, ay = groundTop + q[1] * s, bx2 = bx + q[2] * s, by = groundTop + q[3] * s;
            sX0[k] = ax; sY0[k] = ay; sX1[k] = bx2; sY1[k] = by;
            sT0[k] = axisTime(q[6] / maxD);
            sT1[k] = axisTime(q[7] / maxD);
            sPar[k] = (int) q[9];
            sDepth[k] = (int) q[8];
            sTh[k] = q[4] * s;
            float len = (float) Math.hypot(bx2 - ax, by - ay);
            int steps = Math.max(1, (int) Math.ceil(len * 2));
            for (int st = 0; st <= steps; st++) {
                float f = st / (float) steps;
                float cx = ax + (bx2 - ax) * f, cy = ay + (by - ay) * f;
                float rr = Math.max(0.5f, (q[4] + (q[5] - q[4]) * f) * s / 2);
                int tAx = sT0[k] + Math.round((sT1[k] - sT0[k]) * f);
                int ir = (int) Math.ceil(rr);
                for (int dy = -ir; dy <= ir; dy++) for (int dx = -ir; dx <= ir; dx++) {
                    int xx = Math.round(cx) + dx, yy = Math.round(cy) + dy;
                    float ddx = xx - cx, ddy = yy - cy;
                    float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                    if (d > rr + 0.3f || xx < 0 || yy < 0 || xx >= cols || yy >= groundY[Math.max(0, Math.min(cols - 1, xx))] + 1) continue;
                    int i = yy * cols + xx;
                    int t = Math.min(9000, tAx + Math.round(1700 * d / rootR));
                    if (t < barkT[i]) {
                        barkT[i] = (short) t;
                        bark[i] = (byte) (ddx > rr * 0.35f ? 2 : 1);
                    }
                    if (xx < x0) x0 = xx;
                    if (xx > x1) x1 = xx;
                    if (yy < y0) y0 = yy;
                    if (yy > y1) y1 = yy;
                }
            }
        }
        // Leaf clusters: shortly after their twig, growing outwards; a few gaps to see through.
        clusterT = new int[clus.size()];
        int[] li = new int[cols * rows / 4];
        int nl = 0;
        for (int k = 0; k < clus.size(); k++) {
            float[] c = clus.get(k);
            Random cr = new Random((long) c[5]);
            float cx = bx + c[0] * s, cy = groundTop + c[1] * s, R = Math.max(2.5f, c[2] * s);
            int ct = Math.min(9300, axisTime(c[4] / maxD) + 150 + cr.nextInt(300));
            clusterT[k] = ct;
            // A few overlapping blobs.
            int nb = 3 + cr.nextInt(2);
            float[][] blobs = new float[nb][];
            blobs[0] = new float[]{cx, cy, R};
            for (int b = 1; b < nb; b++) {
                double an = cr.nextDouble() * 6.283;
                blobs[b] = new float[]{(float) (cx + Math.cos(an) * R * 0.6f), (float) (cy - Math.abs(Math.sin(an)) * R * 0.45f + R * 0.1f),
                        R * (0.55f + 0.25f * cr.nextFloat())};
            }
            int ir = (int) Math.ceil(R * 1.7f);
            for (int dy = -ir; dy <= ir; dy++) for (int dx = -ir; dx <= ir; dx++) {
                int xx = Math.round(cx) + dx, yy = Math.round(cy) + dy;
                if (xx < 0 || yy < 0 || xx >= cols || yy >= groundY[xx]) continue;
                float best = 9;
                for (float[] b : blobs) {
                    float ex = (xx - b[0]) / b[2], ey = (yy - b[1]) / b[2];
                    best = Math.min(best, (float) Math.sqrt(ex * ex + ey * ey));
                }
                int i = yy * cols + xx;
                int h = hash(i ^ (k * 0x2545F491));
                float jag = ((h >>> 24) & 0xFF) / 255f;
                if (best > 1f - 0.28f * jag) continue;
                if (best > 0.35f && ((h >>> 16) & 0xFF) < 12) continue; // a gap
                int t = Math.min(9500, ct + Math.round(260 * Math.min(1f, (float) Math.hypot(xx - cx, yy - cy) / R)));
                if (leafT[i] == NONE) {
                    if (nl == li.length) li = java.util.Arrays.copyOf(li, nl * 2);
                    li[nl++] = i;
                }
                if (t < leafT[i]) {
                    leafT[i] = (short) t;
                    leafCl[i] = (short) Math.min(Short.MAX_VALUE, k);
                }
                // Light from the upper left: lighter on top, darker underneath.
                float v = -(yy - cy) / R * 0.75f - (xx - cx) / R * 0.3f + (((h >>> 8) & 0xFF) / 255f - 0.5f) * 0.7f;
                leafShade[i] = (byte) (v > 0.42f ? 2 : v < -0.3f ? 0 : 1);
                if (xx < x0) x0 = xx;
                if (xx > x1) x1 = xx;
                if (yy < y0) y0 = yy;
                if (yy > y1) y1 = yy;
            }
        }
        leafIdx = java.util.Arrays.copyOf(li, nl);
        treeBox[0] = x0; treeBox[1] = y0; treeBox[2] = Math.max(0, x1 - x0 + 1); treeBox[3] = Math.max(0, y1 - y0 + 1);
        skel.clear();
        clus.clear();
        return bx;
    }

    /** Branch time from the share of the way out from the root: done by about 20:40. */
    private static int axisTime(float u) {
        return 300 + Math.round(8300 * Math.max(0f, Math.min(1f, u)));
    }

    private static int hash(int i) {
        int h = i * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    // ---- palette ----

    private static final int SKY = 1, STAR = 2, GRASS0 = 3, SOIL0 = 5, BARK0 = 8, BARK1 = 9, LEAF0 = 10,
            AUT0 = 13, TEXT = 16, PLANTER = 17, STEM = 18, PET0 = 19, CENTER = 23, BUD = 24,
            LB_RED = 25, LB_BLACK = 26, BEE_Y = 27, BEE_K = 28, WING = 29, BIRD = 30, BIRD_BELLY = 31,
            BEAK = 32, NEST0 = 33, NEST1 = 34, EGG = 35, CHICK = 36, CAP = 37, DOT = 38, MSTEM = 39,
            FLY = 40, FLY_GLOW = 41, NOTE = 42, SPROUT = 43, N_PAL = 44;
    private final int[] pal = new int[N_PAL];
    private int colorKey = Integer.MIN_VALUE;
    private float dusk; // 0 day sky .. 1 night sky

    private boolean updateColors() {
        int key = prefs.background * 1000000 + prefs.style * 100000 + prefs.theme * 10000
                + prefs.brightness * 1000 + Math.round(prefs.warmth * 20) * 20 + Math.round(dusk * 10);
        if (key == colorKey) return false;
        colorKey = key;
        int st = prefs.style;
        float sf = st == 0 ? Prefs.BACKGROUND_F[prefs.background] : st == 1 ? 0.06f : 0f; // sky
        float tf = st == 0 ? 0.75f : st == 1 ? 0.82f : 1f;   // tree, flowers
        float gf = st == 0 ? 0.45f : st == 1 ? 0.6f : 1f;    // ground
        int skyRgb = mixRgb(0x6A9AC8, 0x1A2850, dusk);
        pal[0] = 0xFF000000;
        pal[SKY] = sf > 0 ? prefs.color(skyRgb, sf) : 0xFF000000;
        pal[STAR] = prefs.color(0xC8CCE0, 0.45f * dusk);
        pal[GRASS0] = prefs.color(0x4E9A3A, gf);
        pal[GRASS0 + 1] = prefs.color(0x3C7E2E, gf);
        pal[SOIL0] = prefs.color(0x5A3E26, gf);
        pal[SOIL0 + 1] = prefs.color(0x4A321E, gf);
        pal[SOIL0 + 2] = prefs.color(0x3C2818, gf);
        pal[BARK0] = prefs.color(0x7A5634, tf);
        pal[BARK1] = prefs.color(0x553A22, tf);
        pal[LEAF0] = prefs.color(0x2C6A2A, tf);
        pal[LEAF0 + 1] = prefs.color(0x3F8C34, tf);
        pal[LEAF0 + 2] = prefs.color(0x66B048, tf);
        pal[AUT0] = prefs.color(0xE8C23A, tf);
        pal[AUT0 + 1] = prefs.color(0xE0802A, tf);
        pal[AUT0 + 2] = prefs.color(0xC0392A, tf);
        pal[TEXT] = prefs.textColor(1f);
        pal[PLANTER] = prefs.color(0x2A1C12, st == 2 ? 1f : 0.8f);
        pal[STEM] = prefs.color(0x4FA040, tf);
        pal[PET0] = prefs.color(0xF06A8A, tf);
        pal[PET0 + 1] = prefs.color(0xF4F0E8, tf);
        pal[PET0 + 2] = prefs.color(0xA078E0, tf);
        pal[PET0 + 3] = prefs.color(0xF6C640, tf);
        pal[CENTER] = prefs.color(0xF2B030, tf);
        pal[BUD] = prefs.color(0x8CC060, tf);
        pal[LB_RED] = prefs.color(0xE0302A, 1f);
        pal[LB_BLACK] = prefs.color(0x141414, 1f);
        pal[BEE_Y] = prefs.color(0xF4C430, 1f);
        pal[BEE_K] = prefs.color(0x1A1A1A, 1f);
        pal[WING] = prefs.color(0xD8EEF8, 0.9f);
        pal[BIRD] = prefs.color(0x4A6A9A, 1f);
        pal[BIRD_BELLY] = prefs.color(0xE0C8A0, 1f);
        pal[BEAK] = prefs.color(0xF0A030, 1f);
        pal[NEST0] = prefs.color(0x8A6236, tf);
        pal[NEST1] = prefs.color(0x5E4024, tf);
        pal[EGG] = prefs.color(0xB8E0E8, tf);
        pal[CHICK] = prefs.color(0xE8D070, tf);
        pal[CAP] = prefs.color(0xC83A2E, gf + 0.2f);
        pal[DOT] = prefs.color(0xF0E8E0, gf + 0.2f);
        pal[MSTEM] = prefs.color(0xE0D4BC, gf + 0.1f);
        pal[FLY] = prefs.color(0xF4F08A, 1f);
        pal[FLY_GLOW] = prefs.color(0x8A9A3A, 0.7f);
        pal[NOTE] = prefs.color(0xEEF4E4, 0.9f);
        pal[SPROUT] = prefs.color(0x7CC050, tf);
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
        skyKey = Integer.MIN_VALUE;
        return true;
    }

    private static int mixRgb(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gg = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (gg << 8) | bl;
    }

    // ---- what each still cell looks like ----

    private int baseColor(int x, int y) {
        int i = y * cols + x;
        if (text[i] != 0) return pal[TEXT];
        if (planter[i] != 0) return pal[PLANTER];
        if (flower[i] != 0) return pal[flower[i]];
        if (extra[i] != 0) return pal[extra[i]];
        int lt = leafT[i];
        if (lt <= g) {
            int c = leafColor(i);
            if (c >= 0) return pal[c];
        }
        if (barkT[i] <= g) return pal[bark[i] == 2 ? BARK1 : BARK0];
        int gy = groundY[x];
        if (y >= gy) {
            int d = y - gy;
            int h = hash(i);
            if (d < 2 * unit) return pal[GRASS0 + ((h >>> 30) == 0 ? 1 : 0)];
            return pal[SOIL0 + ((h >>> 28) % 3)];
        }
        if (y >= gy - blade[x] && (x & 1) == 0) return pal[GRASS0 + 1];
        if (sky[i] != 0) return sky[i];
        if (evening && star[i] != 0) return pal[STAR];
        return pal[SKY];
    }

    /** A leaf's colour now (autumn turns it, then it falls): palette index, -1 = gone. */
    private int leafColor(int i) {
        if (aStep <= 0) return LEAF0 + leafShade[i];
        float a = aStep / (float) A_STEPS;
        float turn = turnOf(i);
        float fall = fallOf(i);
        if (a >= fall) return -1;
        float q = (a - turn) / (fall - turn);
        if (q < 0) return LEAF0 + leafShade[i];
        return AUT0 + Math.min(2, (int) (q * 3));
    }

    /** When a leaf starts to turn: mostly by its cluster, so patches of the crown turn together. */
    private float turnOf(int i) {
        int hc = hash(leafCl[i] * 0x2545F491 + day);
        int h = hash(i ^ (day * 0x27D4EB2F));
        return ((hc >>> 8) & 0xFF) / 255f * 0.42f + (h & 0xFF) / 255f * 0.08f;
    }

    /** When a leaf falls: after it has turned, all gone just before the end. */
    private float fallOf(int i) {
        float turn = turnOf(i);
        int h = hash(i ^ (day * 0x27D4EB2F));
        return turn + 0.28f + ((h >>> 8) & 0xFF) / 255f * (0.69f - turn);
    }

    private void composeAll() {
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) base[y * cols + x] = baseColor(x, y);
        fullDirty = true;
    }

    private void composeRect(int x0, int y0, int w, int h) {
        int x1 = Math.min(cols, x0 + w), y1 = Math.min(rows, y0 + h);
        for (int y = Math.max(0, y0); y < y1; y++) for (int x = Math.max(0, x0); x < x1; x++) {
            base[y * cols + x] = baseColor(x, y);
        }
    }

    // ---- time ----

    void startGrowIn(long t) { growStart = t; }

    /** Midnight without autumn: the leaves fall now, quickly. */
    private float dropFrom;

    void startDrop(long t) {
        if (dropStart < 0) {
            dropStart = t;
            frozenG = Math.max(g, 0);
            // Carry on from wherever autumn had got to (e.g. the last once-a-minute night
            // step was at 23:59), so leaves already fallen don't come back green.
            dropFrom = aStep / (float) A_STEPS;
        }
    }

    boolean dropped(long t) { return dropStart >= 0 && t - dropStart >= DROP_MS; }

    /** True while falling leaves or petals are still in the air. */
    boolean airborne() { return nP > 0; }

    /** True when there is nothing left to fall (autumn done, or no leaves yet). */
    boolean bare() {
        if (aStep * 100 >= 97 * A_STEPS) return true; // every leaf has fallen by 97 % of autumn
        for (int ct : clusterT) if (ct <= g) return false;
        return true;
    }

    /** "Preview end of day": the full tree's autumn, sped up. */
    void startDemo(long t, Calendar c) { demoStart = t; }

    boolean demoOver(long t) { return demoStart >= 0 && t - demoStart > DEMO_MS + 3000; }

    private static int minuteOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static long msOfDay(Calendar c) {
        return (minuteOfDay(c) * 60L + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
    }

    /** Sets g (growth shown) and aStep (autumn) for now. */
    private void timeState(Calendar c, long t, boolean vacuumAllowed) {
        int minute = minuteOfDay(c);
        int full = minute * G_FULL / 1440;
        autumn = false;
        float a = 0;
        if (demoStart >= 0) {
            full = G_FULL;
            a = Math.min(1f, (t - demoStart) / (float) DEMO_MS);
            autumn = a < 1f;
        } else if (dropStart >= 0) {
            full = frozenG;
            a = dropFrom + (1 - dropFrom) * Math.min(1f, (t - dropStart) / (float) DROP_MS);
            autumn = a < 1f;
        } else {
            int vm = Prefs.VACUUM_MIN[prefs.gardenAutumn];
            long vacFrom = DAY_MS - vm * 60_000L;
            long ms = msOfDay(c);
            if (vm > 0 && ms >= vacFrom && vacuumAllowed) {
                a = Math.min(1f, (ms - vacFrom) / (vm * 60_000f));
                autumn = a < 1f;
                full = (int) (vacFrom / 60_000L) * G_FULL / 1440;
            }
        }
        if (growStart >= 0) {
            float p = Math.min(1f, (t - growStart) / (float) GROW_MS);
            if (p >= 1f) growStart = -1;
            full = Math.round(full * (1 - (1 - p) * (1 - p) * (1 - p)));
        }
        g = full;
        aStep = Math.round(a * A_STEPS);
    }

    // ---- the text ----

    private void layoutText(Calendar c, long wall, boolean fresh, boolean animate) {
        List<ClockText.Slot> next = ClockText.layout(prefs, c, cols, rows);
        HashSet<String> nextIds = new HashSet<>();
        for (ClockText.Slot s : next) nextIds.add(s.id);
        // Characters that change drop their petals.
        if (!fresh) {
            for (int k = 0; k < slots.size(); k++) {
                ClockText.Slot s = slots.get(k);
                if (nextIds.contains(s.id)) continue;
                born.remove(s.id);
                if (!animate) continue;
                for (int[] sp : spots.get(k)) {
                    if (sp[4] < 2) continue;
                    int top = sp[1] - (sp[4] == 3 ? 5 : 4) * unit;
                    for (int n = 0; n < 4; n++) {
                        addParticle(sp[0] + rnd.nextInt(3 * unit) - unit, top + rnd.nextInt(2 * unit),
                                (rnd.nextFloat() - 0.5f) * 0.4f, -0.2f * rnd.nextFloat(),
                                sp[4] == 3 ? PET0 + sp[2] : BUD, rnd.nextInt(10));
                    }
                }
            }
        }
        java.util.Arrays.fill(text, (byte) 0);
        java.util.Arrays.fill(planter, (byte) 0);
        slots = next;
        if (fresh) ages(c, wall);
        for (ClockText.Slot s : slots) {
            for (int i : s.cells) text[i] = 1;
            for (int i : s.lids) text[i] = 1;
            for (int i : s.inner) planter[i] = 1;
            if (!born.containsKey(s.id)) born.put(s.id, wall);
        }
        // Where flowers can stand: the top edges, one every few cells.
        spots.clear();
        int gap = 4 * unit;
        for (ClockText.Slot s : slots) {
            List<int[]> list = new ArrayList<>();
            int seed = s.id.hashCode();
            int off = (seed >>> 4) % gap;
            HashSet<Integer> taken = new HashSet<>();
            int[][] groups = {s.cells, s.lids};
            for (int[] grp : groups) for (int i : grp) {
                int x = i % cols, y = i / cols;
                if (y < 6 * unit || (x + off) % gap != 0) continue;
                if (!topEdge(i) || x < 1 || x >= cols - 1 || !topEdge(i - 1) || !topEdge(i + 1)) continue;
                if (!taken.add(x)) continue;
                int h = hash(i ^ seed);
                list.add(new int[]{x, y, (h >>> 8) & 3, (h >>> 16) % 550, -1});
            }
            spots.add(list.toArray(new int[0][]));
        }
        growFlowers(c, wall);
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
    }

    private boolean topEdge(int i) {
        if (text[i] == 0 || i < cols) return false;
        int a = i - cols;
        return text[a] == 0 && planter[a] == 0;
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

    // Flowers, bottom-aligned on the edge: sprout, stem, bud, bloom.
    private static final String[][] FLOWER = {
            {"g.g", ".g."},
            {".g.", "gg.", ".g.", ".g."},
            {".b.", ".g.", "gg.", ".g."},
            {"ppp", "pcp", "ppp", ".gg", ".g."},
    };

    /** Flowers on characters that stay: full bloom after an hour (the date: over the day). */
    private void growFlowers(Calendar c, long wall) {
        java.util.Arrays.fill(flower, (byte) 0);
        for (int k = 0; k < slots.size(); k++) {
            ClockText.Slot s = slots.get(k);
            Long b = born.get(s.id);
            float f = s.part == 'd' ? minuteOfDay(c) / 1440f
                    : Math.min(1f, (wall - (b != null ? b : wall)) / 3_600_000f);
            for (int[] sp : spots.get(k)) {
                float p = (f - sp[3] / 1000f) / 0.45f;
                int stage = p < 0 ? -1 : p < 0.25f ? 0 : p < 0.55f ? 1 : p < 0.8f ? 2 : 3;
                sp[4] = stage;
                if (stage < 0) continue;
                String[] spr = FLOWER[stage];
                int h = spr.length;
                int x0 = sp[0] - unit - unit / 2, y0 = sp[1] - h * unit;
                for (int yy = 0; yy < h * unit; yy++) for (int xx = 0; xx < 3 * unit; xx++) {
                    char ch = spr[yy / unit].charAt(xx / unit);
                    if (ch == '.') continue;
                    int X = x0 + xx, Y = y0 + yy;
                    if (X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
                    int i = Y * cols + X;
                    if (text[i] != 0 || planter[i] != 0) continue;
                    flower[i] = (byte) (ch == 'g' ? STEM : ch == 'b' ? BUD : ch == 'c' ? CENTER : PET0 + sp[2]);
                }
            }
        }
    }

    // ---- sky ----

    /** Sun by day, moon (tonight's phase) by night, along an arc; recomputed once a minute. */
    private boolean updateSky(Calendar c) {
        int m = minuteOfDay(c);
        int[] tt = prefs.city > 0 ? Sun.times(prefs.city, c) : new int[]{420, 1155};
        int rise = tt[0], set = tt[1];
        float dk;
        if (m >= set + 45 || m < rise - 45) dk = 1f;
        else if (m >= set - 15) dk = (m - (set - 15)) / 60f;
        else if (m < rise + 15) dk = 1f - (m - (rise - 45)) / 60f;
        else dk = 0f;
        dk = Math.round(dk * 10) / 10f;
        boolean changed = false;
        if (dk != dusk) { dusk = dk; changed = true; }
        afterSunset = m >= set;
        boolean ev = m >= set || m < rise;
        if (ev != evening) { evening = ev; changed = true; }
        int key = prefs.sunMoon ? m + colorKey * 1441 : -2;
        if (key == skyKey && !changed) return false;
        skyKey = key;
        for (int k = 0; k < nSky; k++) sky[skyCells[k]] = 0;
        nSky = 0;
        if (!prefs.sunMoon) return true;
        boolean sun = m >= rise && m < set;
        float p;
        if (sun) {
            p = (m - rise) / (float) Math.max(1, set - rise);
        } else {
            int mm = m < rise ? m + 1440 : m;
            p = (mm - set) / (float) Math.max(1, 1440 - set + rise);
        }
        int horizon = groundTop, top = Math.round(rows * 0.1f);
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
                    boolean lit = phase < 0.5 ? dx >= term : dx <= -term;
                    if (!lit) continue;
                }
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= cols || y >= groundTop) continue;
                int i = y * cols + x;
                sky[i] = d > r - 1.2 ? edge : core;
                skyCells[nSky++] = i;
            }
        }
        return true;
    }

    // ---- particles (petals and falling leaves) ----

    private void addParticle(float x, float y, float vx, float vy, int col, int wait) {
        if (nP >= MAX_P) return;
        pX[nP] = x; pY[nP] = y; pVx[nP] = vx; pVy[nP] = vy; pCol[nP] = col; pWait[nP] = wait;
        nP++;
    }

    private void tickParticles(int ticks) {
        for (int n = 0; n < ticks; n++) {
            for (int k = 0; k < nP; k++) {
                if (pWait[k] > 0) { pWait[k]--; continue; }
                pVy[k] = Math.min(0.55f * sc + 0.1f, pVy[k] + 0.02f);
                pX[k] += pVx[k] + (float) Math.sin((pY[k] + k * 13) / 6.0) * 0.3f;
                pY[k] += pVy[k];
                int x = Math.round(pX[k]);
                boolean gone = x < 0 || x >= cols || pY[k] >= groundY[Math.max(0, Math.min(cols - 1, x))];
                if (gone) {
                    nP--;
                    pX[k] = pX[nP]; pY[k] = pY[nP]; pVx[k] = pVx[nP]; pVy[k] = pVy[nP]; pCol[k] = pCol[nP]; pWait[k] = pWait[nP];
                    k--;
                }
            }
        }
    }

    /** Leaves whose time to fall came between two autumn steps drift down (some of them). */
    private void dropLeaves(int fromStep, int toStep, long durationMs) {
        float a0 = fromStep / (float) A_STEPS, a1 = toStep / (float) A_STEPS;
        if (a1 >= 1f) a1 = 1.01f;
        float rate = durationMs < 30_000 ? 60f : 14f; // particles per second, about
        float chance = Math.min(0.5f, rate * durationMs / 1000f / Math.max(1, leafIdx.length));
        for (int i : leafIdx) {
            if (leafT[i] > g) continue;
            float f = fallOf(i);
            if (f < a0 || f >= a1) continue;
            if (rnd.nextFloat() >= chance) continue;
            int col = AUT0 + 1 + rnd.nextInt(2);
            if (rnd.nextInt(4) == 0) col = AUT0;
            addParticle(i % cols, i / cols, (rnd.nextFloat() - 0.5f) * 0.3f, 0.05f, col, 0);
        }
    }

    // ---- per frame ----

    private int lastExtrasMinute = -1;
    private int lastG = -1;

    /**
     * Brings the scene to time c (wall clock) / t (uptime ms) and redraws what moved.
     * animate = false: no particles, bee, birds or fireflies; the ladybug ticks.
     */
    void update(Calendar c, long t, boolean animate, boolean vacuumAllowed) {
        boolean recompose = updateColors();
        long wall = c.getTimeInMillis();
        int minute = minuteOfDay(c);
        boolean gap = lastWall == 0 || Math.abs(wall - lastWall) > 120_000;
        lastWall = wall;
        if (!animate) growStart = -1;

        int oldA = aStep;
        int oldG = g;
        timeState(c, t, vacuumAllowed);
        boolean treeChanged = g != oldG || aStep != oldA;

        String key = ClockText.key(prefs, c);
        if (!key.equals(shownKey)) {
            layoutText(c, wall, shownKey.isEmpty() || gap || !animate, animate);
            shownKey = key;
            recompose = true;
        } else if (minute != lastMinute) {
            growFlowers(c, wall);
            recompose = true;
        }
        if ((minute != lastMinute || skyKey == Integer.MIN_VALUE) && updateSky(c)) {
            updateColors(); // dusk may have changed the sky
            recompose = true;
        }
        if (minute != lastMinute) {
            boolean critters = prefs.gardenCritters;
            if (lastMinute >= 0 && !gap) {
                // The bee visits once an hour, at :50, when most flowers on the hour's digits
                // are in full bloom (and clear of the stats bird at :45).
                if (minute % 60 == 50 && animate && critters && !autumn && demoStart < 0 && dropStart < 0) startBee(t);
                if (recording && critters && nPath > 1 && aStep == 0 && demoStart < 0 && dropStart < 0) {
                    stats.addLadybugLap();
                }
                if (recording && lastG >= 0) {
                    int n = 0;
                    for (int ct : clusterT) if (ct > lastG && ct <= g) n++;
                    if (n > 0) stats.addLeaves(n);
                }
            }
            lastMinute = minute;
            recompose = true;
        }
        if (growStart < 0 && demoStart < 0 && dropStart < 0) lastG = g;
        if (minute != lastExtrasMinute || treeChanged) {
            lastExtrasMinute = minute;
            if (buildExtras(c)) recompose = true;
        }
        if (animate && aStep > oldA && oldG >= 0) {
            long dur = demoStart >= 0 ? DEMO_MS : dropStart >= 0 ? DROP_MS
                    : Prefs.VACUUM_MIN[prefs.gardenAutumn] * 60_000L;
            dropLeaves(oldA, aStep, dur);
        }
        if (recompose) {
            composeAll();
            buildPath(minute);
        } else if (treeChanged) {
            composeRect(treeBox[0], treeBox[1], treeBox[2], treeBox[3]);
            addCur(treeBox[0], treeBox[1], treeBox[2], treeBox[3]);
            if (g != oldG) buildPath(minute);
        }

        if (lastTick < 0) lastTick = t;
        int ticks = (int) Math.min(6, (t - lastTick) / 33);
        lastTick += ticks * 33L;
        if (t - lastTick > 200) lastTick = t;
        if (ticks > 0) tickParticles(ticks);
        if (!animate) { nP = 0; beeStart = -1; }

        render(c, t, animate);
    }

    /** The ladybug's way for this minute: from the root up to a grown branch end. */
    private void buildPath(int minute) {
        nPath = 0;
        if (nSeg == 0 || g < sT0[0] + 100) return;
        Random r = new Random(day * 1441L + minute);
        // Candidates: grown segment ends, preferring higher, further-out ones.
        int pick = -1;
        int[] cand = new int[nSeg];
        int nc = 0;
        for (int k = 0; k < nSeg; k++) if (sT1[k] <= g && sDepth[k] >= 2 && (k + 1 >= nSeg || sPar[k + 1] != k || sDepth[k + 1] != sDepth[k])) cand[nc++] = k;
        if (nc > 0) pick = cand[r.nextInt(nc)];
        int end;
        float endFrac = 1f;
        if (pick >= 0) {
            end = pick;
        } else {
            // Only the trunk so far: climb to its grown top.
            end = 0;
            while (end + 1 < nSeg && sPar[end + 1] == end && sT1[end + 1] <= g) end++;
            if (end + 1 < nSeg && sPar[end + 1] == end) end++;
            endFrac = Math.max(0f, Math.min(1f, (g - sT0[end]) / (float) Math.max(1, sT1[end] - sT0[end])));
        }
        // Walk back to the root.
        int[] chain = new int[64];
        int n = 0;
        for (int k = end; k >= 0 && n < chain.length; k = sPar[k]) chain[n++] = k;
        float total = 0;
        for (int j = n - 1; j >= 0; j--) {
            int k = chain[j];
            float f = j == 0 ? endFrac : 1f;
            total += Math.hypot(sX1[k] - sX0[k], sY1[k] - sY0[k]) * f;
        }
        int np = Math.max(2, (int) total + 2);
        if (pathX.length < np) { pathX = new float[np]; pathY = new float[np]; }
        for (int j = n - 1; j >= 0; j--) {
            int k = chain[j];
            float f = j == 0 ? endFrac : 1f;
            float len = (float) Math.hypot(sX1[k] - sX0[k], sY1[k] - sY0[k]) * f;
            int steps = Math.max(1, Math.round(len));
            for (int s = 0; s < steps && nPath < np; s++) {
                float q = s / (float) steps * f;
                pathX[nPath] = sX0[k] + (sX1[k] - sX0[k]) * q;
                pathY[nPath] = sY0[k] + (sY1[k] - sY0[k]) * q;
                nPath++;
            }
        }
        if (nPath < 2) nPath = 0;
    }

    /**
     * The hourly bee's tour: in from one side, a stop at up to four flowers on the digits
     * (open ones first), in order across the screen, then out the other side.
     */
    private void startBee(long t) {
        List<int[]> open = new ArrayList<>(), any = new ArrayList<>();
        for (int k = 0; k < slots.size(); k++) {
            for (int[] sp : spots.get(k)) {
                if (sp[4] == 3) open.add(sp);
                if (sp[4] >= 0) any.add(sp);
            }
        }
        List<int[]> pool = new ArrayList<>(open);
        if (pool.size() < BEE_STOPS) for (int[] sp : any) if (!pool.contains(sp)) pool.add(sp);
        if (pool.isEmpty()) {
            for (int k = 0; k < slots.size() && pool.isEmpty(); k++) if (spots.get(k).length > 0) pool.add(spots.get(k)[0]);
        }
        if (pool.isEmpty()) return;
        java.util.Collections.shuffle(pool, rnd);
        final boolean fromLeft = rnd.nextBoolean();
        List<int[]> stops = new ArrayList<>(pool.subList(0, Math.min(BEE_STOPS, pool.size())));
        java.util.Collections.sort(stops, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return fromLeft ? a[0] - b[0] : b[0] - a[0]; }
        });
        beeN = 0;
        int firstY = beeY(stops.get(0));
        beeWX[beeN] = fromLeft ? -BEE.w * unit - 2 : cols + 2;
        beeWY[beeN++] = Math.round(firstY - rows * 0.15f);
        for (int[] sp : stops) {
            beeWX[beeN] = sp[0] - BEE.w * unit / 2;
            beeWY[beeN++] = beeY(sp);
        }
        beeWX[beeN] = fromLeft ? cols + 2 : -BEE.w * unit - 2;
        beeWY[beeN] = Math.round(beeWY[beeN - 1] - rows * 0.25f);
        beeN++;
        beeDur = (long) (beeN - 1) * BEE_FLY_MS + (long) stops.size() * BEE_HOVER_MS;
        beeStart = t;
        if (recording) stats.addBee();
    }

    private int beeY(int[] sp) {
        int stage = Math.max(0, sp[4]);
        return sp[1] - FLOWER[stage].length * unit - BEE.h * unit + unit;
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
        boolean critters = prefs.gardenCritters;
        // Ladybug: root to tip over the minute.
        boolean bug = critters && nPath > 1 && aStep == 0 && demoStart < 0 && dropStart < 0;
        int bw = LADYBUG.w * unit, bh = LADYBUG.h * unit;
        if (bug) {
            float s = (ms % 60_000) / 60_000f;
            if (!animate) s = (int) (s * 60) / 60f;
            // Up to the tip one minute, back down the next (no jump back to the root).
            boolean up = (ms / 60_000) % 2 == 0;
            float along = up ? s : 1 - s;
            int k = Math.min(nPath - 1, Math.round(along * (nPath - 1)));
            int k2 = up ? Math.min(nPath - 1, k + 3) : Math.max(0, k - 3);
            if (k2 != k) bugRight = pathX[k2] >= pathX[k];
            bugX = Math.round(pathX[k]) - bw / 2;
            bugY = Math.round(pathY[k]) - bh / 2;
            addCur(bugX, bugY, bw, bh);
        }
        // Bee: in, a visit at a flower, out.
        int beeX = 0, beeY = 0;
        boolean beeRight = true;
        if (beeStart >= 0 && t - beeStart > beeDur) beeStart = -1;
        boolean bee = beeStart >= 0 && animate && critters;
        if (bee) {
            // Walk the tour: fly a leg, hover at the flower it reaches, fly on.
            long e = t - beeStart;
            int leg = 0;
            while (leg < beeN - 1) {
                if (e < BEE_FLY_MS) break;
                e -= BEE_FLY_MS;
                boolean flower = leg + 1 < beeN - 1;
                if (flower && e < BEE_HOVER_MS) { leg = -(leg + 1) - 1; break; } // hovering
                if (flower) e -= BEE_HOVER_MS;
                leg++;
            }
            if (leg < 0) {
                int at = -leg - 1;
                beeX = beeWX[at];
                beeY = beeWY[at] + ((e / 330) % 2 == 0 ? 0 : -unit / 2 - (unit > 1 ? 0 : 1));
                beeRight = at + 1 < beeN && beeWX[at + 1] >= beeWX[at];
            } else {
                leg = Math.min(leg, beeN - 2);
                float p = Math.min(1f, e / (float) BEE_FLY_MS);
                float q = p * p * (3 - 2 * p); // ease in and out
                int ax = beeWX[leg], ay = beeWY[leg], bx = beeWX[leg + 1], by = beeWY[leg + 1];
                beeX = Math.round(ax + (bx - ax) * q);
                // A little hop up between flowers, plus a wobble.
                beeY = Math.round(ay + (by - ay) * q - (float) Math.sin(Math.PI * p) * rows * 0.06f
                        + (float) Math.sin(t / 110.0) * unit);
                beeRight = bx >= ax;
            }
            addCur(beeX, beeY, BEE.w * unit, BEE.h * unit);
        }
        // Fireflies after sunset: slow, blinking.
        int nf = animate && critters && afterSunset && aStep == 0 && demoStart < 0 && dropStart < 0 && g > 1500 ? 7 : 0;
        int[] fx = flyX, fy = flyY;
        boolean[] fon = flyOn;
        for (int k = 0; k < nf; k++) {
            Random r = new Random(day * 97L + k);
            float bx = treeBox[0] + r.nextFloat() * treeBox[2], by = treeBox[1] + treeBox[3] * (0.2f + 0.75f * r.nextFloat());
            double ph = r.nextDouble() * 6.283, sp1 = 5200 + r.nextInt(3000), sp2 = 3900 + r.nextInt(2500);
            fx[k] = Math.round(bx + (float) Math.sin(t / sp1 + ph) * 16 * sc);
            fy[k] = Math.round(by + (float) Math.sin(t / sp2 + ph * 2) * 9 * sc);
            long cyc = (t + r.nextInt(4000)) % (3000 + r.nextInt(2500));
            fon[k] = cyc < 1100;
            addCur(fx[k] - 1, fy[k] - 1, 3, 3);
        }
        // The stats bird (and its line).
        int birdX = 0, birdY = 0;
        boolean bird = birdUpdate(c, t, animate);
        float bp = bird ? (t - birdStart) / (float) BIRD_MS : 0f;
        boolean note = bird && bp > 0.25f && bp < 0.75f;
        boolean showBird = bird && critters;
        if (showBird) {
            float bx = bp * (cols + BIRD_UP.w * unit);
            birdX = Math.round(birdFromRight ? cols - bx : -BIRD_UP.w * unit + bx);
            // Flap for a while (rising a little), then glide (sinking a little), like small birds.
            long cyc = (t - birdStart) % 2400;
            float rise = cyc < 1600 ? cyc / 1600f : 1f - (cyc - 1600) / 800f;
            birdY = Math.round(rows * 0.14f - rise * 2 * unit + (float) Math.sin(t / 1700.0) * 2 * unit);
            addCur(birdX, birdY, BIRD_UP.w * unit, BIRD_UP.h * unit);
        }
        if (bird || noteShown) addCur(noteX0, noteY0, noteW, noteH);
        noteShown = note;
        if (nP > 0) {
            int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
            for (int k = 0; k < nP; k++) {
                int x = Math.round(pX[k]), y = Math.round(pY[k]);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1);
            }
            addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        }

        if (fullDirty) {
            System.arraycopy(base, 0, px, 0, px.length);
        } else {
            restore(prev, nPrev);
            restore(cur, nCur);
        }

        if (bug) drawSprite(LADYBUG, bugX, bugY, !bugRight, true);
        for (int k = 0; k < nf; k++) {
            if (!fon[k]) continue;
            int x = fx[k], y = fy[k];
            put(x, y, FLY, true);
            put(x - 1, y, FLY_GLOW, true);
            put(x + 1, y, FLY_GLOW, true);
            put(x, y - 1, FLY_GLOW, true);
            put(x, y + 1, FLY_GLOW, true);
        }
        for (int k = 0; k < nP; k++) {
            if (pWait[k] > 0) continue;
            put(Math.round(pX[k]), Math.round(pY[k]), pCol[k], true);
        }
        if (bee) drawSprite((t / 70) % 2 == 0 ? BEE : BEE2, beeX, beeY, !beeRight, false);
        if (showBird) {
            boolean gliding = (t - birdStart) % 2400 >= 1600;
            Sprite b = gliding ? BIRD_GLIDE : (t / 130) % 2 == 0 ? BIRD_UP : BIRD_DOWN;
            drawSprite(b, birdX, birdY, birdFromRight, false);
        }
        if (note) for (int i : noteCells) px[i] = pal[NOTE];

        nDirty = 0;
        if (!fullDirty) {
            for (int k = 0; k < nPrev && nDirty < dirty.length; k++) dirty[nDirty++] = prev[k];
            for (int k = 0; k < nCur && nDirty < dirty.length; k++) dirty[nDirty++] = cur[k];
        }
        int[] tmp = prev; prev = cur; cur = tmp;
        nPrev = nCur; nCur = 0;
    }

    private final int[] flyX = new int[8], flyY = new int[8];
    private final boolean[] flyOn = new boolean[8];

    private void put(int x, int y, int pi, boolean behindText) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        int i = y * cols + x;
        if (behindText && (text[i] != 0 || planter[i] != 0)) return;
        px[i] = pal[pi];
    }

    private void restore(int[] r, int n) {
        for (int k = 0; k + 3 < n; k += 4) {
            int x = r[k], y = r[k + 1], w = r[k + 2], h = r[k + 3];
            for (int yy = y; yy < y + h; yy++) System.arraycopy(base, yy * cols + x, px, yy * cols + x, w);
        }
    }

    /** True while something needs smooth frames (petals, leaves, the bee, grow-in, autumn). */
    boolean busy() {
        return nP > 0 || beeStart >= 0 || growStart >= 0 || autumn;
    }

    /** True while something moves slowly (ladybug, fireflies, the bird): 8 fps is enough. */
    boolean moving() {
        return prefs.gardenCritters && (nPath > 1 || afterSunset || birdStart >= 0);
    }

    /** The bird crosses fast (about 12 cells a second): it needs 8 fps; the rest is slow. */
    boolean fastMoving() { return prefs.gardenCritters && birdStart >= 0; }

    private void drawSprite(Sprite s, int x, int y, boolean flip, boolean behindText) {
        for (int sy = 0; sy < s.h * unit; sy++) {
            int yy = y + sy;
            if (yy < 0 || yy >= rows) continue;
            for (int sx = 0; sx < s.w * unit; sx++) {
                int xx = x + sx;
                if (xx < 0 || xx >= cols) continue;
                int pi = s.at(sx / unit, sy / unit, flip);
                if (pi < 0) continue;
                int i = yy * cols + xx;
                if (behindText && (text[i] != 0 || planter[i] != 0)) continue;
                px[i] = pal[pi];
            }
        }
    }

    // ---- extras ----

    /**
     * The nest (then eggs, then chicks) on a sturdy fork and mushrooms by the roots, from this
     * fortnight's stats; and the seed at the foot of the tree before it sprouts. Rebuilt only
     * when they change. True if anything changed.
     */
    private boolean buildExtras(Calendar c) {
        int nest = 0, mush = 0;
        long period = 0;
        if (stats != null) {
            stats.refresh(c);
            nest = stats.gardenNestStage();
            mush = stats.mushrooms();
            period = stats.period;
        }
        int fork = nest > 0 ? nestFork() : -1;
        boolean nestShown = fork >= 0 && sT1[fork] + 400 <= g;
        boolean seed = g < 600;
        // A young tree with no leaves yet: a tuft at its growing tip.
        int tipX = -1, tipY = -1;
        boolean leafy = false;
        for (int ct : clusterT) if (ct <= g) { leafy = true; break; }
        if (!seed && !leafy && nSeg > 0) {
            int k = 0;
            while (k + 1 < nSeg && sPar[k + 1] == k && sT1[k] <= g) k++;
            float f = Math.max(0f, Math.min(1f, (g - sT0[k]) / (float) Math.max(1, sT1[k] - sT0[k])));
            tipX = Math.round(sX0[k] + (sX1[k] - sX0[k]) * f);
            tipY = Math.round(sY0[k] + (sY1[k] - sY0[k]) * f);
        }
        long key = ((period * 1000 + (nestShown ? nest : 0) * 100 + mush * 10 + (seed ? 1 : 0)) * 4096 + tipX + 1) * 4096 + tipY + 1;
        if (key == extrasKey) return false;
        extrasKey = key;
        java.util.Arrays.fill(extra, (byte) 0);
        if (seed) stamp(SEEDLING, baseX - SEEDLING.w * unit / 2, groundY[baseX] - SEEDLING.h * unit + unit);
        if (tipX >= 0) stamp(TUFT, tipX - TUFT.w * unit / 2, tipY - TUFT.h * unit + unit);
        if (nestShown) {
            int nx = Math.round(sX1[fork]) - NEST.w * unit / 2, ny = Math.round(sY1[fork]) - unit;
            stamp(NEST, nx, ny);
            if (nest == 2) stamp(EGGS, nx, ny - EGGS.h * unit);
            if (nest == 3) stamp(CHICKS, nx, ny - CHICKS.h * unit);
        }
        Random r = new Random(period * 977 + 5);
        float trunk = sTh.length > 0 ? sTh[0] : 4;
        for (int k = 0; k < mush; k++) {
            int side = k % 2 == 0 ? 1 : -1;
            int dist = Math.round(trunk + (4 + k / 2 * 9 + r.nextInt(5)) * unit);
            int x = baseX + side * dist - MUSH.w * unit / 2;
            if (x < 0 || x + MUSH.w * unit >= cols) continue;
            stamp(k % 3 == 2 ? MUSH_SMALL : MUSH, x, groundY[x + MUSH.w * unit / 2] - (k % 3 == 2 ? MUSH_SMALL : MUSH).h * unit + unit);
        }
        return true;
    }

    /** A fork low in the crown for the nest: thick enough, away from the digits if it can be. */
    private int nestFork() {
        int best = -1;
        float bestScore = -1e9f;
        for (int k = 0; k < nSeg; k++) {
            if (sDepth[k] < 1 || sDepth[k] > 2) continue;
            if (k + 1 < nSeg && sPar[k + 1] == k && sDepth[k + 1] == sDepth[k]) continue; // mid-limb
            float x = sX1[k], y = sY1[k];
            // The clock's whole area (whatever the digits are), so the nest stays on one fork
            // all day and the same after a restart.
            int st = Math.max(1, Math.round(rows * 0.2f / PixelFont.H));
            int sd = Math.max(1, Math.round(rows * 0.075f / PixelFont.H));
            int top = rows / 2 - PixelFont.H * st - 6 * unit;
            int bottom = rows / 2 + Math.round(rows * 0.06f) + PixelFont.H * sd + unit;
            boolean onText = y + unit >= top && y - 4 * unit <= bottom
                    && x + 5 * unit >= cols * 0.15f && x - 5 * unit <= cols * 0.85f;
            float score = sTh[k] - (onText ? 100 : 0) - sT1[k] / 2000f;
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    private void stamp(Sprite s, int x, int y) {
        for (int sy = 0; sy < s.h * unit; sy++) for (int sx = 0; sx < s.w * unit; sx++) {
            int pi = s.at(sx / unit, sy / unit, false);
            int X = x + sx, Y = y + sy;
            if (pi < 0 || X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
            extra[Y * cols + X] = (byte) pi;
        }
    }

    /**
     * Every two hours (at :45) a bird flies across the sky; while it passes the middle, a line
     * about the garden's lifetime stats shows at the top of the screen. True while it is out.
     */
    private boolean birdUpdate(Calendar c, long t, boolean animate) {
        if (stats == null || !animate || autumn || demoStart >= 0 || dropStart >= 0) { birdStart = -1; return false; }
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (birdStart < 0 && h % 2 == 0 && m == 45 && c.get(Calendar.SECOND) < 30 && birdHour != h) {
            birdHour = h;
            String msg = stats.gardenMessage(c, h / 2);
            if (msg == null) return false;
            birdStart = t;
            birdFromRight = rnd.nextBoolean();
            int s = Math.max(1, unit);
            int w = PixelFont.width(msg) * s;
            int top = Math.round(rows * 0.035f);
            int left = Math.max(0, (cols - w) / 2);
            List<Integer> cells = new ArrayList<>();
            int x = left;
            for (int k = 0; k < msg.length(); k++) {
                for (int i : PixelFont.cells(msg.charAt(k), x, top, s, cols, rows)) cells.add(i);
                x += PixelFont.advance(msg.charAt(k), s);
            }
            noteCells = new int[cells.size()];
            for (int k = 0; k < noteCells.length; k++) noteCells[k] = cells.get(k);
            noteX0 = left; noteY0 = top; noteW = Math.min(cols, w + s); noteH = PixelFont.H * s;
        }
        if (birdStart >= 0 && t - birdStart > BIRD_MS) birdStart = -1;
        return birdStart >= 0;
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

    /** Facing right (flipped to face left). */
    static final Sprite LADYBUG = new Sprite("RK", new int[]{LB_RED, LB_BLACK},
            ".RR.",
            "RKRK",
            "RRRK",
            ".K..");
    static final Sprite BEE = new Sprite("YKW", new int[]{BEE_Y, BEE_K, WING},
            "..WW..",
            "..WW..",
            "YKYKYK",
            ".KYKY.");
    static final Sprite BEE2 = new Sprite("YKW", new int[]{BEE_Y, BEE_K, WING},
            "......",
            ".WWW..",
            "YKYKYK",
            ".KYKY.");
    static final Sprite BIRD_UP = new Sprite("BLkW", new int[]{BIRD, BIRD_BELLY, BEAK, BIRD},
            "...WW...",
            "....WW..",
            "BB.BBBBk",
            "..BLLLB.",
            "........");
    static final Sprite BIRD_DOWN = new Sprite("BLkW", new int[]{BIRD, BIRD_BELLY, BEAK, BIRD},
            "........",
            "........",
            "BB.BBBBk",
            "..BLWWB.",
            "....WW..");
    static final Sprite BIRD_GLIDE = new Sprite("BLkW", new int[]{BIRD, BIRD_BELLY, BEAK, BIRD},
            "........",
            "........",
            "BBWWBBBk",
            "..BLLLB.",
            "........");
    static final Sprite NEST = new Sprite("Nn", new int[]{NEST0, NEST1},
            "NnNnNnNnN",
            ".nNnNnNn.",
            "..NnNnN..");
    static final Sprite EGGS = new Sprite("E", new int[]{EGG},
            "..E.E.E..");
    static final Sprite CHICKS = new Sprite("Ck", new int[]{CHICK, BEAK},
            "..k...k..",
            ".CC..CC..",
            ".CC..CC..");
    static final Sprite MUSH = new Sprite("CDS", new int[]{CAP, DOT, MSTEM},
            ".CCC.",
            "CDCDC",
            "..S..",
            "..S..");
    static final Sprite MUSH_SMALL = new Sprite("CDS", new int[]{CAP, DOT, MSTEM},
            ".CDC.",
            "..S..");
    static final Sprite TUFT = new Sprite("gl", new int[]{LEAF0 + 1, LEAF0 + 2},
            ".l.l.",
            "lglgl",
            ".ggg.");
    static final Sprite SEEDLING = new Sprite("gs", new int[]{SPROUT, NEST1},
            "g.g",
            ".g.",
            "sss");
}
