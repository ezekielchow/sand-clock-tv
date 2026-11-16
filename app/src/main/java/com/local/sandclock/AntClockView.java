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

import java.util.Calendar;
import java.util.Random;

/**
 * The ant farm on screen: runs AntScene, uploads only the rectangles that changed, and draws
 * the small picture scaled up with hard pixel edges. At night: a black screen, or the dim
 * clock with faint stars, the fortnight's constellation and the moon.
 */
public class AntClockView extends SceneView {
    private static final int GRID_ROWS = 270;
    private static final float NIGHT_DIM = 0.35f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint pixelPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint starPaint = new Paint();
    private final Rect dst = new Rect();

    private Prefs prefs = new Prefs();
    private Listener listener;
    private boolean previewMode;
    private boolean running;
    private Boolean lastNight;
    private boolean demoRequested;

    private AntScene scene;
    private Bitmap picture, textLayer;
    private float cell;

    /** The hourglass constellation, traced in order (unit square); a star per few hours. */
    private static final float[][] CONSTELLATION = {
            {0f, 0f}, {0.5f, 0f}, {1f, 0f}, {0.75f, 0.25f}, {0.55f, 0.5f}, {0.75f, 0.75f},
            {1f, 1f}, {0.5f, 1f}, {0f, 1f}, {0.25f, 0.75f}, {0.45f, 0.5f}, {0.25f, 0.25f}};

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            scheduleNext();
        }
    };

    public AntClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setAntiAlias(false);
        dimPaint.setFilterBitmap(false);
        dimPaint.setAlpha((int) (255 * NIGHT_DIM));
    }

    @Override
    void setPrefs(Prefs p) {
        prefs = p;
        lastNight = null;
        scene = null;
        if (running) {
            handler.removeCallbacks(tick);
            tick.run();
        } else {
            invalidate();
        }
    }

    @Override
    void setListener(Listener l) { listener = l; }

    @Override
    void setPreviewMode(boolean preview) { previewMode = preview; }

    @Override
    void demoVacuum() {
        demoRequested = true;
        scene = null;
    }

    @Override
    void start() {
        if (running) return;
        running = true;
        tick.run();
    }

    @Override
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
        scene = null;
        if (running) {
            handler.removeCallbacks(tick);
            tick.run();
        }
    }

    private boolean isNight() {
        return !previewMode && Boolean.TRUE.equals(lastNight);
    }

    private static int dayKey(Calendar c) {
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private void build(Calendar c, int w, int h, long t) {
        cell = Math.max(1, Math.round(h / (float) GRID_ROWS));
        int cols = (int) Math.ceil(w / cell), rows = (int) Math.ceil(h / cell);
        Stats st = stats();
        st.refresh(c);
        // Bedrock: up to 8 cells over the two weeks; set when the day's farm is built.
        int bed = Math.round(8 * st.sandGrowth());
        scene = new AntScene(cols, rows, dayKey(c), prefs, st, bed);
        if (picture == null || picture.getWidth() != cols || picture.getHeight() != rows) {
            picture = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
            textLayer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        }
        if (demoRequested) {
            demoRequested = false;
            scene.startDemo(t, c);
        } else if (prefs.pourIn && !isNight() && prefs.anim != Prefs.ANIM_OFF && !previewMode) {
            scene.startDigIn(t);
        }
    }

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
        long t = SystemClock.uptimeMillis();

        boolean reset = !isNight() || prefs.nightFlip;
        if (scene == null) {
            build(c, w, h, t);
        } else if (scene.demoOver(t)) {
            scene = null;
            build(c, w, h, t);
        } else if (dayKey(c) != scene.day) {
            // Midnight. With rain before midnight the farm is already washed clean; otherwise
            // rain now, quickly.
            if (reset && !scene.isEmpty() && !scene.rained(t) && prefs.anim != Prefs.ANIM_OFF) {
                scene.startRain(t);
            } else {
                build(c, w, h, t);
            }
        }
        boolean animate = prefs.anim != Prefs.ANIM_OFF && !isNight();
        if (!previewMode) countTime(ANTS, c, isNight());
        scene.setRecording(recording && !previewMode);
        scene.update(c, t, animate, !previewMode && reset);
        upload();
    }

    private void upload() {
        AntScene s = scene;
        if (s.fullDirty) {
            picture.setPixels(s.px, 0, s.cols, 0, 0, s.cols, s.rows);
            s.fullDirty = false;
        } else {
            for (int k = 0; k + 3 < s.nDirty; k += 4) {
                int x = s.dirty[k], y = s.dirty[k + 1], rw = s.dirty[k + 2], rh = s.dirty[k + 3];
                picture.setPixels(s.px, y * s.cols + x, s.cols, x, y, rw, rh);
            }
        }
        if (s.textChanged) {
            textLayer.setPixels(s.textPx, 0, s.cols, 0, 0, s.cols, s.rows);
            s.textChanged = false;
        }
    }

    private void scheduleNext() {
        long now = System.currentTimeMillis();
        long delay;
        if (scene == null) {
            delay = 100;
        } else if (isNight()) {
            delay = 60_000 - now % 60_000 + 30;
        } else if (prefs.anim == Prefs.ANIM_OFF) {
            delay = 1000 - now % 1000 + 5; // the surface ant ticks once a second
        } else if (scene.busy()) {
            delay = frameMs();
        } else {
            // Only the ants move, about a cell per 1/8 s: 8 fps is enough.
            delay = Math.max(frameMs(), 125);
        }
        handler.postDelayed(tick, delay);
    }

    private int frameMs() {
        switch (prefs.anim) {
            case Prefs.ANIM_SMOOTH: return 33;
            case Prefs.ANIM_LOW: return 125;
            default: return 66;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w == 0 || h == 0 || scene == null || picture == null) return;
        dst.set(0, h - Math.round(scene.rows * cell), Math.round(scene.cols * cell), h);
        if (isNight()) {
            if (prefs.nightStyle != 1) return; // black screen
            if (prefs.sunMoon) drawNightSky(canvas);
            canvas.drawBitmap(textLayer, null, dst, dimPaint);
            return;
        }
        canvas.drawBitmap(picture, null, dst, pixelPaint);
    }

    /** Dim night clock: a few faint stars, the fortnight's constellation and the moon. */
    private void drawNightSky(Canvas canvas) {
        Random r = new Random(scene.day * 7L + 3);
        starPaint.setColor(prefs.nightBacklight ? 0xFF2C2C34 : 0xFF3C3C48);
        int band = Math.round(scene.ground * 0.9f);
        for (int k = 0; k < 40; k++) dot(canvas, r.nextInt(scene.cols), r.nextInt(band), 1);
        drawConstellation(canvas);
        for (int k = 0; k < scene.nSky; k++) {
            int i = scene.skyCell(k);
            starPaint.setColor(dimColor(scene.skyColor(i)));
            dot(canvas, i % scene.cols, i / scene.cols, 1);
        }
    }

    private void drawConstellation(Canvas canvas) {
        int n = stats().stars();
        if (n <= 0) return;
        float bw = scene.cols * 0.14f, bh = scene.ground * 0.6f;
        float ox = scene.cols * 0.07f, oy = scene.ground * 0.12f;
        int line = prefs.nightBacklight ? 0xFF1C1C24 : 0xFF26262E;
        int star = prefs.nightBacklight ? 0xFF4A4A58 : 0xFF62627A;
        starPaint.setColor(line);
        boolean closed = n == CONSTELLATION.length;
        for (int k = 0; k < n; k++) {
            if (k == 0 && !closed) continue;
            float[] p = CONSTELLATION[k], q = CONSTELLATION[k > 0 ? k - 1 : CONSTELLATION.length - 1];
            float x0 = ox + q[0] * bw, y0 = oy + q[1] * bh, x1 = ox + p[0] * bw, y1 = oy + p[1] * bh;
            int steps = (int) (Math.hypot(x1 - x0, y1 - y0) / 3);
            for (int s = 1; s < steps; s++) {
                dot(canvas, Math.round(x0 + (x1 - x0) * s / steps), Math.round(y0 + (y1 - y0) * s / steps), 1);
            }
        }
        starPaint.setColor(star);
        for (int k = 0; k < n; k++) {
            dot(canvas, Math.round(ox + CONSTELLATION[k][0] * bw), Math.round(oy + CONSTELLATION[k][1] * bh), 2);
        }
    }

    private void dot(Canvas canvas, int x, int y, int size) {
        canvas.drawRect(x * cell, dst.top + y * cell, (x + size) * cell, dst.top + (y + size) * cell, starPaint);
    }

    private static int dimColor(int c) {
        float f = NIGHT_DIM;
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * f) << 16)
                | ((int) (((c >> 8) & 0xFF) * f) << 8) | (int) ((c & 0xFF) * f);
    }
}
