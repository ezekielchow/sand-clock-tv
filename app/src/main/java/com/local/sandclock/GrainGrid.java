package com.local.sandclock;

import java.util.Arrays;

/**
 * Falling-sand grid, the technique of classic sand games. Every cell is empty, sand or solid
 * (the clock's digits). A grain falls if the cell below is empty, otherwise slides one cell
 * diagonally, and on a gentler slope sometimes two cells sideways (random), which gives
 * ragged, natural piles at about 41°.
 *
 * Only grains that might move are processed ("awake"). A grain that can't move goes to
 * sleep until something next to it moves, so a settled pile costs nothing.
 */
final class GrainGrid {
    static final byte EMPTY = 0, SAND = 1, SOLID = 2;
    /**
     * Chance a grain on a gentle slope slides two cells sideways instead of stopping; higher
     * means a gentler pile that holds more sand. Tuned per drop position (by simulating full
     * days) so a 480×270 screen fills with 86,400 grains: a pile near a wall needs a gentler
     * slope to hold a full day. Index = drop position / 5 for 15..50 %, mirrored above 50 %.
     */
    private static final float[] SLIDE2_BY_DROP = {
            1.0f, 0.998f, 0.996f, 0.9916f, 0.9879f, 0.9848f, 0.9831f, 0.9822f}; // 15,20,...,50 %
    static final int MIN_DROP = 15, MAX_DROP = 85;

    static float slideFor(int dropPos) {
        int p = Math.max(MIN_DROP, Math.min(MAX_DROP, dropPos));
        if (p > 50) p = 100 - p;
        float f = (p - MIN_DROP) / 5f;
        int i = Math.min(SLIDE2_BY_DROP.length - 2, (int) f);
        float t = f - i;
        return SLIDE2_BY_DROP[i] * (1 - t) + SLIDE2_BY_DROP[i + 1] * t;
    }

    private final float slide2;
    /** Physics ticks per second. */
    static final int TICK_HZ = 30;

    final int cols, rows;
    final byte[] c;
    final float gravity;            // cells/tick²
    private final float maxSpeed = 12f;
    private final float[] vy;       // falling speed of each grain, cells/tick
    private int seed;               // xorshift state (java.util.Random is slow on TV chips)
    private final boolean[] queued;
    private final int[] stamp;      // tick a grain last moved, so it moves once per tick
    private final byte[] rest;      // ticks a grain has been unable to move (sleeps after 3)
    private int tickId = 1;
    private int[] cur = new int[4096];
    private int[] next = new int[4096];
    private int nNext;

    /** Cells changed since the renderer last looked. */
    private int[] changed = new int[4096];
    private int nChanged;
    private boolean allChanged = true;

    /** Midnight: the bottom opens and the sand pours out. */
    boolean floorOpen;
    int sandCount;

    GrainGrid(int cols, int rows, long seed, float slide2) {
        this.cols = cols;
        this.rows = rows;
        this.slide2 = slide2;
        c = new byte[cols * rows];
        vy = new float[cols * rows];
        queued = new boolean[cols * rows];
        stamp = new int[cols * rows];
        rest = new byte[cols * rows];
        seen = new int[cols * rows];
        target = new int[cols * rows];
        this.seed = (int) (seed * 2654435761L) | 1;
        // A fall from the top to the bottom takes about 1.3 s (39 ticks).
        gravity = 2f * rows / (39f * 39f);
    }

    private int rand() {
        int x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        return seed = x;
    }

    private boolean coin() { return (rand() & 1) == 0; }

    private float chance() { return (rand() >>> 8) / (float) (1 << 24); }

    boolean isEmpty(int x, int y) {
        if (x < 0 || x >= cols) return false;
        if (y >= rows) return floorOpen;
        if (y < 0) return true;
        return c[y * cols + x] == EMPTY;
    }

    boolean hasActive() {
        return nNext > 0 || pourLeft != null || holeX >= 0;
    }

    /** Empty cells from the top of column x down to the first sand or digit. */
    int freeDepth(int x) {
        int y = 0;
        while (y < rows && c[y * cols + x] == EMPTY) y++;
        return y;
    }

    // ---- change tracking for the renderer ----

    private void mark(int i) {
        if (allChanged) return;
        if (nChanged == changed.length) {
            if (changed.length >= 1 << 18) { allChanged = true; return; }
            changed = Arrays.copyOf(changed, changed.length * 2);
        }
        changed[nChanged++] = i;
    }

