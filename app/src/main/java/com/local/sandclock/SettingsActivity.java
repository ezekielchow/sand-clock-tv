package com.local.sandclock;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Remote-friendly settings: OK cycles a value, Left/Right adjusts it. Live preview on the right. */
public class SettingsActivity extends Activity {

    private abstract static class Row {
        final String label;
        TextView valueView;
        boolean repeatable = true; // false: holding the button down doesn't repeat it
        Row(String label) { this.label = label; }
        abstract String value();
        abstract void step(int dir);
    }

    private Prefs prefs;
    private SceneView preview;
    private LinearLayout root;
    /** The scene the preview on the right shows. */
    private int previewScene;
    private final List<Row> rows = new ArrayList<>();
    private TextView status;
    private CloseOnScreensaver closer;

    // Built-in updater state, shown in the "Updates" row.
    private final Updater updater = new Updater();
    private String updateStatus = "▶ to check";
    private boolean updateReady, updateBusy;

    /** Android's installer reports here: ask to confirm, then success or failure. */
    private final android.content.BroadcastReceiver installStatus = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            int st = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
                setUpdateStatus("confirm on screen", false);
            } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                setUpdateStatus("updated", false);
            } else {
                String msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                setUpdateStatus("install cancelled" + (msg != null && st != android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ? ": " + msg : ""), false);
                updateReady = true;
            }
        }
    };

    private void setUpdateStatus(final String text, final boolean busy) {
        runOnUiThread(new Runnable() {
            public void run() {
                updateStatus = text;
                updateBusy = busy;
                for (Row r : rows) r.valueView.setText(r.value());
            }
        });
    }

    /** ▶ on the Updates row: check first; if an update was found, download and install it. */
    private void runUpdater() {
        if (updateBusy) return;
        final boolean install = updateReady;
        setUpdateStatus(install ? "downloading…" : "checking…", true);
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (install) {
                        updater.install(SettingsActivity.this);
                        setUpdateStatus("starting install…", true);
                    } else if (updater.check()) {
                        updateReady = true;
                        setUpdateStatus(updater.latestName + " available · ▶ install", false);
                    } else {
                        setUpdateStatus("up to date", false);
                    }
                } catch (java.io.IOException e) {
                    updateReady = install && updateReady;
                    setUpdateStatus("couldn't " + (install ? "download" : "check") + ": " + e.getMessage(), false);
                }
            }
        }, "updater").start();
    }

    /** Shows whether Sand Clock is still the TV's screensaver (firmware updates can reset it). */
    private void refreshStatus() {
        String current = null;
        try {
            current = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        } catch (RuntimeException ignored) {
            // Some firmware blocks reading this setting.
        }
        if (current == null) {
            status.setText("Screensaver status: unknown on this TV");
            status.setTextColor(0xFF9A8A7A);
        } else if (current.contains(getPackageName())) {
            status.setText(SystemTweaks.canWrite(this) && prefs.keepScreensaver
                    ? "✓ Active as the TV screensaver (auto-repair on)"
                    : "✓ Active as the TV screensaver");
            status.setTextColor(0xFF8FB07A);
        } else {
            status.setText("✗ Not the TV screensaver. Run the adb command in the README.");
            status.setTextColor(0xFFD9584A);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.load(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.42f));

        TextView title = text("Sand Clock", 26, 0xFFD9B38C);
        title.setPadding(dp(12), 0, 0, dp(4));
        list.addView(title);
        status = text("", 13, 0xFF9A8A7A);
        status.setPadding(dp(12), 0, 0, dp(12));
        list.addView(status);

        addRow(list, new Row("Start screensaver after") {
            final int[] options = {1, 2, 5, 10, 15, 30, 60};
            String value() {
                if (!SystemTweaks.canSetDelay(SettingsActivity.this)) return "needs adb grant";
                int m = SystemTweaks.screensaverDelayMin(SettingsActivity.this);
                return m < 0 ? "?" : m + " min";
            }
            void step(int d) {
                if (!SystemTweaks.canSetDelay(SettingsActivity.this)) return;
                int cur = SystemTweaks.screensaverDelayMin(SettingsActivity.this);
                int k = 0;
                while (k < options.length - 1 && options[k] < cur) k++;
                if (options[k] == cur || d < 0) k = wrap(k + d, options.length);
                SystemTweaks.setScreensaverDelayMin(SettingsActivity.this, options[k]);
            }
        });
        // Android's own idle settings that decide when the TV switches itself off.
        addRow(list, new Row("Turn TV off after no use") {
            final int[] options = {-1, 30, 60, 120, 240, 480};
            String value() {
                if (!SystemTweaks.canWrite(SettingsActivity.this)) return "needs adb grant";
                return minutesLabel(SystemTweaks.secureMinutes(SettingsActivity.this, "sleep_timeout"), "Never");
            }
            void step(int d) {
                cycleSecure("sleep_timeout", options, d);
            }
        });
        addRow(list, new Row("Off after hours without a button") {
            final int[] options = {-1, 240, 480, 720, 1440};
            String value() {
                if (!SystemTweaks.canWrite(SettingsActivity.this)) return "needs adb grant";
                return minutesLabel(SystemTweaks.secureMinutes(SettingsActivity.this, "attentive_timeout"), "Off");
            }
            void step(int d) {
                cycleSecure("attentive_timeout", options, d);
            }
        });
        // Scenes: every combination of the scene bits (1 = sand, 2 = ocean), both first.
        addRow(list, new Row("Scenes") {
            final int[] options = {3, 1, 2};
            String value() {
                if (prefs.scenes == 3) return "Sand + Ocean (random)";
                return SceneView.NAMES[prefs.scenes == 2 ? 1 : 0] + " only";
            }
            void step(int d) {
                int k = 0;
                for (int i = 0; i < options.length; i++) if (options[i] == prefs.scenes) k = i;
                prefs.scenes = options[wrap(k + d, options.length)];
                if ((prefs.scenes & (1 << previewScene)) == 0) showPreview(SceneView.pick(prefs));
            }
        });
        addRow(list, new Row("Preview shows") {
            String value() { return SceneView.NAMES[previewScene]; }
            void step(int d) { showPreview(wrap(previewScene + d, SceneView.NAMES.length)); }
        });
        addRow(list, new Row("Style") {
            String value() { return Prefs.STYLES[prefs.style]; }
            void step(int d) { prefs.style = wrap(prefs.style + d, Prefs.STYLES.length); }
        });
        addRow(list, new Row("Background") {
            String value() { return prefs.style < 2 ? Prefs.BACKGROUND[prefs.background] : "— (black)"; }
            void step(int d) { prefs.background = wrap(prefs.background + d, Prefs.BACKGROUND.length); }
        });
        addRow(list, new Row("Sand: grains fall at") {
            String value() {
                int v = prefs.dropPos;
                if (v == Prefs.DROP_RANDOM) return "Random (new each day)";
                return v + "%" + (v == 50 ? " (centre)" : v < 50 ? " (left)" : " (right)");
            }
            void step(int d) {
                // 15 %, 20 %, ... 85 %, then Random.
                int n = (GrainGrid.MAX_DROP - GrainGrid.MIN_DROP) / 5 + 1;
                int i = prefs.dropPos == Prefs.DROP_RANDOM ? n : (prefs.dropPos - GrainGrid.MIN_DROP) / 5;
                i = wrap(i + d, n + 1);
                prefs.dropPos = i == n ? Prefs.DROP_RANDOM : GrainGrid.MIN_DROP + 5 * i;
            }
        });
        addRow(list, new Row("Sand: stream sway") {
            String value() { return Prefs.SWAY[prefs.sway]; }
            void step(int d) { prefs.sway = wrap(prefs.sway + d, Prefs.SWAY.length); }
        });
        addRow(list, new Row("Colour") {
            String value() { return Prefs.THEMES[prefs.theme]; }
            void step(int d) { prefs.theme = wrap(prefs.theme + d, Prefs.THEMES.length); }
        });
        addRow(list, new Row("Brightness") {
            String value() { return Prefs.BRIGHTNESS[prefs.brightness]; }
            void step(int d) { prefs.brightness = wrap(prefs.brightness + d, Prefs.BRIGHTNESS.length); }
        });
        addRow(list, new Row("Clock") {
            String value() { return prefs.use24h ? "24-hour" : "12-hour"; }
            void step(int d) { prefs.use24h = !prefs.use24h; }
        });
        addRow(list, new Row("Animation") {
            String value() { return Prefs.ANIM[prefs.anim]; }
            void step(int d) {
                int k = 0;
                for (int i = 0; i < Prefs.ANIM_ORDER.length; i++) if (Prefs.ANIM_ORDER[i] == prefs.anim) k = i;
                prefs.anim = Prefs.ANIM_ORDER[wrap(k + d, Prefs.ANIM_ORDER.length)];
            }
        });
        addRow(list, new Row("Pour / tide in at start") {
            String value() { return prefs.pourIn ? "On" : "Off"; }
            void step(int d) { prefs.pourIn = !prefs.pourIn; }
        });
        addRow(list, new Row("Vacuum / whirlpool before midnight") {
            String value() { return Prefs.VACUUM[prefs.vacuum]; }
            void step(int d) { prefs.vacuum = wrap(prefs.vacuum + d, Prefs.VACUUM.length); }
        });
        addRow(list, new Row("Night schedule") {
            String value() { return prefs.nightEnabled ? "On" : "Off"; }
            void step(int d) { prefs.nightEnabled = !prefs.nightEnabled; }
        });
        addRow(list, new Row("Night starts") {
            String value() { return prefs.nightEnabled ? Prefs.formatMinutes(prefs.nightStart) : "—"; }
            void step(int d) {
                if (!prefs.nightEnabled) return;
                prefs.nightStart = wrap(prefs.nightStart + 30 * d, 1440);
                if (prefs.nightStart == prefs.nightEnd) prefs.nightStart = wrap(prefs.nightStart + 30 * d, 1440);
            }
        });
        addRow(list, new Row("Night ends") {
            String value() { return prefs.nightEnabled ? Prefs.formatMinutes(prefs.nightEnd) : "—"; }
            void step(int d) {
                if (!prefs.nightEnabled) return;
                prefs.nightEnd = wrap(prefs.nightEnd + 30 * d, 1440);
                if (prefs.nightEnd == prefs.nightStart) prefs.nightEnd = wrap(prefs.nightEnd + 30 * d, 1440);
            }
        });
        addRow(list, new Row("At night show") {
            String value() { return Prefs.NIGHT_STYLE[prefs.nightStyle]; }
            void step(int d) { prefs.nightStyle = wrap(prefs.nightStyle + d, Prefs.NIGHT_STYLE.length); }
        });
        addRow(list, new Row("Lower backlight at night") {
            String value() { return prefs.nightBacklight ? "On" : "Off"; }
            void step(int d) { prefs.nightBacklight = !prefs.nightBacklight; }
        });
        addRow(list, new Row("Midnight reset during night") {
            String value() { return prefs.nightFlip ? "On (dimmed)" : "Off"; }
            void step(int d) { prefs.nightFlip = !prefs.nightFlip; }
        });
        addRow(list, new Row("Evening warmth") {
            String value() {
                if (prefs.city == 0) return "Off";
                int set = Sun.times(prefs.city, java.util.Calendar.getInstance())[1];
                return Sun.CITIES[prefs.city] + " · sunset " + Prefs.formatMinutes(set);
            }
            void step(int d) { prefs.city = wrap(prefs.city + d, Sun.CITIES.length); }
        });
        addRow(list, new Row("Backlight while showing") {
            String value() {
                if (!SystemTweaks.canWrite(SettingsActivity.this)) return "needs adb grant";
                return Prefs.DREAM_BACKLIGHT[prefs.dreamBacklight];
            }
            void step(int d) { prefs.dreamBacklight = wrap(prefs.dreamBacklight + d, Prefs.DREAM_BACKLIGHT.length); }
        });
        addRow(list, new Row("Keep as screensaver") {
            String value() {
                if (!SystemTweaks.canWrite(SettingsActivity.this)) return "needs adb grant";
                return prefs.keepScreensaver ? "On" : "Off";
            }
            void step(int d) { prefs.keepScreensaver = !prefs.keepScreensaver; }
        });
        String version = BuildConfig.VERSION_NAME.startsWith("dev")
                ? "dev build " + BuildConfig.VERSION_NAME.substring(Math.min(4, BuildConfig.VERSION_NAME.length()))
                : "v" + BuildConfig.VERSION_NAME;
        Row updates = new Row("Updates (" + version + ")") {
            String value() { return updateStatus; }
            void step(int d) { if (d > 0) runUpdater(); }
        };
        updates.repeatable = false;
        addRow(list, updates);
        addAction(list, "Run clock now", new Runnable() {
            public void run() {
                // Close settings, so leaving the clock goes back to the home screen.
                startActivity(new Intent(SettingsActivity.this, ClockActivity.class));
                finish();
            }
        });
        addAction(list, "Preview end of day (at 3× speed)", new Runnable() {
            public void run() {
                startActivity(new Intent(SettingsActivity.this, ClockActivity.class)
                        .putExtra(ClockActivity.EXTRA_DEMO_VACUUM, true)
                        .putExtra(ClockActivity.EXTRA_SCENE, previewScene));
                finish();
            }
        });
        addAction(list, "Open TV screensaver settings", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(SettingsActivity.this,
                            "This TV hides that screen. Set it with adb (see README).",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        TextView hint = text("◀ ▶ to change  ·  ▶ to open", 13, 0xFF7A6A5A);
        hint.setPadding(dp(12), dp(12), 0, 0);
        list.addView(hint);

        previewScene = SceneView.pick(prefs);
        preview = makePreview(previewScene);

        setContentView(root);
        closer = CloseOnScreensaver.register(this);
        android.content.IntentFilter f = new android.content.IntentFilter(Updater.ACTION_INSTALL_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(installStatus, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(installStatus, f);
        preview.setPrefs(prefs);
        rows.get(0).valueView.post(new Runnable() {
            public void run() { ((View) rows.get(0).valueView.getParent()).requestFocus(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SystemTweaks.restoreBacklight(this); // in case a crash left it dimmed
        SystemTweaks.ensureScreensaver(this);
        refreshStatus();
        preview.start();
    }

    @Override
    protected void onPause() {
        preview.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (closer != null) closer.unregister();
        try { unregisterReceiver(installStatus); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    private SceneView makePreview(int scene) {
        SceneView v = SceneView.create(this, scene);
        v.setPreviewMode(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 0.58f);
        plp.gravity = Gravity.CENTER_VERTICAL;
        plp.leftMargin = dp(24);
        root.addView(v, plp);
        return v;
    }

    /** Swaps the preview on the right to another scene. */
    private void showPreview(int scene) {
        if (scene == previewScene && preview != null) return;
        previewScene = scene;
        preview.stop();
        root.removeView(preview);
        preview = makePreview(scene);
        preview.setPrefs(prefs);
        preview.start();
    }

    private void addRow(LinearLayout list, final Row row) {
        LinearLayout item = rowContainer();
        TextView label = text(row.label, 16, 0xFFBFAF9F);
        item.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        row.valueView = text(row.value(), 16, 0xFFF0D9BF);
        row.valueView.setTypeface(Typeface.DEFAULT_BOLD);
        item.addView(row.valueView);
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { change(row, 1); }
        });
        item.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                boolean ours = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || isSelect(keyCode);
                if (ours && e.getRepeatCount() > 0 && !row.repeatable) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { change(row, -1); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || isSelect(keyCode)) { change(row, 1); return true; }
                return false;
            }
        });
        list.addView(item);
        rows.add(row);
    }

    private void addAction(LinearLayout list, String label, final Runnable action) {
        LinearLayout item = rowContainer();
        item.addView(text(label, 16, 0xFFF0D9BF), new LinearLayout.LayoutParams(0, -2, 1f));
        item.addView(text("▶", 16, 0xFFF0D9BF));
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        // Remotes without an OK button: ▶ opens it too.
        item.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || isSelect(keyCode)) { action.run(); return true; }
                return false;
            }
        });
        list.addView(item);
    }

    private void cycleSecure(String key, int[] options, int d) {
        int cur = SystemTweaks.secureMinutes(this, key);
        int k = 0;
        for (int i = 0; i < options.length; i++) if (options[i] == cur) k = i;
        SystemTweaks.setSecureMinutes(this, key, options[wrap(k + d, options.length)]);
    }

    private static String minutesLabel(int m, String never) {
        if (m < 0) return never;
        if (m < 60) return m + " min";
        return (m % 60 == 0 ? String.valueOf(m / 60) : String.format(java.util.Locale.US, "%.1f", m / 60f)) + " h";
    }

    /** Buttons that some remotes use instead of a centre OK. */
    private static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT;
    }

    private void change(Row row, int dir) {
        row.step(dir);
        prefs.save(this);
        SystemTweaks.ensureScreensaver(this);
        refreshStatus();
        for (Row r : rows) r.valueView.setText(r.value());
        preview.setPrefs(prefs);
    }

    private LinearLayout rowContainer() {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setFocusable(true);
        item.setClickable(true);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF3A2A1C);
        focused.setCornerRadius(dp(6));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, focused);
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        item.setBackground(bg);
        return item;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int wrap(int v, int n) {
        return ((v % n) + n) % n;
    }
}
