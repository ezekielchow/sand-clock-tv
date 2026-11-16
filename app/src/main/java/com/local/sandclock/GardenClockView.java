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
 * The garden scene on screen: runs GardenScene, uploads only the rectangles that changed, and
 * draws the small picture scaled up with hard pixel edges. At night: a black screen, or the dim
 * clock with a few faint stars and fireflies.
 */
public class GardenClockView extends SceneView {
    private static final int GRID_ROWS = 270;
    private static final float NIGHT_DIM = 0.35f;
    private static final int STARS = 30, FIREFLIES = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint pixelPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint dotPaint = new Paint();
    private final Rect dst = new Rect();

    private Prefs prefs = new Prefs();
    private Listener listener;
    private boolean previewMode;
    private boolean running;
    private Boolean lastNight;
    private boolean demoRequested;

    private GardenScene scene;
    private Bitmap picture, textLayer;
    private float cell;
    private long midnightAt = -1; // uptime the day changed, while the old day finishes

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            scheduleNext();
        }
    };

    public GardenClockView(Context context) {
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
        scene = new GardenScene(cols, rows, dayKey(c), prefs);
        if (picture == null || picture.getWidth() != cols || picture.getHeight() != rows) {
            picture = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
            textLayer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        }
        if (demoRequested) {
            demoRequested = false;
            scene.startDemo(t, c);
        } else if (prefs.gardenGrowIn && !isNight() && prefs.anim != Prefs.ANIM_OFF && !previewMode) {
            scene.startGrowIn(t);
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

        boolean reset = !isNight() || prefs.nightFlip; // midnight shown (dimmed at night)
        if (scene == null) {
            build(c, w, h, t);
        } else if (scene.demoOver(t)) {
            scene = null; // preview over: back to the day as it is
            build(c, w, h, t);
        } else if (dayKey(c) != scene.day) {
            // Midnight. After autumn the tree is already bare; otherwise drop its leaves now.
            // Either way the new day starts once the last leaves have landed (at most 15 s).
            if (midnightAt < 0) midnightAt = t;
            if (reset && !scene.bare() && !scene.dropped(t) && prefs.anim != Prefs.ANIM_OFF) {
                scene.startDrop(t);
            } else if (!scene.airborne() || t - midnightAt > 15_000) {
                midnightAt = -1;
                build(c, w, h, t);
            }
        }
        boolean animate = prefs.anim != Prefs.ANIM_OFF && !isNight();
        countTime(GARDEN, c, isNight());
        scene.setStats(stats(), recording && !previewMode);
        scene.update(c, t, animate, !previewMode && reset);
        upload();
    }

    private void upload() {
        GardenScene s = scene;
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
            delay = prefs.nightStyle == 1 ? 2000 : 60_000 - now % 60_000 + 30;
        } else if (prefs.anim == Prefs.ANIM_OFF) {
            delay = 1000 - now % 1000 + 5; // the ladybug ticks once a second
        } else if (scene.busy()) {
            delay = frameMs(); // petals, leaves, the bee, grow-in, autumn
        } else if (scene.moving()) {
            // The bird needs 8 fps while it crosses; the ladybug (~2.5 cells a second) and the
            // slow fireflies are fine at 4 fps.
            delay = Math.max(frameMs(), scene.fastMoving() ? 125 : 250);
        } else {
            delay = 1000 - now % 1000 + 5; // nothing moves: check the clock once a second
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
            canvas.drawBitmap(textLayer, null, dst, dimPaint);
            drawNightGarden(canvas);
            return;
        }
        canvas.drawBitmap(picture, null, dst, pixelPaint);
    }

    /** Dim night clock: a few faint stars, and fireflies near the tree, each lit now and then. */
    private void drawNightGarden(Canvas canvas) {
        Random r = new Random(scene.day * 131L);
        long phase = SystemClock.uptimeMillis() / 2000;
        dotPaint.setColor(prefs.nightBacklight ? 0xFF2C2C34 : 0xFF3C3C48);
        int band = Math.max(1, Math.round(scene.groundTop * 0.5f));
        for (int k = 0; k < STARS; k++) {
            int x = r.nextInt(scene.cols), y = r.nextInt(band);
            float l = x * cell, tp = dst.top + y * cell;
            canvas.drawRect(l, tp, l + cell, tp + cell, dotPaint);
        }
        int[] box = scene.treeBox;
        if (!prefs.gardenCritters || box[2] <= 0 || box[3] <= 0) return;
        dotPaint.setColor(prefs.nightBacklight ? 0xFF34381A : 0xFF464C20);
        for (int k = 0; k < FIREFLIES; k++) {
            int x = box[0] + r.nextInt(box[2]), y = box[1] + box[3] / 4 + r.nextInt(Math.max(1, box[3] * 3 / 4));
            int beat = r.nextInt(4);
            if ((phase + beat) % 4 != 0) continue;
            float l = x * cell, tp = dst.top + y * cell;
            canvas.drawRect(l, tp, l + cell, tp + cell, dotPaint);
        }
    }
}
