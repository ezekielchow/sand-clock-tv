package com.local.sandclock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;

/**
 * Keeps the screen on today's scene: just after midnight (once the old day's sand or sea has
 * emptied) it checks the new day's pick and asks the host to swap if it changed.
 */
final class DailyScene {
    interface Host { void showScene(int scene); }

    /** After midnight's drain (sand: up to 14 s; sea: 7 s). */
    private static final int AFTER_MIDNIGHT_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Host host;
    private int current;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            int s = SceneView.today(Prefs.load(context));
            if (s != current) {
                current = s;
                host.showScene(s);
            }
            schedule();
        }
    };

    DailyScene(Context context, Host host, int current) {
        this.context = context;
        this.host = host;
        this.current = current;
    }

    void start() {
        handler.removeCallbacks(check);
        // Started on another day than the scene was picked (e.g. the clock left open)? Check now.
        if (SceneView.today(Prefs.load(context)) != current) handler.post(check);
        else schedule();
    }

    void stop() {
        handler.removeCallbacks(check);
    }

    private void schedule() {
        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        long delay = next.getTimeInMillis() - System.currentTimeMillis() + AFTER_MIDNIGHT_MS;
        handler.postDelayed(check, Math.max(1000, delay));
    }
}
