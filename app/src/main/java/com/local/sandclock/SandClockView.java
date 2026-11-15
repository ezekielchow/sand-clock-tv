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
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The whole screen is the hourglass, drawn as chunky pixels like an old game. One grain falls
 * every second of the day from a chosen point at the top. Grains pile up on the ground and on
 * the clock's digits, spill off their edges, and when the minute changes everything resting on
 * the digits falls off. By midnight the screen is full; the floor opens and it pours out.
 *
 * Power notes: only moving grains are simulated; the picture is a small bitmap scaled up
 * (no smoothing); black sand switches the mini-LED zones under it fully off.
 */
public class SandClockView extends View {

    interface Listener {
        void onNightChanged(boolean night);
    }

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
    private int gridDay = -1;
    private int spawned;
    private long lastTickAt;

    // The clock as solid cells. On a minute change the old digits stop being solid for a moment
    // (everything on them falls), then the new ones become solid.
    /** One character of the clock text, as solid cells. Same id = same character in the same
     *  place, so it keeps its sand when the minute changes. */
    private static final class Slot {
        final String id;
        final int[] cells;  // walls: solid
        final int[] lids;   // open top edges: drawn, but sand passes through
        Slot(String id, int[] cells, int[] lids) { this.id = id; this.cells = cells; this.lids = lids; }
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
    private final int[] shades = new int[4];

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
        float frac = prefs.dropPos / 100f;
        cell = Math.max(1, Math.round(h / (float) GRID_ROWS));
        int cols = (int) Math.ceil(w / cell), rows = (int) Math.ceil(h / cell);
        int day = dayKey(c);
        grid = new GrainGrid(cols, rows, day, GrainGrid.slideFor(prefs.dropPos));
        gridDay = day;
        dropX = Math.round(frac * (cols - 1));
        draining = false;
        vacuumOn = false;
        shakeUntil = -1;
        pendingSlots = null;
        px = new int[cols * rows];
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

    /** Column grain n falls from: drifting back and forth around the drop point (3-minute swing). */
    private int dropColumn(int n) {
        double x = dropX + swayAmp(n) * Math.sin(2 * Math.PI * n / 180.0);
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
            while (spawned < due) { grid.spawn(dropColumn(spawned)); spawned++; }
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
            while (spawned < due) { grid.dropInstant(dropColumn(spawned)); spawned++; }
        } else {
            while (spawned < due) { grid.spawn(dropColumn(spawned)); spawned++; }
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
        Date now = c.getTime();
        String time = new SimpleDateFormat(prefs.use24h ? "HH:mm" : "h:mm", Locale.US).format(now);
        String ampm = prefs.use24h ? "" : new SimpleDateFormat("a", Locale.US).format(now);
        String date = new SimpleDateFormat("EEE d MMM", Locale.US).format(now).toUpperCase(Locale.US);
        String key = time + ampm + date;
        if (key.equals(shownTime) && !force) return;
        shownTime = key;

        int cols = grid.cols, rows = grid.rows;
        int st = Math.max(1, Math.round(rows * 0.2f / PixelFont.H));   // time: 8 on a TV
        int sa = Math.max(1, st / 4);                                   // AM/PM
        int sd = Math.max(1, Math.round(rows * 0.075f / PixelFont.H)); // date
        int mid = rows / 2;
        int tw = PixelFont.width(time) * st;
        int tLeft = (cols - tw) / 2, tTop = mid - PixelFont.H * st;
        java.util.List<Slot> slots = new java.util.ArrayList<>();
        addSlots(slots, "t", time, tLeft, tTop, st);
        if (!ampm.isEmpty()) addSlots(slots, "a", ampm, tLeft + tw + st, mid - PixelFont.H * sa, sa);
        int dw = PixelFont.width(date) * sd;
        addSlots(slots, "d", date, (cols - dw) / 2, mid + Math.round(rows * 0.06f), sd);
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

    private void addSlots(java.util.List<Slot> out, String part, String s, int left, int top, int scale) {
        int x = left;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch != ' ') {
                int[][] wl = walls(PixelFont.cells(ch, x, top, scale, grid.cols, grid.rows));
                out.add(new Slot(part + ch + "@" + x + "," + top + "," + scale, wl[0], wl[1]));
            }
            x += PixelFont.advance(ch, scale);
        }
    }

    /**
     * Turns a letter into a container: its outline becomes wall, except the upward-facing
     * edges between corners, which are left open so sand can pour in and fill it.
     */
    private int[][] walls(int[] glyph) {
        int cols = grid.cols, rows = grid.rows;
        java.util.HashSet<Integer> in = new java.util.HashSet<>();
        for (int i : glyph) in.add(i);
        int[] out = new int[glyph.length];
        int[] lids = new int[glyph.length];
        int n = 0, m = 0;
        for (int i : glyph) {
            int x = i % cols, y = i / cols;
            boolean up = y > 0 && in.contains(i - cols);
            boolean down = y < rows - 1 && in.contains(i + cols);
            boolean left = x > 0 && in.contains(i - 1);
            boolean right = x < cols - 1 && in.contains(i + 1);
            if (up && down && left && right) continue;   // inside: room for sand
            if (!up && left && right) {                  // top edge: the opening
                lids[m++] = i;
                continue;
            }
            out[n++] = i;
        }
        return new int[][]{java.util.Arrays.copyOf(out, n), java.util.Arrays.copyOf(lids, m)};
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
        for (int i = 0; i < 4; i++) shades[i] = prefs.sandColor(f[i]);
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
        if (v != GrainGrid.SAND) return background;
        int hsh = i * 0x9E3779B1;
        return shades[(hsh >>> 28) & 3];
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
        // Grid is anchored to the bottom of the screen (its top row may sit just off-screen).
        dst.set(0, h - Math.round(grid.rows * cell), Math.round(grid.cols * cell), h);

        if (isNight() && !draining && !vacuumOn) {
            if (prefs.nightStyle == 1) canvas.drawBitmap(textLayer, null, dst, dimPaint);
            return; // black screen: zones stay dark
        }
        updateColors();
        flushPixels();
        Paint p = isNight() ? dimPaint : pixelPaint;
        canvas.drawBitmap(scene, null, dst, p);
        if (vacuumOn) drawHole(canvas);

    }
}
