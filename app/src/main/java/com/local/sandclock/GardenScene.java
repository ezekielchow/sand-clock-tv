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
 * A few shrubs sprout around the tree in the morning and flower by evening. On some afternoons
 * (the same days and times as the kampung's) a storm darkens the sky: rain, a puddle at the
 * roots that dries slowly, and a faint rainbow after. On festival days a few small, still
 * decorations go up one at a time.
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
    private int[] clusterSeg = new int[0]; // the limb a leaf cluster grows on
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
    private int[][] noteChars = new int[0][];  // the bird's line, cells per character
    private int noteX0, noteY0, noteW, noteH;
    private int noteVis;                        // characters typed so far

    // ---- weather (the shared weather setting; same storm days as the kampung) ----
    private final int stormFrom, stormLen;      // minute of the day it starts, minutes; -1 = none today
    private float storm;                        // 0..1 in quarter steps (sky darkness)
    private int puddle;                         // 0..PUDDLE_STEPS
    private int rainbow;                        // 0..3 (faint .. less faint)
    private int rbX, rbY, rbOut, rbBand;        // the rainbow's arc
    private static final int PUDDLE_STEPS = 8, DRY_MIN = 48, MAX_DROPS = 110;
    private final float[] dX = new float[MAX_DROPS], dY = new float[MAX_DROPS];
    private int nDrops;
    private boolean raining, flashPrev;

    // ---- shrubs and festival things (still layer; deterministic per date) ----
    private final int fest;
    private int[] shrubX = new int[0], shrubFrom = new int[0], shrubStep = new int[0], shrubCol = new int[0];
    private final int[] shrubStage = new int[4];
    private final int[] lightCell = new int[24];
    private int nLights;
    private final int[] lanX = {-1, -1}, lanY = new int[2], lanT = new int[2];
    private int poleX = -1, flagDir = 1;
    private int festShown, festMask;

    void setStats(Stats s, boolean record) {
        stats = s;
        recording = record && s != null;
    }

    // ---- dirty rectangles ----
    private int[] prev = new int[64], cur = new int[64];
    private int nPrev, nCur;
    final int[] dirty = new int[4 * 300]; // rain: a small rectangle per drop
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
        shade = new byte[cols];
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

        int[] sp = stormPlan(day);
        stormFrom = sp != null ? sp[0] : -1;
        stormLen = sp != null ? sp[1] : 0;
        Calendar dc = Calendar.getInstance();
        dc.clear();
        dc.set(Calendar.YEAR, day / 1000);
        dc.set(Calendar.DAY_OF_YEAR, day % 1000);
        dc.set(Calendar.HOUR_OF_DAY, 12);
        fest = Festivals.of(prefs, dc);
        planDecor(new Random(day * 4243L + 3));
        planLife(new Random(day * 6151L + 17));
    }

    /**
     * Today's afternoon storm {start minute, length in minutes}, or null (about one day in three).
     * The kampung's rule from the same seed, so both scenes have their storm on the same days at
     * the same time: its generator first draws 60 stars (three numbers each) and two hill phases.
     */
    /** Today's afternoon storm, the same one the Kampung has (one shared rule). */
    static int[] stormPlan(int day) {
        return KampungScene.stormFor(day);
    }

    /** Where today's shrubs, festival lights, lanterns and flag pole go. */
    private void planDecor(Random r) {
        float trunk = sTh.length > 0 ? sTh[0] / 2 : 2;
        int n = 2 + r.nextInt(3);
        shrubX = new int[n]; shrubFrom = new int[n]; shrubStep = new int[n]; shrubCol = new int[n];
        int side = r.nextBoolean() ? 1 : -1, ns = 0;
        int half = SHRUB[SHRUB.length - 1].w * unit / 2 + 1;
        for (int k = 0; k < n; k++) {
            int dist = Math.round(trunk + (31 + (k / 2) * 17 + r.nextInt(7)) * unit);
            int s = k % 2 == 0 ? side : -side;
            int x = baseX + s * dist;
            if (x - half < 1 || x + half >= cols - 1) x = baseX - s * (dist + 34 * unit); // other side, further out
            int from = 360 + r.nextInt(150), step = 100 + r.nextInt(35), col = r.nextInt(3);
            if (x - half < 1 || x + half >= cols - 1) continue;
            shrubX[ns] = x; shrubFrom[ns] = from; shrubStep[ns] = step; shrubCol[ns] = col;
            ns++;
        }
        shrubX = java.util.Arrays.copyOf(shrubX, ns);
        // Christmas lights: leaf pixels spread over the day's canopy, clear of the clock where
        // the tree allows (each shows once its leaf has grown).
        nLights = 0;
        int gap = 7 * unit;
        for (int tries = 0; tries < 600 && nLights < lightCell.length && leafIdx.length > 0; tries++) {
            int i = leafIdx[r.nextInt(leafIdx.length)], x = i % cols, y = i / cols;
            if (tries < 400 && onClock(x, y, x + unit, y + unit)) continue;
            boolean near = false;
            for (int k = 0; k < nLights && !near; k++) {
                near = Math.abs(lightCell[k] % cols - x) < gap && Math.abs(lightCell[k] / cols - y) < gap;
            }
            if (near && tries < 500) continue;
            lightCell[nLights++] = i;
        }
        // Lanterns: low on a branch on each side of the trunk (half way along it or at its end),
        // clear of the clock if the tree allows.
        float[] best = {-1e9f, -1e9f};
        for (int k = 0; k < nSeg; k++) {
            if (sDepth[k] < 1 || sDepth[k] > 3) continue;
            for (int h = 1; h <= 2; h++) {
                float f = h / 2f;
                float x = sX0[k] + (sX1[k] - sX0[k]) * f, y = sY0[k] + (sY1[k] - sY0[k]) * f;
                float dx = x - baseX;
                if (Math.abs(dx) < Math.max(8 * unit, trunk * 3) || y + 12 * unit >= groundTop) continue;
                int sd = dx < 0 ? 0 : 1;
                float score = y - (onClock(x - 3 * unit, y, x + 3 * unit, y + 11 * unit) ? 1000 : 0);
                if (score > best[sd]) {
                    best[sd] = score;
                    lanX[sd] = Math.round(x);
                    lanY[sd] = Math.round(y);
                    lanT[sd] = sT0[k] + Math.round((sT1[k] - sT0[k]) * f) + 200;
                }
            }
        }
        // The flag pole: beside the tree, with the flag flying away from it.
        int d = Math.round(trunk + 24 * unit);
        int w = FLAG_W + 2;
        if (baseX + d + w < cols - 1) { poleX = baseX + d; flagDir = 1; }
        else if (baseX - d - w > 1) { poleX = baseX - d; flagDir = -1; }
        else poleX = -1;
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
        clusterSeg = new int[clus.size()];
        for (int k = 0; k < clus.size(); k++) clusterSeg[k] = (int) clus.get(k)[3];
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
            FLY = 40, FLY_GLOW = 41, NOTE = 42, SPROUT = 43, RAIN = 44, FLASH = 45, PUDDLE = 46, PUDDLE_HI = 47,
            RB0 = 48, FLAME = 54, FLAME_CORE = 55, BAMBOO = 56, BAMBOO_D = 57, CLAY = 58, CLAY_D = 59, XL0 = 60,
            LANT = 64, LANT_HI = 65, GOLD = 66, POLE = 67, FL_RED = 68, FL_WHITE = 69, FL_BLUE = 70, FL_YEL = 71,
            SF0 = 72, SHADE0 = 75, CLOUD = 85, CLOUD_SH = 86, MIST = 87, BLOS = 88, FRUIT0 = 89,
            SQ_BODY = 93, SQ_TAIL = 94, SQ_BELLY = 95, SQ_STRIPE = 96, SQ_EYE = 97, SQ_FRUIT = 98,
            BUL_BACK = 99, BUL_HEAD = 100, BUL_BELLY = 101, BUL_VENT = 102, BF0 = 103, BF_K = 105,
            SN_SHELL = 106, SN_SHELL_D = 107, SN_BODY = 108, SN_TRAIL = 109, CAT0 = 110, CAT1 = 111,
            CAT_EYE = 112, OWL = 113, OWL_L = 114, OWL_EYE = 115, DF_BODY = 116, DF_WING = 117,
            DF_HEAD = 118, N_PAL = 119;
    /** The rainbow's bands, outside in. */
    private static final int[] RAINBOW = {0xE05050, 0xF09040, 0xF0D850, 0x70C060, 0x5090E0, 0x9070D0};
    private final int[] pal = new int[N_PAL];
    private int colorKey = Integer.MIN_VALUE;
    private float dusk; // 0 day sky .. 1 night sky

    private boolean updateColors() {
        int key = prefs.background * 1000000 + prefs.style * 100000 + prefs.theme * 10000
                + prefs.brightness * 1000 + Math.round(prefs.warmth * 20) * 20 + Math.round(dusk * 10);
        key = key * 20 + Math.round(storm * 4) * 4 + rainbow;
        if (key == colorKey) return false;
        colorKey = key;
        int st = prefs.style;
        float sf = st == 0 ? Prefs.BACKGROUND_F[prefs.background] : st == 1 ? 0.06f : 0f; // sky
        float tf = st == 0 ? 0.75f : st == 1 ? 0.82f : 1f;   // tree, flowers
        float gf = st == 0 ? 0.45f : st == 1 ? 0.6f : 1f;    // ground
        float gloom = 1 - 0.25f * storm;                       // a storm darkens everything a little
        tf *= gloom;
        gf *= gloom;
        int skyRgb = mixRgb(0x6A9AC8, 0x1A2850, dusk);
        if (storm > 0) skyRgb = mixRgb(skyRgb, 0x2E343C, storm * 0.8f);
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
        pal[RAIN] = prefs.color(0x9AB0C8, 0.55f);
        pal[FLASH] = prefs.color(0x8A90A8, st == 2 ? 0.35f : 0.6f);
        pal[PUDDLE] = prefs.color(mixRgb(0x7898B8, skyRgb, 0.35f), Math.min(1f, gf + 0.3f));
        pal[PUDDLE_HI] = prefs.color(0xC0D4E8, Math.min(1f, gf + 0.35f));
        for (int k = 0; k < 6; k++) pal[RB0 + k] = mixArgb(pal[SKY], prefs.color(RAINBOW[k], 1f), 0.11f * rainbow);
        pal[FLAME] = prefs.color(0xFFC840, 1f);
        pal[FLAME_CORE] = prefs.color(0xFF7A20, 1f);
        pal[BAMBOO] = prefs.color(0xC8A860, tf);
        pal[BAMBOO_D] = prefs.color(0x7A6234, tf);
        pal[CLAY] = prefs.color(0xB0582A, tf);
        pal[CLAY_D] = prefs.color(0x7A3818, tf);
        pal[XL0] = prefs.color(0xFF4040, 1f);
        pal[XL0 + 1] = prefs.color(0xFFD040, 1f);
        pal[XL0 + 2] = prefs.color(0x50A0FF, 1f);
        pal[XL0 + 3] = prefs.color(0xF0F0F0, 1f);
        pal[LANT] = prefs.color(0xD02020, tf + 0.1f);
        pal[LANT_HI] = prefs.color(0xF05040, tf + 0.1f);
        pal[GOLD] = prefs.color(0xE8B830, tf + 0.1f);
        pal[POLE] = prefs.color(0xB8B8B0, tf);
        pal[FL_RED] = prefs.color(0xCC0001, tf + 0.1f);
        pal[FL_WHITE] = prefs.color(0xF4F4F4, tf + 0.1f);
        pal[FL_BLUE] = prefs.color(0x010066, tf + 0.25f);
        pal[FL_YEL] = prefs.color(0xFFCC00, tf + 0.1f);
        pal[SF0] = prefs.color(0xE83040, tf);      // hibiscus red
        pal[SF0 + 1] = prefs.color(0xE860B0, tf);  // bougainvillea pink
        pal[SF0 + 2] = prefs.color(0xF4D040, tf);  // allamanda yellow
        lifeColors(st, sf, tf, gf);
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

    private static int mixArgb(int a, int b, float t) {
        return 0xFF000000 | mixRgb(a & 0xFFFFFF, b & 0xFFFFFF, t);
    }

    // ---- what each still cell looks like ----

    private int baseColor(int x, int y) {
        int i = y * cols + x;
        if (text[i] != 0) return pal[TEXT];
        if (planter[i] != 0) return pal[PLANTER];
        if (flower[i] != 0) return pal[flower[i]];
        int sn = snailAt(x, y);
        if (sn >= 0) return pal[sn];
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
            if (Math.abs(x - baseX) <= puddleHalf(d)) return pal[(hash(x * 7 + d) & 15) == 0 ? PUDDLE_HI : PUDDLE];
            if (d == 0 && trailAt(x)) return pal[SN_TRAIL];
            int h = hash(i);
            int sh = shade[x] == 0 ? -1 : SHADE0 + (shade[x] - 1) * 5;
            if (d < 2 * unit) {
                int k = (h >>> 30) == 0 ? 1 : 0;
                return pal[sh < 0 ? GRASS0 + k : sh + k];
            }
            int k = (h >>> 28) % 3;
            return pal[sh < 0 ? SOIL0 + k : sh + 2 + k];
        }
        if (y >= gy - blade[x] && (x & 1) == 0 && Math.abs(x - baseX) > puddleHalf(0)) {
            return pal[shade[x] == 0 ? GRASS0 + 1 : SHADE0 + (shade[x] - 1) * 5 + 1];
        }
        int cl = cloudAt(x, y);
        if (cl != 0) return pal[cl];
        if (sky[i] != 0) return sky[i];
        if (rainbow > 0) {
            int dx = x - rbX, dy = y - rbY;
            int d2 = dx * dx + dy * dy, in = rbOut - 6 * rbBand;
            if (d2 < rbOut * rbOut && d2 >= in * in) {
                int b = (int) ((rbOut - Math.sqrt(d2)) / rbBand);
                return pal[RB0 + Math.max(0, Math.min(5, b))];
            }
        }
        if (evening && star[i] != 0 && storm == 0) return pal[STAR];
        return pal[SKY];
    }

    /** Half the puddle's width `d` rows down from the top of the ground (-1: none). */
    private int puddleHalf(int d) {
        if (puddle <= 0 || d > unit) return -1;
        return Math.round(16 * unit * puddle / (float) PUDDLE_STEPS) - d * d * 2;
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
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) base[y * cols + x] = stillColor(x, y);
        fullDirty = true;
    }

    private void composeRect(int x0, int y0, int w, int h) {
        int x1 = Math.min(cols, x0 + w), y1 = Math.min(rows, y0 + h);
        for (int y = Math.max(0, y0); y < y1; y++) for (int x = Math.max(0, x0); x < x1; x++) {
            base[y * cols + x] = stillColor(x, y);
        }
    }

    /** A still cell, with the morning mist over the lower garden (not over the clock). */
    private int stillColor(int x, int y) {
        int c = baseColor(x, y);
        if (mistLevel > 0 && y > mistTop) {
            int i = y * cols + x;
            if (text[i] != 0 || planter[i] != 0) return c;
            float a = mistLevel / 5f * 0.55f * Math.min(1f, (y - mistTop) / (float) Math.max(1, groundTop - mistTop));
            c = mixArgb(c, pal[MIST], a);
        }
        return c;
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

    /**
     * Sun by day, moon (tonight's phase) by night, along an arc (SunMoon, shared with the other
     * scenes); recomputed once a minute. Hidden behind the clouds in a storm.
     */
    private boolean updateSky(Calendar c) {
        int m = minuteOfDay(c);
        int[] tt = SunMoon.times(prefs, c);
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
        boolean show = prefs.sunMoon && storm < 0.5f;
        int key = show ? m + colorKey * 1441 : -2;
        if (key == skyKey && !changed) return false;
        skyKey = key;
        for (int k = 0; k < nSky; k++) sky[skyCells[k]] = 0;
        nSky = 0;
        if (!show) return true;
        int max = SunMoon.maxCells(rows);
        if (skyCells.length < max) { skyCells = new int[max]; skyColors = new int[max]; }
        nSky = SunMoon.draw(prefs, c, cols, rows, groundTop, Math.round(rows * 0.1f), skyCells, skyColors);
        for (int k = 0; k < nSky; k++) sky[skyCells[k]] = skyColors[k];
        return true;
    }

    private int[] skyColors = new int[0];

    // ---- weather ----

    /**
     * The storm now (the kampung's timing: three minutes darkening, rain for stormLen minutes,
     * three clearing), the puddle at the roots (spreads in the rain, dries over DRY_MIN
     * minutes) and the rainbow after a daytime storm (in and out over a few minutes).
     */
    private void weather(Calendar c, boolean animate) {
        float st = 0;
        int pd = 0, rb = 0;
        if (prefs.kampungWeather && stormFrom >= 0 && demoStart < 0 && dropStart < 0) {
            float into = msOfDay(c) / 60_000f - stormFrom;
            if (into >= -3 && into < stormLen + 3) {
                st = into < 0 ? (into + 3) / 3f : into > stormLen ? (stormLen + 3 - into) / 3f : 1f;
                st = Math.round(Math.max(0f, Math.min(1f, st)) * 4) / 4f;
            }
            int im = minuteOfDay(c) - stormFrom;
            if (im >= -1 && im < stormLen) pd = 1 + (im + 1) * (PUDDLE_STEPS - 1) / (stormLen + 1);
            else if (im >= stormLen) pd = PUDDLE_STEPS - (im - stormLen) * PUDDLE_STEPS / DRY_MIN;
            pd = Math.max(0, Math.min(PUDDLE_STEPS, pd));
            int j = im - stormLen - 2;
            if (j >= 0 && j <= 8 && SunMoon.isDay(prefs, c)) {
                rb = Math.max(0, Math.min(3, Math.min(j + 1, 8 - j)));
                if (rb > 0 && rainbow == 0) {
                    // Opposite the sun as the storm ends; a big arc rising from below the ground.
                    int[] tt = SunMoon.times(prefs, c);
                    float p = Math.max(0f, Math.min(1f, (stormFrom + stormLen - tt[0]) / (float) Math.max(1, tt[1] - tt[0])));
                    rbX = Math.round(cols * (1 - (0.06f + 0.88f * p)));
                    rbY = groundTop + Math.round(rows * 0.12f);
                    rbOut = Math.round(rows * 0.62f);
                    rbBand = Math.max(1, unit);
                }
            }
        }
        storm = st;
        puddle = pd;
        rainbow = rb;
        raining = storm >= 0.75f && animate;
    }

    /** Rain streaks fall through the air and stop at the first thing they meet. */
    private void rainTick(int ticks) {
        int want = raining ? Math.round(MAX_DROPS * Math.min(1f, storm) * Math.min(1f, cols / 480f)) : 0;
        int skyC = pal[SKY];
        while (nDrops < want) {
            dX[nDrops] = rnd.nextInt(cols);
            dY[nDrops] = -rnd.nextInt(Math.max(1, groundTop));
            nDrops++;
        }
        for (int k = 0; k < nDrops; k++) {
            dY[k] += 3.2f * sc * ticks;
            dX[k] -= 0.6f * sc * ticks;
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            boolean land = x < 0 || (x < cols && (y >= groundY[x] || (y >= 0 && base[y * cols + x] != skyC && cloudAt(x, y) == 0)));
            if (!land) continue;
            if (k < want) { dX[k] = rnd.nextInt(cols + Math.round(20 * sc)); dY[k] = -rnd.nextInt(Math.max(1, Math.round(30 * sc))); }
            else { nDrops--; dX[k] = dX[nDrops]; dY[k] = dY[nDrops]; k--; }
        }
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
        long oldGrow = growStart;
        int oldPuddle = puddle;
        float oldStorm = storm;
        weather(c, animate);
        boolean recompose = updateColors() || puddle != oldPuddle;
        if ((storm >= 0.5f) != (oldStorm >= 0.5f)) skyKey = Integer.MIN_VALUE; // sun behind the clouds
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
            layoutText(c, wall, shownKey.isEmpty() || gap, animate);
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
        // Once a minute (and when the grow-in ends): the tree's shadow and the morning mist.
        long msDay = msOfDay(c);
        if (minute != lastLifeMinute || gap || (oldGrow >= 0 && growStart < 0)) {
            if (animate && !gap && lastLifeMinute >= 0) {
                for (int k = 0; k < nFruit; k++) if (frDrop[k] == minute && aStep == 0) {
                    for (int n = 0; n < 3; n++) addParticle(frX[k] + n % 2, frY[k] + n / 2, 0, 0.3f, FRUIT0 + 3, n);
                }
            }
            lastLifeMinute = minute;
            if (updateShadow(c)) recompose = true;
            if (updateMist(c)) recompose = true;
        }
        if (stepVisitors(c, t, msDay, animate, gap)) lastExtrasMinute = -1;   // a fruit was picked
        if (minute != lastExtrasMinute || treeChanged) {
            lastExtrasMinute = minute;
            if (buildExtras(c)) recompose = true;
        }
        if (animate && aStep > oldA && oldG >= 0) {
            long dur = demoStart >= 0 ? DEMO_MS : dropStart >= 0 ? DROP_MS
                    : Prefs.VACUUM_MIN[prefs.gardenAutumn] * 60_000L;
            dropLeaves(oldA, aStep, dur);
        }
        // Clouds and the snail creep a cell every few seconds; while nothing is animated (night,
        // animation off) they wait for the minute's recompose instead.
        if (animate || recompose) {
            moveClouds(msDay, recompose);
            moveSnail(msDay, recompose);
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
        if (ticks > 0) rainTick(ticks);
        if (!animate) { nP = 0; beeStart = -1; nDrops = 0; }

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
        if (nf > 0 && flyP == null) {
            // Each firefly's path and blink, worked out once (not every frame).
            flyP = new double[7][6];
            for (int k = 0; k < 7; k++) {
                Random r = new Random(day * 97L + k);
                double[] f = flyP[k];
                f[0] = treeBox[0] + r.nextFloat() * treeBox[2];
                f[1] = treeBox[1] + treeBox[3] * (0.2f + 0.75f * r.nextFloat());
                f[2] = r.nextDouble() * 6.283; f[3] = 5200 + r.nextInt(3000); f[4] = 3900 + r.nextInt(2500);
                f[5] = r.nextInt(4000) * 10_000L + (3000 + r.nextInt(2500));
            }
        }
        for (int k = 0; k < nf; k++) {
            double[] f = flyP[k];
            fx[k] = (int) Math.round(f[0] + Math.sin(t / f[3] + f[2]) * 16 * sc);
            fy[k] = (int) Math.round(f[1] + Math.sin(t / f[4] + f[2] * 2) * 9 * sc);
            long blink = (long) f[5];
            long cyc = (t + blink / 10_000) % (blink % 10_000);
            fon[k] = cyc < 1100;
            addCur(fx[k] - 1, fy[k] - 1, 3, 3);
        }
        // The stats bird (and its line).
        int birdX = 0, birdY = 0;
        boolean bird = birdUpdate(c, t, animate);
        float bp = bird ? (t - birdStart) / (float) BIRD_MS : 0f;
        // Its line types in while the bird passes the middle, then un-types.
        int vis = bird ? TypedLine.visible(noteChars.length, t - birdStart - BIRD_MS / 4, BIRD_MS / 2) : 0;
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
        if (vis != noteVis) addCur(noteX0, noteY0, noteW, noteH); // only when a letter comes or goes
        noteVis = vis;
        // Rain: a small rectangle per streak.
        int streak = 2 * unit;
        for (int k = 0; k < nDrops; k++) {
            addCur(Math.round(dX[k]), Math.round(dY[k]) - streak + 1, streak / 3 + 1, streak);
        }
        // Lightning: one brief flash, about a third of the way into the rain.
        boolean flash = false;
        if (raining && nDrops > 0) {
            float dtS = (ms / 60_000f - stormFrom - stormLen * 0.3f) * 60f;
            flash = dtS >= 0 && dtS < 0.2f;
        }
        if (nP > 0) {
            int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
            for (int k = 0; k < nP; k++) {
                int x = Math.round(pX[k]), y = Math.round(pY[k]);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1);
            }
            addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        }

        placeLife(t, animate);

        if (nPrev + nCur > dirty.length) fullDirty = true;
        if (fullDirty || flash || flashPrev) {
            System.arraycopy(base, 0, px, 0, px.length);
            if (flash || flashPrev) fullDirty = true;
        } else {
            restore(prev, nPrev);
            restore(cur, nCur);
        }
        flashPrev = flash;
        int skyC = pal[SKY];
        if (flash) {
            int fc = pal[FLASH];
            for (int i = 0; i < px.length; i++) if (base[i] == skyC) px[i] = fc;
        }
        if (nDrops > 0) {
            int rc = pal[RAIN];
            for (int k = 0; k < nDrops; k++) {
                int x = Math.round(dX[k]), y = Math.round(dY[k]);
                for (int j = 0; j < streak; j++) {
                    int yy = y - j, xx = x + j / 3;
                    if (xx < 0 || yy < 0 || xx >= cols || yy >= rows) continue;
                    int i = yy * cols + xx;
                    if (base[i] == skyC) px[i] = rc;
                }
            }
        }

        drawLife(t);
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
        for (int k = 0; k < vis; k++) for (int i : noteChars[k]) px[i] = pal[NOTE];

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

    /** True while something needs smooth frames (petals, leaves, the bee, grow-in, autumn, rain). */
    boolean busy() {
        return nP > 0 || beeStart >= 0 || growStart >= 0 || autumn || nDrops > 0;
    }

    /** Busy only because of rain: streaks look the same at ~9 fps (saves power in storms). */
    private double[][] flyP;   // fireflies: base x, y, phase, two periods, blink

    boolean rainOnly() {
        return nDrops > 0 && nP == 0 && beeStart < 0 && growStart < 0 && !autumn;
    }

    /** True while something moves slowly (ladybug, fireflies, the bird): 8 fps is enough. */
    boolean moving() {
        // The stats line types in even without the bird (critters off), so it counts too.
        return (prefs.gardenCritters && (nPath > 1 || afterSunset || lifeMoving)) || birdStart >= 0;
    }

    /** The bird crosses fast (about 12 cells a second): it needs 8 fps; the rest is slow. */
    boolean fastMoving() { return prefs.gardenCritters && (birdStart >= 0 || lifeFast); }

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

    // ---- more life: the tree's shadow, clouds, mist, fruit and visitors ----
    //
    // Power: the shadow, mist, fruit, owl, clouds and snail are part of the still layer. The
    // shadow and mist change once a minute (with the minute's recompose); a cloud or the snail
    // moves a cell every few seconds and only its own box is recomposed then. The butterflies,
    // dragonflies and the cat move slowly enough for the 4 fps the ladybug already needs; only
    // the squirrel's dashes and the bulbul's flights ask for 8 fps, a minute or two an hour.

    private int lastLifeMinute = -1;
    private boolean lifeMoving, lifeFast;     // something of these moving now (slow / fast)

    /** Growth at minute m of the day (the tree's plan, for things planned ahead). */
    private static int gAt(int m) { return m * G_FULL / 1440; }

    private void planLife(Random r) {
        planClouds(r);
        mistDay = r.nextFloat() < 0.45f;
        mistTop = groundTop - Math.round(rows * 0.3f);
        planFruit(r);
        planVisits(r);
        catDay = r.nextFloat() < 0.6f;
        catType = r.nextInt(3);
        catFrom = 600 + r.nextInt(90);
        catTo = 960 + r.nextInt(80);
        planOwl();
    }

    private void lifeColors(int st, float sf, float tf, float gf) {
        // The shadow: the ground's colours, darker (two steps, for a softer edge).
        int[] gr = {GRASS0, GRASS0 + 1, SOIL0, SOIL0 + 1, SOIL0 + 2};
        for (int l = 0; l < 2; l++) for (int k = 0; k < 5; k++) {
            pal[SHADE0 + l * 5 + k] = mixArgb(pal[gr[k]], 0xFF080C06, l == 0 ? 0.22f : 0.42f);
        }
        float cf = st == 0 ? Math.min(1f, sf + 0.3f) : st == 1 ? 0.16f : 0.22f;
        int cl = mixRgb(mixRgb(0xF4F6FA, 0x56607A, dusk), 0x585C64, storm * 0.85f);
        pal[CLOUD] = prefs.color(cl, cf);
        pal[CLOUD_SH] = prefs.color(mixRgb(cl, 0x7A84A0, 0.45f), cf);
        pal[MIST] = prefs.color(0xDCE2E8, st == 0 ? 0.9f : st == 1 ? 0.35f : 0.45f);
        pal[BLOS] = prefs.color(0xF6E0EA, tf);
        int[] fr = fruitType == 0 ? new int[]{0x5A9A3A, 0xB8B83C, 0xE07A2A, 0xCC2A2A}
                : new int[]{0x4E8A30, 0x9AB040, 0xE8C030, 0xF09030};
        for (int k = 0; k < 4; k++) pal[FRUIT0 + k] = prefs.color(fr[k], tf);
        pal[SQ_BODY] = prefs.color(0x7A5A3C, 1f);
        pal[SQ_TAIL] = prefs.color(0x5E4630, 1f);
        pal[SQ_BELLY] = prefs.color(0xC0602E, 1f);
        pal[SQ_STRIPE] = prefs.color(0xE8DCC0, 1f);
        pal[SQ_EYE] = prefs.color(0x101010, 1f);
        pal[SQ_FRUIT] = pal[FRUIT0 + 3];
        pal[BUL_BACK] = prefs.color(0x7A6A4E, 1f);
        pal[BUL_HEAD] = prefs.color(0x2A2620, 1f);
        pal[BUL_BELLY] = prefs.color(0xE8E2D2, 1f);
        pal[BUL_VENT] = prefs.color(0xF2CC30, 1f);
        pal[BF0] = prefs.color(0xF08A28, 1f);       // a common tiger
        pal[BF0 + 1] = prefs.color(0xF4E070, 1f);   // a grass yellow
        pal[BF_K] = prefs.color(0x1A1612, 1f);
        pal[SN_SHELL] = prefs.color(0xC48A4A, gf + 0.25f);
        pal[SN_SHELL_D] = prefs.color(0x7A4A26, gf + 0.25f);
        pal[SN_BODY] = prefs.color(0xC8BCA0, gf + 0.25f);
        pal[SN_TRAIL] = prefs.color(0xB8C4CC, gf + 0.15f);
        int[][] cats = {{0xE0902A, 0xB0601A}, {0x2A2A2C, 0xF0F0EC}, {0x9A9690, 0x5E5A56}};
        pal[CAT0] = prefs.color(cats[catType][0], 1f);
        pal[CAT1] = prefs.color(cats[catType][1], 1f);
        pal[CAT_EYE] = prefs.color(catType == 1 ? 0x8A8A80 : 0x2A2018, 1f);
        pal[OWL] = prefs.color(0x6A5038, tf + 0.1f);
        pal[OWL_L] = prefs.color(0xC8B08A, tf + 0.1f);
        pal[OWL_EYE] = prefs.color(0xF0C020, 1f);
        pal[DF_BODY] = prefs.color(0x2A7AC8, 1f);
        pal[DF_WING] = prefs.color(0xD8EAF4, 0.75f);
        pal[DF_HEAD] = prefs.color(0x1E5A3A, 1f);
    }

    // ---- the shadow: a sundial ----

    private final byte[] shade;               // per column: 0 none, 1 light, 2 full shadow
    private int shadeKey = Integer.MIN_VALUE;
    private int shadeMid = -1;                // middle of the full shadow (-1: none)

    /**
     * The tree's shadow on the ground for the sun now: every grown pixel of it projected along
     * the sun's rays (long and to the west in the morning, under the tree at noon, long to the
     * east in the evening). None at night or under storm clouds. True if it changed.
     */
    private boolean updateShadow(Calendar c) {
        int m = minuteOfDay(c);
        int[] tt = SunMoon.times(prefs, c);
        float p = (m - tt[0]) / (float) Math.max(1, tt[1] - tt[0]);
        boolean on = p > 0.01f && p < 0.99f && storm < 0.5f && g > 0 && demoStart < 0 && dropStart < 0;
        int key = on ? (m * 2 + (aStep > 0 ? 1 : 0)) * 20_000 + g : -1;
        if (key == shadeKey) return false;
        shadeKey = key;
        java.util.Arrays.fill(shade, (byte) 0);
        shadeMid = -1;
        if (!on) return true;
        double el = Math.PI * p;               // the sun's height (it rises on the left)
        float lean = (float) Math.max(-2.4, Math.min(2.4, Math.cos(el) / Math.max(0.3, Math.sin(el)))) * 0.75f;
        int[] cnt = new int[cols];
        int x0 = treeBox[0], y0 = treeBox[1], x1 = x0 + treeBox[2], y1 = y0 + treeBox[3];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int i = y * cols + x;
            if (!(barkT[i] <= g || (leafT[i] <= g && leafColor(i) >= 0))) continue;
            int h = groundY[x] - y;
            if (h < 0) continue;
            int xs = x + Math.round(h * lean);
            if (xs >= 0 && xs < cols) cnt[xs]++;
        }
        boolean low = p < 0.07f || p > 0.93f;  // faint near sunrise and sunset
        long sum = 0, n = 0;
        for (int x = 0; x < cols; x++) {
            int lv = cnt[x] >= 3 ? 2 : cnt[x] >= 1 ? 1 : 0;
            if (low) lv = Math.min(lv, 1);
            shade[x] = (byte) lv;
            if (cnt[x] >= 3) { sum += x; n++; }
        }
        for (int x = 1; x + 1 < cols; x++) if (shade[x] == 0 && shade[x - 1] > 0 && shade[x + 1] > 0) shade[x] = 1;
        if (n > 0 && !low) shadeMid = (int) (sum / n);
        return true;
    }

    // ---- clouds ----

    private static final int MAX_CLOUDS = 4;
    private int nClouds, windDir;
    private final boolean[][] cMask = new boolean[MAX_CLOUDS][];
    private final int[] cW = new int[MAX_CLOUDS], cH = new int[MAX_CLOUDS], cY = new int[MAX_CLOUDS],
            cX = new int[MAX_CLOUDS], cPhase = new int[MAX_CLOUDS];
    private final long[] cMsPerCell = new long[MAX_CLOUDS];

    /** Two to four clouds a day, each crossing the sky in 20-40 minutes, all with the wind. */
    private void planClouds(Random r) {
        nClouds = 2 + r.nextInt(3);
        windDir = r.nextBoolean() ? 1 : -1;
        for (int k = 0; k < nClouds; k++) {
            int w = Math.max(10, Math.round((34 + r.nextInt(40)) * sc)), h = Math.max(4, Math.round((9 + r.nextInt(6)) * sc));
            boolean[] m = new boolean[w * h];
            int nb = 3 + r.nextInt(3);
            float[][] b = new float[nb][];
            for (int j = 0; j < nb; j++) {
                // Round puffs along a flat base, the middle ones biggest.
                float mid = 1f - Math.abs(j - (nb - 1) / 2f) / Math.max(1f, nb / 2f);
                float br = h * (0.3f + 0.25f * mid + 0.15f * r.nextFloat());
                float bx = w * (0.16f + 0.68f * j / Math.max(1, nb - 1)) + (r.nextFloat() - 0.5f) * w * 0.06f;
                b[j] = new float[]{bx, h - br * 0.85f, br};
            }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                for (float[] q : b) {
                    float dx = (x - q[0]) / (q[2] * 1.25f), dy = (y - q[1]) / q[2];
                    if (dx * dx + dy * dy <= 1f) { m[y * w + x] = true; break; }
                }
            }
            cMask[k] = m;
            cW[k] = w;
            cH[k] = h;
            cY[k] = Math.round(rows * (0.11f + 0.27f * r.nextFloat()));
            cMsPerCell[k] = Math.max(200, (20 + r.nextInt(20)) * 60_000L / (cols + w));
            cPhase[k] = r.nextInt(cols + w);
            cX[k] = Integer.MIN_VALUE;
        }
    }

    private int cloudLeft(int k, long msDay) {
        int span = cols + cW[k];
        int pos = (int) ((msDay / cMsPerCell[k] + cPhase[k]) % span);
        return windDir > 0 ? pos - cW[k] : cols - pos;
    }

    /** The cloud colour at (x, y), or 0. */
    private int cloudAt(int x, int y) {
        for (int k = 0; k < nClouds; k++) {
            int dx = x - cX[k], dy = y - cY[k];
            if (dx < 0 || dy < 0 || dx >= cW[k] || dy >= cH[k] || !cMask[k][dy * cW[k] + dx]) continue;
            return dy >= cH[k] - Math.max(1, cH[k] / 4) ? CLOUD_SH : CLOUD;
        }
        return 0;
    }

    /** Clouds to now; where one moved, only its box is recomposed (unless all of it is). */
    private void moveClouds(long msDay, boolean recompose) {
        for (int k = 0; k < nClouds; k++) {
            int nx = cloudLeft(k, msDay), old = cX[k];
            if (nx == old) continue;
            cX[k] = nx;
            if (recompose || old == Integer.MIN_VALUE) continue;
            int a = Math.min(old, nx), b = Math.max(old, nx) + cW[k];
            composeRect(a, cY[k], b - a, cH[k]);
            addCur(a, cY[k], b - a, cH[k]);
        }
    }

    // ---- morning mist ----

    private boolean mistDay;
    private int mistLevel, mistTop;          // 0..5; the top of the mist

    /** Some mornings a mist lies low over the garden from before dawn and lifts by mid-morning. */
    private boolean updateMist(Calendar c) {
        int lv = 0;
        if (mistDay && demoStart < 0 && dropStart < 0) {
            int m = minuteOfDay(c), rise = SunMoon.times(prefs, c)[0];
            float f = m < rise - 120 || m > rise + 110 ? 0f : m < rise + 10 ? 1f : 1f - (m - rise - 10) / 100f;
            lv = Math.round(Math.max(0f, f) * 5);
        }
        if (lv == mistLevel) return false;
        mistLevel = lv;
        return true;
    }

    // ---- fruit ----

    private static final int MAX_FRUIT = 14, NEVER = 9999, NEVER_S = Integer.MAX_VALUE;
    private int fruitType, nFruit;                // 0 rambutan, 1 mango
    private final int[] frX = new int[MAX_FRUIT], frY = new int[MAX_FRUIT], frSeg = new int[MAX_FRUIT],
            frBloom = new int[MAX_FRUIT], frSet = new int[MAX_FRUIT], frRipe = new int[MAX_FRUIT],
            frEaten = new int[MAX_FRUIT], frDrop = new int[MAX_FRUIT], frRoll = new int[MAX_FRUIT];
    private int takenFruit = -1;                  // picked by the squirrel on this visit
    private int visitFruit = -1;                  // the fruit the squirrel out now has come for
    private int lifeSec;                          // second of the day (for the fruit)

    /**
     * Fruit for the day (rambutan or mango): spots under the leaves grown by 17:00 (the crown
     * fills in through the afternoon). Each blossoms half an hour or so after its leaves (from
     * noon), sets fruit an hour later, and ripens over the next few hours (green, yellow,
     * orange, red by evening). Some ripe ones drop; the squirrel takes others.
     */
    private void planFruit(Random r) {
        fruitType = r.nextInt(2);
        int g10 = gAt(1020);
        List<Integer> cand = new ArrayList<>();
        for (int i : leafIdx) {
            int y = i / cols;
            if (leafT[i] > g10 || y + 1 >= rows || y > groundTop - 14 * unit) continue;
            if (leafT[i + cols] != NONE) continue;          // the underside of the leaves
            cand.add(i);
        }
        java.util.Collections.shuffle(cand, r);
        boolean[] behind = clockBoxes();
        int gap = 8 * unit;
        for (int pass = 0; pass < 2; pass++) {
            for (int i : cand) {
                if (nFruit >= (pass == 0 ? MAX_FRUIT : 6)) break;
                int x = i % cols, y = i / cols + 1;
                // Clear of the characters' boxes (whatever they show), unless that leaves too few.
                if (pass == 0 && (behind[y * cols + x] || behind[Math.min(rows - 1, y + 4 * unit) * cols + x])) continue;
                boolean near = false;
                for (int k = 0; k < nFruit && !near; k++) near = Math.abs(frX[k] - x) < gap && Math.abs(frY[k] - y) < gap;
                if (near) continue;
                int k = nFruit++;
                frX[k] = x;
                frY[k] = y;
                int cl = leafCl[i];
                frSeg[k] = cl < clusterSeg.length ? clusterSeg[cl] : 0;
                int leafMin = leafT[i] * 1440 / G_FULL;
                frBloom[k] = Math.max(leafMin + 20 + r.nextInt(40), 720 + r.nextInt(60));
                frSet[k] = frBloom[k] + 60 + r.nextInt(40);
                frRipe[k] = frSet[k] + 150 + r.nextInt(90);
                frEaten[k] = NEVER_S;           // second of the day the squirrel comes for it
                frDrop[k] = r.nextFloat() < 0.35f ? Math.min(1430, frRipe[k] + 20 + r.nextInt(200)) : NEVER;
                frRoll[k] = (r.nextInt(9) - 4) * unit;
            }
        }
    }

    /** Cells inside the clock's character boxes (as laid out for a couple of times today). */
    private boolean[] clockBoxes() {
        boolean[] m = new boolean[cols * rows];
        int[][] times = {{18, 38}, {10, 51}};
        for (int[] hm : times) {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Calendar.YEAR, day / 1000);
            c.set(Calendar.DAY_OF_YEAR, day % 1000);
            c.set(Calendar.HOUR_OF_DAY, hm[0]);
            c.set(Calendar.MINUTE, hm[1]);
            for (ClockText.Slot sl : ClockText.layout(prefs, c, cols, rows)) {
                int x0 = cols, y0 = rows, x1 = -1, y1 = -1;
                for (int[] grp : new int[][]{sl.cells, sl.lids}) for (int i : grp) {
                    x0 = Math.min(x0, i % cols); x1 = Math.max(x1, i % cols);
                    y0 = Math.min(y0, i / cols); y1 = Math.max(y1, i / cols);
                }
                for (int y = Math.max(0, y0 - 2 * unit); y <= Math.min(rows - 1, y1 + unit); y++) {
                    for (int x = Math.max(0, x0 - 2 * unit); x <= Math.min(cols - 1, x1 + 2 * unit); x++) m[y * cols + x] = true;
                }
            }
        }
        return m;
    }

    /** 0..3 from green to ripe. */
    private int ripeness(int k, int m) {
        float q = (m - frSet[k]) / (float) Math.max(1, frRipe[k] - frSet[k]);
        return Math.max(0, Math.min(3, (int) (q * 4)));
    }

    // ---- visitors: the squirrel and the bulbul (planned for the day, so a restart agrees) ----

    private static final int MAX_VISITS = 26;
    private int nSq, nBul;
    private final int[] sqStart = new int[MAX_VISITS], sqFruit = new int[MAX_VISITS], sqSeg = new int[MAX_VISITS];
    private final boolean[] sqLeft = new boolean[MAX_VISITS];
    private final int[] bulStart = new int[MAX_VISITS], bulSeg = new int[MAX_VISITS], bulStay = new int[MAX_VISITS];
    private final boolean[] bulLeft = new boolean[MAX_VISITS];

    /** A grown limb end for a visitor (depth from..to), clear of the clock if it can be; -1 none. */
    private int pickLimb(Random r, int m, int from, int to) {
        int gm = gAt(m);
        int best = -1;
        for (int tries = 0; tries < 60 && nSeg > 0; tries++) {
            int k = r.nextInt(nSeg);
            if (sDepth[k] < from || sDepth[k] > to || sT1[k] > gm) continue;
            if (sY1[k] > groundTop - 20 * unit) continue;
            best = k;
            if (!onClock(sX1[k] - 6 * unit, sY1[k] - 8 * unit, sX1[k] + 6 * unit, sY1[k] + unit)) break;
        }
        return best;
    }

    /**
     * From 07:00 to 19:00 (once the tree has limbs for them): the squirrel comes twice an hour
     * (for ripe fruit if there is some), the bulbul twice an hour; clear of the bird at :45 and
     * the bee at :50.
     */
    private void planVisits(Random r) {
        for (int h = 7; h < 19; h++) {
            for (int j = 0; j < 2 && nSq < MAX_VISITS; j++) {
                int m = h * 60 + (j == 0 ? 2 + r.nextInt(14) : 24 + r.nextInt(14));
                int fruit = -1;
                int n = 0;
                for (int k = 0; k < nFruit; k++) {
                    if (ripeness(k, m) < 2 || frEaten[k] != NEVER_S || frDrop[k] <= m || sT1[frSeg[k]] > gAt(m)) continue;
                    if (r.nextInt(++n) == 0) fruit = k;
                }
                int seg = fruit >= 0 ? frSeg[fruit] : pickLimb(r, m, 1, 4);
                if (seg < 0) continue;
                int k = nSq++;
                sqStart[k] = m * 60 + r.nextInt(20);
                if (fruit >= 0) { frEaten[fruit] = sqStart[k]; if (frDrop[fruit] > m) frDrop[fruit] = NEVER; }
                sqFruit[k] = fruit;
                sqSeg[k] = seg;
                sqLeft[k] = r.nextBoolean();
            }
            for (int j = 0; j < 2 && nBul < MAX_VISITS; j++) {
                int m = h * 60 + (j == 0 ? 10 + r.nextInt(10) : 31 + r.nextInt(10));
                int seg = pickLimb(r, m, 2, MAX_DEPTH);
                if (seg < 0) continue;
                int k = nBul++;
                bulStart[k] = m * 60 + r.nextInt(30);
                bulSeg[k] = seg;
                bulStay[k] = 30_000 + r.nextInt(40_000);
                bulLeft[k] = r.nextBoolean();
            }
        }
    }

    // The squirrel's run: points (feet), the time it reaches each (ms from the visit's start), a pose.
    private static final int SQ_RUN = 0, SQ_SIT = 1, SQ_CLIMB = 2, SQ_EAT = 3;
    private float[] tkX = new float[256], tkY = new float[256];
    private int[] tkT = new int[256];
    private byte[] tkPose = new byte[256];
    private int nTk, tkEat = -1, tkEatEnd = -1, curSq = -1;

    private void tkAdd(float x, float y, int ms, int pose) {
        if (nTk == tkX.length) {
            int n = nTk * 2;
            tkX = java.util.Arrays.copyOf(tkX, n); tkY = java.util.Arrays.copyOf(tkY, n);
            tkT = java.util.Arrays.copyOf(tkT, n); tkPose = java.util.Arrays.copyOf(tkPose, n);
        }
        tkX[nTk] = x; tkY[nTk] = y; tkT[nTk] = ms; tkPose[nTk] = (byte) pose;
        nTk++;
    }

    /** A leg from the last point to (x, y), `speed` cells a second, stopping every `every` cells. */
    private void tkLeg(float x, float y, float speed, float every, int stopMs, int pose, int stopPose) {
        float x0 = tkX[nTk - 1], y0 = tkY[nTk - 1];
        int t = tkT[nTk - 1];
        int steps = Math.max(1, Math.round((float) Math.hypot(x - x0, y - y0)));
        float since = 0;
        int dt = Math.round(1000 / speed);
        for (int s = 1; s <= steps; s++) {
            t += dt;
            float px = x0 + (x - x0) * s / steps, py = y0 + (y - y0) * s / steps;
            tkAdd(px, py, t, pose);
            since += 1;
            if (every > 0 && since >= every && s < steps - 4) {
                since = 0;
                t += stopMs;
                tkAdd(px, py, t, stopPose);
            }
        }
    }

    private void tkHold(int ms, int pose) { tkAdd(tkX[nTk - 1], tkY[nTk - 1], tkT[nTk - 1] + ms, pose); }

    /** Lays out visit k: over the grass to the trunk, up to the limb (and the fruit), back, away. */
    private void buildSquirrelRun(int k) {
        nTk = 0;
        Random r = new Random(day * 31L + k);
        boolean left = sqLeft[k];
        float off = 14 * unit;
        float gy = groundY[Math.max(0, Math.min(cols - 1, baseX))];
        float trunk = sTh.length > 0 ? sTh[0] / 2 : 2;
        float run = 24 * sc + 4, climb = 14 * sc + 3;
        tkAdd(left ? -off : cols + off, gy, 0, SQ_RUN);
        tkLeg(baseX + (left ? -1 : 1) * (trunk + 5 * unit), gy, run, 50 * sc + 10, 700 + r.nextInt(600), SQ_RUN, SQ_SIT);
        tkHold(500, SQ_SIT);
        // Up: the limbs from the root to the chosen one.
        int[] chain = new int[64];
        int n = 0;
        for (int s = sqSeg[k]; s >= 0 && n < chain.length; s = sPar[s]) chain[n++] = s;
        tkLeg(sX0[chain[n - 1]], sY0[chain[n - 1]] - unit, climb, 0, 0, SQ_CLIMB, SQ_CLIMB);
        for (int j = n - 1; j >= 0; j--) tkLeg(sX1[chain[j]], sY1[chain[j]], climb, 60 * sc + 10, 400 + r.nextInt(500), SQ_CLIMB, SQ_CLIMB);
        int fruit = sqFruit[k];
        if (fruit >= 0) tkLeg(frX[fruit], frY[fruit] + 2 * unit, climb * 0.7f, 0, 0, SQ_CLIMB, SQ_CLIMB);
        tkEat = fruit >= 0 ? tkT[nTk - 1] : -1;
        tkHold(fruit >= 0 ? 9000 + r.nextInt(4000) : 3000 + r.nextInt(3000), fruit >= 0 ? SQ_EAT : SQ_SIT);
        tkEatEnd = tkT[nTk - 1];
        // Down the same way, head first, then off (usually the way it came).
        for (int j = 0; j < n; j++) tkLeg(sX0[chain[j]], sY0[chain[j]], climb * 1.1f, 70 * sc + 10, 300 + r.nextInt(400), SQ_CLIMB, SQ_CLIMB);
        tkLeg(baseX + (left ? -1 : 1) * (trunk + 5 * unit), gy, climb, 0, 0, SQ_CLIMB, SQ_CLIMB);
        tkHold(600 + r.nextInt(900), SQ_SIT);
        boolean back = r.nextInt(3) > 0 ? left : !left;
        tkLeg(back ? -off : cols + off, gy, run, 50 * sc + 10, 600 + r.nextInt(500), SQ_RUN, SQ_SIT);
        curSq = k;
    }

    // Where the visitors are this frame.
    private boolean sqOn, bulOn;
    private int sqX, sqY, sqPose, sqFrame;
    private boolean sqFaceLeft, sqDown, sqCarry;
    private int bulX, bulY, bulMode, bulStartIdx = -1;    // 0 flying, 1 perched, 2 singing
    private boolean bulFaceLeft;

    /**
     * The visitors for now (all from the clock). True when the squirrel has just picked its
     * fruit (or a restart finds it gone), so the still layer drops it from the tree.
     */
    private boolean stepVisitors(Calendar c, long t, long msDay, boolean animate, boolean gap) {
        boolean critters = prefs.gardenCritters && animate && aStep == 0 && demoStart < 0 && dropStart < 0;
        boolean picked = false;
        // The squirrel.
        sqOn = false;
        int was = takenFruit, wasFor = visitFruit;
        takenFruit = -1;
        visitFruit = -1;
        if (critters && storm == 0) {
            int sec = (int) (msDay / 1000);
            for (int k = 0; k < nSq; k++) {
                if (sec < sqStart[k] || sec > sqStart[k] + 240) continue;
                if (curSq != k) buildSquirrelRun(k);
                int e = (int) (msDay - sqStart[k] * 1000L);
                if (e >= tkT[nTk - 1]) break;
                int j = 0;
                int lo = 0, hi = nTk - 1;
                while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (tkT[mid] <= e) lo = mid; else hi = mid - 1; }
                j = lo;
                int j2 = Math.min(nTk - 1, j + 1);
                float f = tkT[j2] > tkT[j] ? (e - tkT[j]) / (float) (tkT[j2] - tkT[j]) : 0f;
                float x = tkX[j] + (tkX[j2] - tkX[j]) * f, y = tkY[j] + (tkY[j2] - tkY[j]) * f;
                sqPose = tkPose[j2];
                boolean still = tkX[j2] == tkX[j] && tkY[j2] == tkY[j];
                if (still && sqPose == SQ_RUN) sqPose = SQ_SIT;
                // Which way: from a few points ahead.
                int ja = Math.min(nTk - 1, j + 4), jb = Math.max(0, j - 4);
                float hx = tkX[ja] - tkX[jb], hy = tkY[ja] - tkY[jb];
                if (Math.abs(hx) > 0.5f) sqFaceLeft = hx < 0;
                sqDown = hy > 0;
                if (sqPose == SQ_CLIMB && Math.abs(hx) > Math.abs(hy) * 1.3f) sqPose = SQ_RUN;   // along a limb
                sqX = Math.round(x);
                sqY = Math.round(y);
                sqFrame = j & 1;
                sqCarry = sqFruit[k] >= 0 && tkEat >= 0 && e >= tkEat && e < tkEatEnd;
                if (sqPose == SQ_EAT) sqFaceLeft = frX[sqFruit[k]] < sqX;
                visitFruit = sqFruit[k];
                if (sqFruit[k] >= 0 && tkEat >= 0 && e >= tkEat) takenFruit = sqFruit[k];
                sqOn = true;
                break;
            }
        }
        if (takenFruit != was || visitFruit != wasFor) picked = true;
        // The bulbul: flies in to a limb end, hops and sings a while, flies off.
        bulOn = false;
        if (critters && storm == 0) {
            int sec = (int) (msDay / 1000);
            for (int k = 0; k < nBul; k++) {
                long e = msDay - bulStart[k] * 1000L;
                long total = 2 * BUL_FLY_MS + bulStay[k];
                if (e < 0 || e >= total) continue;
                if (sec < bulStart[k]) continue;
                int s = bulSeg[k];
                float px = sX1[s], py = sY1[s];
                // A second spot a little way back along the limb, to hop to.
                float qx = sX1[s] + (sX0[s] - sX1[s]) * 0.35f, qy = sY1[s] + (sY0[s] - sY1[s]) * 0.35f;
                float sx = bulLeft[k] ? -10 * unit : cols + 10 * unit, sy = rows * 0.1f;
                if (e < BUL_FLY_MS || e >= total - BUL_FLY_MS) {
                    boolean in = e < BUL_FLY_MS;
                    float p = in ? e / (float) BUL_FLY_MS : (e - (total - BUL_FLY_MS)) / (float) BUL_FLY_MS;
                    float ax = in ? sx : px, ay = in ? sy : py, bx = in ? px : (bulLeft[k] ? cols + 10 * unit : -10 * unit), by = in ? py : sy;
                    float q = in ? 1 - (1 - p) * (1 - p) : p * p;
                    bulX = Math.round(ax + (bx - ax) * q);
                    bulY = Math.round(ay + (by - ay) * q - (float) Math.sin(Math.PI * p) * rows * 0.04f);
                    bulFaceLeft = bx < ax;
                    bulMode = 0;
                } else {
                    long pe = e - BUL_FLY_MS;
                    int hopEvery = 9000;
                    long phase = pe % hopEvery;
                    boolean atQ = (pe / hopEvery) % 2 == 1;
                    float fx = atQ ? qx : px, fy = atQ ? qy : py, ox = atQ ? px : qx, oy = atQ ? py : qy;
                    if (pe >= hopEvery && phase < 350) {       // a hop
                        float p = phase / 350f;
                        bulX = Math.round(ox + (fx - ox) * p);
                        bulY = Math.round(oy + (fy - oy) * p - (float) Math.sin(Math.PI * p) * 3 * unit);
                        bulFaceLeft = fx < ox;
                        bulMode = 1;
                    } else {
                        bulX = Math.round(fx);
                        bulY = Math.round(fy);
                        bulFaceLeft = (pe / 4000) % 3 == 1 ? !bulLeft[k] : bulLeft[k];
                        bulMode = phase > 3000 && phase < 5400 && ((phase / 300) & 1) == 0 ? 2 : 1;   // a song
                    }
                }
                bulOn = true;
                break;
            }
        }
        lifeFast = (sqOn && (sqPose == SQ_RUN || sqPose == SQ_CLIMB)) || (bulOn && bulMode == 0);
        stepSmallLife(c, t, critters, gap);
        lifeMoving = sqOn || bulOn || bfOn || dfOn || catState != 0;
        return picked;
    }

    private static final int BUL_FLY_MS = 2600;

    // ---- butterflies, dragonflies, the cat ----

    private boolean bfOn, dfOn;
    private final float[] bfX = new float[2], bfY = new float[2], bfTX = new float[2], bfTY = new float[2];
    private final long[] bfUntil = new long[2];
    private final boolean[] bfRest = new boolean[2];
    private final float[] dfX = new float[2], dfY = new float[2], dfTX = new float[2], dfTY = new float[2];
    private final long[] dfUntil = new long[2];
    private final boolean[] dfRest = new boolean[2];
    private long smallLast = -1;

    private boolean catDay;
    private int catType, catFrom, catTo, catState;     // 0 away, 1 walking, 2 lying
    private float catX, catGoal;
    private boolean catLeaving, catFaceLeft;

    private void stepSmallLife(Calendar c, long t, boolean critters, boolean gap) {
        float dt = smallLast < 0 || gap ? 0f : Math.min(1000, t - smallLast) / 1000f;
        smallLast = t;
        int m = minuteOfDay(c);
        int[] tt = SunMoon.times(prefs, c);
        boolean day = m >= tt[0] + 30 && m < tt[1] - 20;
        // Butterflies: in the daytime, out of the rain, from flower to flower.
        boolean bf = critters && day && storm == 0 && g > 2000;
        if (bf && !bfOn) {
            for (int k = 0; k < 2; k++) {
                float[] p = flowerSpot();
                bfX[k] = p[0] + (rnd.nextFloat() - 0.5f) * 40 * sc;
                bfY[k] = p[1] - 20 * sc;
                bfRest[k] = false;
                bfTX[k] = p[0];
                bfTY[k] = p[1];
            }
        }
        bfOn = bf;
        if (bfOn) for (int k = 0; k < 2; k++) {
            if (bfRest[k]) {
                if (t < bfUntil[k]) continue;
                float[] p = flowerSpot();
                bfTX[k] = p[0];
                bfTY[k] = p[1];
                bfRest[k] = false;
            }
            if (fly(bfX, bfY, bfTX, bfTY, k, 7 * sc + 1, dt)) {
                bfRest[k] = true;
                bfUntil[k] = t + 4000 + rnd.nextInt(9000);
            }
        }
        // Dragonflies: the half hour after a storm, over the puddle; hover, dart, hover.
        int im = stormFrom >= 0 ? m - stormFrom - stormLen : -1;
        boolean df = critters && day && storm == 0 && im >= 2 && im < 40;
        if (df && !dfOn) for (int k = 0; k < 2; k++) {
            float[] p = hoverSpot();
            dfX[k] = p[0]; dfY[k] = p[1]; dfTX[k] = p[0]; dfTY[k] = p[1];
            dfRest[k] = true;
            dfUntil[k] = t + rnd.nextInt(3000);
        }
        dfOn = df;
        if (dfOn) for (int k = 0; k < 2; k++) {
            if (dfRest[k]) {
                if (t < dfUntil[k]) continue;
                float[] p = hoverSpot();
                dfTX[k] = p[0];
                dfTY[k] = p[1];
                dfRest[k] = false;
            }
            if (fly(dfX, dfY, dfTX, dfTY, k, 45 * sc + 4, dt)) {
                dfRest[k] = true;
                dfUntil[k] = t + 1500 + rnd.nextInt(3500);
            }
        }
        // The cat: some days it naps in the tree's shade from late morning, moving when the shade
        // does; it leaves in the afternoon, or when the rain comes or the shade goes.
        boolean want = critters && catDay && m >= catFrom && m < catTo && shadeMid >= 0 && storm == 0;
        float spot = catSpot();
        int cw = CAT_LIE.w * unit;
        if (catState == 0) {
            if (want && spot >= 0) {
                if (gap || dt == 0f) { catX = spot; catState = 2; }
                else { catX = spot < cols / 2 ? -cw : cols + cw; catGoal = spot; catState = 1; }
                catLeaving = false;
            }
        } else if (!want || spot < 0) {
            if (!catLeaving) {
                catLeaving = true;
                catGoal = catX < cols / 2 ? -cw - 2 : cols + cw + 2;
                catState = 1;
            }
        } else if (catState == 2 && Math.abs(spot - catX) > 12 * unit) {
            catGoal = spot;
            catState = 1;
        }
        if (catState == 1) {
            float step = (5 * sc + 1) * dt;
            catFaceLeft = catGoal < catX;
            if (Math.abs(catGoal - catX) <= step) {
                catX = catGoal;
                if (catLeaving) { catState = 0; catLeaving = false; }
                else catState = 2;
            } else catX += Math.signum(catGoal - catX) * step;
        }
        if (!critters) { catState = 0; catLeaving = false; }
    }

    /** Moves flier k toward its target; true when it gets there. */
    private boolean fly(float[] x, float[] y, float[] tx, float[] ty, int k, float speed, float dt) {
        float dx = tx[k] - x[k], dy = ty[k] - y[k];
        float d = (float) Math.hypot(dx, dy), step = speed * dt;
        if (d <= Math.max(0.6f, step)) { x[k] = tx[k]; y[k] = ty[k]; return true; }
        x[k] += dx / d * step;
        y[k] += dy / d * step;
        return false;
    }

    /** A flower to land on: one open on the digits, or a shrub in flower; else over the grass. */
    private float[] flowerSpot() {
        List<float[]> list = new ArrayList<>();
        for (int s = 0; s < spots.size(); s++) for (int[] sp : spots.get(s)) {
            if (sp[4] >= 2) list.add(new float[]{sp[0], sp[1] - FLOWER[sp[4]].length * unit});
        }
        for (int k = 0; k < shrubX.length; k++) {
            if (shrubStage[k] >= SHRUB.length) list.add(new float[]{shrubX[k], groundY[shrubX[k]] - SHRUB[SHRUB.length - 1].h * unit + unit});
        }
        if (list.isEmpty()) {
            int x = 10 + rnd.nextInt(Math.max(1, cols - 20));
            return new float[]{x, groundY[x] - (8 + rnd.nextInt(30)) * sc};
        }
        return list.get(rnd.nextInt(list.size()));
    }

    private float[] hoverSpot() {
        float x = baseX + (rnd.nextFloat() - 0.5f) * 90 * unit;
        x = Math.max(4, Math.min(cols - 10, x));
        return new float[]{x, groundTop - (8 + rnd.nextInt(34)) * sc};
    }

    /** Where the cat lies: in the full shade, beside the trunk rather than on it. */
    private float catSpot() {
        if (shadeMid < 0) return -1;
        float cw = CAT_LIE.w * unit;
        float x = shadeMid - cw / 2;
        float trunk = (sTh.length > 0 ? sTh[0] / 2 : 2) + 2 * unit;
        if (x + cw > baseX - trunk && x < baseX + trunk) {
            x = shadeMid >= baseX ? baseX + trunk : baseX - trunk - cw;
        }
        return Math.max(2, Math.min(cols - cw - 2, x));
    }

    // ---- the snail: across the grass once an hour (there and back), a minutes hand ----

    private int snX = Integer.MIN_VALUE;
    private boolean snLeft;

    private int snailLeft(long msDay) {
        int w = SNAIL.w * unit, span = cols + w;
        float a = (msDay % 3_600_000L) / 3_600_000f;
        boolean back = (msDay / 3_600_000L) % 2 == 1;
        snLeft = back;
        int x = Math.round((back ? 1 - a : a) * span) - w;
        return x;
    }

    private int snailY() {
        int x = Math.max(0, Math.min(cols - 1, snX + SNAIL.w * unit / 2));
        return groundY[x] - SNAIL.h * unit + unit;
    }

    /** The snail's palette entry at (x, y), or -1. */
    private int snailAt(int x, int y) {
        if (snX == Integer.MIN_VALUE || !prefs.gardenCritters) return -1;
        int dx = x - snX, y0 = snailY(), dy = y - y0;
        if (dx < 0 || dy < 0 || dx >= SNAIL.w * unit || dy >= SNAIL.h * unit) return -1;
        return SNAIL.at(dx / unit, dy / unit, snLeft);
    }

    /** Its silvery trail along the top of the grass behind it. */
    private boolean trailAt(int x) {
        if (snX == Integer.MIN_VALUE || !prefs.gardenCritters) return false;
        int len = 36 * unit, w = SNAIL.w * unit;
        boolean on = snLeft ? x >= snX + w && x < snX + w + len : x < snX && x >= snX - len;
        return on && (hash(x * 31 + day) & 3) != 0;
    }

    private void moveSnail(long msDay, boolean recompose) {
        if (!prefs.gardenCritters) return;
        int nx = snailLeft(msDay), old = snX;
        if (nx == old) return;
        snX = nx;
        if (recompose || old == Integer.MIN_VALUE || Math.abs(nx - old) > 4 * unit) {
            if (!recompose && old != Integer.MIN_VALUE) { composeAll(); }
            return;
        }
        int len = 36 * unit + 2, w = SNAIL.w * unit;
        int x0 = Math.min(old, nx) - len, x1 = Math.max(old, nx) + w + len;
        int y0 = groundTop - SNAIL.h * unit - 3, y1 = groundTop + 3;
        composeRect(x0, y0, x1 - x0, y1 - y0);
        addCur(x0, y0, x1 - x0, y1 - y0);
    }

    // ---- the owl: on a sturdy limb from dusk ----

    private int owlSeg = -1;

    private void planOwl() {
        int nest = nestFork();
        float best = -1e9f;
        for (int k = 0; k < nSeg; k++) {
            if (sDepth[k] < 1 || sDepth[k] > 3 || k == nest) continue;
            if (k + 1 < nSeg && sPar[k + 1] == k && sDepth[k + 1] == sDepth[k]) continue;
            float x = sX1[k], y = sY1[k];
            if (y > groundTop - 30 * unit) continue;
            boolean onText = onClock(x - 4 * unit, y - 8 * unit, x + 4 * unit, y + unit);
            boolean awayFromNest = nest < 0 || (x - baseX) * (sX1[nest] - baseX) < 0;
            float score = sTh[k] - (onText ? 100 : 0) + (awayFromNest ? 20 : 0);
            if (score > best) { best = score; owlSeg = k; }
        }
    }

    private boolean owlShown(Calendar c) {
        if (owlSeg < 0 || !prefs.gardenCritters || aStep > 0 || demoStart >= 0 || dropStart >= 0) return false;
        if (sT1[owlSeg] > g) return false;
        int m = minuteOfDay(c);
        int[] tt = SunMoon.times(prefs, c);
        return m >= tt[1] + 25 || m < tt[0] - 30;
    }

    private int owlX() { return Math.round(sX1[owlSeg]) - OWL_S.w * unit / 2; }
    private int owlY() { return Math.round(sY1[owlSeg]) - OWL_S.h * unit + unit; }

    // ---- into the still layer (with the extras) ----

    /** Changes whenever the fruit, the owl or the squirrel's pick change. */
    private long lifeKey(Calendar c) {
        int m = minuteOfDay(c);
        lifeSec = (int) (msOfDay(c) / 1000);
        long key = 7;
        for (int k = 0; k < nFruit; k++) key = key * 31 + fruitState(k, m);
        key = key * 31 + takenFruit;
        key = key * 31 + (owlShown(c) ? 1 : 0);
        return key;
    }

    /** -1 nothing, 0 bud, 1 blossom, 2 fruitlet, 3..6 fruit by ripeness, 7 on the ground. */
    private int fruitState(int k, int m) {
        if (aStep * 2 > A_STEPS) return -1;                   // autumn takes it with the leaves
        if (m >= frDrop[k]) return 7;
        // Eaten: gone once its squirrel has come, except while it climbs up to it.
        if (lifeSec >= frEaten[k] && !(k == visitFruit && takenFruit != k)) return -1;
        if (m < frBloom[k] - 40) return -1;
        if (m < frBloom[k]) return 0;
        if (m < frSet[k]) return 1;
        if (m < frSet[k] + 60) return 2;
        return 3 + ripeness(k, m);
    }

    private void stampLife(Calendar c) {
        int m = minuteOfDay(c);
        for (int k = 0; k < nFruit; k++) {
            int st = fruitState(k, m);
            if (st < 0) continue;
            if (st < 7 && leafT[frY[k] * cols - cols + frX[k]] > g) continue;   // its leaves not grown yet
            Sprite sp = st == 0 ? F_BUD : st == 1 ? F_BLOSSOM : st == 2 ? F_SMALL
                    : st == 7 ? F_FALLEN : fruitType == 0 ? F_RAMBUTAN : F_MANGO;
            int x = frX[k] - sp.w * unit / 2, y = frY[k];
            if (st == 7) {
                x = Math.max(0, Math.min(cols - sp.w * unit, x + frRoll[k]));
                y = groundY[Math.max(0, Math.min(cols - 1, x + unit))] - sp.h * unit + unit;
            }
            int ripe = st == 7 ? 3 : Math.max(0, st - 3);
            stamp(sp, x, y, FRUIT0, FRUIT0 + ripe);
        }
        if (owlShown(c)) stamp(OWL_S, owlX(), owlY());
    }

    // ---- drawing the visitors ----

    private boolean owlBlink;
    private final int[] bfDX = new int[2], bfDY = new int[2], dfDX = new int[2], dfDY = new int[2];
    private int catDX, catDY;

    private void placeLife(long t, boolean animate) {
        if (sqOn) addCur(sqX - 9 * unit, sqY - 12 * unit, 18 * unit, 18 * unit);
        if (bulOn) addCur(bulX - 6 * unit, bulY - 8 * unit, 12 * unit, 10 * unit);
        if (bfOn) for (int k = 0; k < 2; k++) {
            float bob = bfRest[k] ? 0 : (float) Math.sin(t / 260.0 + k * 2) * 2 * unit;
            bfDX[k] = Math.round(bfX[k]) - BF_OPEN.w * unit / 2;
            bfDY[k] = Math.round(bfY[k] + bob) - BF_OPEN.h * unit + unit;
            addCur(bfDX[k], bfDY[k], BF_OPEN.w * unit, BF_OPEN.h * unit);
        }
        if (dfOn) for (int k = 0; k < 2; k++) {
            float wob = dfRest[k] ? (float) Math.sin(t / 300.0 + k * 3) * unit : 0;
            dfDX[k] = Math.round(dfX[k]) - DF_A.w * unit / 2;
            dfDY[k] = Math.round(dfY[k] + wob);
            addCur(dfDX[k], dfDY[k], DF_A.w * unit, DF_A.h * unit);
        }
        if (catState != 0) {
            Sprite s = catState == 2 ? CAT_LIE : CAT_WALK_A;
            catDX = Math.round(catX);
            int foot = groundY[Math.max(0, Math.min(cols - 1, catDX + s.w * unit / 2))];
            catDY = foot - s.h * unit + unit;
            addCur(catDX, catDY - unit, s.w * unit, (s.h + 1) * unit);
        }
        // The owl blinks now and then (its eyes only; the rest of it is in the still layer).
        owlBlink = false;
        int eye = (owlY() + 2 * unit) * cols + owlX() + unit;
        if (animate && owlSeg >= 0 && eye >= 0 && eye < extra.length && extra[eye] == OWL_EYE) {
            owlBlink = t % 6100 < 280 || (t % 23_000) < 200;
            if (owlBlink) addCur(owlX(), owlY() + 2 * unit, OWL_S.w * unit, unit);
        }
    }

    private void drawLife(long t) {
        if (catState == 2) drawSpr((t / 1700) % 2 == 0 ? CAT_LIE : CAT_LIE_B, catDX, catDY, catFaceLeft, false, false, -1, -1);
        else if (catState == 1) drawSpr(((int) (catX / unit)) % 2 == 0 ? CAT_WALK_A : CAT_WALK_B, catDX, catDY, catFaceLeft, false, false, -1, -1);
        if (dfOn) for (int k = 0; k < 2; k++) drawSpr((t / 120) % 2 == 0 ? DF_A : DF_B, dfDX[k], dfDY[k], dfTX[k] < dfX[k], false, true, -1, -1);
        if (bfOn) for (int k = 0; k < 2; k++) {
            boolean open = bfRest[k] ? (t / 1100 + k) % 2 == 0 : (t / 180 + k) % 2 == 0;
            drawSpr(open ? BF_OPEN : BF_SHUT, bfDX[k], bfDY[k], false, false, true, BF0, BF0 + k);
        }
        if (sqOn) {
            Sprite s;
            boolean fy = false;
            if (sqPose == SQ_CLIMB) { s = SQ_UP; fy = sqDown; }
            else if (sqPose == SQ_EAT) s = SQ_NIBBLE;
            else if (sqPose == SQ_SIT) s = SQ_SITS;
            else s = sqFrame == 0 ? SQ_RUN_A : SQ_RUN_B;
            int w = s.w * unit, h = s.h * unit;
            int x = sqX - w / 2, y = sqPose == SQ_CLIMB ? sqY - h / 2 : sqY - h + unit;
            drawSpr(s, x, y, sqFaceLeft, fy, true, -1, -1);
        }
        if (bulOn) {
            Sprite s = bulMode == 0 ? (((t / 120) % 2 == 0) ? BUL_UP : BUL_DOWN) : bulMode == 2 ? BUL_SING : BUL_PERCH;
            int w = s.w * unit, h = s.h * unit;
            drawSpr(s, bulX - w / 2, bulMode == 0 ? bulY - h / 2 : bulY - h + unit, bulFaceLeft, false, false, -1, -1);
        }
        if (owlBlink) {
            int y = owlY() + 2 * unit;
            for (int yy = y; yy < y + unit; yy++) for (int xx = owlX(); xx < owlX() + OWL_S.w * unit; xx++) {
                if (xx >= 0 && xx < cols && yy >= 0 && yy < rows && extra[yy * cols + xx] == OWL_EYE) px[yy * cols + xx] = pal[OWL];
            }
        }
    }

    /** Draws a sprite (unit-scaled), mirrored either way, with palette entry `from` swapped for `to`. */
    private void drawSpr(Sprite s, int x, int y, boolean fx, boolean fy, boolean behindText, int from, int to) {
        for (int sy = 0; sy < s.h * unit; sy++) {
            int yy = y + sy;
            if (yy < 0 || yy >= rows) continue;
            int ry = fy ? s.h - 1 - sy / unit : sy / unit;
            for (int sx = 0; sx < s.w * unit; sx++) {
                int xx = x + sx;
                if (xx < 0 || xx >= cols) continue;
                int pi = s.at(sx / unit, ry, fx);
                if (pi < 0) continue;
                int i = yy * cols + xx;
                if (behindText && (text[i] != 0 || planter[i] != 0)) continue;
                px[i] = pal[pi == from ? to : pi];
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
        key = (key * 1_000_003L + decorState(c)) * 1_000_003L + lifeKey(c);
        if (key == extrasKey) return false;
        extrasKey = key;
        java.util.Arrays.fill(extra, (byte) 0);
        stampShrubs();
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
        stampFestival();
        stampLife(c);
        return true;
    }

    // ---- shrubs and festival things ----

    /** Things go up from 07:00, one every `every` minutes; autumn takes them down again. */
    private static int upTo(int n, int from, int every, int m, float a) {
        int up = m < from ? 0 : Math.min(n, 1 + (m - from) / every);
        if (a > 0) up = Math.min(up, (int) Math.ceil(n * (1 - Math.min(1f, a / 0.85f))));
        return up;
    }

    /**
     * Today's shrub stages and festival things for now (a pure function of the time, so a
     * restart shows the same). Returns a key that changes whenever any of them changes a step.
     */
    private long decorState(Calendar c) {
        // At midnight the old day finishes as it ended (its autumn takes everything down).
        int m = demoStart >= 0 || dropStart >= 0 ? 1439 : minuteOfDay(c);
        float a = aStep / (float) A_STEPS;
        long key = 17;
        int top = SHRUB.length;
        int wilt = a > 0 ? (int) Math.ceil(top * (1 - Math.min(1f, a / 0.85f))) : top;
        for (int k = 0; k < shrubX.length; k++) {
            int st = m < shrubFrom[k] ? 0 : Math.min(top, 1 + (m - shrubFrom[k]) / shrubStep[k]);
            shrubStage[k] = Math.min(st, wilt);
            key = key * 8 + shrubStage[k];
        }
        festShown = 0;
        festMask = 0;
        switch (fest) {
            case Festivals.RAYA:
            case Festivals.DEEPAVALI:
                festShown = upTo(5, 420, 2, m, a);
                break;
            case Festivals.XMAS:
                festShown = upTo(nLights, 420, 1, m, a);
                for (int k = 0; k < festShown; k++) {
                    int i = lightCell[k];
                    if (leafT[i] <= g && leafColor(i) >= 0) festMask |= 1 << k;
                }
                break;
            case Festivals.CNY:
                festShown = upTo(2, 420, 3, m, a);
                for (int k = 0; k < festShown; k++) if (lanX[k] >= 0 && lanT[k] <= g) festMask |= 1 << k;
                break;
            case Festivals.MERDEKA:
            case Festivals.MSIA:
                // The pole, then the flag raised up it a step a minute.
                festShown = poleX >= 0 ? upTo(6, 420, 1, m, a) : 0;
                break;
            default:
                break;
        }
        return (key * 64 + festShown) * 1_000_000_007L + festMask;
    }

    private void stampShrubs() {
        for (int k = 0; k < shrubX.length; k++) {
            int st = shrubStage[k];
            if (st <= 0) continue;
            Sprite sp = SHRUB[st - 1];
            int x = shrubX[k] - sp.w * unit / 2;
            stamp(sp, x, groundY[shrubX[k]] - sp.h * unit + unit, SF0, SF0 + shrubCol[k]);
        }
    }

    private void stampFestival() {
        if (festShown <= 0) return;
        switch (fest) {
            case Festivals.RAYA:
            case Festivals.DEEPAVALI: {
                // Lamps along the front of the bed below the tree, put out from the middle.
                Sprite sp = fest == Festivals.RAYA ? PELITA : DIYA;
                int[] order = {2, 1, 3, 0, 4};
                for (int n = 0; n < festShown; n++) {
                    int k = order[n];
                    int x = baseX + (k - 2) * 13 * unit - sp.w * unit / 2;
                    x = Math.max(1, Math.min(cols - sp.w * unit - 1, x));
                    int bottom = Math.min(rows - 2 * unit, groundY[x] + 10 * unit);
                    stamp(sp, x, bottom - sp.h * unit);
                }
                break;
            }
            case Festivals.XMAS:
                for (int k = 0; k < festShown; k++) {
                    if ((festMask & (1 << k)) == 0) continue;
                    int i = lightCell[k], x = i % cols, y = i / cols;
                    for (int yy = y; yy < y + unit && yy < rows; yy++) for (int xx = x; xx < x + unit && xx < cols; xx++) {
                        extra[yy * cols + xx] = (byte) (XL0 + k % 4);
                    }
                }
                break;
            case Festivals.CNY:
                for (int k = 0; k < 2; k++) {
                    if ((festMask & (1 << k)) == 0) continue;
                    int hx = lanX[k], hy = lanY[k] + 1;
                    int cord = 3 * unit;
                    for (int y = hy; y < hy + cord && y < rows; y++) if (hx >= 0 && hx < cols && y >= 0) extra[y * cols + hx] = (byte) NEST1;
                    stamp(LANTERN, hx - LANTERN.w * unit / 2, hy + cord);
                }
                break;
            case Festivals.MERDEKA:
            case Festivals.MSIA: {
                int gy = groundY[poleX];
                int h = Math.round(48 * sc), top = gy - h;
                for (int y = top; y < gy; y++) extra[y * cols + poleX] = (byte) POLE;
                for (int y = top - 1; y <= top; y++) for (int x = poleX; x <= poleX + 1; x++) if (x < cols && y >= 0) extra[y * cols + x] = (byte) GOLD;
                if (festShown < 2) break;
                int step = festShown - 2; // 0..4: from low on the pole to the top
                int hi = top + 2, lo = gy - FLAG_H - 6;
                int fy = lo + (hi - lo) * step / 4;
                for (int r = 0; r < FLAG_H; r++) for (int q = 0; q < FLAG_W; q++) {
                    int x = poleX + flagDir * (1 + q), y = fy + r;
                    if (x < 0 || x >= cols || y < 0 || y >= rows) continue;
                    extra[y * cols + x] = (byte) flagCell(q, r);
                }
                break;
            }
            default:
                break;
        }
    }

    /** The Jalur Gemilang, FLAG_W x FLAG_H, q from the hoist: 14 stripes, the canton with the crescent and star. */
    private static final int FLAG_W = 28, FLAG_H = 14;
    private static final String[] CANTON = {
            "..............",
            "...YY...Y.Y.Y.",
            "..YY.....YYY..",
            "..Y.....YYYYY.",
            "..Y......YYY..",
            "..YY....Y.Y.Y.",
            "...YY.........",
            "..............",
    };

    private static int flagCell(int q, int r) {
        if (r < CANTON.length && q < CANTON[0].length()) return CANTON[r].charAt(q) == 'Y' ? FL_YEL : FL_BLUE;
        return r % 2 == 0 ? FL_RED : FL_WHITE;
    }

    /** A fork low in the crown for the nest: thick enough, away from the digits if it can be. */
    private int nestFork() {
        int best = -1;
        float bestScore = -1e9f;
        for (int k = 0; k < nSeg; k++) {
            if (sDepth[k] < 1 || sDepth[k] > 2) continue;
            if (k + 1 < nSeg && sPar[k + 1] == k && sDepth[k + 1] == sDepth[k]) continue; // mid-limb
            float x = sX1[k], y = sY1[k];
            boolean onText = onClock(x - 5 * unit, y - 4 * unit, x + 5 * unit, y + unit);
            float score = sTh[k] - (onText ? 100 : 0) - sT1[k] / 2000f;
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    /**
     * True if the box overlaps the clock's whole area (whatever the digits are), so things
     * placed by it stay put all day and the same after a restart.
     */
    private boolean onClock(float x0, float y0, float x1, float y1) {
        int st = Math.max(1, Math.round(rows * 0.2f / PixelFont.H));
        int sd = Math.max(1, Math.round(rows * 0.075f / PixelFont.H));
        int top = rows / 2 - PixelFont.H * st - 6 * unit;
        int bottom = rows / 2 + Math.round(rows * 0.06f) + PixelFont.H * sd + unit;
        return y1 >= top && y0 <= bottom && x1 >= cols * 0.15f && x0 <= cols * 0.85f;
    }

    private void stamp(Sprite s, int x, int y) { stamp(s, x, y, -1, -1); }

    /** Stamps a sprite into the still layer, with palette entry `from` swapped for `to`. */
    private void stamp(Sprite s, int x, int y, int from, int to) {
        for (int sy = 0; sy < s.h * unit; sy++) for (int sx = 0; sx < s.w * unit; sx++) {
            int pi = s.at(sx / unit, sy / unit, false);
            int X = x + sx, Y = y + sy;
            if (pi < 0 || X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
            extra[Y * cols + X] = (byte) (pi == from ? to : pi);
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
            int left = TypedLine.centred(msg, s, cols);
            noteChars = TypedLine.cells(msg, left, top, s, cols, rows);
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

    /** A shrub's stages: sprout, seedling, young plant, bush, in bud, in flower. */
    private static final String SK = "gldsbfc";
    private static final int[] SI = {LEAF0 + 1, LEAF0 + 2, LEAF0, BARK1, BUD, SF0, CENTER};
    static final Sprite[] SHRUB = {
            new Sprite(SK, SI,
                    "g.g",
                    ".g."),
            new Sprite(SK, SI,
                    ".l.l.",
                    "..g..",
                    "lgg..",
                    "..g.."),
            new Sprite(SK, SI,
                    "...l...",
                    ".l.gl..",
                    ".lglgl.",
                    "lggdggl",
                    "..dsd.."),
            new Sprite(SK, SI,
                    "...lll...",
                    ".llglgll.",
                    "lglgglglg",
                    "gdgggdggd",
                    ".ddgsgdd."),
            new Sprite(SK, SI,
                    "...lbl...",
                    ".blglglb.",
                    "lglgblglg",
                    "gdgggdggd",
                    ".ddgsgdd."),
            new Sprite(SK, SI,
                    "..f.l.f..",
                    ".fcflgfcf",
                    "lgflgflgf",
                    "gdgggdgfd",
                    ".ddgsgdd."),
    };
    /** Hari Raya: a pelita, an oil lamp on a bamboo pole. */
    static final Sprite PELITA = new Sprite("yotb", new int[]{FLAME, FLAME_CORE, BAMBOO_D, BAMBOO},
            ".y.",
            "yoy",
            ".t.",
            "ttt",
            ".b.",
            ".t.",
            ".b.",
            ".b.");
    /** Deepavali: a clay diya. */
    static final Sprite DIYA = new Sprite("yocC", new int[]{FLAME, FLAME_CORE, CLAY_D, CLAY},
            "..y..",
            ".yoy.",
            "cCCCc",
            ".CCC.");
    /** Chinese New Year: a red lantern. */
    static final Sprite LANTERN = new Sprite("grh", new int[]{GOLD, LANT, LANT_HI},
            ".ggg.",
            "rrrrr",
            "rhrrr",
            "rhrrr",
            "rrrrr",
            ".ggg.",
            "..g..");

    // ---- the garden's visitors and fruit ----

    /** The plantain squirrel: brown, a pale side stripe, a rufous belly; facing right. */
    private static final String SQK = "TBLSeF";
    private static final int[] SQI = {SQ_TAIL, SQ_BODY, SQ_BELLY, SQ_STRIPE, SQ_EYE, SQ_FRUIT};
    static final Sprite SQ_RUN_A = new Sprite(SQK, SQI,
            "TTT.......",
            "T.TT...BB.",
            "...TBBBBeB",
            "...BBSSSBB",
            "....LLLLB.",
            "....B..B..");
    static final Sprite SQ_RUN_B = new Sprite(SQK, SQI,
            ".TTT......",
            "TT.T...BB.",
            "...TBBBBeB",
            "...BBSSSBB",
            "....LLLLB.",
            "...B....B.");
    static final Sprite SQ_SITS = new Sprite(SQK, SQI,
            "TT...BB.",
            "TTT.BBeB",
            ".TT.BBBB",
            "..TBBLL.",
            "..TBSLL.",
            "...TBLL.",
            "...BB.B.");
    static final Sprite SQ_NIBBLE = new Sprite(SQK, SQI,
            "TT...BB.",
            "TTT.BBeB",
            ".TT.BBBF",
            "..TBBLFF",
            "..TBSLL.",
            "...TBLL.",
            "...BB.B.");
    /** Climbing, head up (drawn upside down going down: squirrels come down head first). */
    static final Sprite SQ_UP = new Sprite(SQK, SQI,
            "..BB..",
            ".BeeB.",
            "B.BB.B",
            ".BSSB.",
            ".BLLB.",
            "B.BB.B",
            "..BB..",
            "..TT..",
            ".TTTT.",
            ".TTTT.",
            "..TT..");
    /** The yellow-vented bulbul: dark crest, brown back, pale front, yellow under the tail. */
    private static final String BUK = "KBWYbf";
    private static final int[] BUI = {BUL_HEAD, BUL_BACK, BUL_BELLY, BUL_VENT, BUL_HEAD, NEST1};
    static final Sprite BUL_PERCH = new Sprite(BUK, BUI,
            "...KK.",
            "..KKKb",
            ".BBWW.",
            "BBBWW.",
            "B.BYY.",
            "...f..");
    static final Sprite BUL_SING = new Sprite(BUK, BUI,
            "...KKb",
            "..KKK.",
            ".BBWWb",
            "BBBWW.",
            "B.BYY.",
            "...f..");
    static final Sprite BUL_UP = new Sprite("BLkW", new int[]{BUL_BACK, BUL_BELLY, BUL_HEAD, BUL_BACK},
            "...WW...",
            "....WW..",
            "BB.BBBBk",
            "..BLLLB.",
            "........");
    static final Sprite BUL_DOWN = new Sprite("BLkW", new int[]{BUL_BACK, BUL_BELLY, BUL_HEAD, BUL_BACK},
            "........",
            "........",
            "BB.BBBBk",
            "..BLWWB.",
            "....WW..");
    /** Butterflies (O = wing colour, swapped per butterfly). */
    static final Sprite BF_OPEN = new Sprite("OK", new int[]{BF0, BF_K},
            "OO.OO",
            "OOKOO",
            ".OKO.",
            "K.K.K");
    static final Sprite BF_SHUT = new Sprite("OK", new int[]{BF0, BF_K},
            "..O..",
            ".OO..",
            ".OK..",
            "..K..");
    /** A dragonfly, wings up and down. */
    static final Sprite DF_A = new Sprite("BWH", new int[]{DF_BODY, DF_WING, DF_HEAD},
            "..WWW..",
            "BBBBBBH",
            ".......");
    static final Sprite DF_B = new Sprite("BWH", new int[]{DF_BODY, DF_WING, DF_HEAD},
            ".......",
            "BBBBBBH",
            "..WWW..");
    /** A snail, facing right. */
    static final Sprite SNAIL = new Sprite("Ssbk", new int[]{SN_SHELL, SN_SHELL_D, SN_BODY, SN_SHELL_D},
            "..SSS...",
            ".SsssS.k",
            ".SsSSSbk",
            "bbbbbbb.");
    /** A cat asleep, curled up (two breaths), and walking; facing right. */
    private static final String CK = "OSe";
    private static final int[] CI = {CAT0, CAT1, CAT_EYE};
    static final Sprite CAT_LIE = new Sprite(CK, CI,
            "........O.O",
            "..OOOOO.OOO",
            ".OOSOOSOOeO",
            "SOOOOOOOOOO",
            "SSOOOOOOOO.");
    static final Sprite CAT_LIE_B = new Sprite(CK, CI,
            "........O.O",
            "...OOOO.OOO",
            ".OOSOOSOOeO",
            "SOOOOOOOOOO",
            "SSOOOOOOOO.");
    static final Sprite CAT_WALK_A = new Sprite(CK, CI,
            "S.........OO",
            "S........OOO",
            ".S.......OeO",
            "..OOSOOSOOO.",
            "..OOOOOOOO..",
            "..O.O..O.O..");
    static final Sprite CAT_WALK_B = new Sprite(CK, CI,
            ".S........OO",
            "S........OOO",
            ".S.......OeO",
            "..OOSOOSOOO.",
            "..OOOOOOOO..",
            "...O.O.O.O..");
    /** A small owl, facing out. */
    static final Sprite OWL_S = new Sprite("OLYkf", new int[]{OWL, OWL_L, OWL_EYE, BEAK, NEST1},
            "O....O",
            "OOOOOO",
            "OYOOYO",
            "OOkkOO",
            ".OLLO.",
            ".OLLO.",
            "..ff..");
    /** Fruit (F is the ripeness colour) and its blossom. */
    private static final String FK = "Fspcb";
    private static final int[] FI = {FRUIT0, BARK1, BLOS, CENTER, BUD};
    static final Sprite F_BUD = new Sprite(FK, FI, "b");
    static final Sprite F_BLOSSOM = new Sprite(FK, FI,
            ".p.",
            "pcp",
            ".p.");
    static final Sprite F_SMALL = new Sprite(FK, FI,
            ".s.",
            ".F.");
    static final Sprite F_RAMBUTAN = new Sprite(FK, FI,
            ".s.",
            "FFF",
            "FFF");
    static final Sprite F_MANGO = new Sprite(FK, FI,
            ".s.",
            ".FF",
            "FFF",
            "FF.");
    static final Sprite F_FALLEN = new Sprite(FK, FI,
            ".FF",
            "FFF");
}