    void markAll() { allChanged = true; }

    /** Returns -1 if everything changed, else the number of entries in changedCells(). */
    int takeChanges() {
        if (allChanged) {
            allChanged = false;
            nChanged = 0;
            return -1;
        }
        int n = nChanged;
        nChanged = 0;
        return n;
    }

    int[] changedCells() { return changed; }

    // ---- waking ----

    private void wakeIdx(int i) {
        if (c[i] != SAND || queued[i]) return;
        queued[i] = true;
        if (nNext == next.length) next = Arrays.copyOf(next, next.length * 2);
        next[nNext++] = i;
    }

    private void wake(int x, int y) {
        if (x < 0 || x >= cols || y < 0 || y >= rows) return;
        wakeIdx(y * cols + x);
    }

    /** A cell was vacated: grains above or beside it may now be able to move. */
    private void wakeAround(int x, int y) {
        for (int dx = -2; dx <= 2; dx++) {
            wake(x + dx, y - 1);
            wake(x + dx, y);
        }
    }

    // ---- moving ----

    /** Moves grain i to (nx, ny); below the floor it leaves the screen. Returns new index or -1. */
    private int move(int i, int x, int y, int nx, int ny) {
        c[i] = EMPTY;
        float v = vy[i];
        vy[i] = 0;
        mark(i);
        wakeAround(x, y);
        if (ny >= rows) {
            sandCount--;
            return -1;
        }
        int j = ny * cols + nx;
        c[j] = SAND;
        vy[j] = v;
        stamp[j] = tickId;
        mark(j);
        return j;
    }

    // ---- end-of-day vacuum: a hole that sucks the sand away ----

    private int holeX = -1, holeY, holeR;
    private float reach;          // grains closer than this feel the suction (grows over time)
    private float strength = 1f;  // suction multiplier, rising toward midnight
    private float strongR;        // within this distance the pull moves all sand, not just surface
    private int vacuumTicks;

    /** Opens the hole. Its pull starts at nothing and is widened with setVacuumReach. */
    void startVacuum(int x, int y, int radius) {
        holeX = x;
        holeY = y;
        holeR = radius;
        reach = 0;
        vacuumTicks = 0;
    }

    void stopVacuum() { holeX = -1; }

    /** Moves/resizes the open hole (it stays on the floor as it widens). */
    void startVacuumMove(int x, int y, int radius) {
        holeX = x;
        holeY = y;
        holeR = Math.max(1, radius);
    }

    boolean vacuuming() { return holeX >= 0; }

    /** r: how far the pull reaches; s: its strength; strong: radius inside which it moves all
     *  sand (outside it only lifts surface grains). */
    void setVacuumReach(float r, float s, float strong) { reach = r; strength = s; strongR = strong; }

    /** The hole widens toward midnight, so more sand pours through it. */
    void setHoleRadius(int r) { holeR = Math.max(1, r); }

    int holeRadius() { return holeR; }

