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
    private int queenChamber, eggChamber, foodChamber;   // chamber ids (1..n) for the extras
    private final int[][] chambers = new int[12][];      // {cx, cy, rx, ry}
    private int nChambers;
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
            tunnel(r, nx[from], ny[from], nx[to], ny[to], rad, from == 0);
            dugChamber(to - 1, nx[from], ny[from]);
            done[to] = true;
        }
        // Then the loops, shallowest first, and a couple of side tunnels off to the edges.
        for (int[] e : loops) tunnel(r, nx[e[0]], ny[e[0]], nx[e[1]], ny[e[1]], rad, false);
        for (int k = 0; k < 2 && nChambers > 0; k++) {
            int c = r.nextInt(nChambers);
            float tx = chambers[c][0] < cols / 2 ? cols * 0.04f : cols * 0.96f;
            float ty = chambers[c][1] + (r.nextFloat() - 0.5f) * soilH * 0.4f;
            ty = Math.max(soilTop + rad + 2, Math.min(soilBot - rad - 2, ty));
            tunnel(r, chambers[c][0], chambers[c][1], tx, ty, rad - 1, false);
        }
        // Roles for the two-week extras: the deepest chamber is the queen's, then eggs, food.
        Integer[] byDepth = new Integer[nChambers];
        for (int k = 0; k < nChambers; k++) byDepth[k] = k;
        Arrays.sort(byDepth, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return chambers[b][1] - chambers[a][1]; }
        });
        queenChamber = nChambers > 0 ? byDepth[0] + 1 : 0;
        eggChamber = nChambers > 1 ? byDepth[1] + 1 : 0;
        foodChamber = nChambers > 2 ? byDepth[2] + 1 : 0;

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
    private void tunnel(Random r, float x0, float y0, float x1, float y1, int rad, boolean fromEntrance) {
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
    }

    // ---- palette ----

    private static final int SKY = 1, SOIL0 = 2, RIM = 5, TUNNEL = 6, SHADOW = 7, WATER0 = 8,
            WATER_TOP = 10, GRASS0 = 11, TEXT = 13, ANT = 14, WORKER = 15, CRUMB = 16, POUR = 17,
            ROCK0 = 18, BONE = 20, QUEEN = 21, EGG = 22, SEED = 23, DEW = 24, NOTE = 25,
            HILL0 = 26, N_PAL = 26 + 25 * 4;
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
        pal[CRUMB] = prefs.color(0xE8D8A8, 1f);
        pal[ROCK0] = prefs.color(0x3A322A, st < 2 ? 0.7f : 1f);
        pal[ROCK0 + 1] = prefs.color(0x2C2620, st < 2 ? 0.7f : 1f);
        pal[BONE] = prefs.color(0xD8CCB0, 0.6f);
        pal[QUEEN] = prefs.color(0x6A2A14, 1f);
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

    /** What the two-week extras put in a chamber: the queen, eggs, stored seeds (0 = nothing). */
    private int contents(int c, int x, int y) {
        if (c == queenChamber && queenShown) {
            int[] ch = chambers[c - 1];
            int qw = QUEEN_ART[0].length() * unit, qh = QUEEN_ART.length * unit;
            int qx = x - (ch[0] - qw / 2), qy = y - (ch[1] + ch[3] / 2 - qh + unit);
            if (qx >= 0 && qy >= 0 && qx < qw && qy < qh && QUEEN_ART[qy / unit].charAt(qx / unit) == '#') return QUEEN;
        }
        int[] ch = chambers[c - 1];
        int hsh = (y * cols + x) * 0x9E3779B1;
        boolean floor = y >= ch[1] + ch[3] / 4;
        if (c == eggChamber && floor && ((hsh >>> 27) & 7) < Math.round(8 * eggFill) && ((x + y) & 1) == 0) return EGG;
        if (c == foodChamber && floor && ((hsh >>> 27) & 7) < Math.round(8 * seedFill)) return SEED;
        return 0;
    }

    private boolean queenShown;
    private float eggFill, seedFill;

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
        queenShown = g >= 0.25f;
        eggFill = Math.min(1f, Math.max(0f, (g - 0.5f) * 2f));
        seedFill = Math.min(1f, Math.max(0f, (g - 0.75f) * 4f));
        long key = stats.period * 1000 + (queenShown ? 100 : 0) + Math.round(eggFill * 8) * 10 + Math.round(seedFill * 8);
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

    private static final int MAX_WORKERS = 14;
    private final int[] wX = new int[MAX_WORKERS], wY = new int[MAX_WORKERS], wDir = new int[MAX_WORKERS];
    private final int[] wFrame = new int[MAX_WORKERS];
    private final boolean[] wCrumb = new boolean[MAX_WORKERS];

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
            Sprite sp = dDir < 2 ? WALK_A : CLIMB_A;
            if (dDir == 0) { dx = hx - sp.w + 1; dy = hy - 1; }
            else if (dDir == 1) { dx = hx; dy = hy - 1; }
            else if (dDir == 2) { dx = hx - 1; dy = hy - sp.h + 1; }
            else { dx = hx - 1; dy = hy; }
            addCur(dx - 1, dy - 1, sp.w + 2, sp.h + 2);
        }

        // Workers walk the finished tunnels, back and forth, about 2.5 cells a second.
        int nw = 0;
        if (animate && floodP < 0) {
            int ready = 0;
            for (int k = 0; k < paths.size(); k++) if (pathDone.get(k) <= shown) ready++;
            int count = Math.min(MAX_WORKERS, ready * 2);
            for (int k = 0; k < count; k++) {
                // Two carry crumbs up the entrance tunnel (path 0) to the hill; the rest spread
                // over the finished tunnels.
                boolean carrier = k < 2;
                int[] path = paths.get(carrier ? 0 : (k * 7 + 3) % ready);
                if (path.length < 4) continue;
                long period = path.length * 2L * 400;               // 0.4 s per cell
                long ph = (t + k * 9_137L) % period;
                float q = ph / (float) (period / 2);
                boolean back = q > 1f;
                float a = back ? 2f - q : q;
                int idx = Math.min(path.length - 2, Math.round(a * (path.length - 2)));
                int i0 = path[idx], i1 = path[idx + 1];
                if (!dug[i0]) continue;
                int ddx = i1 % cols - i0 % cols, ddy = i1 / cols - i0 / cols;
                if (back) { ddx = -ddx; ddy = -ddy; }
                int dir = Math.abs(ddx) >= Math.abs(ddy) ? (ddx >= 0 ? 0 : 1) : (ddy >= 0 ? 2 : 3);
                Sprite sp = dir < 2 ? WALK_A : CLIMB_A;
                // Walk along the tunnel floor (or one side of a shaft), not its middle.
                int side = (k % 2 == 0 ? 1 : -1) * unit;
                wX[nw] = i0 % cols - sp.w / 2 + (dir >= 2 ? side : 0);
                wY[nw] = i0 / cols - sp.h / 2 + (dir < 2 ? side : 0);
                wDir[nw] = dir;
                wFrame[nw] = idx % 2;
                wCrumb[nw] = carrier && back;      // heading back up toward the entrance
                addCur(wX[nw] - 1, wY[nw] - 1, sp.w + 2, sp.h + 2);
                nw++;
            }
        }

        if (nDrops > 0) addCur(0, 0, cols, ground);

        if (fullDirty) System.arraycopy(base, 0, px, 0, px.length);
        else {
            restorePrev();
            restore(cur, nCur);
        }

        drawAnt(sx, sy, right ? 0 : 1, sFrame, true, false, pal[ANT]);
        if (dx > -9999) {
            int f = animate ? (int) ((t / 500) % 2) : 0;
            drawAnt(dx, dy, dDir, f, lastDigAt >= 0 && t - lastDigAt < 1500, false, pal[WORKER]);
        }
        for (int k = 0; k < nw; k++) drawAnt(wX[k], wY[k], wDir[k], wFrame[k], wCrumb[k], true, pal[WORKER]);
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
    private void drawAnt(int x, int y, int dir, int frame, boolean crumb, boolean airOnly, int col) {
        boolean side = dir < 2;
        Sprite s = side ? (frame == 0 ? WALK_A : WALK_B) : (frame == 0 ? CLIMB_A : CLIMB_B);
        boolean fx = dir == 1, fy = dir == 3;
        for (int yy = 0; yy < s.h; yy++) for (int xx = 0; xx < s.w; xx++) {
            if (!s.on(fx ? s.w - 1 - xx : xx, fy ? s.h - 1 - yy : yy)) continue;
            plotAnt(x + xx, y + yy, col, airOnly);
        }
        if (crumb) {
            int bx = dir == 0 ? x + s.w : dir == 1 ? x - 1 : x + s.w / 2;
            int by = dir == 2 ? y + s.h : dir == 3 ? y - 1 : y + 1;
            plotAnt(bx, by, pal[CRUMB], airOnly);
        }
    }

    private void plotAnt(int X, int Y, int col, boolean airOnly) {
        if (X < 0 || Y < 0 || X >= cols || Y >= rows) return;
        int i = Y * cols + X;
        if (airOnly && Y >= ground && (!dug[i] || water[i] || text[i] != 0)) return;
        px[i] = col;
    }

    /** True while something needs smooth frames (dig-in, the flood, sliding grains). */
    boolean busy() { return digInStart >= 0 || flooding() || nDrops > 0 || hill.hasActive(); }

    // ---- sprites (original pixel art) ----

    static final class Sprite {
        final String[] r;
        final int w, h;
        Sprite(String... r) { this.r = r; w = r[0].length(); h = r.length; }
        boolean on(int x, int y) { return r[y].charAt(x) == '#'; }
    }

    private static final String[] QUEEN_ART = {
            "...........#",
            "..####.###.#",
            ".###########",
            "######.####.",
            ".####.#.#.#.",
            "..#..#.#...."};
    /** Ants, one cell per pixel: walking (head on the right) and climbing (head down). */
    static final Sprite WALK_A = new Sprite(
            "....#",
            "##.##",
            ".#.#.");
    static final Sprite WALK_B = new Sprite(
            "....#",
            "##.##",
            "#.#.#");
    static final Sprite CLIMB_A = new Sprite(
            ".#.",
            "#.#",
            ".#.",
            "#.#",
            ".#.");
    static final Sprite CLIMB_B = new Sprite(
            "#.#",
            ".#.",
            "#.#",
            ".#.",
            ".#.");
}
