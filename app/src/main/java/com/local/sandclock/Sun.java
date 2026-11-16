package com.local.sandclock;

import java.util.Calendar;
import java.util.TimeZone;

/** Offline sunrise/sunset (NOAA approximation) for evening warmth. */
final class Sun {
    static final String[] CITIES = {
            "Off", "Kuala Lumpur", "George Town", "Johor Bahru", "Ipoh", "Melaka",
            "Kuantan", "Kota Bharu", "Kota Kinabalu", "Kuching", "Singapore"};
    private static final double[][] LAT_LON = {
            {0, 0}, {3.139, 101.687}, {5.414, 100.329}, {1.493, 103.741}, {4.597, 101.090},
            {2.189, 102.250}, {3.817, 103.326}, {6.125, 102.238}, {5.980, 116.073},
            {1.553, 110.359}, {1.352, 103.820}};

    /** {sunrise, sunset} in local minutes of the day for the given city and date. */
    static int[] times(int city, Calendar day) {
        double lat = Math.toRadians(LAT_LON[city][0]);
        double lon = LAT_LON[city][1];
        double g = 2 * Math.PI / 365.0 * (day.get(Calendar.DAY_OF_YEAR) - 1 + 0.5);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(decl))
                - Math.tan(lat) * Math.tan(decl);
        double ha = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosHa))));
        int offset = TimeZone.getDefault().getOffset(day.getTimeInMillis()) / 60_000;
        int rise = (int) Math.round(720 - 4 * (lon + ha) - eqTime) + offset;
        int set = (int) Math.round(720 - 4 * (lon - ha) - eqTime) + offset;
        return new int[]{((rise % 1440) + 1440) % 1440, ((set % 1440) + 1440) % 1440};
    }

    /** Moon age as a fraction of the cycle: 0 new, 0.5 full. */
    static double moonPhase(long ms) {
        double days = ms / 86_400_000.0 + 2440587.5 - 2451550.1;
        double f = days / 29.530588853;
        return f - Math.floor(f);
    }

    /** 0 in daytime, easing to 1 from 30 min before sunset to 60 min after; back to 0 after sunrise. */
    static float warmth(int city, Calendar now) {
        if (city <= 0) return 0f;
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int[] t = times(city, now);
        int rise = t[0], set = t[1];
        if (m >= set - 30) return Math.min(1f, (m - (set - 30)) / 90f);
        if (m < rise) return 1f;
        if (m < rise + 60) return 1f - (m - rise) / 60f;
        return 0f;
    }
}
