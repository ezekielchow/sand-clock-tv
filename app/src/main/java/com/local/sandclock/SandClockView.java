package com.local.sandclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Random;

/**
 * The whole screen is the hourglass, drawn as chunky pixels like an old game. One grain falls
 * every second of the day from a chosen point at the top. Grains pile up on the ground and on
 * the clock's digits, spill off their edges, and when the minute changes everything resting on
 * the digits falls off. By midnight the screen is full; the floor opens and it pours out.
 *
 * Power notes: only moving grains are simulated; the picture is a small bitmap scaled up
 * (no smoothing); black sand switches the mini-LED zones under it fully off.
 */
public class SandClockView extends SceneView {

    private static final int GRAINS_PER_DAY = 86_400;
    /** Pour-in at start: the day's sand so far is released over this many ticks (3 s). */
    private static final int POUR_TICKS = 90;
    private static final long SHAKE_MS = 900;
    /** A full screen pours out in about 9 s (one row per tick); allow some slack. */
    private static final long DRAIN_MAX_MS = 14000;
    private static final float NIGHT_DIM = 0.35f;
    private static final float GLOW_SAND = 0.55f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint pixelPaint = new Paint(); // no filtering: hard pixel edges
    private final Paint dimPaint = new Paint();
    private final Rect dst = new Rect();

    private Prefs prefs = new Prefs();
    private Listener listener;
    private boolean previewMode;
    private boolean running;
    private Boolean lastNight;

    private GrainGrid grid;
    private float cell;
    private int dropX;
    /** Random drop point: grain planStart[i] onwards falls at column planX[i] (null = fixed). */
    private int[] planStart, planX;
    private int gridDay = -1;
    private int spawned;
    private long lastTickAt;

    // The clock as solid cells. On a minute change the old digits stop being solid for a moment
    // (everything on them falls), then the new ones become solid. Same slot id = same character
    // in the same place, so it keeps its sand when the minute changes.
    private static final class Slot {
        final String id;
        final int[] cells, lids;
        Slot(ClockText.Slot s) { id = s.id; cells = s.cells; lids = s.lids; }
    }
    /** Cells drawn as the letters' top edges (not solid). */
    private boolean[] lid = new boolean[0];
    private java.util.List<Slot> solidSlots = new java.util.ArrayList<>();
    private java.util.List<Slot> pendingSlots;   // appearing after the shake
    private java.util.List<Slot> newSlots;       // latest layout from renderClock
    private int textCol = Color.WHITE;
    private String shownTime = "", shownDate = "";
    /** The time the solid digits in the grid currently represent. */
    private String solidTime = "";
    private long shakeUntil = -1;

    // End-of-day vacuum: a hole opens X minutes before midnight and sucks everything away.
    private boolean vacuumOn;
    private long vacuumOpenedAt;      // uptime, for the hole's opening animation
    private int holeX, holeY, holeR;
    private final Paint holePaint = new Paint();
    /** "Preview end of day": the real 5-minute vacuum, run now at DEMO_SPEED× (about 100 s). */
    private long demoStart = -1;
    private static final int DEMO_SPEED = 3;
    private static final long DEMO_MS = 5 * 60_000 / DEMO_SPEED;

    void demoVacuum() {
        demoStart = SystemClock.uptimeMillis();
    }

    private boolean draining;
    private long drainStart;

    private Bitmap scene, textLayer;
    private int[] px, textPx;
    private int colorKey = Integer.MIN_VALUE;
    private int background = Color.BLACK;
    /** Sand colours per hour layer (0 = unknown, 1..24) and texture shade (4). */
    private final int[][] shades = new int[25][4];
    /** Hour layers cycle through these tones (brightness, warm(+)/cool(-) shift). */
    private static final float[] LAYER_B = {1.00f, 0.84f, 1.10f, 0.92f, 0.78f, 1.04f};
    private static final float[] LAYER_H = {0.00f, 0.10f, -0.08f, 0.05f, 0.00f, -0.05f};

    // Sun, moon and glints: a few cells drawn over the sky or the pile's surface.
    private int[] sky = new int[0];          // colour over an empty cell, 0 = none
    private int[] skyCells = new int[0];
    private int nSky;
    private int skyKey = -1;
    private int glint = -1, glintCol;
    private final java.util.Random rnd = new java.util.Random();
    private final Paint starPaint = new Paint();

