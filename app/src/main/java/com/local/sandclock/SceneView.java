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

    /** One of the scenes the user picked, at random. */
    static int pick(Prefs p) {
        int n = 0;
        for (int i = 0; i < NAMES.length; i++) if ((p.scenes & (1 << i)) != 0) n++;
        if (n == 0) return SAND;
        int k = new java.util.Random().nextInt(n);
        for (int i = 0; i < NAMES.length; i++) {
            if ((p.scenes & (1 << i)) != 0 && k-- == 0) return i;
        }
        return SAND;
    }
}
