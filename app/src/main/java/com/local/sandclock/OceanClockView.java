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
 * The ocean scene on screen: runs OceanScene, uploads only the rectangles that changed, and
 * draws the small picture scaled up with hard pixel edges. At night: a black screen, or the dim
 * clock with a few faint, slowly twinkling plankton.
 */
public class OceanClockView extends SceneView {
    private static final int GRID_ROWS = 270;
    private static final float NIGHT_DIM = 0.35f;
    private static final int PLANKTON = 36;

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

    private OceanScene scene;
    private Bitmap picture, textLayer;
    private float cell;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step();
            invalidate();
            scheduleNext();
        }
    };

    public OceanClockView(Context context) {
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
        scene = new OceanScene(cols, rows, dayKey(c), prefs);
        if (picture == null || picture.getWidth() != cols || picture.getHeight() != rows) {
            picture = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
            textLayer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        }
        if (demoRequested) {
            demoRequested = false;
            scene.startDemo(t, c);
        } else if (prefs.tideIn && !isNight() && prefs.anim != Prefs.ANIM_OFF && !previewMode) {
            scene.startTide(t);
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
            // Midnight. With the whirlpool on the sea is already gone; otherwise drain it now.
            boolean empty = scene.levelRow() >= scene.emptyRow - 1;
            if (reset && !empty && !scene.drained(t) && prefs.anim != Prefs.ANIM_OFF) {
                scene.startDrain(t);
            } else {
                build(c, w, h, t);
            }
        }
        boolean animate = prefs.anim != Prefs.ANIM_OFF && !isNight();
        scene.update(c, t, animate, !previewMode && reset);
        upload();
    }

    private void upload() {
        OceanScene s = scene;
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
            delay = 1000 - now % 1000 + 5; // the whale ticks once a second
        } else if (scene.busy()) {
            delay = frameMs();
        } else {
            // Only the whale and the animals move, about a cell per 1/8 s: 8 fps is enough.
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
            canvas.drawBitmap(textLayer, null, dst, dimPaint);
            drawPlankton(canvas);
            return;
        }
        canvas.drawBitmap(picture, null, dst, pixelPaint);
    }

    /** A few faint glowing dots in the dark water, each lit now and then. */
    private void drawPlankton(Canvas canvas) {
        Random r = new Random(scene.day * 131L);
        int top = Math.min(scene.levelRow() + 3, scene.floorRow - 4);
        int span = Math.max(1, scene.floorRow - 2 - top);
        long phase = SystemClock.uptimeMillis() / 2000;
        dotPaint.setColor(prefs.nightBacklight ? 0xFF0E3A44 : 0xFF174E5A);
        for (int k = 0; k < PLANKTON; k++) {
            int x = r.nextInt(scene.cols), y = top + r.nextInt(span);
            int beat = r.nextInt(5);
            if ((phase + beat) % 5 >= 2) continue;
            float l = x * cell, tp = dst.top + y * cell;
            canvas.drawRect(l, tp, l + cell, tp + cell, dotPaint);
        }
    }
}
