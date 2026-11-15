package com.local.sandclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

/** All user settings, stored in SharedPreferences. */
final class Prefs {
    static final String[] THEMES = {"Sand", "Mono", "Amber", "Red"};
    // {sand, glass/frame, text}
    private static final int[][] THEME_COLORS = {
            {0xFFB07A45, 0xFF6B5440, 0xFFD9B38C},
            {0xFFD8D8D8, 0xFF707070, 0xFFEDEDED},
            {0xFFE89A2C, 0xFF7A5420, 0xFFF2B04A},
            {0xFFC0392B, 0xFF6E2018, 0xFFD9584A},
    };
    static final String[] BRIGHTNESS = {"Low", "Medium", "High"};
    static final float[] BRIGHTNESS_FACTOR = {0.45f, 0.7f, 1f};
    // Stored values stay stable for saved settings; ANIM_ORDER is the order the setting cycles in.
    static final String[] ANIM = {"Gentle (15 fps)", "Smooth (30 fps)", "Off", "Low (8 fps)"};
    static final int ANIM_GENTLE = 0, ANIM_SMOOTH = 1, ANIM_OFF = 2, ANIM_LOW = 3;
    static final int[] ANIM_ORDER = {ANIM_LOW, ANIM_GENTLE, ANIM_SMOOTH, ANIM_OFF};
    /** Background brightness in the Black/Dark sand styles: the lit area most of the morning. */
    static final String[] BACKGROUND = {"Normal", "Dim"};
    static final float[] BACKGROUND_F = {0.4f, 0.2f};
    int background = 0;
    /** When the screensaver starts, rain the day's sand so far down instead of placing it. */
    boolean pourIn = true;
    static final String[] NIGHT_STYLE = {"Black screen", "Dim clock"};

    static final String[] STYLES = {"Black sand", "Dark sand", "Glowing sand"};
    int style = 0;
    /** Where grains fall from, % across the top of the screen. */
    int dropPos = 50;
    /** How far the stream drifts back and forth around dropPos. */
    static final String[] SWAY = {"Off", "Gentle", "Wide"};
    static final float[] SWAY_AMP = {0f, 0.18f, 0.40f}; // share of screen width, each side
    int sway = 1;
    int theme = 0;
    int brightness = 1;
    int anim = 0;
    boolean use24h = true;
    boolean nightEnabled = true;
    int nightStart = 23 * 60; // minutes of day
    int nightEnd = 7 * 60;
    int nightStyle = 0;
    boolean nightBacklight = true;
    boolean nightFlip = true;
    int city = 1;               // index into Sun.CITIES; 0 = evening warmth off
    boolean keepScreensaver = true;
    int dreamBacklight = 2;     // index into DREAM_BACKLIGHT
    static final String[] DREAM_BACKLIGHT = {"Unchanged", "75%", "50%", "25%"};
    static final float[] DREAM_BACKLIGHT_F = {1f, 0.75f, 0.5f, 0.25f};

    /** Runtime only: 0 = daytime colours, 1 = full evening warmth. */
    float warmth = 0f;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sandclock", Context.MODE_PRIVATE);
    }

    static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.style = clamp(s.getInt("style", 0), STYLES.length);
        // Outside 15..85 % the pile can't hold a full day before reaching the top.
        p.dropPos = Math.max(GrainGrid.MIN_DROP, Math.min(GrainGrid.MAX_DROP, s.getInt("dropPos", 50)));
        p.sway = clamp(s.getInt("sway", 1), SWAY.length);
        p.theme = clamp(s.getInt("theme", 0), THEMES.length);
        p.brightness = clamp(s.getInt("brightness", 1), BRIGHTNESS.length);
        p.anim = clamp(s.getInt("anim", 0), ANIM.length);
        p.background = clamp(s.getInt("background", 0), BACKGROUND.length);
        p.pourIn = s.getBoolean("pourIn", true);
        p.use24h = s.getBoolean("use24h", DateFormat.is24HourFormat(c));
        p.nightEnabled = s.getBoolean("nightEnabled", true);
        p.nightStart = s.getInt("nightStart", 23 * 60);
        p.nightEnd = s.getInt("nightEnd", 7 * 60);
        p.nightStyle = clamp(s.getInt("nightStyle", 0), NIGHT_STYLE.length);
        p.nightBacklight = s.getBoolean("nightBacklight", true);
        p.nightFlip = s.getBoolean("nightFlip", true);
        p.city = clamp(s.getInt("city", 1), Sun.CITIES.length);
        p.keepScreensaver = s.getBoolean("keepScreensaver", true);
        p.dreamBacklight = clamp(s.getInt("dreamBacklight", 2), DREAM_BACKLIGHT.length);
        return p;
    }

    void save(Context c) {
        sp(c).edit()
                .putInt("style", style)
                .putInt("dropPos", dropPos)
                .putInt("sway", sway)
                .putInt("theme", theme)
                .putInt("brightness", brightness)
                .putInt("anim", anim)
                .putInt("background", background)
                .putBoolean("pourIn", pourIn)
                .putBoolean("use24h", use24h)
                .putBoolean("nightEnabled", nightEnabled)
                .putInt("nightStart", nightStart)
                .putInt("nightEnd", nightEnd)
                .putInt("nightStyle", nightStyle)
                .putBoolean("nightBacklight", nightBacklight)
                .putBoolean("nightFlip", nightFlip)
                .putInt("city", city)
                .putBoolean("keepScreensaver", keepScreensaver)
                .putInt("dreamBacklight", dreamBacklight)
                .apply();
    }

    /** True if the given minute of the day falls inside the night schedule. */
    boolean isNight(int minuteOfDay) {
        if (!nightEnabled || nightStart == nightEnd) return false;
        if (nightStart < nightEnd) return minuteOfDay >= nightStart && minuteOfDay < nightEnd;
        return minuteOfDay >= nightStart || minuteOfDay < nightEnd;
    }

    int sandColor() { return scaled(THEME_COLORS[theme][0], 1f); }
    int glassColor() { return scaled(THEME_COLORS[theme][1], 1f); }
    int textColor(float extra) { return scaled(THEME_COLORS[theme][2], extra); }
    int sandColor(float extra) { return scaled(THEME_COLORS[theme][0], extra); }
    int glassColor(float extra) { return scaled(THEME_COLORS[theme][1], extra); }

    /** Darkens a colour by the brightness setting (dim pixels = less light on mini-LED). */
    private int scaled(int color, float extra) {
        // Evening warmth: dimmer overall, and less green/blue so the light turns amber.
        float f = BRIGHTNESS_FACTOR[brightness] * extra * (1f - 0.3f * warmth);
        int r = ch(((color >> 16) & 0xFF) * f);
        int g = ch(((color >> 8) & 0xFF) * f * (1f - 0.15f * warmth));
        int b = ch((color & 0xFF) * f * (1f - 0.5f * warmth));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int ch(float v) {
        return v > 255 ? 255 : (int) v;
    }

    static String formatMinutes(int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
