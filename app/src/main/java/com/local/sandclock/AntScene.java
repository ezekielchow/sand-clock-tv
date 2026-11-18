package com.local.sandclock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The ant farm as pixels (plain Java, no Android, so it can be tested on a desktop).
 *
 * A side view through glass: sky above, soil below. Through the day the ants dig the day's nest
 * (a new one each day, seeded by the date): oval chambers joined by wide, winding tunnels, some
 * looping back to each other, dug from the top down so it is complete just before midnight.
 * The soil they carry up piles into an anthill by the entrance, one grain every
 * SECONDS_PER_GRAIN seconds (the sand physics of GrainGrid). The clock sits in the soil and the
 * tunnels pass behind it. Worker ants walk the finished tunnels, one digs at the face, and one
 * walks the surface as the seconds hand. Before midnight it rains: the water runs in at the
 * entrance and fills the tunnels from the bottom up, then soaks away as the tunnels close, and
 * the hill washes down, ready for a new nest.
 *
 * Power: like the ocean, still things are composed into `base` and only rectangles around
 * moving things are redrawn. The hill changes every few seconds, one grain at a time.
 */
final class AntScene {
    static final int DAY_MS = 86_400_000;
    static final int GRAINS_PER_DAY = 86_400 / Stats.SECONDS_PER_GRAIN;
    private static final int DIG_IN_MS = 3500;
    static final int RAIN_MS = 8000;      // midnight with the flood set to 0 minutes
    static final long DEMO_MS = 100_000;  // "Preview end of day": 5 minutes of flood at 3x
    /** Flood: water fills the tunnels for this share of it, then soaks away for the rest. */
    private static final float FILL_PART = 0.65f;

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

    // ---- the nest (fixed plan for the day) ----
    private final int soilTop, soilBot;   // rows the nest may use
    private final int[] order;            // cells in the order they are dug
    private int nOrder;
    private final boolean[] dug;          // dug out (air)
    private final boolean[] water;        // flooded
    private final byte[] chamberOf;       // which chamber a cell belongs to (1..n), 0 = tunnel/soil
    private int dugCount;
    private int shown;                    // cells dug so far (as shown)
    private final int[] oHead;            // per dug cell: the digger's head when it dug it
    private final byte[] oDir;            // ... and the way it faced (0 right, 1 left, 2 down, 3 up)
    private int digHead, digDir;          // set while planning
    private long lastDigAt = -1;          // uptime of the last cell dug (the digger holds a crumb)
    private int lastWant = -1;            // grains dug at the last update (for the stats)
    /** Tunnel centre lines (for the workers): cell indexes, and the order index they finish at. */
    private final List<int[]> paths = new ArrayList<>();
    private final List<Integer> pathDone = new ArrayList<>();
    private final List<int[]> pathEnds = new ArrayList<>(); // {node a, node b}: path runs a -> b
    private int eggChamber, foodChamber;                  // chamber ids (1..n): eggs; the pantry
    private final int[][] chambers = new int[12][];      // {cx, cy, rx, ry}
    private final int[] chamberDone = new int[12];       // order index a chamber is finished at
    private int nChambers;
    /** Nest graph nodes: 0 the entrance, 1..nChambers the chambers, then side-tunnel ends. */
    private int nNodes;
    private final int[] nodeEdge = new int[16];          // a side end's path (else -1)
    private final byte[] patch;           // soil tone: 0 dark, 1 mid, 2 light

    // ---- clock text underground ----
    private List<ClockText.Slot> slots = new ArrayList<>();
    private final byte[] text;            // 1 wall/edge
    private final boolean[] hollow;       // a letter's inside
    private final boolean[] textEdge;     // just outside a letter (a dark edge over tunnels)
    private String shownKey = "";
    final int[] textPx;
    boolean textChanged = true;
    private final int textTop;

    // ---- sky ----
    private final int[] skyCol;           // sun/moon colour over an empty sky cell, 0 none
    private int[] skyCells = new int[0];
    int nSky;
    private int skyKey = -1;
    private final int[] noteCol;          // the hourly line
    private final int[] decor;            // festival decorations standing on the grass (colour)
    private final List<Integer> decorCells = new ArrayList<>();
    private long decorKey = -1;
    private int noteHour = -1;

    // ---- timing ----
    private long digInStart = -1, rainStart = -1, demoStart = -1;
    private int demoFrom;
    private float floodP = -1;            // 0..1 through the flood, -1 = no flood
    private int[] floodFill, floodSoak;   // cells: bottom-up to fill, top-down to close
    private int nFlood, filled, soaked;
    private long lastTick = -1, lastWall;
    private int glint = -1;

    // Rain (the flood comes as rain; the water gets in through the entrance).
    private static final int MAX_DROPS = 110;
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
        int n = cols * rows;
        px = new int[n];
        base = new int[n];
        textPx = new int[n];
        text = new byte[n];
        hollow = new boolean[n];
        textEdge = new boolean[n];
        dug = new boolean[n];
        water = new boolean[n];
        chamberOf = new byte[n];
        patch = new byte[n];
        skyCol = new int[n];
        noteCol = new int[n];
        decor = new int[n];
        order = new int[n];
        oHead = new int[n];
        oDir = new byte[n];
        rnd = new Random(day * 31L + System.nanoTime());

        soilTop = ground + 2 * unit;
        soilBot = rows - bedRows - 2 * unit;
        int soilH = rows - bedRows - ground;
        int st = Math.max(1, Math.round(rows * 0.15f / PixelFont.H));
        int mid = ground + Math.round(soilH * 0.55f);
        textTop = mid - PixelFont.H * st;

        // Nest entrance: the setting (or a different spot each day for "Random").
        Random r = new Random(day * 7919L + 41);
        int pos = prefs.dropPos == Prefs.DROP_RANDOM
                ? GrainGrid.MIN_DROP + r.nextInt(GrainGrid.MAX_DROP - GrainGrid.MIN_DROP + 1) : prefs.dropPos;
        entrance = Math.round(pos / 100f * (cols - 1));
        swayAmp = Math.max(0.12f, Prefs.SWAY_AMP[prefs.sway]) * cols;
        hill = new GrainGrid(cols, ground, day, 0.99f);

