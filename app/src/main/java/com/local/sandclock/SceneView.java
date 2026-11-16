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

    /** Today's scene (see Prefs.sceneFor). */
    static int today(Prefs p) {
        return p.sceneFor(java.util.Calendar.getInstance());
    }
}
