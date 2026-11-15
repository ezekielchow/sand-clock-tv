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
        Row(String label) { this.label = label; }
        abstract String value();
        abstract void step(int dir);
    }

    private Prefs prefs;
    private SandClockView preview;
    private final List<Row> rows = new ArrayList<>();
    private TextView status;

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

        LinearLayout root = new LinearLayout(this);
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
        addRow(list, new Row("Style") {
            String value() { return Prefs.STYLES[prefs.style]; }
            void step(int d) { prefs.style = wrap(prefs.style + d, Prefs.STYLES.length); }
        });
        addRow(list, new Row("Grains fall at") {
            String value() {
                int v = prefs.dropPos;
                return v + "%" + (v == 50 ? " (centre)" : v < 50 ? " (left)" : " (right)");
            }
            void step(int d) { prefs.dropPos = wrap(prefs.dropPos + 5 * d, 105); }
        });
        addRow(list, new Row("Stream sway") {
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
        addRow(list, new Row("Falling sand") {
            String value() { return Prefs.ANIM[prefs.anim]; }
            void step(int d) { prefs.anim = wrap(prefs.anim + d, Prefs.ANIM.length); }
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
        addAction(list, "Run clock now", new Runnable() {
            public void run() { startActivity(new Intent(SettingsActivity.this, ClockActivity.class)); }
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

        TextView hint = text("OK to change  ·  ◀ ▶ to adjust", 13, 0xFF7A6A5A);
        hint.setPadding(dp(12), dp(12), 0, 0);
        list.addView(hint);

        preview = new SandClockView(this);
        preview.setPreviewMode(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 0.58f);
        plp.gravity = Gravity.CENTER_VERTICAL;
        plp.leftMargin = dp(24);
        root.addView(preview, plp);

        setContentView(root);
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { change(row, -1); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { change(row, 1); return true; }
                return false;
            }
        });
        list.addView(item);
        rows.add(row);
    }

    private void addAction(LinearLayout list, String label, final Runnable action) {
        LinearLayout item = rowContainer();
        item.addView(text(label, 16, 0xFFF0D9BF));
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        list.addView(item);
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
