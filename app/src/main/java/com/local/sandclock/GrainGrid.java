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

    boolean vacuuming() { return holeX >= 0; }

    void setVacuumReach(float r) { reach = r; }

    /** Grains near the hole are pulled toward it: slowly far away, faster close in. */
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
        // Cells per tick: a slow drift far away (scaled to the screen so distant sand still
        // arrives in time), much faster close in.
        float speed = 0.2f * rows / 90f + 4f * holeR / d;
        int steps = (int) speed + (chance() < speed - (int) speed ? 1 : 0);
        int cur = i, cx = x, cy = y;
        for (int s = 0; s < steps; s++) {
            dx = holeX - cx;
            dy = holeY - cy;
            if (dx * dx + dy * dy <= holeR * holeR) break;
            float ax = Math.abs(dx), ay = Math.abs(dy);
            boolean alongX = chance() * (ax + ay) < ax; // head roughly toward the hole
            int mx = alongX ? (int) Math.signum(dx) : 0;
            int my = alongX ? 0 : (int) Math.signum(dy);
            if (chance() < 0.12f) { // a gust: drift sideways for a moment
                if (alongX) { mx = 0; my = coin() ? 1 : -1; } else { my = 0; mx = coin() ? 1 : -1; }
            }
            int nx = cx + mx, ny = cy + my;
            if (!inside(nx, ny) || c[ny * cols + nx] != EMPTY) {
                // Blocked: try the other direction toward the hole.
                nx = cx + (alongX ? 0 : (int) Math.signum(dx));
                ny = cy + (alongX ? (int) Math.signum(dy) : 0);
                if (!inside(nx, ny) || c[ny * cols + nx] != EMPTY) break;
            }
            cur = move(cur, cx, cy, nx, ny);
            cx = nx;
            cy = ny;
        }
        return cur; // stays awake while the vacuum runs, even if blocked this tick
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < cols && y >= 0 && y < rows;
    }

    /** Every half second, wake resting grains the widening pull has just reached. */
    private void wakeInReach() {
        if (holeX < 0 || vacuumTicks++ % 15 != 0) return;
        float r2 = reach * reach;
        for (int i = 0; i < c.length; i++) {
            if (c[i] != SAND || queued[i]) continue;
            float dx = holeX - i % cols, dy = holeY - i / cols;
            if (dx * dx + dy * dy <= r2) wakeIdx(i);
        }
    }

    /** One step for the grain at i. Returns its new index, or -1 if it rests (or left). */
    private int step(int i) {
        int x = i % cols, y = i / cols;
        if (holeX >= 0) {
            float dx = holeX - x, dy = holeY - y;
            if (dx * dx + dy * dy <= reach * reach) return suck(i, x, y);
        }
        if (isEmpty(x, y + 1)) {
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
            if (j >= 0) wakeIdx(j); // still moving: check again next tick
        }
        pourStep();
        wakeInReach();
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
        if (!isEmpty(x, 0)) return false;
        boolean open = floorOpen;
        floorOpen = false;
        int y = 0;
        while (true) {
            if (y + 1 < rows && isEmpty(x, y + 1)) { y++; continue; }
            int d = coin() ? 1 : -1;
            boolean moved = false;
            for (int k = 0; k < 2 && !moved; k++, d = -d) {
                if (y + 1 < rows && isEmpty(x + d, y + 1) && isEmpty(x + d, y)) { x += d; y++; moved = true; }
            }
            boolean onText = y + 1 < rows && c[(y + 1) * cols + x] == SOLID;
            if (!moved && !onText && chance() < slide2) {
                for (int k = 0; k < 2 && !moved; k++, d = -d) {
                    if (y + 1 < rows && isEmpty(x + d, y) && isEmpty(x + 2 * d, y) && isEmpty(x + 2 * d, y + 1)) {
                        x += 2 * d; y++; moved = true;
                    }
                }
            }
            if (!moved) break;
        }
        floorOpen = open;
        int i = y * cols + x;
        c[i] = SAND;
        sandCount++;
        mark(i);
        return true;
    }

    /**
     * Fast catch-up on an empty grid with no digits: builds the pile n grains would form
     * directly. Its surface is the same ragged staircase the falling grains make (each column
     * one lower than the last with probability `slope`), with the peak height chosen so it
     * holds exactly n grains. A swaying stream makes a flat-topped mound of half-width
     * `plateau`. Returns the number placed.
     */
    int fill(int n, int x0, float slope, int plateau) {
        return fillHeights(planPile(n, x0, slope, plateau));
    }

    /** Column heights of the pile n grains would form (see fill). */
    int[] planPile(int n, int x0, float slope, int plateau) {
        int[] off = new int[cols]; // how far below the peak each column's surface sits
        for (int x = x0 + 1; x < cols; x++) {
            off[x] = off[x - 1] + (x - x0 > plateau && chance() < slope ? 1 : 0);
        }
        for (int x = x0 - 1; x >= 0; x--) {
            off[x] = off[x + 1] + (x0 - x > plateau && chance() < slope ? 1 : 0);
        }
        // Highest peak P whose pile holds at most n grains.
        int lo = 0, hi = rows + cols;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pileSize(off, mid) <= n) lo = mid; else hi = mid - 1;
        }
        int[] h = new int[cols];
        long total = 0;
        for (int x = 0; x < cols; x++) {
            h[x] = Math.max(0, Math.min(rows, lo - off[x]));
            total += h[x];
        }
        // Spread the few leftover grains on the surface next to the peak.
        int left = (int) Math.min(n - total, Integer.MAX_VALUE);
        for (int dist = 0; left > 0 && dist < cols; dist++) {
            for (int side = -1; side <= 1 && left > 0; side += 2) {
                int x = x0 + side * dist;
                if (x < 0 || x >= cols || (dist == 0 && side == 1)) continue;
                if (h[x] < rows && h[x] == Math.max(0, Math.min(rows, lo - off[x]))) {
                    h[x]++;
                    left--;
                }
            }
        }
        return h;
    }

    /** Writes a planned pile straight into the grid. Returns the number placed. */
    int fillHeights(int[] h) {
        int placed = 0;
        for (int x = 0; x < cols; x++) {
            for (int y = rows - h[x]; y < rows; y++) c[y * cols + x] = SAND;
            placed += h[x];
        }
        sandCount += placed;
        allChanged = true;
        return placed;
    }

    // ---- pour-in: the sand so far rains down across the whole width ----

    private int[] pourLeft;   // grains still to release per column
    private int pourTicks;    // ticks left in the pour

    /** Rains down a planned pile over `ticks` physics ticks; physics settles it. */
    void startPour(int[] h, int ticks) {
        pourLeft = h.clone();
        pourTicks = ticks;
    }

    boolean pouring() { return pourLeft != null; }

    /** Releases this tick's grains: each column gets its share so all finish together. */
    private void pourStep() {
        if (pourLeft == null) return;
        int ticksLeft = Math.max(1, pourTicks);
        pourTicks--;
        boolean any = false;
        for (int x = 0; x < cols; x++) {
            int q = pourLeft[x];
            if (q <= 0) continue;
            any = true;
            if (c[x] != EMPTY) {
                // This column is full to the top: hand its grains to the nearest open one.
                for (int d = 1; d < cols && pourLeft[x] > 0; d++) {
                    for (int nx : new int[]{x - d, x + d}) {
                        if (nx >= 0 && nx < cols && c[nx] == EMPTY) {
                            pourLeft[nx] += q;
                            pourLeft[x] = 0;
                            break;
                        }
                    }
                }
                continue;
            }
            int want = (int) (q / (float) ticksLeft + chance()); // a little randomness: rain
            for (int y = 0; y < want; y++) {
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
        // Done, or a column stays blocked long after the pour should have finished.
        if (!any || pourTicks < -300) pourLeft = null;
    }

    private long pileSize(int[] off, int peak) {
        long sum = 0;
        for (int x = 0; x < cols; x++) sum += Math.max(0, Math.min(rows, peak - off[x]));
        return sum;
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