        makePatches(new Random(day * 131L + 7));
        planNest(r);
        planFood(new Random(day * 977L + 5));
    }

    void setRecording(boolean on) { recording = on && stats != null; }

    // ---- the soil ----

    /** Darker and lighter patches in the soil (smooth value noise), different each day. */
    private void makePatches(Random r) {
        int g = Math.max(8, 11 * unit);
        int gw = cols / g + 2, gh = rows / g + 2;
        float[] v = new float[gw * gh];
        for (int k = 0; k < v.length; k++) v[k] = r.nextFloat();
        for (int y = ground; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float fx = x / (float) g, fy = y / (float) g;
                int ix = (int) fx, iy = (int) fy;
                float tx = fx - ix, ty = fy - iy;
                tx = tx * tx * (3 - 2 * tx);
                ty = ty * ty * (3 - 2 * ty);
                float a = v[iy * gw + ix] * (1 - tx) + v[iy * gw + ix + 1] * tx;
                float b = v[(iy + 1) * gw + ix] * (1 - tx) + v[(iy + 1) * gw + ix + 1] * tx;
                float f = a * (1 - ty) + b * ty;
                patch[y * cols + x] = (byte) (f < 0.33f ? 0 : f > 0.68f ? 2 : 1);
            }
        }
    }

    // ---- the day's nest ----

    private boolean inSoil(int x, int y) {
        return x >= 2 && x < cols - 2 && y >= soilTop && y < soilBot;
    }

    private void dig(int x, int y, int chamber) {
        if (!inSoil(x, y)) return;
        int i = y * cols + x;
        if (dug[i]) return;
        dug[i] = true; // marks "planned"; reset below so the shown state starts empty
        if (chamber > 0) chamberOf[i] = (byte) chamber;
        oHead[nOrder] = digHead;
        oDir[nOrder] = (byte) digDir;
        order[nOrder++] = i;
    }

    /**
     * The day's nest, the way a cut-away ant farm looks: a few oval chambers spread through the
     * soil, joined by wide winding tunnels (each linked to the nest by its nearest way, plus a
     * few extra links that make loops), and a couple of side tunnels. Dug from the entrance
     * outward, shallowest first, so the nest grows down through the day.
     */
    private void planNest(Random r) {
        int rad = Math.max(2, 2 * unit);                 // tunnel half-width: 9 cells wide on a TV
        int soilH = soilBot - soilTop;
        // Chambers: spread over the whole farm. Slots: four across above the clock, four below
        // the date, and one each side of the clock; 7 or 8 of them, each nudged at random.
        int sd = Math.max(1, Math.round(rows * 0.055f / PixelFont.H));
        int st = Math.max(1, Math.round(rows * 0.15f / PixelFont.H));
        int textBottom = textTop + PixelFont.H * st + Math.round(rows * 0.06f) + PixelFont.H * sd;
        List<float[]> slotsXY = new ArrayList<>(); // {x0, x1, y0, y1} areas for a chamber's centre
        float upperY0 = soilTop, upperY1 = textTop, lowerY0 = textBottom, lowerY1 = soilBot;
        for (int k = 0; k < 4; k++) {
            float x0 = cols * (0.04f + 0.23f * k), x1 = x0 + cols * 0.23f;
            slotsXY.add(new float[]{x0, x1, upperY0, upperY1});
            slotsXY.add(new float[]{x0, x1, lowerY0, lowerY1});
        }
        slotsXY.add(new float[]{cols * 0.03f, cols * 0.17f, textTop, textBottom});
        slotsXY.add(new float[]{cols * 0.83f, cols * 0.97f, textTop, textBottom});
        java.util.Collections.shuffle(slotsXY, r);
        int want = 7 + r.nextInt(2);
        for (float[] sl : slotsXY) {
            if (nChambers >= want) break;
            int rx = (10 + r.nextInt(5)) * unit, ry = (5 + r.nextInt(2)) * unit;
            int spanY = Math.round(sl[3] - sl[2]) - 2 * ry;
            int spanX = Math.round(sl[1] - sl[0]) - 2 * rx;
            int x = Math.round(sl[0]) + rx + (spanX > 0 ? r.nextInt(spanX) : spanX / 2);
            int y = Math.round(sl[2]) + ry + (spanY > 0 ? r.nextInt(spanY) : spanY / 2);
            x = Math.max(rx + 3, Math.min(cols - rx - 4, x));
            y = Math.max(soilTop + ry + 1, Math.min(soilBot - ry - 1, y));
            chambers[nChambers++] = new int[]{x, y, rx, ry};
        }
        // Nodes: 0 = the entrance, 1..n = chambers. Links: nearest-first tree from the entrance.
        int nn = nChambers + 1;
        float[] nx = new float[nn], ny = new float[nn];
        nx[0] = entrance; ny[0] = ground;
        for (int k = 0; k < nChambers; k++) { nx[k + 1] = chambers[k][0]; ny[k + 1] = chambers[k][1]; }
        boolean[] in = new boolean[nn];
        in[0] = true;
        List<int[]> tree = new ArrayList<>();
        for (int added = 1; added < nn; added++) {
            int bi = -1, bj = -1;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < nn; i++) {
                if (!in[i]) continue;
                for (int j = 0; j < nn; j++) {
                    if (in[j]) continue;
                    float d = (float) Math.hypot(nx[i] - nx[j], (ny[i] - ny[j]) * 1.3f);
                    if (d < bd) { bd = d; bi = i; bj = j; }
                }
            }
            in[bj] = true;
            tree.add(new int[]{bi, bj});
        }
        // A few extra links between near chambers: loops, like a real nest.
        List<int[]> loops = new ArrayList<>();
        for (int i = 1; i < nn && loops.size() < 4; i++) {
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int j = 1; j < nn; j++) {
                if (j == i || linked(tree, i, j) || linked(loops, i, j)) continue;
                float d = (float) Math.hypot(nx[i] - nx[j], ny[i] - ny[j]);
                if (d < bd) { bd = d; best = j; }
            }
            if (best > 0 && bd < cols * 0.45f && r.nextInt(3) > 0) loops.add(new int[]{i, best});
        }

        // Dig: the tree from the entrance, always the link to the shallowest chamber next.
        boolean[] done = new boolean[nn];
        done[0] = true;
        int left = tree.size();
        boolean[] used = new boolean[tree.size()];
        while (left > 0) {
            int pick = -1;
            for (int k = 0; k < tree.size(); k++) {
                if (used[k]) continue;
                int[] e = tree.get(k);
                if (!done[e[0]] && !done[e[1]]) continue;
                int to = done[e[0]] ? e[1] : e[0];
                if (pick < 0 || ny[to] < ny[farEnd(tree.get(pick), done)]) pick = k;
            }
            if (pick < 0) break;
            used[pick] = true;
            left--;
            int[] e = tree.get(pick);
            int from = done[e[0]] ? e[0] : e[1], to = done[e[0]] ? e[1] : e[0];
            tunnel(r, nx[from], ny[from], nx[to], ny[to], rad, from == 0, from, to);
            dugChamber(to - 1, nx[from], ny[from]);
            done[to] = true;
        }
        Arrays.fill(nodeEdge, -1);
        nNodes = nn;
        // Then the loops, shallowest first, and a couple of side tunnels off to the edges.
        for (int[] e : loops) tunnel(r, nx[e[0]], ny[e[0]], nx[e[1]], ny[e[1]], rad, false, e[0], e[1]);
        for (int k = 0; k < 2 && nChambers > 0; k++) {
            int c = r.nextInt(nChambers);
            float tx = chambers[c][0] < cols / 2 ? cols * 0.04f : cols * 0.96f;
            float ty = chambers[c][1] + (r.nextFloat() - 0.5f) * soilH * 0.4f;
            ty = Math.max(soilTop + rad + 2, Math.min(soilBot - rad - 2, ty));
            nodeEdge[nNodes] = paths.size();
            tunnel(r, chambers[c][0], chambers[c][1], tx, ty, rad - 1, false, c + 1, nNodes++);
        }
        // Roles: the queen lives in the deepest finished chamber (see the colony); the second
        // deepest keeps the eggs (a two-week extra); the first one dug is the pantry.
        Integer[] byDepth = new Integer[nChambers];
        for (int k = 0; k < nChambers; k++) byDepth[k] = k;
        Arrays.sort(byDepth, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return chambers[b][1] - chambers[a][1]; }
        });
        eggChamber = nChambers > 1 ? byDepth[1] + 1 : 0;
        foodChamber = 0;
        for (int k = 0; k < nChambers; k++) if (foodChamber == 0 || chamberDone[k] < chamberDone[foodChamber - 1]) foodChamber = k + 1;

        Arrays.fill(dug, false); // nothing is dug yet; `shown` reveals the plan
    }

    private static boolean linked(List<int[]> es, int a, int b) {
        for (int[] e : es) if ((e[0] == a && e[1] == b) || (e[0] == b && e[1] == a)) return true;
        return false;
    }

    private static int farEnd(int[] e, boolean[] done) { return done[e[0]] ? e[1] : e[0]; }

    /**
     * A winding tunnel from (x0, y0) to (x1, y1): a smooth curve through a few points pushed off
     * the straight line, dug with a round brush. The one from the entrance first goes down.
     */
    private void tunnel(Random r, float x0, float y0, float x1, float y1, int rad, boolean fromEntrance, int na, int nb) {
        pathEnds.add(new int[]{na, nb});
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        int mids = 1 + (int) (len / (28 * unit));
        float[] qxs = new float[mids + 4], qys = new float[mids + 4];
        int n = 0;
        qxs[n] = x0; qys[n++] = y0;
        if (fromEntrance) { qxs[n] = x0 + (r.nextFloat() - 0.5f) * 4 * unit; qys[n++] = y0 + 10 * unit; }
        float ux = (x1 - x0) / Math.max(1, len), uy = (y1 - y0) / Math.max(1, len);
        for (int k = 1; k <= mids; k++) {
            float t = k / (float) (mids + 1), off = (r.nextFloat() - 0.5f) * len * 0.35f;
            float qx = x0 + (x1 - x0) * t - uy * off, qy = y0 + (y1 - y0) * t + ux * off;
            qy = Math.max(soilTop + rad + 1, Math.min(soilBot - rad - 1, qy));
            qx = Math.max(rad + 3, Math.min(cols - rad - 4, qx));
            qxs[n] = qx; qys[n++] = qy;
        }
        qxs[n] = x1; qys[n++] = y1;
        List<Integer> line = new ArrayList<>();
        for (int s = 0; s + 1 < n; s++) {
            // Catmull-Rom between points s and s+1.
            float ax = qxs[Math.max(0, s - 1)], ay = qys[Math.max(0, s - 1)];
            float bx = qxs[s], by = qys[s], cx = qxs[s + 1], cy = qys[s + 1];
            float dx = qxs[Math.min(n - 1, s + 2)], dy = qys[Math.min(n - 1, s + 2)];
            int steps = Math.max(2, (int) (Math.hypot(cx - bx, cy - by) * 1.5f));
            for (int k = 0; k < steps; k++) {
                float t = k / (float) steps, t2 = t * t, t3 = t2 * t;
                float x = 0.5f * (2 * bx + (-ax + cx) * t + (2 * ax - 5 * bx + 4 * cx - dx) * t2 + (-ax + 3 * bx - 3 * cx + dx) * t3);
                float y = 0.5f * (2 * by + (-ay + cy) * t + (2 * ay - 5 * by + 4 * cy - dy) * t2 + (-ay + 3 * by - 3 * cy + dy) * t3);
                float tx = cx - bx, ty = cy - by;
                digDir = Math.abs(tx) >= Math.abs(ty) ? (tx >= 0 ? 0 : 1) : (ty >= 0 ? 2 : 3);
                int ix = Math.round(x), iy = Math.round(Math.max(ground, y));
                digHead = iy * cols + ix;
                for (int yy = -rad; yy <= rad; yy++) for (int xx = -rad; xx <= rad; xx++) {
                    if (xx * xx + yy * yy <= rad * rad + rad / 2) dig(ix + xx, iy + yy, 0);
                }
                // The entrance shaft reaches up through the grass.
                if (fromEntrance && iy < soilTop) for (int yy = ground; yy < soilTop; yy++) digSoilTop(ix, yy, rad);
                int c = iy * cols + ix;
                if (line.isEmpty() || line.get(line.size() - 1) != c) line.add(c);
            }
        }
        int[] path = new int[line.size()];
        for (int k = 0; k < path.length; k++) path[k] = line.get(k);
        paths.add(path);
        pathDone.add(nOrder);
    }

    /** The shaft's top, through the grass row (outside the normal soil bounds). */
    private void digSoilTop(int x, int y, int rad) {
        for (int xx = -rad + 1; xx <= rad - 1; xx++) {
            int X = x + xx;
            if (X < 2 || X >= cols - 2) continue;
            int i = y * cols + X;
            if (dug[i]) continue;
            dug[i] = true;
            oHead[nOrder] = digHead;
            oDir[nOrder] = (byte) digDir;
            order[nOrder++] = i;
        }
    }

    /** A chamber, dug outward from where its tunnel arrives; slightly lumpy, like real ones. */
    private void dugChamber(int k, float fromX, float fromY) {
        if (k < 0) return;
        int[] c = chambers[k];
        float d = (float) Math.hypot(fromX - c[0], fromY - c[1]);
        float ex = c[0] + (fromX - c[0]) / Math.max(1, d) * c[2] * 0.7f;
        float ey = c[1] + (fromY - c[1]) / Math.max(1, d) * c[3] * 0.7f;
        List<int[]> cells = new ArrayList<>();
        double ph = (c[0] * 7 + c[1]) * 0.1;
        for (int dy = -c[3] - 2; dy <= c[3] + 2; dy++) for (int dx = -c[2] - 2; dx <= c[2] + 2; dx++) {
            double ang = Math.atan2(dy * c[2] / (double) c[3], dx);
            double lump = 1 + 0.12 * Math.sin(ang * 3 + ph) + 0.06 * Math.sin(ang * 5 + ph * 2);
            float e = dx * dx / (float) (c[2] * c[2]) + dy * dy / (float) (c[3] * c[3]);
            if (e > lump * lump) continue;
            int x = c[0] + dx, y = c[1] + dy;
            cells.add(new int[]{x, y, Math.round((float) Math.hypot(x - ex, y - ey) * 100)});
        }
        java.util.Collections.sort(cells, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[2] - b[2]; }
        });
        for (int[] cell : cells) {
            digHead = cell[1] * cols + cell[0];
            digDir = cell[0] >= ex ? 0 : 1;
            dig(cell[0], cell[1], k + 1);
        }
        chamberDone[k] = nOrder;
    }

    // ---- palette ----

    private static final int SKY = 1, SOIL0 = 2, RIM = 5, TUNNEL = 6, SHADOW = 7, WATER0 = 8,
            WATER_TOP = 10, GRASS0 = 11, TEXT = 13, ANT = 14, WORKER = 15, CRUMB = 16, POUR = 17,
            ROCK0 = 18, BONE = 20, QUEEN = 21, EGG = 22, SEED = 23, DEW = 24, NOTE = 25,
            HILL0 = 26, WORKER_SKY = 26 + 25 * 4, N_PAL = WORKER_SKY + 1;
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
        float bg = Prefs.BACKGROUND_F[prefs.background];
        // Glowing: the soil and the tunnels in full colour. Dark: near-black soil, dim tunnels.
        // Black: black soil, the tunnels (the air) softly lit, like the sky.
        float soilF = st == 0 ? 0f : st == 1 ? 0.12f : 1f;
        float tunF = st == 0 ? bg * 1.1f : st == 1 ? 0.4f : 1f;
        pal[SKY] = st < 2 ? prefs.color(prefs.sandRgb(), bg) : 0xFF000000;
        pal[SOIL0] = prefs.color(0x2E1A0E, soilF);
        pal[SOIL0 + 1] = prefs.color(0x3E2414, soilF);
        pal[SOIL0 + 2] = prefs.color(0x5A3720, soilF);
        pal[RIM] = prefs.color(0x8A5A32, tunF);
        pal[TUNNEL] = prefs.color(0xC48A50, tunF);
        pal[SHADOW] = prefs.color(0x24140A, Math.max(0.5f, tunF));
        pal[WATER0] = prefs.color(0x3C78B4, Math.max(0.6f, tunF));
        pal[WATER0 + 1] = prefs.color(0x3470AA, Math.max(0.6f, tunF));
        pal[WATER_TOP] = prefs.color(0x9CCCF0, Math.max(0.6f, tunF));
        pal[POUR] = prefs.color(0x7CB4E8, 0.9f);
        float gf = st == 0 ? 0.45f : st == 1 ? 0.55f : 0.9f;
        pal[GRASS0] = prefs.color(0x3E7A34, gf);
        pal[GRASS0 + 1] = prefs.color(0x56984A, gf);
        pal[TEXT] = prefs.textColor(1f);
        pal[ANT] = prefs.color(0xA8492A, 1f);
        pal[WORKER] = prefs.color(0x1E120A, 1f);
        pal[WORKER_SKY] = st == 2 ? prefs.color(0x7A5638, 1f) : pal[WORKER];   // shows on a black sky
        pal[CRUMB] = prefs.color(0xE8D8A8, 1f);
        pal[ROCK0] = prefs.color(0x3A322A, st < 2 ? 0.7f : 1f);
        pal[ROCK0 + 1] = prefs.color(0x2C2620, st < 2 ? 0.7f : 1f);
        pal[BONE] = prefs.color(0xD8CCB0, 0.6f);
        pal[QUEEN] = prefs.color(0x9A3C1C, 1f);
        pal[EGG] = prefs.color(0xF2EEDC, 0.85f);
        pal[SEED] = prefs.color(0x8A6A2A, 0.9f);
        pal[DEW] = prefs.color(0xFFF4DC, 1f);
        pal[NOTE] = prefs.textColor(0.75f);
        // The hill: each hour's soil in its own tone (sand-art layers), 4 texture shades.
        int sand = prefs.sandRgb();
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

    private boolean open(int i) { return dug[i] || water[i]; }

    private int baseColor(int i) {
        int x = i % cols, y = i / cols;
        int h = i * 0x9E3779B1;
        if (y < ground) {
            if (hill.c[i] == GrainGrid.SAND) {
                if (i == glint) return pal[DEW];
                return pal[HILL0 + hill.layer[i] * 4 + ((h >>> 28) & 3)];
            }
            if (noteCol[i] != 0) return noteCol[i];
            if (decor[i] != 0) return decor[i];
            if (skyCol[i] != 0) return skyCol[i];
            return pal[SKY];
        }
        if (y >= rows - bedRows) return bedrock(x, y - (rows - bedRows), h);
        if (text[i] != 0) return pal[TEXT];
        if (water[i]) {
            boolean top = y > ground && !water[i - cols];
            return top ? pal[WATER_TOP] : pal[WATER0 + ((y >> 1) & 1)];
        }
        if (hollow[i]) return pal[TUNNEL];
        if (dug[i]) {
            if (textEdge[i]) return pal[SHADOW];
            int c = chamberOf[i];
            if (c != 0) {
                int content = contents(c, x, y);
                if (content != 0) return pal[content];
            }
            return pal[TUNNEL];
        }
        // Soil: a darker rim around the tunnels, grass on top, patches below.
        boolean rim = (x > 0 && open(i - 1)) || (x < cols - 1 && open(i + 1))
                || (y > ground && open(i - cols)) || (y < rows - 1 && open(i + cols));
        if (rim) return pal[RIM];
        if (y <= ground + unit) return pal[GRASS0 + ((h >>> 31) & 1)];
        return pal[SOIL0 + patch[i]];
    }

    /**
     * What lies in a chamber (0 = nothing): eggs (a two-week extra), and in the pantry the food
     * the foragers brought in today, piled up from the floor on one side (plus the two-week
     * extra's seeds).
     */
    private int contents(int c, int x, int y) {
        int[] ch = chambers[c - 1];
        int hsh = (y * cols + x) * 0x9E3779B1;
        boolean floor = y >= ch[1] + ch[3] / 4;
        if (c == eggChamber && floor && ((hsh >>> 27) & 7) < Math.round(8 * eggFill) && ((x + y) & 1) == 0) return EGG;
        if (c == foodChamber) {
            if (floor && ((hsh >>> 27) & 7) < Math.round(8 * seedFill)) return SEED;
            float f = pantryStep / 32f;
            boolean side = pantrySide(c) ? x <= ch[0] - ch[2] / 5 : x >= ch[0] + ch[2] / 5;
            if (f > 0 && side && y >= ch[1] + ch[3] - Math.round(f * ch[3] * 1.1f) && ((hsh >>> 28) & 3) != 0) {
                return ((hsh >>> 25) & 3) == 0 ? CRUMB : SEED;
            }
        }
        return 0;
    }

    /** The pantry pile is on the side away from the tunnel the food comes in by. */
    private boolean pantrySide(int c) { return chambers[c - 1][0] < entrance; }

    private float eggFill, seedFill;
    private int pantryStep;               // today's pantry pile, 0..32

    private int bedrock(int x, int y, int h) {
        if (fossilAt(x, y)) return pal[BONE];
        return ((y + ((h >>> 30) & 1)) % 3 == 0) ? pal[ROCK0 + 1] : pal[ROCK0];
    }

    private static final String[][] FOSSILS = {{".##.", "#.##", ".##."}, {"#..#", "####", "#..#"}};

    private boolean fossilAt(int x, int y) {
        if (stats == null || bedRows < 4) return false;
        int n = stats.fossils();
        for (int k = 0; k < n; k++) {
            int fx = Math.round(cols * (0.07f + 0.86f * (k + 0.5f) / 8f)) - 2;
            int fy = Math.max(0, (bedRows - 3) / 2 + (k % 2));
            int q = x - fx, r = y - fy;
            if (q >= 0 && q < 4 && r >= 0 && r < 3 && FOSSILS[k % 2][r].charAt(q) == '#') return true;
        }
        return false;
    }

    private void composeAll() {
        for (int i = 0; i < base.length; i++) base[i] = baseColor(i);
        fullDirty = true;
    }

    private void recolor(int i) { base[i] = baseColor(i); }

    /** Recolours a cell and its neighbours (their rim may change) and grows a dirty box. */
    private void touch(int i, int[] box) {
        int x = i % cols, y = i / cols;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int X = x + dx, Y = y + dy;
            if (X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
            recolor(Y * cols + X);
        }
        box[0] = Math.min(box[0], x - 1); box[1] = Math.min(box[1], y - 1);
        box[2] = Math.max(box[2], x + 1); box[3] = Math.max(box[3], y + 1);
    }

    // ---- time ----

    private static long msOfDay(Calendar c) {
        return ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60
                + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
    }

    private static int dueAt(long ms) {
        return (int) Math.min(GRAINS_PER_DAY, ms / 1000 / Stats.SECONDS_PER_GRAIN);
    }

    void startDigIn(long t) { digInStart = t; }

    /** Midnight with the flood set to 0: flood the day away now, quickly. */
    void startRain(long t) { if (rainStart < 0) rainStart = t; }

    boolean rained(long t) { return rainStart >= 0 && t - rainStart >= RAIN_MS; }

    void startDemo(long t, Calendar c) {
        demoStart = t;
        demoFrom = Math.max(GRAINS_PER_DAY * 3 / 4, dueAt(msOfDay(c)));
    }

    boolean demoOver(long t) { return demoStart >= 0 && t - demoStart > DEMO_MS + 3000; }

    /** Washed clean (or the flood all but done, e.g. its last update came just before midnight). */
    boolean isEmpty() { return (dugCount == 0 && hill.sandCount == 0) || floodSeen >= 0.995f; }

    private float floodSeen = -1;      // furthest the flood has got

    private boolean flooding() { return floodP >= 0 && floodP < 1f; }

    /**
     * How much is dug now (grains; the nest shows the same share of its plan) and how far the
     * flood has got (floodP).
     */
    private int target(Calendar c, long t, boolean rainAllowed) {
        long ms = msOfDay(c);
        floodP = -1;
        if (demoStart >= 0) {
            floodP = Math.min(1f, (t - demoStart) / (float) DEMO_MS);
            digInStart = -1;
            return demoFrom;
        }
        if (rainStart >= 0) {
            floodP = Math.min(1f, (t - rainStart) / (float) RAIN_MS);
            digInStart = -1;
            return GRAINS_PER_DAY;
        }
        int due = dueAt(ms);
        int vm = Prefs.VACUUM_MIN[prefs.vacuum];
        long from = DAY_MS - vm * 60_000L;
        if (vm > 0 && rainAllowed && ms >= from) {
            floodP = Math.min(1f, (ms - from) / (vm * 60_000f));
            due = dueAt(from);
            digInStart = -1; // the nest is shown whole; the flood takes it from there
        }
        if (digInStart >= 0) {
            float p = Math.min(1f, (t - digInStart) / (float) DIG_IN_MS);
            if (p >= 1f) digInStart = -1;
            due = Math.round(due * (1 - (1 - p) * (1 - p) * (1 - p)));
        }
        return due;
    }

    /** The water's plan: every open cell, bottom rows first to fill, top rows first to close. */
    private void planFlood() {
        int n = 0;
        int[] cells = new int[dugCount];
        for (int k = 0; k < shown; k++) { int i = order[k]; if (dug[i] && n < cells.length) cells[n++] = i; }
        nFlood = n;
        long[] key = new long[n];
        Random r = new Random(day * 3L);
        for (int k = 0; k < n; k++) key[k] = ((long) (rows - cells[k] / cols) << 32) | (r.nextInt() & 0x7FFFFFFFL) << 1 | 0;
        // Fill: lowest rows first (with a little jitter across each row).
        Integer[] idx = new Integer[n];
        for (int k = 0; k < n; k++) idx[k] = k;
        final long[] kk = key;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return Long.compare(kk[a], kk[b]); }
        });
        floodFill = new int[n];
        for (int k = 0; k < n; k++) floodFill[k] = cells[idx[k]];
        // Close: the top rows first, as the soaked soil settles from above.
        floodSoak = new int[n];
        for (int k = 0; k < n; k++) floodSoak[k] = floodFill[n - 1 - k];
        filled = soaked = 0;
    }

    // ---- the clock ----

    private final int[] textBox = {0, 0, -1, -1}; // clock + its edge: x0, y0, x1, y1

    private void layoutText(Calendar c) {
        int st = Math.max(1, Math.round(rows * 0.15f / PixelFont.H));
        int sd = Math.max(1, Math.round(rows * 0.055f / PixelFont.H));
        int mid = textTop + PixelFont.H * st;
        Arrays.fill(text, (byte) 0);
        Arrays.fill(hollow, false);
        Arrays.fill(textEdge, false);
        slots = ClockText.layoutAt(prefs, c, cols, rows, mid, st, sd);
        for (ClockText.Slot s : slots) {
            for (int i : s.cells) text[i] = 1;
            for (int i : s.lids) text[i] = 1;
            for (int i : s.inner) hollow[i] = true;
        }
        textBox[0] = cols; textBox[1] = rows; textBox[2] = -1; textBox[3] = -1;
        for (int i = 0; i < text.length; i++) {
            if (text[i] == 0) continue;
            int x = i % cols, y = i / cols;
            textBox[0] = Math.min(textBox[0], x - 1); textBox[1] = Math.min(textBox[1], y - 1);
            textBox[2] = Math.max(textBox[2], x + 1); textBox[3] = Math.max(textBox[3], y + 1);
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int X = x + dx, Y = y + dy;
                if (X < 0 || Y < 0 || X >= cols || Y >= rows) continue;
                int j = Y * cols + X;
                if (text[j] == 0 && !hollow[j]) textEdge[j] = true;
            }
        }
        for (int i = 0; i < textPx.length; i++) textPx[i] = text[i] != 0 ? pal[TEXT] : 0;
        textChanged = true;
    }

    // ---- extras ----

    private long extrasKey = -1;

    private boolean buildExtras(Calendar c) {
        if (stats == null) return false;
        stats.refresh(c);
        float g = stats.sandGrowth();
        eggFill = Math.min(1f, Math.max(0f, (g - 0.25f) * 1.5f));
        seedFill = Math.min(1f, Math.max(0f, (g - 0.75f) * 4f));
        long key = stats.period * 1000 + Math.round(eggFill * 8) * 10 + Math.round(seedFill * 8);
        if (key == extrasKey) return false;
        extrasKey = key;
        return true;
    }

    // ---- sky: sun, moon and the hourly line ----

    private int[] skyColors = new int[0];

    private void updateSky(Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int key = prefs.sunMoon ? m + colorKey * 1441 : -2;
        if (key == skyKey) return;
        skyKey = key;
        for (int k = 0; k < nSky; k++) { skyCol[skyCells[k]] = 0; recolor(skyCells[k]); }
        markCells(skyCells, nSky);
        int max = SunMoon.maxCells(rows);
        if (skyCells.length < max) { skyCells = new int[max]; skyColors = new int[max]; }
        nSky = SunMoon.draw(prefs, c, cols, rows, ground, Math.round(rows * 0.08f), skyCells, skyColors);
        for (int k = 0; k < nSky; k++) { skyCol[skyCells[k]] = skyColors[k]; recolor(skyCells[k]); }
        markCells(skyCells, nSky);
    }

    /** Sky cells of the moon (for the dim night clock), and their colours. */
    int skyCell(int k) { return skyCells[k]; }
    int skyColor(int i) { return skyCol[i]; }

    private int[][] noteChars = new int[0][];
    private long noteStart;
    private int noteShown;                  // letters showing now
    private static final long NOTE_MS = 12_000;

    /**
     * Once an hour (at :30) a line about the farm's lifetime stats types itself in near the top
     * of the sky, holds, then un-types; drawn over empty sky only (the hill hides it).
     */
    private void updateNote(Calendar c, boolean animate) {
        long now = c.getTimeInMillis();
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (noteChars.length == 0 && stats != null && animate && !flooding() && noteHour != h
                && c.get(Calendar.MINUTE) == 30 && c.get(Calendar.SECOND) <= 20) {
            noteHour = h;
            String msg = stats.sandMessage(c, h);
            if (msg != null) {
                noteChars = TypedLine.cells(msg, TypedLine.centred(msg, unit, cols), Math.round(rows * 0.035f), unit, cols, ground);
                noteStart = now;
                noteShown = 0;
            }
        }
        if (noteChars.length == 0) return;
        int want = (!animate || flooding()) ? 0 : TypedLine.visible(noteChars.length, now - noteStart, NOTE_MS);
        if (want == noteShown) {
            if (want == 0 && now - noteStart > 0) noteChars = new int[0][];
            return;
        }
        int lo = Math.min(want, noteShown), hi = Math.max(want, noteShown);
        int col = want > noteShown ? pal[NOTE] : 0;
        for (int k = lo; k < hi; k++) {
            for (int i : noteChars[k]) { noteCol[i] = col; recolor(i); }
            markCells(noteChars[k], noteChars[k].length);
        }
        noteShown = want;
        if (want == 0) noteChars = new int[0][];
    }

    /**
     * Festival days: a few small things stand on the grass, put out one at a time through the
     * morning: a Jalur Gemilang flag (Merdeka, Malaysia Day), red lanterns (CNY), oil lamps that
     * light after dark (Hari Raya, Deepavali), a little tree with lights (Christmas). Updated
     * once a minute at most.
     */
    private void updateDecor(Calendar c) {
        int fest = Festivals.of(prefs, c);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean dark = !SunMoon.isDay(prefs, c);
        int steps = fest == Festivals.NONE || m < 420 ? 0 : Math.min(8, (m - 420) / 12 + 1); // from 07:00
        long key = (((long) fest * 16 + steps) * 2 + (dark ? 1 : 0)) * 2 + (floodP >= 0 ? 1 : 0) + colorKey * 1000L;
        if (key == decorKey) return;
        decorKey = key;
        int[] cl = new int[decorCells.size()];
        for (int k = 0; k < cl.length; k++) { cl[k] = decorCells.get(k); decor[cl[k]] = 0; recolor(cl[k]); }
        markCells(cl, cl.length);
        decorCells.clear();
        if (steps == 0 || floodP >= 0) return;
        // Drawn on a coarse grid (one decoration pixel = unit x unit cells), standing on the grass.
        dOx = entrance < cols / 2 ? Math.round(cols * 0.8f) : Math.round(cols * 0.2f);
        dOy = ground - unit;                       // bottom row of decoration pixels
        int red = prefs.color(0xD02828, 1f), white = prefs.color(0xF0F0F0, 1f), blue = prefs.color(0x1A2A8A, 1f);
        int gold = prefs.color(0xF0C020, 1f), pole = prefs.color(0x8A8A8A, 1f), green = prefs.color(0x2E7A30, 1f);
        int clay = prefs.color(0x9A5A2A, 1f), flame = prefs.color(0xFFC040, 1f);
        if (fest == Festivals.MERDEKA || fest == Festivals.MSIA) {
            int ph = 9;                            // the pole goes up first, then the flag
            int poleUp = Math.min(ph, steps * ph / 3);
            for (int k = 0; k < poleUp; k++) dp(0, k, pole);
            if (steps >= 4) {
                for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) {
                    int col = (x < 3 && y < 3) ? (x == 1 && y == 1 ? gold : blue) : (y % 2 == 0 ? red : white);
                    dp(1 + x, ph - 1 - y, col);
                }
            }
        } else if (fest == Festivals.CNY) {
            for (int k = 0; k < Math.min(2, steps / 3); k++) {
                int x = k == 0 ? -4 : 4;
                for (int y = 0; y < 5; y++) dp(x, y, pole);
                for (int y = 0; y < 3; y++) for (int dx = -1; dx <= 1; dx++) dp(x + dx, 6 + y, y == 1 ? gold : red);
                dp(x, 9, gold);
            }
        } else if (fest == Festivals.RAYA || fest == Festivals.DEEPAVALI) {
            for (int k = 0; k < steps; k++) {
                int x = -10 + k * 3;
                dp(x - 1, 0, clay); dp(x, 0, clay); dp(x + 1, 0, clay);
                if (dark) dp(x, 1, flame);
            }
        } else if (fest == Festivals.XMAS) {
            int tiers = Math.min(5, steps);        // the tree grows a tier at a time
            dp(0, 0, clay); dp(0, 1, clay);
            for (int y = 0; y < tiers * 2; y++) {
                int half = (tiers * 2 - y) / 2;
                for (int dx = -half; dx <= half; dx++) dp(dx, 2 + y, green);
            }
            if (steps >= 6) dp(0, 2 + tiers * 2, gold);
            if (dark) for (int k = 0; k < (steps - 5) * 3; k++) {
                int y = (k * 7) % Math.max(1, tiers * 2 - 1), half = (tiers * 2 - y) / 2;
                dp(-half + (k * 5) % Math.max(1, 2 * half + 1), 2 + y, k % 3 == 0 ? red : k % 3 == 1 ? gold : blue);
            }
        }
        int[] nc = new int[decorCells.size()];
        for (int k = 0; k < nc.length; k++) { nc[k] = decorCells.get(k); recolor(nc[k]); }
        markCells(nc, nc.length);
    }

    private int dOx, dOy;

    /** A decoration pixel dx right, dy up from the decoration's base (unit x unit cells). */
    private void dp(int dx, int dy, int col) {
        int x0 = dOx + dx * unit, y0 = dOy - dy * unit;
        for (int yy = 0; yy < unit; yy++) for (int xx = 0; xx < unit; xx++) put(x0 + xx, y0 + yy, col);
    }

    private void put(int x, int y, int col) {
        if (x < 0 || y < 0 || x >= cols || y >= ground) return;
        int i = y * cols + x;
        decor[i] = col;
        decorCells.add(i);
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
     * Brings the scene to time c / uptime t. animate = false: no moving ants, pouring or dew;
     * the nest, hill and flood still follow the time.
     */
    void update(Calendar c, long t, boolean animate, boolean rainAllowed) {
        boolean recompose = updateColors();
        long wall = c.getTimeInMillis();
        boolean gap = lastWall == 0 || Math.abs(wall - lastWall) > 120_000;
        lastWall = wall;

        String key = ClockText.key(prefs, c);
        if (!key.equals(shownKey)) {
            int[] old = textBox.clone();
            layoutText(c);
            shownKey = key;
            if (!recompose) {
                int x0 = Math.min(old[0], textBox[0]), y0 = Math.min(old[1], textBox[1]);
                int x1 = Math.max(old[2], textBox[2]), y1 = Math.max(old[3], textBox[3]);
                if (x1 < x0) recompose = true; // first layout
                else {
                    for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) recolor(y * cols + x);
                    addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
                }
            }
        }
        if (buildExtras(c)) recompose = true;

        int want = target(c, t, rainAllowed);
        if (floodP > floodSeen) floodSeen = floodP;
        int[] box = {cols, rows, -1, -1};

        // The nest shows the same share of its plan as the day (complete just before midnight).
        if (floodP < 0 || floodFill == null) {
            int wantCells = (int) ((long) nOrder * want / GRAINS_PER_DAY);
            if (shown < wantCells && wantCells - shown <= 40) lastDigAt = t;
            while (shown < wantCells && shown < nOrder) {
                int i = order[shown++];
                if (!dug[i]) { dug[i] = true; dugCount++; }
                if (!recompose) touch(i, box);
            }
        }
        // The flood: plan it once, then fill bottom-up and close top-down.
        if (floodP >= 0) {
            if (floodFill == null) planFlood();
            float pf = Math.min(1f, floodP / FILL_PART), ps = Math.max(0f, (floodP - FILL_PART) / (1 - FILL_PART));
            int wantFill = Math.round(nFlood * pf), wantSoak = floodP >= 1f ? nFlood : Math.round(nFlood * ps);
            while (filled < wantFill) {
                int i = floodFill[filled++];
                water[i] = true;
                if (!recompose) touch(i, box);
            }
            while (soaked < wantSoak) {
                int i = floodSoak[soaked++];
                water[i] = false;
                if (dug[i]) { dug[i] = false; dugCount--; }
                if (!recompose) touch(i, box);
            }
        }
        if (box[2] >= 0) addCur(box[0], box[1], box[2] - box[0] + 1, box[3] - box[1] + 1);

        // Grains dug as the day goes: count them (only live digging, as the screensaver). At
        // night the farm updates once a minute (10 grains); bigger jumps are catch-up or dig-in.
        if (recording && lastWant >= 0 && want > lastWant && want - lastWant <= 20 && digInStart < 0 && floodP < 0 && !gap) {
            for (int k = lastWant; k < want; k++) stats.addGrain();
        }
        lastWant = want;

        // The hill: a grain per dig; the flood washes it down.
        int hillWant = floodP >= 0 ? Math.round(want * (1 - floodP)) : want;
        while (placed < hillWant) {
            hill.spawnLayer = GrainGrid.layerOf(placed);
            hill.dropInstant(dropColumn(placed));
            placed++;
        }
        if (hill.sandCount > hillWant && placed > hillWant) {
            int excess = hill.sandCount - hillWant;
            for (int k = 0; k < excess * 3 && hill.sandCount > hillWant; k++) hill.removeTop(rnd.nextInt(cols));
            // Near the end most columns are bare and random picks miss: sweep across instead.
            for (int pass = 0, x0 = rnd.nextInt(cols); pass < ground && hill.sandCount > hillWant; pass++) {
                boolean any = false;
                for (int k = 0; k < cols && hill.sandCount > hillWant; k++) any |= hill.removeTop((x0 + k) % cols);
                if (!any) break;
            }
            placed = Math.max(hillWant, Math.min(placed, hill.sandCount));
        }

        // Physics only while grains are sliding (after the water takes some away).
        if (lastTick < 0) lastTick = t;
        int ticks = (int) Math.min(6, (t - lastTick) / 33);
        lastTick += ticks * 33L;
        if (t - lastTick > 200) lastTick = t;
        if (hill.hasActive()) for (int k = 0; k < ticks; k++) hill.tick();

        // The colony lives while the farm is animated and dry (it starts after the dig-in).
        if (animate && floodP < 0 && digInStart < 0) simulate(c, t, gap);
        else { colonyLive = false; fFall = -1; }
        if (pantryDirty) {
            pantryDirty = false;
            if (!recompose && foodChamber > 0) recolorChamber(foodChamber);
        }

        updateSky(c);
        updateNote(c, animate);
        updateDecor(c);
        if (recompose) composeAll();
        else {
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
        if (animate && floodP < 0 && rnd.nextFloat() < 0.04f) {
            int x = rnd.nextInt(cols), y = hill.freeDepth(x);
            if (y < ground && hill.c[y * cols + x] == GrainGrid.SAND) {
                glint = y * cols + x;
                recolor(glint);
                addCur(x, y, 1, 1);
            }
        }

        // Rain: heavy while the tunnels fill, easing off as the water soaks away.
        float strength = !flooding() ? 0f : floodP < FILL_PART ? 1f : 1f - (floodP - FILL_PART) / (1 - FILL_PART);
        int wantDrops = animate ? Math.round(MAX_DROPS * strength) : 0;
        while (nDrops < wantDrops) {
            dX[nDrops] = rnd.nextInt(cols);
            dY[nDrops] = -rnd.nextInt(ground);
            nDrops++;
        }
        int dticks = Math.max(1, ticks);
        for (int k = 0; k < nDrops; k++) {
            dY[k] += 2.2f * dticks;
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            boolean land = y >= ground || (y >= 0 && x >= 0 && x < cols && hill.c[y * cols + x] != GrainGrid.EMPTY);
            if (!land) continue;
            if (k < wantDrops) { dX[k] = rnd.nextInt(cols); dY[k] = -rnd.nextInt(20); }
            else { nDrops--; dX[k] = dX[nDrops]; dY[k] = dY[nDrops]; k--; }
        }
        if (!animate) nDrops = 0;

        render(c, t, animate);
    }

    private void addCur(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(cols, x + w), y1 = Math.min(rows, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (nCur + 4 > cur.length) cur = Arrays.copyOf(cur, cur.length * 2);
        cur[nCur++] = x0; cur[nCur++] = y0; cur[nCur++] = x1 - x0; cur[nCur++] = y1 - y0;
    }

    /** The surface: the first row with a grain (or the ground) in column x. */
    private int surfaceAt(int x) {
        return hill.freeDepth(Math.max(0, Math.min(cols - 1, x)));
    }

    private void render(Calendar c, long t, boolean animate) {
        long ms = msOfDay(c);
        int aw = WALK_A.w, ah = WALK_A.h;

        // The seconds ant patrols a trail by the nest, one way each minute, turning at the ends.
        // Feet on the soil under its middle, so it walks up and down the hill.
        float s = (ms % 60_000) / 60_000f;
        if (!animate) s = (int) (s * 60) / 60f;
        boolean right = (ms / 60_000) % 2 == 0;
        int half = cols / 6;
        int t0 = Math.max(2, Math.min(cols - aw - 2 - 2 * half, entrance - half));
        float along = (right ? s : 1 - s) * 2 * half;
        int sx = t0 + Math.round(along);
        int sy = surfaceAt(sx + aw / 2) - ah;
        int sFrame = Math.round(along) % 2;               // legs move as it steps a cell
        addCur(sx, sy - 1, aw, ah + 1);

        // The digger, its head at the face it is digging, facing that way.
        int dx = -9999, dy = 0, dDir = 0;
        if (floodP < 0 && shown > 0 && shown < nOrder) {
            int head = oHead[shown - 1];
            dDir = oDir[shown - 1];
            int hx = head % cols, hy = head / cols;
            Sprite sp = dDir < 2 ? WALK_A : UPRIGHT;
            if (dDir == 0) { dx = hx - sp.w + 1; dy = hy - 1; }
            else if (dDir == 1) { dx = hx; dy = hy - 1; }
            else if (dDir == 2) { dx = hx - 1; dy = hy - sp.h + 1; }
            else { dx = hx - 1; dy = hy; }
            addCur(dx - 1, dy - 1, sp.w + 2, sp.h + 2);
        }

        // The colony: food on the grass, the queen, and the workers at their jobs.
        boolean colony = animate && floodP < 0 && colonyLive;
        if (colony) placeColony(t);

        if (nDrops > 0) addCur(0, 0, cols, ground);

        if (nPrev + nCur > dirty.length) fullDirty = true;      // too many pieces: send it all
        if (fullDirty) System.arraycopy(base, 0, px, 0, px.length);
        else {
            restorePrev();
            restore(cur, nCur);
        }

        if (colony) drawColony();
        drawAnt(sx, sy, right ? 0 : 1, sFrame, 0, false, pal[ANT]);
        if (dx > -9999) {
            int f = animate ? (int) ((t / 500) % 2) : 0;
            drawAnt(dx, dy, dDir, f, lastDigAt >= 0 && t - lastDigAt < 1500 ? pal[RIM] : 0, false, pal[WORKER]);
        }
        for (int k = 0; k < nDrops; k++) {
            int x = Math.round(dX[k]), y = Math.round(dY[k]);
            for (int l = 0; l < 2 * unit; l++) {
                int yy = y - l;
                if (x < 0 || x >= cols || yy < 0 || yy >= ground) continue;
                int i = yy * cols + x;
                if (hill.c[i] == GrainGrid.EMPTY) px[i] = pal[POUR];
            }
        }

        nDirty = 0;
        if (!fullDirty) {
            for (int k = 0; k + 3 < nPrev && nDirty + 4 <= dirty.length; k += 4) {
                if (inCur(prev[k], prev[k + 1], prev[k + 2], prev[k + 3])) continue;
                for (int j = 0; j < 4; j++) dirty[nDirty++] = prev[k + j];
            }
            for (int k = 0; k < nCur && nDirty < dirty.length; k++) dirty[nDirty++] = cur[k];
        }
        int[] tmp = prev; prev = cur; cur = tmp;
        nPrev = nCur; nCur = 0;
    }

    /** True if (x, y, w, h) lies inside one of this frame's rectangles (no need to redo it). */
    private boolean inCur(int x, int y, int w, int h) {
        for (int k = 0; k + 3 < nCur; k += 4) {
            if (x >= cur[k] && y >= cur[k + 1] && x + w <= cur[k] + cur[k + 2] && y + h <= cur[k + 1] + cur[k + 3]) return true;
        }
        return false;
    }

    /** Restores last frame's rectangles, skipping those this frame redoes anyway. */
    private void restorePrev() {
        for (int k = 0; k + 3 < nPrev; k += 4) {
            int x = prev[k], y = prev[k + 1], w = prev[k + 2], h = prev[k + 3];
            if (inCur(x, y, w, h)) continue;
            for (int yy = y; yy < y + h; yy++) System.arraycopy(base, yy * cols + x, px, yy * cols + x, w);
        }
    }

    private void restore(int[] r, int n) {
        for (int k = 0; k + 3 < n; k += 4) {
            int x = r[k], y = r[k + 1], w = r[k + 2], h = r[k + 3];
            for (int yy = y; yy < y + h; yy++) System.arraycopy(base, yy * cols + x, px, yy * cols + x, w);
        }
    }

    /**
     * A small ant, one cell per pixel of its art, facing dir (0 right, 1 left, 2 down, 3 up),
     * with a crumb in its jaws if asked. With airOnly it shows only over open tunnel.
     */
    private void drawAnt(int x, int y, int dir, int frame, int crumb, boolean airOnly, int col) {
        Sprite s = frame == 0 ? WALK_A : WALK_B;
        drawSprite(s, x, y, dir, col, airOnly);
        if (crumb != 0) {
            int w = dir < 2 ? s.w : s.h, h = dir < 2 ? s.h : s.w;
            int bx = dir == 0 ? x + w : dir == 1 ? x - 1 : x + w / 2;
            int by = dir == 2 ? y + h : dir == 3 ? y - 1 : y;
            plotAnt(bx, by, crumb, airOnly);
            plotAnt(bx, by + (dir < 2 ? 1 : 0), crumb, airOnly);   // a crumb a little bigger than a cell
        }
    }

    private void plotAnt(int X, int Y, int col, boolean airOnly) {
        if (X < 0 || Y < 0 || X >= cols || Y >= rows) return;
        int i = Y * cols + X;
        if (airOnly && Y >= ground && (!dug[i] || water[i] || text[i] != 0)) return;
        px[i] = col;
    }

    /** True while something needs smooth frames (dig-in, the flood, sliding grains). */
    boolean busy() { return digInStart >= 0 || flooding() || nDrops > 0 || hill.hasActive() || fFall >= 0; }

    // ---- the colony: workers with jobs, the queen, food on the grass ----
    //
    // Each worker walks a route of cells through the finished tunnels at its own pace, pausing
    // now and then and stopping briefly when it meets another head-on. Jobs:
    //  - soil: from the face being dug up to the mound with a crumb of soil, and back;
    //  - forage: when food lands on the grass, ants are recruited a few at a time; they walk
    //    out over the mound to it, bite off a piece and carry it down to the pantry, until the
    //    food is gone (the pantry's pile grows through the day);
    //  - nurse: two stay by the queen; she lives in the deepest finished chamber, with her eggs,
    //    and moves house (escorted) when a deeper one is finished;
    //  - wander / mill: the rest go from chamber to chamber, or potter about and rest in one.
    // Routes are cell indexes; a cell in row 0 stands for the surface at that column (the mound
    // changes), so "outside" routes run over the grass and down through the mound.

    private static final int J_WANDER = 0, J_MILL = 1, J_TO_PANTRY = 2, J_FORAGE = 3, J_SOIL = 4,
            J_NURSE = 5, J_ESCORT = 6;
    private static final int MAX_ANTS = 18, MAX_FORAGERS = 10;
    private static final int BITE_S = 25;  // about how often a bite of food is carried in (s)
    private int nAnts;
    private final int[] aJob = new int[MAX_ANTS], aDir = new int[MAX_ANTS], aFrom = new int[MAX_ANTS],
            aTo = new int[MAX_ANTS], aCarry = new int[MAX_ANTS], aLane = new int[MAX_ANTS],
            aFace = new int[MAX_ANTS], aRounds = new int[MAX_ANTS];
    private final int[][] aRoute = new int[MAX_ANTS][];
    private final float[] aPos = new float[MAX_ANTS], aSpeed = new float[MAX_ANTS];
    private final long[] aPause = new long[MAX_ANTS], aCool = new long[MAX_ANTS];
    private long simT = -1, lastSpawn;
    private final int[] mX = new int[MAX_ANTS], mY = new int[MAX_ANTS], mF = new int[MAX_ANTS];
    private int simSec;                    // seconds of the day at the last step
    private boolean colonyLive, pantryDirty;

    // The queen: in a chamber (at rest, shuffling about) or walking her route to a deeper one.
    private int qNode = -1, qTo = -1, qFace;
    private int[] qRoute;
    private float qPos, qX, qGoal;
    private long qWait;

    // Food: a few times a day something lands on the grass.
    private static final int MAX_FOOD = 12;
    private int nFood, totalBites, stored;
    private final int[] evStart = new int[MAX_FOOD], evType = new int[MAX_FOOD], evX = new int[MAX_FOOD],
            evBites = new int[MAX_FOOD];
    private int fIdx = -1, fBites, fW, fH, fColorKey = Integer.MIN_VALUE, fCrumb;
    private long fFall = -1;
    private int[] fCells = new int[0], fCol = new int[0];   // food cells (dy * 64 + dx), eaten first first

    // Where things are drawn this frame.
    private final int[] pX = new int[MAX_ANTS], pY = new int[MAX_ANTS], pFr = new int[MAX_ANTS];
    private final boolean[] pOn = new boolean[MAX_ANTS];
    private int qpX, qpY, qpFr, foodY = -1;

    /**
     * The day's food: from about 07:30 to 20:30, every 70-130 minutes, on the grass either side
     * of the nest, past the mound where there is room (so the trail shows against the sky).
     */
    private void planFood(Random r) {
        int decoX = entrance < cols / 2 ? Math.round(cols * 0.8f) : Math.round(cols * 0.2f);
        float clear = swayAmp + cols * 0.09f;          // past the mound's foot
        int m = 7 * 60 + 15 + r.nextInt(40);
        while (m < 20 * 60 + 30 && nFood < MAX_FOOD) {
            int type = r.nextInt(FOOD_ART.length);
            int w = FOOD_ART[type][0].length();
            int side = r.nextBoolean() ? 1 : -1;
            float d = clear + cols * 0.10f * r.nextFloat();
            int x = 0;
            for (int tries = 0; tries < 2; tries++) {
                x = Math.round(entrance + side * d) - (side < 0 ? w : 0);
                boolean off = x < 6 || x + w > cols - 6;
                boolean onDecor = Math.abs(x + w / 2 - decoX) < 14 * unit;
                if (!off && !onDecor) break;
                side = -side;
            }
            x = Math.max(6, Math.min(cols - w - 6, x));
            evStart[nFood] = m * 60;
            evType[nFood] = type;
            evX[nFood] = x;
            evBites[nFood] = 28 + r.nextInt(16);
            totalBites += evBites[nFood];
            nFood++;
            m += 70 + r.nextInt(60);
        }
    }

    /** Sets up the current food (k), its cells in the order they are eaten, and its colours. */
    private void setFood(int k) {
        fIdx = k;
        String[] art = FOOD_ART[evType[k]];
        fW = art[0].length();
        fH = art.length;
        boolean nestLeft = evX[k] > entrance;
        Random r = new Random(day * 13L + k);
        List<long[]> cells = new ArrayList<>();
        for (int y = 0; y < fH; y++) for (int x = 0; x < fW; x++) {
            char ch = art[y].charAt(x);
            if (ch == '.') continue;
            int edge = nestLeft ? x : fW - 1 - x;      // bitten from the side facing the nest
            cells.add(new long[]{edge * 3L + y + r.nextInt(5), y * 64 + x, ch});
        }
        java.util.Collections.sort(cells, new java.util.Comparator<long[]>() {
            public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
        });
        fCells = new int[cells.size()];
        fCol = new int[cells.size()];
        for (int j = 0; j < fCells.length; j++) { fCells[j] = (int) cells.get(j)[1]; fCol[j] = (int) cells.get(j)[2]; }
        fColorKey = Integer.MIN_VALUE;
        foodColors();
    }

    private final int[] fPal = new int[128];

    private void foodColors() {
        if (fColorKey == colorKey || fIdx < 0) return;
        fColorKey = colorKey;
        String keys = "rkwgcdybBsS";
        int[] rgb = {0xD8343A, 0x1A1A1A, 0xE8F0C8, 0x3C8A3A, 0xC8904A, 0x4A2A14, 0xF0D040, 0x9A5A24, 0xE8C890, 0xF6F6F0, 0xD0D0D8};
        for (int j = 0; j < keys.length(); j++) fPal[keys.charAt(j)] = prefs.color(rgb[j], 1f);
        fCrumb = fPal[FOOD_MAIN.charAt(evType[fIdx])];
    }

    /** Food cells still there (the first `eaten` are gone). */
    private int foodEaten() {
        if (fIdx < 0) return 0;
        if (fBites <= 0) return fCells.length;
        return fCells.length - Math.round(fCells.length * fBites / (float) evBites[fIdx]);
    }

    /** Which food is down now; with resync, how much of it (and of the day's) is eaten by now. */
    private void updateFood(long t, boolean resync) {
        int k = -1;
        for (int j = 0; j < nFood; j++) if (evStart[j] <= simSec) k = j;
        if (k < 0) { fIdx = -1; if (resync) stored = 0; return; }
        if (k != fIdx || resync) {
            int age = simSec - evStart[k];
            if (k != fIdx) setFood(k);
            if (age < 20 && !resync) {
                fBites = evBites[k];
                fFall = t;                                     // it drops onto the grass
            } else if (resync) {
                float eaten = Math.max(0f, Math.min(1f, (age - 60) / (float) (evBites[k] * BITE_S)));
                fBites = Math.round(evBites[k] * (1 - eaten));
                fFall = age < 3 ? t : -1;
                stored = evBites[k] - fBites;
                for (int j = 0; j < k; j++) stored += evBites[j];
            } else {
                fBites = evBites[k];
                fFall = -1;
            }
        }
    }

    private int foragersWanted() {
        if (fIdx < 0 || fBites <= 0 || fFall >= 0 || foodChamber == 0 || !nodeReady(foodChamber)) return 0;
        return Math.min(MAX_FORAGERS, 1 + (simSec - evStart[fIdx]) / 15);   // more join as word spreads
    }

    /** The column a forager stands at to bite the food. */
    private int foodSpot() {
        return evX[fIdx] > entrance ? evX[fIdx] - 2 : evX[fIdx] + fW + 1;
    }

    // ---- the nest as a graph ----

    private boolean pathReady(int e) { return pathDone.get(e) <= shown; }

    private boolean nodeReady(int n) {
        if (n == 0) return true;
        if (n <= nChambers) return chamberDone[n - 1] <= shown;
        return n < nNodes && nodeEdge[n] >= 0 && pathReady(nodeEdge[n]);
    }

    /** Cells from node `from` to node `to` through finished tunnels (null if there is no way). */
    private int[] route(int from, int to) {
        if (from == to || from < 0 || to < 0) return null;
        int[] prevE = new int[nNodes];
        Arrays.fill(prevE, -2);
        prevE[from] = -1;
        int[] queue = new int[nNodes];
        int qh = 0, qt = 0;
        queue[qt++] = from;
        while (qh < qt && prevE[to] == -2) {
            int u = queue[qh++];
            for (int e = 0; e < paths.size(); e++) {
                if (!pathReady(e)) continue;
                int[] pe = pathEnds.get(e);
                int v = pe[0] == u ? pe[1] : pe[1] == u ? pe[0] : -1;
                if (v < 0 || prevE[v] != -2 || (v != to && !nodeReady(v))) continue;
                prevE[v] = e;
                queue[qt++] = v;
            }
        }
        if (prevE[to] == -2) return null;
        int[] edges = new int[nNodes];
        int n = 0, len = 0;
        for (int v = to; v != from; ) {
            int e = prevE[v];
            edges[n++] = e;
            len += paths.get(e).length;
            int[] pe = pathEnds.get(e);
            v = pe[0] == v ? pe[1] : pe[0];
        }
        int[] out = new int[len];
        int o = 0, u = from;
        for (int k = n - 1; k >= 0; k--) {
            int e = edges[k];
            int[] p = paths.get(e), pe = pathEnds.get(e);
            boolean fwd = pe[0] == u;
            for (int j = 0; j < p.length; j++) out[o++] = p[fwd ? j : p.length - 1 - j];
            u = fwd ? pe[1] : pe[0];
        }
        return out;
    }

    /** From the entrance to the face being dug now (null when nothing is being dug). */
    private int[] faceRoute() {
        if (shown <= 0 || shown >= nOrder) return null;
        for (int k = 0; k < nChambers; k++) {
            if (chamberDone[k] <= shown) continue;
            for (int e = 0; e < paths.size(); e++) {
                if (pathEnds.get(e)[1] == k + 1 && pathReady(e)) return route(0, k + 1);
            }
        }
        for (int e = 0; e < paths.size(); e++) {
            if (pathReady(e)) continue;
            int a = pathEnds.get(e)[0];
            int[] base = a == 0 ? new int[0] : route(0, a);
            if (base == null) return null;
            int[] p = paths.get(e);
            int n = 0;
            while (n < p.length && dug[p[n]]) n++;
            if (base.length + n < 2) return null;
            int[] out = Arrays.copyOf(base, base.length + n);
            System.arraycopy(p, 0, out, base.length, n);
            return out;
        }
        return null;
    }

    /** Over the grass from column x to the entrance, down through the mound, then `in`. */
    private int[] outside(int x, int[] in) {
        int top = Math.max(1, Math.min(ground - 1, surfaceAt(entrance) - 1));   // row 0 means "surface"
        int nH = Math.abs(x - entrance), nV = ground - top;
        int[] out = new int[nH + nV + in.length];
        int o = 0, s = x > entrance ? -1 : 1;
        for (int k = 0; k < nH; k++) out[o++] = x + s * k;                 // row 0: the surface
        for (int y = top; y < ground; y++) out[o++] = y * cols + entrance;   // inside the mound
        System.arraycopy(in, 0, out, o, in.length);
        return out;
    }

    private int[] forageRoute() {
        if (fIdx < 0 || foodChamber == 0) return null;
        int[] in = route(0, foodChamber);
        return in == null ? null : outside(foodSpot(), in);
    }

    private int[] soilRoute() {
        int[] in = faceRoute();
        if (in == null) return null;
        int dump = entrance + (rnd.nextBoolean() ? 1 : -1) * (2 + rnd.nextInt(5)) * unit;
        return outside(Math.max(2, Math.min(cols - 3, dump)), in);
    }

    private int[] line(int x0, int x1, int y) {
        int n = Math.abs(x1 - x0) + 1, s = x1 >= x0 ? 1 : -1;
        int[] out = new int[n];
        for (int k = 0; k < n; k++) out[k] = y * cols + x0 + s * k;
        return out;
    }

    /** A random finished node other than `not` (chambers more likely), or -1. */
    private int randomNode(int not) {
        int total = 0;
        for (int n = 0; n < nNodes; n++) if (n != not && nodeReady(n)) total += n >= 1 && n <= nChambers ? 3 : 1;
        if (total == 0) return -1;
        int pick = rnd.nextInt(total);
        for (int n = 0; n < nNodes; n++) {
            if (n == not || !nodeReady(n)) continue;
            pick -= n >= 1 && n <= nChambers ? 3 : 1;
            if (pick < 0) return n;
        }
        return -1;
    }

    private int readyChambers() {
        int n = 0;
        for (int k = 0; k < nChambers; k++) if (chamberDone[k] <= shown) n++;
        return n;
    }

    // ---- positions ----

    private int cellX(int c) { return c % cols; }

    private int cellY(int c) {
        int y = c / cols;
        return y == 0 ? surfaceAt(c % cols) - 1 : y;
    }

    private int curCell(int k) {
        int[] r = aRoute[k];
        return r[Math.max(0, Math.min(r.length - 1, (int) aPos[k]))];
    }

    /** Which way a walker on route r at index idx, going dir, faces (0 right, 1 left, 2 down, 3 up). */
    private int heading(int[] r, int idx, int dir, int keep) {
        int j = Math.max(0, Math.min(r.length - 1, idx + 4 * dir));
        int sign = 1;
        if (j == idx) { j = Math.max(0, Math.min(r.length - 1, idx - 4 * dir)); sign = -1; }
        int hx = (cellX(r[j]) - cellX(r[idx])) * sign, hy = (cellY(r[j]) - cellY(r[idx])) * sign;
        if (hx == 0 && hy == 0) return keep;
        return Math.abs(hx) >= Math.abs(hy) ? (hx >= 0 ? 0 : 1) : (hy >= 0 ? 2 : 3);
    }

    /** The tunnel floor under (x, y): the lowest open cell below it. */
    private int floorBelow(int x, int y) {
        int yy = y;
        for (int k = 0; k < 24 && yy + 1 < rows && dug[(yy + 1) * cols + x]; k++) yy++;
        return yy;
    }

    /** The floor under a body from column x0, w wide: the highest of the floors below it. */
    private int floorUnder(int x0, int w, int y) {
        int f = Integer.MAX_VALUE;
        for (int x = Math.max(0, x0); x < Math.min(cols, x0 + w); x++) {
            if (dug[y * cols + x]) f = Math.min(f, floorBelow(x, y));
        }
        return f == Integer.MAX_VALUE ? y : f;
    }

    /** The tunnel wall beside (x, y) in direction s (the last open cell). */
    private int wallBeside(int x, int y, int s) {
        int xx = x;
        for (int k = 0; k < 14 && xx + s > 0 && xx + s < cols - 1 && dug[y * cols + xx + s]; k++) xx += s;
        return xx;
    }

    // ---- the ants' day ----

    private int spawn(int job, int[] route, float pos, int dir, int from, int to) {
        if (nAnts >= MAX_ANTS || route == null || route.length < 2) return -1;
        int k = nAnts++;
        aJob[k] = job;
        setRoute(k, route, pos, dir, from, to);
        aCarry[k] = 0;
        aLane[k] = rnd.nextBoolean() ? 1 : -1;
        aSpeed[k] = (1.9f + rnd.nextFloat() * 1.4f) * Math.max(1, unit) / 2f;
        aPause[k] = 0;
        aCool[k] = 0;
        aRounds[k] = 0;
        aFace[k] = 0;
        return k;
    }

    private void setRoute(int k, int[] route, float pos, int dir, int from, int to) {
        aRoute[k] = route;
        aPos[k] = Math.max(0, Math.min(route.length - 1, pos));
        aDir[k] = dir;
        aFrom[k] = from;
        aTo[k] = to;
    }

    /** Potter about in chamber `node`: walk its floor to a spot near aroundX. */
    private boolean mill(int k, int node, int aroundX, int spread) {
        if (node < 1 || node > nChambers) return false;
        int[] ch = chambers[node - 1];
        int lim = ch[2] * 3 / 4;
        int x0 = Math.max(ch[0] - lim, Math.min(ch[0] + lim, cellX(curCell(k))));
        int x1 = aroundX + rnd.nextInt(2 * spread + 1) - spread;
        x1 = Math.max(ch[0] - lim, Math.min(ch[0] + lim, x1));
        if (Math.abs(x1 - x0) < 2) x1 = x0 + (x0 < ch[0] ? 3 : -3);
        setRoute(k, line(x0, x1, ch[1]), 0, 1, node, node);
        return true;
    }

    private int count(int job) {
        int n = 0;
        for (int k = 0; k < nAnts; k++) if (aJob[k] == job) n++;
        return n;
    }

    /** An ant at node `node` with nothing to do picks its next thing. */
    private void nextWander(int k, int node) {
        aJob[k] = J_WANDER;
        int want = foragersWanted();
        if (want > 0 && count(J_FORAGE) + count(J_TO_PANTRY) < want) {
            if (node == foodChamber) {
                int[] l = forageRoute();
                if (l != null) { aJob[k] = J_FORAGE; setRoute(k, l, l.length - 1, -1, -1, foodChamber); return; }
            } else {
                int[] r = route(node, foodChamber);
                if (r != null) { aJob[k] = J_TO_PANTRY; setRoute(k, r, 0, 1, node, foodChamber); return; }
            }
        }
        if (node >= 1 && node <= nChambers && rnd.nextFloat() < 0.5f) {
            aJob[k] = J_MILL;
            aRounds[k] = 1 + rnd.nextInt(4);
            mill(k, node, chambers[node - 1][0], chambers[node - 1][2]);
            aPause[k] = rnd.nextInt(1500);
            return;
        }
        int m = randomNode(node);
        int[] r = m >= 0 ? route(node, m) : null;
        if (r != null) { setRoute(k, r, 0, 1, node, m); return; }
        if (!mill(k, node, node >= 1 && node <= nChambers ? chambers[node - 1][0] : 0, 6)) aPause[k] = 3000;
        else aJob[k] = J_MILL;
    }

    /** From the surface (where it stands) down into the nest, somewhere. */
    private void goDown(int k) {
        aJob[k] = J_WANDER;
        int m = randomNode(0);
        int[] in = m > 0 ? route(0, m) : null;
        if (in == null) { in = new int[]{ground * cols + entrance, (ground + 1) * cols + entrance}; m = 0; }
        setRoute(k, outside(cellX(curCell(k)), in), 0, 1, -1, m);
    }

    private void arrive(int k, boolean atEnd) {
        int node = atEnd ? aTo[k] : aFrom[k];
        switch (aJob[k]) {
            case J_FORAGE:
                if (!atEnd) {                       // at the food: bite off a piece
                    aPause[k] = 900 + rnd.nextInt(1100);
                    if (fIdx >= 0 && fBites > 0) { fBites--; aCarry[k] = fCrumb; }
                    aDir[k] = 1;
                    return;
                }
                if (aCarry[k] != 0) { aCarry[k] = 0; stored++; aPause[k] = 500 + rnd.nextInt(900); }
                if (foragersWanted() > 0 && count(J_FORAGE) <= foragersWanted()) {
                    int[] l = forageRoute();
                    if (l != null) { setRoute(k, l, l.length - 1, -1, -1, foodChamber); return; }
                }
                nextWander(k, foodChamber);
                return;
            case J_TO_PANTRY:
                int[] l = forageRoute();
                if (l != null && foragersWanted() > 0) { aJob[k] = J_FORAGE; setRoute(k, l, l.length - 1, -1, -1, foodChamber); }
                else nextWander(k, node);
                return;
            case J_SOIL:
                if (!atEnd) {                       // on the mound: drop the soil
                    aCarry[k] = 0;
                    aPause[k] = 400 + rnd.nextInt(700);
                    int[] s = soilRoute();
                    if (s != null) setRoute(k, s, 0, 1, -1, -1);
                    else goDown(k);
                } else {                            // at the face: dig a crumb loose
                    aPause[k] = 1500 + rnd.nextInt(2500);
                    aCarry[k] = pal[RIM];
                    int[] s = soilRoute();
                    if (s != null) setRoute(k, s, s.length - 1, -1, -1, -1);
                    else aDir[k] = -1;
                }
                return;
            case J_NURSE:
                if (qNode < 0) { nextWander(k, node); return; }
                aPause[k] = 700 + rnd.nextInt(3500);
                if (rnd.nextFloat() < 0.3f) aCarry[k] = aCarry[k] == 0 ? pal[EGG] : 0;   // tending the eggs
                mill(k, qNode, besideQueen(), unit);
                return;
            case J_ESCORT:
                return;
            case J_MILL:
                if (--aRounds[k] > 0 && mill(k, node, chambers[node - 1][0], chambers[node - 1][2])) {
                    aPause[k] = 500 + rnd.nextInt(rnd.nextFloat() < 0.3f ? 15000 : 3000);   // a rest
                    return;
                }
                nextWander(k, node);
                return;
            default:
                if (node < 0) goDown(k);
                else nextWander(k, node);
        }
    }

    /** A spot just beside the queen (in front of her or behind, past her eggs), for a nurse. */
    private int besideQueen() {
        int side = rnd.nextBoolean() ? 1 : -1;
        return Math.round(qX) + side * (QUEEN_A.w / 2 + 4 + rnd.nextInt(4) + (side == (qFace == 0 ? -1 : 1) ? 10 : 0));
    }

    private void settleQueen(int n) {
        qNode = n;
        qRoute = null;
        qX = qGoal = chambers[n - 1][0];
        qWait = 3000;
        for (int k = 0; k < nAnts; k++) {
            if (aJob[k] != J_ESCORT) continue;
            aJob[k] = J_NURSE;
            if (!mill(k, n, besideQueen(), unit)) nextWander(k, 0);
        }
    }

    private void updateQueen(long dtMs) {
        int want = -1;
        for (int k = 0; k < nChambers; k++) {
            if (chamberDone[k] <= shown && (want < 0 || chambers[k][1] > chambers[want - 1][1])) want = k + 1;
        }
        if (want < 0) { qNode = -1; qRoute = null; return; }
        if (qNode < 0) settleQueen(want);
        float dt = dtMs / 1000f;
        if (qRoute == null && want != qNode) {
            int[] r = route(qNode, want);
            if (r == null) settleQueen(want);
            else {
                // Moving day: she walks to the new chamber, her nurses close behind.
                qRoute = r;
                qPos = 0;
                qTo = want;
                int j = 0;
                for (int k = 0; k < nAnts; k++) {
                    if (aJob[k] != J_NURSE) continue;
                    aJob[k] = J_ESCORT;
                    aRoute[k] = r;
                    aRounds[k] = ++j;
                    aDir[k] = 1;
                    aCarry[k] = 0;
                }
            }
        }
        if (qRoute != null) {
            qPos += 1.3f * Math.max(1, unit) / 2f * dt;
            if (qPos >= qRoute.length - 1) { settleQueen(qTo); return; }
            for (int k = 0; k < nAnts; k++) {
                if (aJob[k] == J_ESCORT) aPos[k] = Math.max(0, qPos - 8 * Math.max(1, unit) * aRounds[k]);
            }
            return;
        }
        // At rest: she shuffles about the chamber now and then.
        if (qWait > 0) qWait -= dtMs;
        else if (Math.abs(qGoal - qX) < 0.5f) {
            int[] ch = chambers[qNode - 1];
            qGoal = ch[0] + (rnd.nextFloat() - 0.5f) * ch[2] * 0.7f;
            qWait = 5000 + rnd.nextInt(15000);
        } else {
            float step = 0.6f * dt * Math.max(1, unit) / 2f;
            qFace = qGoal > qX ? 0 : 1;
            qX = Math.abs(qGoal - qX) <= step ? qGoal : qX + Math.signum(qGoal - qX) * step;
        }
    }

    /** The colony from scratch (start, or back from a gap): everyone somewhere along their way. */
    private void seedColony(long t) {
        nAnts = 0;
        qNode = -1;
        qRoute = null;
        updateFood(t, true);
        updateQueen(0);
        for (int k = 0; k < 2; k++) {
            int[] s = soilRoute();
            if (s == null) break;
            int dir = rnd.nextBoolean() ? 1 : -1;
            int a = spawn(J_SOIL, s, rnd.nextInt(s.length), dir, -1, -1);
            if (a >= 0 && dir < 0) aCarry[a] = pal[RIM];
        }
        if (qNode > 0) for (int k = 0; k < 2; k++) {
            int a = spawn(J_NURSE, line(0, 1, 0), 0, 1, qNode, qNode);
            if (a >= 0) { setRoute(a, line(Math.round(qX), Math.round(qX) + 1, chambers[qNode - 1][1]), 0, 1, qNode, qNode); mill(a, qNode, besideQueen(), unit); }
        }
        int want = foragersWanted();
        for (int k = 0; k < want; k++) {
            int[] l = forageRoute();
            if (l == null) break;
            int dir = rnd.nextBoolean() ? 1 : -1;
            int a = spawn(J_FORAGE, l, rnd.nextInt(l.length), dir, -1, foodChamber);
            if (a >= 0 && dir > 0) aCarry[a] = fCrumb;
        }
        int wander = Math.min(6, readyChambers() + 2);
        for (int k = 0; k < wander && readyChambers() > 0; k++) {
            int n = randomNode(-1), m = randomNode(n);
            int[] r = route(n, m);
            if (r != null) spawn(J_WANDER, r, rnd.nextInt(r.length), 1, n, m);
        }
        lastSpawn = t;
    }

    private void simulate(Calendar c, long t, boolean gap) {
        simSec = (int) (msOfDay(c) / 1000);
        long dtMs = simT < 0 ? 0 : t - simT;
        simT = t;
        if (!colonyLive || gap || dtMs > 5000) {
            seedColony(t);
            colonyLive = true;
            dtMs = 0;
        }
        dtMs = Math.min(dtMs, 1000);
        float dt = dtMs / 1000f;
        updateFood(t, false);
        if (fFall >= 0 && t - fFall > 1400) fFall = -1;
        foodColors();
        updateQueen(dtMs);

        // Keep the colony staffed: soil carriers while digging, two nurses, foragers while
        // there is food, a few wanderers. New ants come up out of a chamber (or the face).
        if (t - lastSpawn > 1500 && nAnts < MAX_ANTS) {
            int a = -1;
            if (count(J_SOIL) < 2 && shown > 0 && shown < nOrder) {
                int[] s = soilRoute();
                a = spawn(J_SOIL, s, s == null ? 0 : s.length - 1, -1, -1, -1);
                if (a >= 0) { aCarry[a] = pal[RIM]; aPause[a] = 1000; }
            } else if (qNode > 0 && qRoute == null && count(J_NURSE) < 2) {
                int y = chambers[qNode - 1][1], x = Math.round(qX);
                a = spawn(J_NURSE, line(x, x + 1, y), 0, 1, qNode, qNode);
                if (a >= 0) mill(a, qNode, besideQueen(), unit);
            } else if (count(J_FORAGE) + count(J_TO_PANTRY) < foragersWanted() && t - lastSpawn > 5000) {
                int[] l = forageRoute();
                a = l == null ? -1 : spawn(J_FORAGE, l, l.length - 1, -1, -1, foodChamber);
            } else if (count(J_WANDER) + count(J_MILL) < Math.min(6, readyChambers() + 2) && readyChambers() > 0) {
                int n = randomNode(0);
                if (n >= 1 && n <= nChambers) {
                    a = spawn(J_MILL, line(chambers[n - 1][0], chambers[n - 1][0] + 1, chambers[n - 1][1]), 0, 1, n, n);
                    if (a >= 0) { aRounds[a] = 1 + rnd.nextInt(3); mill(a, n, chambers[n - 1][0], chambers[n - 1][2]); }
                }
            }
            if (a >= 0) lastSpawn = t;
        }

        for (int k = 0; k < nAnts; k++) {
            if (aJob[k] == J_ESCORT) continue;
            if (aPause[k] > 0) { aPause[k] -= dtMs; continue; }
            boolean errand = aJob[k] == J_WANDER || aJob[k] == J_FORAGE || aJob[k] == J_SOIL || aJob[k] == J_TO_PANTRY;
            if (errand && rnd.nextFloat() < 0.07f * dt) { aPause[k] = 300 + rnd.nextInt(1400); continue; }
            // Now and then a wanderer changes its mind and turns back.
            if (aJob[k] == J_WANDER && aFrom[k] >= 0 && rnd.nextFloat() < 0.015f * dt) {
                aDir[k] = -aDir[k];
            }
            float sp = aSpeed[k] * (aJob[k] == J_MILL || aJob[k] == J_NURSE ? 0.6f : 1f);
            if (aCarry[k] != 0 && aJob[k] == J_FORAGE) sp *= 0.85f;   // loaded
            aPos[k] += aDir[k] * sp * dt;
            int last = aRoute[k].length - 1;
            if (aDir[k] > 0 && aPos[k] >= last) { aPos[k] = last; arrive(k, true); }
            else if (aDir[k] < 0 && aPos[k] <= 0) { aPos[k] = 0; arrive(k, false); }
        }

        // Two meeting head-on stop for a moment (antennae). Where each is and which way it
        // faces, worked out once.
        for (int i = 0; i < nAnts; i++) {
            int ci = curCell(i);
            mX[i] = cellX(ci);
            mY[i] = ci / cols == 0 ? -100 : ci / cols;          // not on the surface
            mF[i] = aPause[i] > 0 || t < aCool[i] || aJob[i] == J_ESCORT || mY[i] < 0 ? -1
                    : heading(aRoute[i], (int) aPos[i], aDir[i], aFace[i]);
        }
        for (int i = 0; i < nAnts; i++) {
            if (mF[i] < 0) continue;
            for (int j = i + 1; j < nAnts; j++) {
                if (mF[j] != (mF[i] ^ 1)) continue;
                if (Math.abs(mX[j] - mX[i]) > 4 || Math.abs(mY[j] - mY[i]) > 4) continue;
                aPause[i] = 500 + rnd.nextInt(800);
                aPause[j] = 500 + rnd.nextInt(800);
                aCool[i] = aCool[j] = t + 8000;
                mF[j] = -1;                                      // one meeting at a time
                break;
            }
        }

        int step = totalBites == 0 ? 0 : Math.min(32, stored * 32 / totalBites);
        if (step != pantryStep) { pantryStep = step; pantryDirty = true; }
    }

    private void recolorChamber(int c) {
        int[] ch = chambers[c - 1];
        int x0 = Math.max(0, ch[0] - ch[2] - 3), x1 = Math.min(cols - 1, ch[0] + ch[2] + 3);
        int y0 = Math.max(0, ch[1] - ch[3] - 3), y1 = Math.min(rows - 1, ch[1] + ch[3] + 3);
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) recolor(y * cols + x);
        addCur(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    // ---- drawing the colony ----

    /** Works out where the food, the queen and each ant are drawn, and marks those areas. */
    private void placeColony(long t) {
        // Food: its bottom on the ground (or the mound), dropping in when it has just landed.
        foodY = -1;
        if (fIdx >= 0 && foodEaten() < fCells.length) {
            foodY = surfaceAt(evX[fIdx] + fW / 2) - fH;
            if (fFall >= 0) {
                float p = (t - fFall) / 1400f;
                if (p >= 1f) fFall = -1;
                else foodY -= Math.round((1 - p) * (1 - p) * foodY);
            }
            addCur(evX[fIdx] - 1, foodY - 1, fW + 2, fH + 2);
        }
        // The queen.
        qpX = -9999;
        if (qNode > 0) {
            int x, y, face;
            if (qRoute != null) {
                int idx = Math.max(0, Math.min(qRoute.length - 1, (int) qPos));
                x = cellX(qRoute[idx]);
                y = cellY(qRoute[idx]);
                qFace = heading(qRoute, idx, 1, qFace);
                qpFr = idx & 1;
            } else {
                x = Math.round(qX);
                y = chambers[qNode - 1][1];
                qpFr = Math.abs(qGoal - qX) > 0.5f && qWait <= 0 ? (int) (qX) & 1 : 0;
            }
            face = qFace;
            int len = QUEEN_A.w, thick = QUEEN_A.h;
            if (face < 2) {
                qpX = x - len / 2;
                // Resting, she lies on the highest floor under her (chamber floors curve).
                qpY = (qRoute == null ? floorUnder(qpX, len, y) : floorBelow(x, y)) - thick + 1;
            } else {
                qpX = wallBeside(x, y, 1) - thick + 1;
                qpY = y - len / 2;
            }
            addCur(qpX - 8, qpY - 2, len + 16, len + 4);
        }
        // The workers.
        for (int k = 0; k < nAnts; k++) {
            pOn[k] = false;
            int[] r = aRoute[k];
            int idx = Math.max(0, Math.min(r.length - 1, (int) aPos[k]));
            int c = r[idx], x = cellX(c), y = cellY(c);
            if (aJob[k] == J_ESCORT && aPos[k] <= 0 && qPos < 4) continue;
            if (aPause[k] <= 0) aFace[k] = heading(r, idx, aDir[k], aFace[k]);
            int face = aFace[k];
            if (face < 2) {
                pX[k] = x - WALK_A.w / 2;
                pY[k] = (y < ground ? surfaceAt(x) - 1 : floorBelow(x, y)) - WALK_A.h + 1;
            } else if (y < ground) {
                pX[k] = x - 1;
                pY[k] = y - UPRIGHT.h / 2;
            } else {
                int wall = wallBeside(x, y, aLane[k]);
                pX[k] = aLane[k] > 0 ? wall - UPRIGHT.w + 1 : wall;
                pY[k] = y - UPRIGHT.h / 2;
            }
            pFr[k] = aPause[k] > 0 ? 0 : idx & 1;
            pOn[k] = true;
            addCur(pX[k] - 2, pY[k] - 2, 9, 9);
        }
    }

    private void drawColony() {
        if (foodY >= 0) {
            for (int j = foodEaten(); j < fCells.length; j++) {
                int x = evX[fIdx] + (fCells[j] & 63), y = foodY + (fCells[j] >> 6);
                if (x >= 0 && x < cols && y >= 0 && y < ground) px[y * cols + x] = fPal[fCol[j]];
            }
        }
        if (qpX > -9999) {
            // Her eggs lie behind her while she rests.
            if (qRoute == null) {
                int n = 4 + Math.round(eggFill * 3);
                int floorY = qpY + QUEEN_A.h - 1;
                for (int e = 0; e < n; e++) {
                    int ey = floorY - EGG_Y[e];
                    int ex = qFace == 0 ? qpX - 3 - EGG_X[e] : qpX + QUEEN_A.w + 1 + EGG_X[e];
                    plotAnt(ex, ey, pal[EGG], true);
                    plotAnt(ex + 1, ey, pal[EGG], true);
                }
            }
            drawQueen(qpX, qpY, qFace, qpFr);
        }
        for (int k = 0; k < nAnts; k++) {
            if (pOn[k]) drawAnt(pX[k], pY[k], aFace[k], pFr[k], aCarry[k], true, pY[k] + 2 < ground ? pal[WORKER_SKY] : pal[WORKER]);
        }
    }

    private static final int[] EGG_X = {0, 3, 6, 1, 9, 4, 7}, EGG_Y = {0, 0, 0, 1, 0, 1, 1};

    /** The queen, twice a worker's length, facing dir (side view; down/up turned 90 degrees). */
    private void drawQueen(int x, int y, int dir, int frame) {
        drawSprite(frame == 0 ? QUEEN_A : QUEEN_B, x, y, dir, pal[QUEEN], true);
    }

    /**
     * A side-view sprite (head on the right) facing dir: 0 right, 1 left, 2 down, 3 up (turned
     * 90 degrees, so it is s.h wide and s.w tall).
     */
    private void drawSprite(Sprite s, int x, int y, int dir, int col, boolean airOnly) {
        int w = dir < 2 ? s.w : s.h, h = dir < 2 ? s.h : s.w;
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
            boolean on;
            if (dir == 0) on = s.on(xx, yy);
            else if (dir == 1) on = s.on(s.w - 1 - xx, yy);
            else if (dir == 2) on = s.on(yy, xx);
            else on = s.on(s.w - 1 - yy, xx);
            if (on) plotAnt(x + xx, y + yy, col, airOnly);
        }
    }

    // ---- sprites (original pixel art) ----

    static final class Sprite {
        final String[] r;
        final int w, h;
        Sprite(String... r) { this.r = r; w = r[0].length(); h = r.length; }
        boolean on(int x, int y) { return r[y].charAt(x) == '#'; }
    }

    /** The queen (head on the right): a big gaster, thorax, head and feelers; two leg frames. */
    static final Sprite QUEEN_A = new Sprite(
            ".............#.",
            "..#####....##.#",
            ".#######.#####.",
            "#############..",
            ".######..#.....",
            ".......#.#.#...");
    static final Sprite QUEEN_B = new Sprite(
            ".............#.",
            "..#####....##.#",
            ".#######.#####.",
            "#############..",
            ".######..#.....",
            "......#.#.#....");

    /** Food that lands on the grass (colour letters; see foodColors) and its crumbs' colour. */
    private static final String[][] FOOD_ART = {
            {"....rrrrrr....",      // a slice of watermelon
             "..rrrkrrrrkr..",
             ".rrkrrrrkrrrr.",
             "rrrrrrkrrrrrkr",
             "wwwwwwwwwwwwww",
             "gggggggggggggg"},
            {"...cccccc...",        // a cookie
             ".ccdccccdcc.",
             "cccccccccccc",
             "ccdcccdcccdc",
             ".cccccccccc."},
            {"...g.gg.g...",        // a strawberry
             "..gggggggg..",
             ".rrryrrrryr.",
             "rryrrrryrrrr",
             ".rrrrryrrrr.",
             "..ryrrrrrr..",
             "....rrrr...."},
            {"..bbbbbbbbbb..",      // a crust of bread
             ".bBBBBBBBBBBb.",
             "bBBBBBBBBBBBBb",
             "bBBBBBBBBBBBBb",
             "bbbbbbbbbbbbbb"},
            {"ssssssss",            // a sugar cube
             "sSssssSs",
             "ssssssss",
             "ssSsssss",
             "sssssSss",
             "SSSSSSSS"}};
    private static final String FOOD_MAIN = "rcrBs";
    /** A worker, one cell per pixel, walking (head on the right); two leg frames. */
    static final Sprite WALK_A = new Sprite(
            "....#",
            "##.##",
            ".#.#.");
    static final Sprite WALK_B = new Sprite(
            "....#",
            "##.##",
            "#.#.#");
    /** The size of a worker turned to go up or down (see drawSprite). */
    static final Sprite UPRIGHT = new Sprite("###", "###", "###", "###", "###");
}
