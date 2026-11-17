package com.local.sandclock;

import java.util.Calendar;

/** Malaysian festival days, shared by every scene (small, cheap decorations). */
final class Festivals {
    static final int NONE = 0, NEWYEAR = 1, CNY = 2, RAYA = 3, DEEPAVALI = 4, MERDEKA = 5, MSIA = 6, XMAS = 7;

    private Festivals() { }

    /*
     * Lunar festival dates, 2026-2032 (year, month, day). Approximate: Hari Raya and Deepavali
     * in Malaysia can be a day off these. Each is decorated for two days.
     */
    private static final int[][] CNY_DAYS = {{2026, 2, 17}, {2027, 2, 6}, {2028, 1, 26}, {2029, 2, 13}, {2030, 2, 3}, {2031, 1, 23}, {2032, 2, 11}};
    private static final int[][] RAYA_DAYS = {{2026, 3, 20}, {2027, 3, 10}, {2028, 2, 27}, {2029, 2, 14}, {2030, 2, 4}, {2031, 1, 24}, {2032, 1, 14}};
    private static final int[][] DEEPAVALI_DAYS = {{2026, 11, 8}, {2027, 10, 28}, {2028, 10, 17}, {2029, 11, 5}, {2030, 10, 25}, {2031, 11, 13}, {2032, 11, 1}};

    private static int cacheDay = -1, cacheVal;

    /** Today's festival (NONE most days). Cached per day: scenes ask every frame. */
    static int of(Calendar c) {
        int key = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        if (key == cacheDay) return cacheVal;
        cacheVal = compute(c);
        cacheDay = key;
        return cacheVal;
    }

    private static int compute(Calendar c) {
        int m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH);
        if (m == 1 && d == 1) return NEWYEAR;
        if (m == 8 && d == 31) return MERDEKA;
        if (m == 9 && d == 16) return MSIA;
        if (m == 12 && d == 25) return XMAS;
        // A festival's own day wins over another's second day (CNY and Raya can be a day apart).
        for (int second = 0; second <= 1; second++) {
            if (inTable(CNY_DAYS, c, second)) return CNY;
            if (inTable(RAYA_DAYS, c, second)) return RAYA;
            if (inTable(DEEPAVALI_DAYS, c, second)) return DEEPAVALI;
        }
        return NONE;
    }

    /** Today's festival if the setting is on, else NONE. */
    static int of(Prefs p, Calendar c) { return p.festivals ? of(c) : NONE; }

    private static boolean inTable(int[][] t, Calendar c, int dayAfter) {
        int y = c.get(Calendar.YEAR);
        for (int[] e : t) {
            if (e[0] != y) continue;
            Calendar f = (Calendar) c.clone();
            f.set(e[0], e[1] - 1, e[2], 12, 0, 0);
            int diff = c.get(Calendar.DAY_OF_YEAR) - f.get(Calendar.DAY_OF_YEAR);
            if (diff == dayAfter) return true;
        }
        return false;
    }
}