    /**
     * Inside the hole's reach a grain feels gravity plus suction toward the hole. Far away the
     * suction is weak, so a pile holds and only surface grains blow toward the hole (like wind
     * eroding a dune); close in the suction wins and sand is pulled in.
     */
    private int suck(int i, int x, int y) {
        float dx = holeX - x, dy = holeY - y;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d <= holeR) { // swallowed
            c[i] = EMPTY;
            vy[i] = 0;
            mark(i);
            wakeAround(x, y);
            sandCount--;
            return -1;
        }
        // Gravity first: sand that can fall or slide downhill does (so faces slump to sand's
        // natural slope); the suction then drags only sand that is resting stably, like wind
        // over a dune. Right by the hole the pull wins outright.
        if (d > holeR * 3) {
            int j = fall(i, x, y);
            if (j >= 0) return j;
        }
        // Far from the hole the pull is weak: it only lifts surface grains (open air beside
        // them), like wind off a dune. Buried sand just obeys gravity and slides into the crater.
        if (d > Math.max(holeR * 8, strongR) && !exposed(x, y)) return -1;
        float pull = strength * (0.2f * rows / 90f + 4f * holeR / d); // suction, cells per tick
        float fx = pull * dx / d, fy = pull * dy / d + 0.5f; // plus gravity
        float speed = (float) Math.sqrt(fx * fx + fy * fy);
        int steps = (int) speed + (chance() < speed - (int) speed ? 1 : 0);
        if (steps == 0) return i; // drifting slowly: stay awake, move another tick
        int cur = i, cx = x, cy = y;
        boolean movedAny = false;
        for (int s = 0; s < steps; s++) {
            if ((holeX - cx) * (holeX - cx) + (holeY - cy) * (holeY - cy) <= holeR * holeR) break;
            float ax = Math.abs(fx), ay = Math.abs(fy);
            int sx = (int) Math.signum(fx), sy = (int) Math.signum(fy);
            boolean alongX = chance() * (ax + ay) < ax;
            int mx = alongX ? sx : 0, my = alongX ? 0 : sy;
            if (chance() < 0.12f) { // a gust: drift sideways for a moment
                if (alongX) { mx = 0; my = coin() ? 1 : -1; } else { my = 0; mx = coin() ? 1 : -1; }
            }
            int[][] tries = {{mx, my}, {sx, sy}, {alongX ? 0 : sx, alongX ? sy : 0}};
            boolean stepped = false;
            for (int[] t : tries) {
                int nx = cx + t[0], ny = cy + t[1];
                if ((t[0] == 0 && t[1] == 0) || !inside(nx, ny) || c[ny * cols + nx] != EMPTY) continue;
                cur = move(cur, cx, cy, nx, ny);
                cx = nx;
                cy = ny;
                stepped = movedAny = true;
                break;
            }
            if (!stepped) break;
        }
        if (movedAny) return cur;
        return d > holeR * 3 ? -1 : fall(i, x, y); // boxed in: sleep until a neighbour moves
    }

    /** The hole swallows whatever sand is in its opening each tick, so it starts draining even
     *  when it opens deep inside the pile; the grains around its edge wake and follow. */
    private void swallow() {
        if (holeX < 0) return;
        for (int dy = -holeR; dy <= holeR; dy++) {
            for (int dx = -holeR; dx <= holeR; dx++) {
                if (dx * dx + dy * dy > holeR * holeR) continue;
                int x = holeX + dx, y = holeY + dy;
                if (!inside(x, y)) continue;
                int i = y * cols + x;
                if (c[i] != SAND) continue;
                c[i] = EMPTY;
                vy[i] = 0;
                mark(i);
                wakeAround(x, y);
                wake(x, y + 1);
                wake(x - 1, y + 1);
                wake(x + 1, y + 1);
                sandCount--;
            }
        }
    }

    /** True if the cell has empty space above, beside or below it. */
    private boolean exposed(int x, int y) {
        return isEmpty(x, y - 1) && y > 0 || isEmpty(x - 1, y) || isEmpty(x + 1, y) || (y + 1 < rows && isEmpty(x, y + 1));
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < cols && y >= 0 && y < rows;
    }

    /**
     * Regularly wake grains on the surface (next to empty space), so nothing stays stuck after
     * the sand around it moved on: every 2 s normally, every half second during the vacuum.
     * Grains that can't move go straight back to sleep, so this costs little.
     */
    private void wakeSurfaceRegularly() {
        int every = holeX >= 0 ? 15 : 60;
        if (vacuumTicks++ % every != 0) return;
        wakeSurface();
    }

    /** One step for the grain at i. Returns its new index, or -1 if it rests (or left). */
    private int step(int i) {
        int x = i % cols, y = i / cols;
        if (holeX >= 0) {
            float dx = holeX - x, dy = holeY - y;
            if (dx * dx + dy * dy <= reach * reach) return suck(i, x, y);
        }
        return fall(i, x, y);
    }

    /** Gravity: fall if nothing is below, otherwise slide down a slope (see SLIDE2). */
    private int fall(int i, int x, int y) {
        if (isEmpty(x, y + 1)) {
            // In a sinking column (just a one-cell gap below, sand right under it) a grain with
            // open space beside it often tips outward instead: a sinking face crumbles to sand's
            // natural slope rather than staying a sheer wall. Grains in open air fall straight.
            if (y + 2 < rows && !isEmpty(x, y + 2) && coin()) {
                int d = coin() ? 1 : -1;
                for (int k = 0; k < 2; k++, d = -d) {
                    if (isEmpty(x + d, y) && isEmpty(x + d, y + 1) && !isEmpty(x - d, y)) {
                        return move(i, x, y, x + d, y + 1);
                    }
                }
            }
            float v = Math.min(maxSpeed, vy[i] + gravity);
            vy[i] = v;
            int steps = Math.max(1, (int) v);
            int ny = y;
            while (steps-- > 0 && isEmpty(x, ny + 1)) {
                ny++;
                if (ny >= rows) break;
            }
            int j = move(i, x, y, x, ny);
            return j;
        }
        vy[i] = 0;
        int d = coin() ? 1 : -1;
        for (int k = 0; k < 2; k++, d = -d) {
            if (isEmpty(x + d, y + 1) && isEmpty(x + d, y)) return move(i, x, y, x + d, y + 1);
        }
        // Text is rough: a grain sitting on a digit doesn't take the extra sideways slide,
        // so heaps on the clock stand at a full 45°.
        boolean onText = y + 1 < rows && c[(y + 1) * cols + x] == SOLID;
        if (!onText && chance() < slide2) {
            for (int k = 0; k < 2; k++, d = -d) {
                if (isEmpty(x + d, y) && isEmpty(x + 2 * d, y) && isEmpty(x + 2 * d, y + 1)) {
                    return move(i, x, y, x + 2 * d, y + 1);
                }
            }
        }
        return -1;
    }

    /** One physics tick for all awake grains, bottom rows first. */
    void tick() {
        int[] work = next;
        int n = nNext;
        next = cur;
        cur = work;
        nNext = 0;
        tickId++;
        for (int k = 0; k < n; k++) queued[work[k]] = false;
        Arrays.sort(work, 0, n);
        for (int k = n - 1; k >= 0; k--) {
            int i = work[k];
            if (c[i] != SAND || stamp[i] == tickId) continue;
            int j = step(i);
            if (j >= 0) {
                rest[j] = 0;
                wakeIdx(j); // still moving: check again next tick
            } else if (c[i] == SAND && rest[i] < 3) {
                rest[i]++;   // blocked, maybe by a grain falling past: try again shortly
                wakeIdx(i);
            } else if (c[i] == SAND) {
                rest[i] = 0; // settled: sleep until a neighbour moves
            }
        }
        pourStep();
        swallow();
        wakeSurfaceRegularly();
    }

    // ---- adding grains ----

    /** Releases a grain at the top of column x (animated). False if the sand reaches the top. */
    boolean spawn(int x) {
        if (!isEmpty(x, 0)) return false;
        c[x] = SAND;
        vy[x] = gravity;
        sandCount++;
        mark(x);
        wakeIdx(x);
        return true;
    }

    /** Drops a grain at the top of column x and lets it settle at once (catching up). */
    boolean dropInstant(int x) {
        boolean open = floorOpen;
        floorOpen = false;
        int i = settle(c, x);
        floorOpen = open;
        if (i < 0) return false;
        c[i] = SAND;
        sandCount++;
        mark(i);
        return true;
    }

    /** Where a grain dropped at the top of column x comes to rest in grid g (same rules as
     *  the animated grains), or -1 if the column is full to the top. Doesn't modify g. */
    private int settle(byte[] g, int x) {
        if (g[x] != EMPTY) return -1;
        int y = 0;
        while (true) {
            if (y + 1 < rows && g[(y + 1) * cols + x] == EMPTY) { y++; continue; }
            int d = coin() ? 1 : -1;
            boolean moved = false;
            for (int k = 0; k < 2 && !moved; k++, d = -d) {
                if (y + 1 < rows && free(g, x + d, y + 1) && free(g, x + d, y)) { x += d; y++; moved = true; }
            }
            boolean onText = y + 1 < rows && g[(y + 1) * cols + x] == SOLID;
            if (!moved && !onText && chance() < slide2) {
                for (int k = 0; k < 2 && !moved; k++, d = -d) {
                    if (y + 1 < rows && free(g, x + d, y) && free(g, x + 2 * d, y) && free(g, x + 2 * d, y + 1)) {
                        x += 2 * d; y++; moved = true;
                    }
                }
            }
            if (!moved) return y * cols + x;
        }
    }

    private boolean free(byte[] g, int x, int y) {
        return x >= 0 && x < cols && y >= 0 && y < rows && g[y * cols + x] == EMPTY;
    }

    /** Source column of the n-th grain of the day (the stream, with its sway). */
    interface Stream { int column(int n); }

    /**
     * Works out where the day's first n grains would really be, letters included (heaps on
     * them, sand inside them, pockets under them), by dropping each one from the stream in a
     * hidden copy of the grid. Returns that grid; the live grid is unchanged.
     */
    byte[] simulateDay(int n, Stream stream) {
        byte[] g = c.clone();
        for (int k = 0; k < n; k++) {
            int i = settle(g, stream.column(k));
            if (i >= 0) g[i] = SAND;
        }
        return g;
    }

    /** Puts a simulated pile straight into the live grid. */
    void applyPile(byte[] g) {
        for (int i = 0; i < c.length; i++) {
            if (g[i] == SAND && c[i] == EMPTY) { c[i] = SAND; sandCount++; }
        }
        allChanged = true;
    }

    /**
     * When to rain which grains so the simulated pile builds up for real: schedule[x * ticks + t]
     * grains are released at the top of column x on tick t. Grains are released by the height
     * of their final spot, lowest first, so the pile rises from the floor as it did through the
     * day. Spots under a letter (no falling grain can reach them in a few seconds; over a day
     * sand rolls in from the sides) are filled in step with the sand around them instead: the
     * level under a letter rises with its surroundings. See shadowCells/shadowTicks.
     */
    int[] rainPlan(byte[] g, int ticks) {
        int[] firstSolid = new int[cols]; // first row a falling grain can't pass, per column
        for (int x = 0; x < cols; x++) {
            int y = 0;
            while (y < rows && c[y * cols + x] != SOLID) y++;
            firstSolid[x] = y;
        }
        int[] schedule = new int[cols * ticks];
        int nShadow = 0;
        int[] shadow = new int[64];
        // Bottom rows first, so shadow cells come out sorted by when they are due.
        for (int y = rows - 1; y >= 0; y--) {
            int t = Math.min(ticks - 1, (rows - 1 - y) * ticks / rows);
            for (int x = 0; x < cols; x++) {
                int i = y * cols + x;
                if (g[i] != SAND || c[i] != EMPTY) continue;
                if (y < firstSolid[x]) {
                    schedule[x * ticks + t]++;
                } else {
                    if (nShadow == shadow.length) shadow = Arrays.copyOf(shadow, nShadow * 2);
                    shadow[nShadow++] = i;
                }
            }
        }
        shadowCells = Arrays.copyOf(shadow, nShadow);
        shadowNext = 0;
        return schedule;
    }

    /** Spots under letters, bottom rows first; filled as the sand around them reaches them. */
    private int[] shadowCells;
    private int shadowNext;

    /** Fills shadow cells up to the current pour tick, each once the cell below is filled. */
    private void fillShadows(int t, int ticks) {
        if (shadowCells == null) return;
        while (shadowNext < shadowCells.length) {
            int i = shadowCells[shadowNext];
            int y = i / cols;
            int due = Math.min(ticks - 1, (rows - 1 - y) * ticks / rows);
            if (due > t) break;
            boolean supported = y == rows - 1 || c[i + cols] != EMPTY;
            if (!supported && t < ticks + 60) break; // wait for the sand below to arrive
            if (c[i] == EMPTY) {
                c[i] = SAND;
                sandCount++;
                mark(i);
            }
            shadowNext++;
        }
    }

    /** Wakes every grain that has empty space beside, above or below it. */
    void wakeSurface() {
        for (int i = 0; i < c.length; i++) {
            if (c[i] != SAND) continue;
            int x = i % cols, y = i / cols;
            if ((y > 0 && c[i - cols] == EMPTY) || (y < rows - 1 && c[i + cols] == EMPTY)
                    || (x > 0 && c[i - 1] == EMPTY) || (x < cols - 1 && c[i + 1] == EMPTY)) {
                wakeIdx(i);
            }
        }
    }

    // ---- pour-in: the sand so far rains down across the whole width ----

    private int[] pourSchedule; // grains to release per column per tick (see rainPlan)
    private int[] pourLeft;     // grains due but not yet released (top of column was busy)
    private int pourTicks, pourTick;

    /** Rains the scheduled grains down over `ticks` physics ticks; physics settles them. */
    void startPour(int[] schedule, int ticks) {
        pourSchedule = schedule;
        pourLeft = new int[cols];
        pourTicks = ticks;
        pourTick = 0;
    }

    boolean pouring() { return pourLeft != null; }

    /** Releases this tick's grains at the top of each column. */
    private void pourStep() {
        if (pourLeft == null) return;
        int t = pourTick++;
        fillShadows(t, pourTicks);
        boolean any = false;
        for (int x = 0; x < cols; x++) {
            if (t < pourTicks) pourLeft[x] += pourSchedule[x * pourTicks + t];
            int q = pourLeft[x];
            if (q <= 0) continue;
            any = true;
            if (c[x] != EMPTY && t > pourTicks + 60) {
                // Still full to the top long after its turn: give its grains to the columns
                // with the most room.
                spreadToRoomiest(x, q);
                continue;
            }
            for (int y = 0; y < q; y++) {
                int i = y * cols + x;
                if (y >= rows || c[i] != EMPTY) break;
                c[i] = SAND;
                vy[i] = gravity;
                sandCount++;
                mark(i);
                wakeIdx(i);
                pourLeft[x]--;
            }
        }
        // Done, or something stays blocked long after the pour should have finished.
        boolean shadowsDone = shadowCells == null || shadowNext >= shadowCells.length;
        if ((!any && t >= pourTicks && shadowsDone) || t > pourTicks + 600) {
            pourLeft = null;
            pourSchedule = null;
            shadowCells = null;
            wakeSurface(); // nothing left hanging next to empty space
        }
    }

    private void spreadToRoomiest(int from, int q) {
        pourLeft[from] = 0;
        int[] depth = new int[cols];
        for (int x = 0; x < cols; x++) depth[x] = x == from ? -1 : freeDepth(x);
        int picks = Math.min(20, cols - 1);
        int[] best = new int[picks];
        for (int k = 0; k < picks; k++) {
            int b = 0;
            for (int x = 1; x < cols; x++) if (depth[x] > depth[b]) b = x;
            best[k] = b;
            depth[b] = -1;
        }
        for (int k = 0; k < q; k++) pourLeft[best[k % picks]]++;
    }

    // ---- the clock's digits as solid cells ----

    private int[] searchQueue = new int[1024];
    private final int[] seen;   // cells visited by the current search
    private final int[] target; // cells of the letter being made solid (by setSolid call id)
    private int seenId;

    /**
     * Makes these cells solid (a character appearing). Sand already there is pushed to the
     * nearest free spot, preferring up and sideways, so it bulges out and slumps naturally.
     */
    void setSolid(int[] cells) {
        int id = ++seenId;
        for (int i : cells) target[i] = id; // the letter's own cells: can't end up there
        for (int i : cells) {
            if (c[i] != SAND) continue;
            c[i] = EMPTY;
            vy[i] = 0;
            int j = nearestFree(i, id);
            if (j >= 0) {
                c[j] = SAND;
                mark(j);
                wakeIdx(j);
            } else {
                sandCount--;
            }
        }
        for (int i : cells) {
            c[i] = SOLID;
            mark(i);
        }
    }

    /** Breadth-first search for the closest empty cell (up, left, right, then down). */
    private int nearestFree(int start, int targetId) {
        int id = ++seenId;
        int head = 0, tail = 0;
        searchQueue[tail++] = start;
        seen[start] = id;
        while (head < tail && head < 60_000) {
            int i = searchQueue[head++];
            int x = i % cols, y = i / cols;
            int[] nb = {y > 0 ? i - cols : -1, x > 0 ? i - 1 : -1, x < cols - 1 ? i + 1 : -1, y < rows - 1 ? i + cols : -1};
            for (int j : nb) {
                // The search may pass through the letter's own cells (a grain deep inside a thick
                // stroke must still get out) but never stop there: they become wall next.
                if (j < 0 || seen[j] == id || c[j] == SOLID) continue;
                if (c[j] == EMPTY && target[j] != targetId) return j;
                seen[j] = id;
                if (tail == searchQueue.length) searchQueue = Arrays.copyOf(searchQueue, tail * 2);
                searchQueue[tail++] = j;
            }
        }
        return -1;
    }

    /** Removes solid cells (a character going away): whatever rested on it falls. */
    void clearSolid(int[] cells) {
        for (int i : cells) {
            if (c[i] == SOLID) {
                c[i] = EMPTY;
                mark(i);
                wakeAround(i % cols, i / cols);
            }
        }
    }

    /** Midnight: open the floor and set the bottom row moving; the rest follows. */
    void openFloor() {
        floorOpen = true;
        int base = (rows - 1) * cols;
        for (int x = 0; x < cols; x++) wakeIdx(base + x);
    }
}
