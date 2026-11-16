package com.local.sandclock;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Runs the clock full screen and keeps the TV awake. Press Back to leave. */
public class ClockActivity extends Activity implements SceneView.Listener {
    /** Start with the end-of-day vacuum running (settings: "Preview end of day"). */
    static final String EXTRA_DEMO_VACUUM = "demoVacuum";
    /** Which scene to show (default: one of the chosen scenes at random). */
    static final String EXTRA_SCENE = "scene";

    private SceneView view;
    private Prefs prefs;
    private CloseOnScreensaver closer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = SceneView.create(this, getIntent().getIntExtra(EXTRA_SCENE, SceneView.pick(Prefs.load(this))));
        view.setListener(this);
        setContentView(view);
        closer = CloseOnScreensaver.register(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Any remote button closes the clock and returns to what was on before, except volume. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_VOLUME_UP:
            case android.view.KeyEvent.KEYCODE_VOLUME_DOWN:
            case android.view.KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.onKeyDown(keyCode, event);
            default:
                finish();
                return true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(this);
        view.setPrefs(prefs);
        if (getIntent().getBooleanExtra(EXTRA_DEMO_VACUUM, false)) {
            getIntent().removeExtra(EXTRA_DEMO_VACUUM); // only once
            view.demoVacuum();
        }
        view.start();
    }

    @Override
    protected void onDestroy() {
        if (closer != null) closer.unregister();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        view.stop();
        SystemTweaks.restoreBacklight(this);
        super.onPause();
    }

    @Override
    public void onNightChanged(boolean night) {
        // At night, stop holding the TV awake so its own screensaver/standby rules apply.
        if (night) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float level = Prefs.DREAM_BACKLIGHT_F[prefs.dreamBacklight];
        if (night && prefs.nightBacklight) level = Math.min(level, 0.1f);
        if (level < 1f) SystemTweaks.dimBacklight(this, level);
        else SystemTweaks.restoreBacklight(this);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = (night && prefs.nightBacklight)
                ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }
}
