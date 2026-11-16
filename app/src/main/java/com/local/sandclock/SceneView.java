package com.local.sandclock;

import android.content.Context;
import android.view.View;

/** A clock scene (sand, ocean, ...): what the screensaver, the clock and the preview show. */
abstract class SceneView extends View {
    interface Listener {
        void onNightChanged(boolean night);
    }

    static final int SAND = 0, OCEAN = 1;
    static final String[] NAMES = {"Sand", "Ocean"};

    SceneView(Context c) { super(c); }

    /** True only while this is the TV's screensaver: then what it shows is counted in Stats. */
    protected boolean recording;
    private long statsAt = -1;

    void setRecording(boolean on) {
        recording = on;
        statsAt = -1;
        if (!on) Stats.get(getContext()).save();
    }

    protected Stats stats() { return Stats.get(getContext()); }

    /** Each step: adds the time since the last one to this scene (only while recording). */
    protected void countTime(int scene, java.util.Calendar c, boolean night) {
        if (!recording) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (statsAt >= 0) stats().addTime(scene, Math.min(now - statsAt, 90_000), c);
        statsAt = now;
        if (night) stats().noteNight(c, Sun.moonPhase(c.getTimeInMillis()));
    }

    abstract void setPrefs(Prefs p);
    abstract void setListener(Listener l);
    /** Settings preview always shows the daytime look. */
    abstract void setPreviewMode(boolean preview);
    abstract void start();
    abstract void stop();
    /** Run the end of the day now, sped up. */
    abstract void demoVacuum();

    static SceneView create(Context c, int scene) {
        return scene == OCEAN ? new OceanClockView(c) : new SandClockView(c);
    }

    /** Today's scene (see Prefs.sceneFor). */
    static int today(Prefs p) {
        return p.sceneFor(java.util.Calendar.getInstance());
    }
}
