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
 * The kampung scene on screen: runs KampungScene, uploads only the rectangles that changed, and
 * draws the small picture scaled up with hard pixel edges. At night: a black screen, or the dim
 * billboard clock with a few faint stars and lit windows.
 */
public class KampungClockView extends SceneView {
    private static final int GRID_ROWS = 270;
    private static final float NIGHT_DIM = 0.35f;
    private static final int STARS = 30;

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

    private KampungScene scene;
    private Bitmap picture, textLayer;
    private float cell;
    private long midnightAt = -1; // uptime the day changed, while the old day is flattened
    private Calendar cal;
    private long calMinute = -1;
    private boolean textFull = true;  // the text layer needs a whole upload (new scene or bitmap)
    private final int[] starX = new int[STARS], starY = new int[STARS];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            scheduleNext();
        }
    };

    public KampungClockView(Context context) {
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
        scene = new KampungScene(cols, rows, dayKey(c), prefs);
        midnightAt = -1;
        textFull = true;
        if (picture == null || picture.getWidth() != cols || picture.getHeight() != rows) {
            picture = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
            textLayer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        }
        // The night clock's faint stars: fixed for the day.
        Random r = new Random(scene.day * 131L);
        int band = Math.max(1, Math.round(scene.G * 0.45f));
        for (int k = 0; k < STARS; k++) {
            starX[k] = r.nextInt(scene.cols);
            starY[k] = r.nextInt(band);
        }
        if (demoRequested) {
            demoRequested = false;
            scene.startDemo(t, c);
        } else if (prefs.kampungGrowIn && !isNight() && prefs.anim != Prefs.ANIM_OFF && !previewMode) {
            scene.startGrowIn(t);
        }
    }

    private void step() {
        // One calendar, made again each minute (so a time zone change is picked up).
        long now = System.currentTimeMillis();
        if (cal == null || now / 60_000 != calMinute) {
            cal = Calendar.getInstance();
            calMinute = now / 60_000;
        }
        Calendar c = cal;
        c.setTimeInMillis(now);
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
            // Midnight. With the rampage on, the biawak has already been through; otherwise it
            // runs through now. Either way the new kampung starts once the dust has settled
            // (at most 15 s).
            // (At night the town isn't shown: no quick run, the new day starts now.)
            if (midnightAt < 0) midnightAt = t;
            if (reset && !isNight() && !scene.flattened() && !scene.quickDone(t) && prefs.anim != Prefs.ANIM_OFF) {
                scene.startQuick(t);
            } else if (!scene.airborne() || t - midnightAt > 15_000) {
                midnightAt = -1;
                build(c, w, h, t);
            }
        }
        boolean animate = prefs.anim != Prefs.ANIM_OFF && !isNight();
        countTime(KAMPUNG, c, isNight());
        scene.setStats(stats(), recording && !previewMode);
        // At night only the text layer and the lit windows show: keep the town current, don't draw it.
        scene.update(c, t, animate, !previewMode && reset, !isNight());
        upload();
    }

    private void upload() {
        KampungScene s = scene;
        if (isNight()) {
            if (s.textChanged && prefs.nightStyle == 1) {
                s.fillTextPx();
                if (textFull) {
                    textLayer.setPixels(s.textPx, 0, s.cols, 0, 0, s.cols, s.rows);
                    textFull = false;
                } else if (s.textW() > 0 && s.textH() > 0) {
                    textLayer.setPixels(s.textPx, s.textY0() * s.cols + s.textX0(), s.cols, s.textX0(), s.textY0(), s.textW(), s.textH());
                }
                s.textChanged = false;
            }
            return; // the picture isn't shown (it is uploaded whole when the day view returns)
        }
        if (s.fullDirty) {
            picture.setPixels(s.px, 0, s.cols, 0, 0, s.cols, s.rows);
            s.fullDirty = false;
        } else {
            for (int k = 0; k + 3 < s.nDirty; k += 4) {
                int x = s.dirty[k], y = s.dirty[k + 1], rw = s.dirty[k + 2], rh = s.dirty[k + 3];
                picture.setPixels(s.px, y * s.cols + x, s.cols, x, y, rw, rh);
            }
        }
    }

    private void scheduleNext() {
        long now = System.currentTimeMillis();
        long delay;
        if (scene == null) {
            delay = 100;
        } else if (isNight()) {
            delay = 60_000 - now % 60_000 + 30; // the text, the lit windows: once a minute
        } else if (prefs.anim == Prefs.ANIM_OFF) {
            delay = 1000 - now % 1000 + 5; // the kite or the train ticks once a second
        } else if (scene.busy()) {
            // The biawak, dust, rain, fireworks, grow-in. Rain alone is fine at about 11 fps, and
            // the biawak's own rampage (a few cells a second) at about 14.
            if (scene.rainOnly()) delay = Math.max(frameMs(), 90);
            else if (scene.rampaging()) delay = Math.max(frameMs(), 70);
            else delay = frameMs();
        } else if (scene.moving()) {
            // The train (about 8 cells a second), the rider and the plane need 8 fps; people,
            // the kite and the cranes are fine at 4 fps.
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
            drawNightTown(canvas);
            return;
        }
        canvas.drawBitmap(picture, null, dst, pixelPaint);
    }

    /** Dim night clock: a few faint stars, and a few of the town's lit windows. */
    private void drawNightTown(Canvas canvas) {
        dotPaint.setColor(prefs.nightBacklight ? 0xFF2C2C34 : 0xFF3C3C48);
        for (int k = 0; k < STARS; k++) {
            float l = starX[k] * cell, tp = dst.top + starY[k] * cell;
            canvas.drawRect(l, tp, l + cell, tp + cell, dotPaint);
        }
        dotPaint.setColor(prefs.nightBacklight ? 0xFF3A3018 : 0xFF4C3E1E);
        for (int i : scene.nightLights) {
            int x = i % scene.cols, y = i / scene.cols;
            float l = x * cell, tp = dst.top + y * cell;
            canvas.drawRect(l, tp, l + cell * 2, tp + cell, dotPaint);
        }
    }
}