    // Extras from Stats (reset every two weeks): bedrock with fossils under the sand, a line in
    // the sky once an hour, and a constellation on the dim night clock.
    private int bedRows;               // bedrock height in cells (grows slowly, set at build)
    private Bitmap bedLayer;
    private final Rect bedDst = new Rect();
    private int[] note = new int[0];   // the sky line's colour over empty cells, 0 = none
    private int[] noteCells = new int[0];
    private int nNote;
    private int noteHour = -1;
    private long noteUntil;
    /** The hourglass constellation, traced in order (unit square); a star per few hours of sand. */
    private static final float[][] CONSTELLATION = {
            {0f, 0f}, {0.5f, 0f}, {1f, 0f}, {0.75f, 0.25f}, {0.55f, 0.5f}, {0.75f, 0.75f},
            {1f, 1f}, {0.5f, 1f}, {0f, 1f}, {0.25f, 0.75f}, {0.45f, 0.5f}, {0.25f, 0.25f}};
    /** Fossils in the bedrock (4×3 cells): an ammonite and a bone, alternating. */
    private static final String[][] FOSSILS = {
            {".##.", "#.##", ".##."}, {"#..#", "####", "#..#"}};

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            scheduleNext();
        }
    };

    public SandClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setAntiAlias(false);
        dimPaint.setFilterBitmap(false);
        dimPaint.setAlpha((int) (255 * NIGHT_DIM));
    }

    void setPrefs(Prefs p) {
        prefs = p;
        lastNight = null;
        grid = null; // rebuild for the new settings (drop point, clock format, ...)
        if (running) {
            handler.removeCallbacks(tick);
            tick.run();
        } else {
            invalidate();
        }
    }

    void setListener(Listener l) { listener = l; }

    /** Settings preview always shows the daytime look. */
    void setPreviewMode(boolean preview) { previewMode = preview; }

    void start() {
        if (running) return;
        running = true;
        lastTickAt = SystemClock.uptimeMillis();
        tick.run();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (!previewMode) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w * 9 / 16);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        grid = null;
        if (running) {
            handler.removeCallbacks(tick);
            tick.run();
        }
    }

    private boolean isNight() {
        return !previewMode && Boolean.TRUE.equals(lastNight);
    }

    // ---- sizing ----

    /** Grid rows: on a 1080-line screen each grain is exactly 4×4 pixels (crisp, even pixels). */
    private static final int GRID_ROWS = 270;

    // ---- the day's grid ----

    private void build(Calendar c, int w, int h) {
        boolean random = prefs.dropPos == Prefs.DROP_RANDOM;
        float frac = (random ? 50 : prefs.dropPos) / 100f;
        cell = Math.max(1, Math.round(h / (float) GRID_ROWS));
        Stats st = stats();
        st.refresh(c);
        // Bedrock under the sand: up to 8 cells over the two weeks; it only grows at a rebuild
        // (screensaver start, midnight), so it never shifts the sand mid-day.
        bedRows = Math.round(8 * st.sandGrowth());
        int cols = (int) Math.ceil(w / cell), rows = (int) Math.ceil(h / cell) - bedRows;
        int day = dayKey(c);
        grid = new GrainGrid(cols, rows, day, GrainGrid.slideFor(random ? RANDOM_SLIDE_AT : prefs.dropPos));
        gridDay = day;
        dropX = Math.round(frac * (cols - 1));
        if (random) planRandomDrops(day, cols);
        else planStart = planX = null;
        draining = false;
        vacuumOn = false;
        shakeUntil = -1;
        pendingSlots = null;
        px = new int[cols * rows];
        note = new int[cols * rows];
        nNote = 0;
        noteHour = -1;
        bedLayer = null;
        sky = new int[cols * rows];
        nSky = 0;
        skyKey = -1;
        glint = -1;
        textPx = new int[cols * rows];
        scene = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        textLayer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        colorKey = Integer.MIN_VALUE;

        // Catch up to now. The letters go in first (solid), then a hidden run of the day so far
        // works out where every grain would really be: heaps on the letters, sand inside them,
        // pockets under them. Pour-in rains that pile down for real; otherwise (night, animation
        // off, settings preview, vacuum time) it is placed at once.
        spawned = dueGrains(c);
        shownTime = "";
        renderClock(c, true);
        solidTime = shownTime;
        solidSlots = newSlots;
        for (Slot sl : solidSlots) grid.setSolid(sl.cells);
        byte[] dayPile = grid.simulateDay(spawned, new GrainGrid.Stream() {
            public int column(int n) { return dropColumn(n); }
        });
        int vm = Prefs.VACUUM_MIN[prefs.vacuum];
        long msNow = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60
                + c.get(Calendar.SECOND)) * 1000L;
        boolean inVacuumWindow = vm > 0 && msNow >= 86_400_000L - vm * 60_000L;
        boolean pour = prefs.pourIn && !isNight() && prefs.anim != Prefs.ANIM_OFF && spawned > 0
                && !inVacuumWindow && !previewMode && demoStart < 0;
        if (pour) grid.startPour(grid.rainPlan(dayPile, POUR_TICKS), POUR_TICKS);
        else grid.applyPile(dayPile);
        lastTickAt = SystemClock.uptimeMillis();
    }

    private static int dayKey(Calendar c) {
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    /** Sway half-width (cells) for grain n: widest at the start of the day, zero at midnight,
     *  so the text gets sprinkled while it is exposed and the last grains finish the peak. */
    private float swayAmp(int n) {
        return Prefs.SWAY_AMP[prefs.sway] * grid.cols * Math.max(0f, 1f - n / (float) GRAINS_PER_DAY);
    }

    /** Random drop point: each spot lasts this many minutes (random in the range). */
    private static final int RANDOM_MIN_MINUTES = 5, RANDOM_MAX_MINUTES = 40;
    /** The next spot is at least this far (% of width) from the last, so the move shows. */
    private static final int RANDOM_MIN_JUMP = 15;
    /** Pile slope used for random drops (as if dropping here); spread-out sand holds a day. */
    private static final int RANDOM_SLIDE_AT = 50;

    /**
     * Plans the day's random drop spots. Seeded by the date, so the screensaver rebuilds the
     * same pile every time it starts today, and tomorrow gets a different plan.
     */
    private void planRandomDrops(int day, int cols) {
        Random r = new Random(day * 7919L + 17);
        int[] start = new int[GRAINS_PER_DAY / (RANDOM_MIN_MINUTES * 60) + 1];
        int[] col = new int[start.length];
        int n = 0, at = 0, last = -100;
        int span = GrainGrid.MAX_DROP - GrainGrid.MIN_DROP;
        while (at < GRAINS_PER_DAY) {
            int pos;
            do { pos = GrainGrid.MIN_DROP + r.nextInt(span + 1); }
            while (Math.abs(pos - last) < RANDOM_MIN_JUMP);
            last = pos;
            start[n] = at;
            col[n] = Math.round(pos / 100f * (cols - 1));
            n++;
            at += 60 * (RANDOM_MIN_MINUTES + r.nextInt(RANDOM_MAX_MINUTES - RANDOM_MIN_MINUTES + 1));
        }
        planStart = Arrays.copyOf(start, n);
        planX = Arrays.copyOf(col, n);
    }

    /** Drop point of grain n before sway: fixed, or the random plan's spot at that time. */
    private int baseColumn(int n) {
        if (planStart == null) return dropX;
        int i = Arrays.binarySearch(planStart, n);
        return planX[i >= 0 ? i : -i - 2];
    }

    /** Column grain n falls from: drifting back and forth around the drop point (3-minute swing). */
    private int dropColumn(int n) {
        double x = baseColumn(n) + swayAmp(n) * Math.sin(2 * Math.PI * n / 180.0);
        return Math.max(0, Math.min(grid.cols - 1, (int) Math.round(x)));
    }

    /**
     * Grains due by now: grain n lands at second n of the day. A grain is released early by
     * its fall time, so it touches the sand right as its second ticks over.
     */
    private int dueGrains(Calendar c) {
        long ms = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60
                + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
        int depth = Math.max(1, grid.freeDepth(dropColumn(spawned)));
        long lead = (long) (Math.sqrt(2.0 * depth / grid.gravity) * 1000 / GrainGrid.TICK_HZ);
        return (int) Math.min(GRAINS_PER_DAY, (ms + lead) / 1000);
    }

    // ---- stepping ----

    private void step() {
        Calendar c = Calendar.getInstance();
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean night = prefs.isNight(minute);
        if (lastNight == null || lastNight != night) {
            lastNight = night;
            if (listener != null) listener.onNightChanged(night && !previewMode);
        }
        prefs.warmth = Sun.warmth(prefs.city, c);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();

        long msOfDay = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60
                + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND);
        int vacMin = Prefs.VACUUM_MIN[prefs.vacuum];
        long vacFrom = 86_400_000L - vacMin * 60_000L;

        if (grid == null) {
            build(c, w, h);
        } else if (dayKey(c) != gridDay && vacuumOn) {
            // Midnight after the vacuum: the screen is empty, start the new day.
            vacuumOn = false;
            build(c, w, h);
        } else if (dayKey(c) != gridDay && !draining) {
            if (!isNight() || prefs.nightFlip) {
                // Midnight: everything falls through the floor.
                draining = true;
                drainStart = now;
                for (Slot sl : solidSlots) grid.clearSolid(sl.cells);
                pendingSlots = null;
                java.util.Arrays.fill(lid, false); // letters are gone: no floating top edges
                grid.markAll();
                grid.openFloor();
            } else {
                build(c, w, h);
            }
        }

        if (grid != null) {
            updateColors(); // also at night, so the dim clock's moon has its colours
            decorate(c);
            if (!previewMode) countTime(SAND, c, isNight());
        }

        if (!vacuumOn && !draining && !previewMode && (demoStart >= 0
                || (vacMin > 0 && msOfDay >= vacFrom && (!isNight() || prefs.nightFlip)))) {
            startVacuum(now);
        }
        if (vacuumOn && demoStart >= 0 && now - demoStart > DEMO_MS + 3000) {
            demoStart = -1; // preview over: back to the day as it was
            build(c, w, h);
            return;
        }
        if (vacuumOn) {
            // The pull spreads out from the hole like wind, reaching the whole screen 60 % of
            // the way through, which leaves time for the last sand to arrive before midnight.
            float f = demoStart >= 0
                    ? Math.min(1f, (now - demoStart) / (0.6f * DEMO_MS))
                    : Math.min(1f, (msOfDay - vacFrom) / (0.6f * vacMin * 60_000f));
            float far = (float) Math.hypot(Math.max(holeX, grid.cols - holeX), Math.max(holeY, grid.rows - holeY));
            float elapsed = demoStart >= 0
                    ? Math.min(1f, (now - demoStart) / (float) DEMO_MS)
                    : Math.min(1f, (msOfDay - vacFrom) / (vacMin * 60_000f));
            // Final sweep: over the last 40 % the strong pull grows to cover the whole screen,
            // so the last sand far from the hole goes too.
            float sweep = Math.max(0f, (elapsed - 0.6f) / 0.4f);
            // The hole widens from a small opening to about three times that, like a growing
            // vortex: more sand pours through as midnight nears.
            float unit = grid.rows / 270f;
            holeR = Math.max(2, Math.round(unit * (4f + 8f * elapsed)));
            holeY = grid.rows - holeR - 2;
            grid.startVacuumMove(holeX, holeY, holeR);
            grid.setVacuumReach(holeR + (far - holeR) * f * f, 1f + 2f * elapsed, far * sweep);
            String before = shownTime;
            renderClock(c, false);
            if (!shownTime.equals(before)) markWallsAsLids(newSlots); // letters stay wall-less
            solidTime = shownTime;
            physics(now);
            int due = dueGrains(c);
            while (spawned < due) { spawnNext(false); }
            return;
        }

        if (draining) {
            physics(now);
            if (grid.sandCount <= 0 || now - drainStart > DRAIN_MAX_MS) build(c, w, h);
            return;
        }

        if (isNight()) {
            renderClock(c, false); // keep the dim clock current; the sand rests
            lastTickAt = now;
            return;
        }

        // Minute change: characters that change let go of their sand (it falls off), and
        // after a moment the new ones appear. Unchanged characters keep their heaps, so the
        // hour digits collect sand for an hour and the date all day.
        renderClock(c, false);
        if (!shownTime.equals(solidTime)) {
            solidTime = shownTime;
            java.util.Set<String> next = new java.util.HashSet<>();
            for (Slot sl : newSlots) next.add(sl.id);
            java.util.List<Slot> keep = new java.util.ArrayList<>();
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (Slot sl : solidSlots) {
                if (next.contains(sl.id)) { keep.add(sl); kept.add(sl.id); }
                else grid.clearSolid(sl.cells);
            }
            solidSlots = keep;
            pendingSlots = new java.util.ArrayList<>();
            for (Slot sl : newSlots) if (!kept.contains(sl.id)) pendingSlots.add(sl);
            shakeUntil = now + SHAKE_MS;

        }
        if (pendingSlots != null && now >= shakeUntil) {
            for (Slot sl : pendingSlots) grid.setSolid(sl.cells);
            solidSlots.addAll(pendingSlots);
            pendingSlots = null;

        }

        physics(now);

        int due = dueGrains(c);
        if (due - spawned > 3 || prefs.anim == Prefs.ANIM_OFF) {
            while (spawned < due) { spawnNext(true); }
        } else {
            while (spawned < due) { spawnNext(false); }
        }
    }

    /** Releases the next grain of the day, tagged with the hour it falls in. */
    private void spawnNext(boolean instant) {
        grid.spawnLayer = GrainGrid.layerOf(spawned);
        if (recording && !previewMode) stats().addGrain();
        if (instant) grid.dropInstant(dropColumn(spawned));
        else grid.spawn(dropColumn(spawned));
        spawned++;
    }

    // ---- sun, moon, glints ----

    /** Keeps the sun or moon in place and sets a surface grain glinting now and then. */
    private void decorate(Calendar c) {
        updateSky(c);
        updateNote(c);
        if (glint >= 0) { grid.markCell(glint); glint = -1; }
        if (isNight() || draining || vacuumOn || prefs.anim == Prefs.ANIM_OFF) return;
        if (rnd.nextFloat() < 0.4f) {
            int x = rnd.nextInt(grid.cols);
            int y = grid.freeDepth(x);
            if (y < grid.rows && grid.c[y * grid.cols + x] == GrainGrid.SAND) {
                glint = y * grid.cols + x;
                grid.markCell(glint);
            }
        }
    }

    /**
     * The sun crosses the sky from sunrise to sunset (the chosen city, else 07:00 to 19:15)
     * and sets behind the pile; the moon, in tonight's real phase, follows until sunrise.
     * Recomputed once a minute; only its cells are redrawn.
     */
    private void updateSky(Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int key = prefs.sunMoon ? m + colorKey * 1441 : -2;
        if (key == skyKey) return;
        skyKey = key;
        for (int k = 0; k < nSky; k++) { sky[skyCells[k]] = 0; grid.markCell(skyCells[k]); }
        nSky = 0;
        if (!prefs.sunMoon) return;
        int[] t = prefs.city > 0 ? Sun.times(prefs.city, c) : new int[]{420, 1155};
        int rise = t[0], set = t[1];
        boolean sun = m >= rise && m < set;
        float p;
        if (sun) {
            p = (m - rise) / (float) Math.max(1, set - rise);
        } else {
            int mm = m < rise ? m + 1440 : m;
            p = (mm - set) / (float) Math.max(1, 1440 - set + rise);
        }
        int cols = grid.cols, rows = grid.rows;
        int horizon = Math.round(rows * 0.92f), top = Math.round(rows * 0.1f);
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
                    // Lit part only: waxing lit on the right, waning on the left.
                    double term = w * Math.cos(2 * Math.PI * phase);
                    boolean lit = phase < 0.5 ? dx >= term : dx <= -term;
                    if (!lit) continue;
                }
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                int i = y * cols + x;
                sky[i] = d > r - 1.2 ? edge : core;
                skyCells[nSky++] = i;
                grid.markCell(i);
            }
        }
    }

    /**
     * Once an hour (at :30, for 12 s) a line about the sand's lifetime stats appears near the
     * top of the sky, drawn over empty cells only (sand in front of it hides it).
     */
    private void updateNote(Calendar c) {
        long now = System.currentTimeMillis();
        if (nNote > 0 && (now > noteUntil || isNight() || vacuumOn || draining)) {
            for (int k = 0; k < nNote; k++) { note[noteCells[k]] = 0; grid.markCell(noteCells[k]); }
            nNote = 0;
        }
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (nNote > 0 || isNight() || vacuumOn || draining || noteHour == h) return;
        if (c.get(Calendar.MINUTE) != 30 || c.get(Calendar.SECOND) > 20) return;
        noteHour = h;
        noteUntil = now + 12_000;
        String msg = stats().sandMessage(c, h);
        int cols = grid.cols, rows = grid.rows;
        int sc = Math.max(1, Math.round(rows / 135f));
        int left = Math.max(0, (cols - PixelFont.width(msg) * sc) / 2);
        int top = Math.round(rows * 0.035f);
        java.util.ArrayList<Integer> cells = new java.util.ArrayList<>();
        int x = left;
        for (int k = 0; k < msg.length(); k++) {
            for (int i : PixelFont.cells(msg.charAt(k), x, top, sc, cols, rows)) cells.add(i);
            x += PixelFont.advance(msg.charAt(k), sc);
        }
        if (noteCells.length < cells.size()) noteCells = new int[cells.size()];
        int col = prefs.textColor(0.75f);
        for (int i : cells) {
            noteCells[nNote++] = i;
            note[i] = col;
            grid.markCell(i);
        }
    }

    /** Bedrock: dark stone in faint strata, with this fortnight's fossils. */
    private void buildBedrock() {
        if (bedRows <= 0) { bedLayer = null; return; }
        int cols = grid.cols;
        int[] p = new int[cols * bedRows];
        int a = prefs.color(0x3A322A, darkStyle() ? 0.7f : 1f), b = prefs.color(0x2C2620, darkStyle() ? 0.7f : 1f);
        for (int y = 0; y < bedRows; y++) for (int x = 0; x < cols; x++) {
            int hsh = (y * cols + x) * 0x9E3779B1;
            p[y * cols + x] = ((y + ((hsh >>> 30) & 1)) % 3 == 0) ? b : a;
        }
        int bone = prefs.color(0xD8CCB0, 0.6f);
        int n = stats().fossils();
        if (bedRows >= 4) {
            for (int k = 0; k < n; k++) {
                String[] f = FOSSILS[k % 2];
                int fx = Math.round(cols * (0.07f + 0.86f * (k + 0.5f) / 8f)) - 2;
                int fy = Math.max(0, (bedRows - 3) / 2 + (k % 2));
                for (int r = 0; r < 3; r++) for (int q = 0; q < 4; q++) {
                    if (f[r].charAt(q) != '#') continue;
                    int x = fx + q, y = fy + r;
                    if (x >= 0 && x < cols && y < bedRows) p[y * cols + x] = bone;
                }
            }
        }
        bedLayer = Bitmap.createBitmap(p, cols, bedRows, Bitmap.Config.ARGB_8888);
    }

    /** Dim night clock: a few faint stars and the moon. */
    private void drawNightSky(Canvas canvas) {
        java.util.Random r = new java.util.Random(gridDay * 7L + 3);
        starPaint.setColor(prefs.nightBacklight ? 0xFF2C2C34 : 0xFF3C3C48);
        int band = Math.round(grid.rows * 0.6f);
        for (int k = 0; k < 40; k++) {
            int x = r.nextInt(grid.cols), y = r.nextInt(band);
            float l = x * cell, t = dst.top + y * cell;
            canvas.drawRect(l, t, l + cell, t + cell, starPaint);
        }
        drawConstellation(canvas);
        for (int k = 0; k < nSky; k++) {
            int i = skyCells[k];
            starPaint.setColor(dimColor(sky[i]));
            float l = (i % grid.cols) * cell, t = dst.top + (i / grid.cols) * cell;
            canvas.drawRect(l, t, l + cell, t + cell, starPaint);
        }
    }

    /** The fortnight's constellation: its stars so far, joined by faint dotted lines. */
    private void drawConstellation(Canvas canvas) {
        int n = stats().stars();
        if (n <= 0) return;
        float bw = grid.cols * 0.14f, bh = grid.rows * 0.3f;
        float ox = grid.cols * 0.07f, oy = grid.rows * 0.08f;
        int line = prefs.nightBacklight ? 0xFF1C1C24 : 0xFF26262E;
        int star = prefs.nightBacklight ? 0xFF4A4A58 : 0xFF62627A;
        for (int k = 0; k < n; k++) {
            float[] p = CONSTELLATION[k];
            boolean closed = n == CONSTELLATION.length;
            if (k > 0 || closed) {
                float[] q = CONSTELLATION[k > 0 ? k - 1 : CONSTELLATION.length - 1];
                float x0 = ox + q[0] * bw, y0 = oy + q[1] * bh, x1 = ox + p[0] * bw, y1 = oy + p[1] * bh;
                int steps = (int) (Math.hypot(x1 - x0, y1 - y0) / 3);
                starPaint.setColor(line);
                for (int s = 1; s < steps; s++) {
                    int x = Math.round(x0 + (x1 - x0) * s / steps), y = Math.round(y0 + (y1 - y0) * s / steps);
                    canvas.drawRect(x * cell, dst.top + y * cell, (x + 1) * cell, dst.top + (y + 1) * cell, starPaint);
                }
            }
        }
        starPaint.setColor(star);
        for (int k = 0; k < n; k++) {
            int x = Math.round(ox + CONSTELLATION[k][0] * bw), y = Math.round(oy + CONSTELLATION[k][1] * bh);
            canvas.drawRect(x * cell, dst.top + y * cell, (x + 2) * cell, dst.top + (y + 2) * cell, starPaint);
        }
    }

    /** Opens the hole at a random spot and takes the walls out of the letters. */
    private void startVacuum(long now) {
        vacuumOn = true;
        vacuumOpenedAt = now;
        java.util.Random r = new java.util.Random();
        holeR = Math.max(2, Math.round(grid.rows / 270f * 4f));
        holeX = grid.cols / 5 + r.nextInt(grid.cols * 3 / 5);
        // At the floor, like the neck of an hourglass: everything above drains down into it
        // under gravity, the crater's slopes all lead to it, and the suction speeds it up.
        holeY = grid.rows - holeR - 2;
        for (Slot sl : solidSlots) grid.clearSolid(sl.cells);
        solidSlots = new java.util.ArrayList<>();
        pendingSlots = null;
        markWallsAsLids(newSlots);
        grid.startVacuum(holeX, holeY, holeR);
    }

    private void physics(long now) {
        long tickMs = 1000 / GrainGrid.TICK_HZ;
        int ticks = (int) ((now - lastTickAt) / tickMs);
        if (ticks <= 0) return;
        lastTickAt += ticks * tickMs;
        int speed = demoStart >= 0 ? DEMO_SPEED : 1;
        ticks = Math.min(ticks * speed, 6 * speed); // after a pause, don't catch up frame by frame
        for (int i = 0; i < ticks; i++) grid.tick();
    }

    private void scheduleNext() {
        long delay;
        long toNextMinute = 60_000 - (System.currentTimeMillis() % 60_000) + 30;
        if (grid == null) {
            delay = 100;
        } else if (draining || vacuumOn || grid.hasActive() || pendingSlots != null) {
            delay = frameMs();
        } else if (isNight() || prefs.anim == Prefs.ANIM_OFF) {
            delay = toNextMinute;
        } else {
            delay = Math.max(33, 1000 - System.currentTimeMillis() % 1000 - 300);
        }
        handler.postDelayed(tick, delay);
    }

    /** Time between frames while sand is moving. The physics always runs at 30 ticks a second;
     *  fewer frames just means it is drawn less often (less work for the TV's display system). */
    private int frameMs() {
        switch (prefs.anim) {
            case Prefs.ANIM_SMOOTH: return 33;  // 30 fps
            case Prefs.ANIM_LOW: return 125;    // 8 fps
            default: return 66;                 // 15 fps
        }
    }

    // ---- the clock as pixels ----

    /**
     * Draws time, AM/PM and date into the grid with the block font when they change (or when
     * forced, e.g. a colour change). Fills newSlots, the text layer and shownTime.
     */
    private void renderClock(Calendar c, boolean force) {
        String key = ClockText.key(prefs, c);
        if (key.equals(shownTime) && !force) return;
        shownTime = key;

        int cols = grid.cols, rows = grid.rows;
        java.util.List<Slot> slots = new java.util.ArrayList<>();
        for (ClockText.Slot cs : ClockText.layout(prefs, c, cols, rows)) slots.add(new Slot(cs));
        newSlots = slots;
        int text = prefs.textColor(1f);
        textCol = text;
        java.util.Arrays.fill(textPx, Color.TRANSPARENT);
        if (lid.length != cols * rows) lid = new boolean[cols * rows];
        java.util.Arrays.fill(lid, false);
        for (Slot sl : slots) {
            for (int i : sl.cells) textPx[i] = text;
            for (int i : sl.lids) { textPx[i] = text; lid[i] = true; }
        }
        grid.markAll(); // repaint letters (walls and top edges)
        textLayer.setPixels(textPx, 0, cols, 0, 0, cols, rows);
    }

    /** Draws letters' walls without them being solid yet (during the pour-in). */
    private void markWallsAsLids(java.util.List<Slot> slots) {
        for (Slot sl : slots) for (int i : sl.cells) lid[i] = true;
        grid.markAll();
    }

    // ---- drawing ----

    private boolean darkStyle() { return prefs.style < 2; }

    private void updateColors() {
        int key = prefs.background * 100000 + prefs.style * 10000 + prefs.theme * 1000
                + prefs.brightness * 100 + Math.round(prefs.warmth * 20);
        if (key == colorKey) return;
        colorKey = key;
        float[] f = prefs.style == 0
                ? new float[]{0f, 0f, 0f, 0f}                       // pitch black: zones fully off
                : prefs.style == 1
                ? new float[]{0.04f, 0.06f, 0.08f, 0.10f}           // near-black, faint texture
                : new float[]{0.78f * GLOW_SAND, 0.9f * GLOW_SAND, GLOW_SAND, 1.12f * GLOW_SAND};
        int base = prefs.sandRgb();
        for (int l = 0; l < shades.length; l++) {
            int band = prefs.sandLayers && l > 0 ? (l - 1) % LAYER_B.length : 0;
            float b = LAYER_B[band], hs = LAYER_H[band];
            int rr = Math.min(255, Math.round(((base >> 16) & 0xFF) * b * (1 + hs)));
            int gg = Math.min(255, Math.round(((base >> 8) & 0xFF) * b));
            int bb = Math.min(255, Math.round((base & 0xFF) * b * (1 - hs)));
            int tinted = (rr << 16) | (gg << 8) | bb;
            for (int i = 0; i < 4; i++) shades[l][i] = prefs.color(tinted, f[i]);
        }
        glintCol = prefs.color(0xFFF4DC, 1f);
        buildBedrock();
        background = darkStyle() ? prefs.sandColor(Prefs.BACKGROUND_F[prefs.background]) : Color.BLACK;
        textCol = prefs.textColor(1f);
        grid.markAll();
        shownTime = ""; // redraw the digits in the new colour on the next step
    }

    private int colorOf(int i) {
        byte v = grid.c[i];
        // Letter walls and top edges, drawn right in the sand picture. Top edges stay drawn
        // even with sand passing through, so outlines stay whole inside the pile.
        if (v == GrainGrid.SOLID || (lid.length > i && lid[i])) return textCol;
        if (v != GrainGrid.SAND) {
            if (note.length > i && note[i] != 0) return note[i];
            return sky.length > i && sky[i] != 0 ? sky[i] : background;
        }
        if (i == glint) return glintCol;
        int hsh = i * 0x9E3779B1;
        return shades[grid.layer[i]][(hsh >>> 28) & 3];
    }

    /** The hole: a black opening with a turning, dashed pixel ring; it grows open over 2 s. */
    private void drawHole(Canvas canvas) {
        long t = SystemClock.uptimeMillis() - vacuumOpenedAt;
        float r = holeR * Math.min(1f, t / 2000f);
        if (r < 0.5f) return;
        float top = dst.top;
        int ring = isNight() ? dimColor(textCol) : textCol;
        int phase = (int) (t / 120);
        for (int dy = -holeR - 1; dy <= holeR + 1; dy++) {
            for (int dx = -holeR - 1; dx <= holeR + 1; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                int color;
                if (d <= r - 0.5f) color = Color.BLACK;
                else if (d <= r + 0.7f) {
                    int a = (int) Math.round((Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI) * 16);
                    if (((a + phase) & 3) >= 2) continue; // dashes that turn
                    color = ring;
                } else continue;
                int x = holeX + dx, y = holeY + dy;
                if (x < 0 || y < 0 || x >= grid.cols || y >= grid.rows) continue;
                holePaint.setColor(color);
                canvas.drawRect(x * cell, top + y * cell, (x + 1) * cell, top + (y + 1) * cell, holePaint);
            }
        }
    }

    private static int dimColor(int c) {
        float f = NIGHT_DIM;
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * f) << 16)
                | ((int) (((c >> 8) & 0xFF) * f) << 8) | (int) ((c & 0xFF) * f);
    }

    private void flushPixels() {
        int n = grid.takeChanges();
        if (n == 0) return;
        int cols = grid.cols;
        if (n < 0) {
            for (int i = 0; i < px.length; i++) px[i] = colorOf(i);
            scene.setPixels(px, 0, cols, 0, 0, cols, grid.rows);
            return;
        }
        // Copy only the rectangle that changed (usually a falling grain and a short slide).
        int[] ch = grid.changedCells();
        int x0 = cols, y0 = grid.rows, x1 = -1, y1 = -1;
        for (int k = 0; k < n; k++) {
            int i = ch[k];
            px[i] = colorOf(i);
            int x = i % cols, y = i / cols;
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
        }
        scene.setPixels(px, y0 * cols + x0, cols, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w == 0 || h == 0 || grid == null) return;
        // Grid sits on the bedrock at the bottom of the screen (its top row may be off-screen).
        int floor = h - Math.round(bedRows * cell);
        dst.set(0, floor - Math.round(grid.rows * cell), Math.round(grid.cols * cell), floor);
        bedDst.set(0, floor, Math.round(grid.cols * cell), h);

        if (isNight() && !draining && !vacuumOn) {
            if (prefs.nightStyle == 1) {
                if (prefs.sunMoon) drawNightSky(canvas);
                canvas.drawBitmap(textLayer, null, dst, dimPaint);
            }
            return; // black screen: zones stay dark
        }
        updateColors();
        flushPixels();
        Paint p = isNight() ? dimPaint : pixelPaint;
        canvas.drawBitmap(scene, null, dst, p);
        if (bedLayer != null) canvas.drawBitmap(bedLayer, null, bedDst, p);
        if (vacuumOn) drawHole(canvas);

    }
}
