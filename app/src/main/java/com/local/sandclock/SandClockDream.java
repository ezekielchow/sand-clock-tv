package com.local.sandclock;

import android.service.dreams.DreamService;
import android.view.WindowManager;

/** The system screensaver. Starts when the TV has been idle for the screensaver timeout. */
public class SandClockDream extends DreamService implements SandClockView.Listener {
    private SandClockView view;
    private Prefs prefs;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any remote key wakes the TV
        setFullscreen(true);
        prefs = Prefs.load(this);
        view = new SandClockView(this);
        view.setListener(this);
        view.setPrefs(prefs);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        SystemTweaks.ensureScreensaver(this);
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        SystemTweaks.restoreBacklight(this);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        SystemTweaks.restoreBacklight(this); // belt and braces
        super.onDetachedFromWindow();
    }

    @Override
    public void onNightChanged(boolean night) {
        boolean dim = night && prefs.nightBacklight;
        // Mi TV backlight (needs the adb grant): screensaver level by day, near-off at night.
        float level = Prefs.DREAM_BACKLIGHT_F[prefs.dreamBacklight];
        if (dim) level = Math.min(level, 0.1f);
        if (level < 1f) SystemTweaks.dimBacklight(this, level);
        else SystemTweaks.restoreBacklight(this);
        // Standard Android routes, for TVs that honour them.
        setScreenBright(!dim);
        if (getWindow() != null) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = dim ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
    }
}
