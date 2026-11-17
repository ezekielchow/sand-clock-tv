package com.local.sandclock;

import java.util.Calendar;

/**
 * The sun by day and the moon (in its real phase) by night, as grid cells on an arc from the
 * horizon row up to `top` and back: sunrise and sunset from the chosen city (07:00 / 19:15
 * otherwise). Shared by every scene; each draws the cells over its own sky.
 */
final class SunMoon {
    private SunMoon() { }

    /** Enough room in the cells/colors arrays for any size. */
    static int maxCells(int rows) {
        int r = Math.max(3, Math.round(rows * 0.026f));
        return (2 * r + 1) * (2 * r + 1);
    }

    /** True between sunrise and sunset. */
    static boolean isDay(Prefs prefs, Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int[] t = times(prefs, c);
        int rise = t[0], set = t[1] <= t[0] ? t[1] + 1440 : t[1];   // the day may wrap midnight
        int mm = m < rise ? m + 1440 : m;
        return mm < set;
    }

    /**
     * 0 at night, 1 in full day, easing over the 40 minutes around sunrise and sunset (for sky
     * and water tints).
     */
    static float daylight(Prefs prefs, Calendar c) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int[] t = times(prefs, c);
        int rise = t[0], set = t[1] <= t[0] ? t[1] + 1440 : t[1];
        float best = 0f;
        for (int x = m - 1440; x <= m + 1440; x += 1440) {        // whichever day it falls in
            float up = Math.min(1f, Math.max(0f, (x - (rise - 20)) / 40f));
            float down = Math.min(1f, Math.max(0f, ((set + 20) - x) / 40f));
            best = Math.max(best, Math.min(up, down));
        }
        return best;
    }

    static int[] times(Prefs prefs, Calendar c) {
        return prefs.city > 0 ? Sun.times(prefs.city, c) : new int[]{420, 1155};
    }

    /**
     * Fills cells/colors with the sun or moon for time c (cells only above `horizon`). Returns
     * how many. Nothing when the setting is off.
     */
    static int draw(Prefs prefs, Calendar c, int cols, int rows, int horizon, int top, int[] cells, int[] colors) {
        if (!prefs.sunMoon) return 0;
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int[] t = times(prefs, c);
        int rise = t[0], set = t[1] <= t[0] ? t[1] + 1440 : t[1];   // the day may wrap midnight
        int mm = m < rise ? m + 1440 : m;                            // rise <= mm < rise + 1440
        boolean sun = mm < set;
        float p = sun ? (mm - rise) / (float) Math.max(1, set - rise)
                : (mm - set) / (float) Math.max(1, rise + 1440 - set);
        int cx = Math.round(cols * (0.06f + 0.88f * p));
        int cy = Math.round(horizon - (horizon - top) * (float) Math.sin(Math.PI * p));
        int r = Math.max(3, Math.round(rows * (sun ? 0.026f : 0.022f)));
        int core = prefs.color(sun ? 0xFFD27A : 0xE8E4D0, sun ? 1f : 0.85f);
        int edge = prefs.color(sun ? 0xF0A850 : 0xC8C4B4, sun ? 1f : 0.85f);
        double phase = sun ? 0 : Sun.moonPhase(c.getTimeInMillis());
        int n = 0;
        for (int dy = -r; dy <= r; dy++) {
            double w = Math.sqrt(Math.max(0, r * r - dy * dy + r * 0.8));
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > r + 0.4) continue;
                if (!sun) {
                    // Lit part only: waxing lit on the right, waning on the left.
                    double term = w * Math.cos(2 * Math.PI * phase);
                    if (!(phase < 0.5 ? dx >= term : dx <= -term)) continue;
                }
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= cols || y >= horizon || n >= cells.length) continue;
                cells[n] = y * cols + x;
                colors[n++] = d > r - 1.2 ? edge : core;
            }
        }
        return n;
    }
}
