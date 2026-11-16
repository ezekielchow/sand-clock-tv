package com.local.sandclock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The clock text (time, AM/PM, date) laid out in grid cells with the pixel font, shared by
 * every scene. Each character becomes a container: walls, an open top edge, and the room
 * inside.
 */
final class ClockText {
    /** One character of the clock text. Same id = same character in the same place. */
    static final class Slot {
        final String id;
        final char part;    // 't' time, 'a' AM/PM, 'd' date
        final int[] cells;  // walls: solid
        final int[] lids;   // open top edges: drawn, but sand and water pass through
        final int[] inner;  // the room inside the walls
        final int top;      // top row of the character
        Slot(String id, char part, int[] cells, int[] lids, int[] inner, int top) {
            this.id = id; this.part = part; this.cells = cells; this.lids = lids;
            this.inner = inner; this.top = top;
        }
    }

    private ClockText() { }

    private static String[] strings(Prefs prefs, Calendar c) {
        java.util.Date now = c.getTime();
        String time = new SimpleDateFormat(prefs.use24h ? "HH:mm" : "h:mm", Locale.US).format(now);
        String ampm = prefs.use24h ? "" : new SimpleDateFormat("a", Locale.US).format(now);
        String date = new SimpleDateFormat("EEE d MMM", Locale.US).format(now).toUpperCase(Locale.US);
        return new String[]{time, ampm, date};
    }

    private static long keyMinute = Long.MIN_VALUE;
    private static boolean keyUse24h;
    private static String keyText;

    /**
     * Changes whenever the text on screen would change. Called every frame, so the text is
     * only formatted again when the minute (or the 12/24-hour choice) changes.
     */
    static String key(Prefs prefs, Calendar c) {
        long minute = (c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 60_000L;
        if (minute != keyMinute || prefs.use24h != keyUse24h || keyText == null) {
            String[] s = strings(prefs, c);
            keyText = s[0] + s[1] + s[2];
            keyMinute = minute;
            keyUse24h = prefs.use24h;
        }
        return keyText;
    }

    /** The slots for the text at time c (cells == null when cells is false: ids only). */
    static List<Slot> layout(Prefs prefs, Calendar c, int cols, int rows) {
        return layout(prefs, c, cols, rows, true);
    }

    /** Just the slot ids at time c: cheap, for working out how long a character has stayed. */
    static HashSet<String> ids(Prefs prefs, Calendar c, int cols, int rows) {
        HashSet<String> out = new HashSet<>();
        for (Slot s : layout(prefs, c, cols, rows, false)) out.add(s.id);
        return out;
    }

    /**
     * The text placed elsewhere: the time's baseline at row `mid`, time and date at scales
     * st and sd (the ant farm puts a smaller clock underground).
     */
    static List<Slot> layoutAt(Prefs prefs, Calendar c, int cols, int rows, int mid, int st, int sd) {
        return layout(prefs, c, cols, rows, true, mid, st, sd);
    }

    private static List<Slot> layout(Prefs prefs, Calendar c, int cols, int rows, boolean cells) {
        int st = Math.max(1, Math.round(rows * 0.2f / PixelFont.H));   // time: 8 on a TV
        int sd = Math.max(1, Math.round(rows * 0.075f / PixelFont.H)); // date
        return layout(prefs, c, cols, rows, cells, rows / 2, st, sd);
    }

    private static List<Slot> layout(Prefs prefs, Calendar c, int cols, int rows, boolean cells,
                                     int mid, int st, int sd) {
        String[] s = strings(prefs, c);
        String time = s[0], ampm = s[1], date = s[2];
        int sa = Math.max(1, st / 4);                                   // AM/PM
        int tw = PixelFont.width(time) * st;
        int tLeft = (cols - tw) / 2, tTop = mid - PixelFont.H * st;
        List<Slot> slots = new ArrayList<>();
        add(slots, 't', time, tLeft, tTop, st, cols, rows, cells);
        if (!ampm.isEmpty()) add(slots, 'a', ampm, tLeft + tw + st, mid - PixelFont.H * sa, sa, cols, rows, cells);
        int dw = PixelFont.width(date) * sd;
        add(slots, 'd', date, (cols - dw) / 2, mid + Math.round(rows * 0.06f), sd, cols, rows, cells);
        return slots;
    }

    private static void add(List<Slot> out, char part, String s, int left, int top, int scale,
                            int cols, int rows, boolean cells) {
        int x = left;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch != ' ') {
                String id = part + "" + ch + "@" + x + "," + top + "," + scale;
                if (cells) {
                    int[][] wl = walls(PixelFont.cells(ch, x, top, scale, cols, rows), cols, rows);
                    out.add(new Slot(id, part, wl[0], wl[1], wl[2], top));
                } else {
                    out.add(new Slot(id, part, null, null, null, top));
                }
            }
            x += PixelFont.advance(ch, scale);
        }
    }

    /**
     * Turns a letter into a container: its outline becomes wall, except the upward-facing
     * edges between corners, which are left open so sand or water can pour in and fill it.
     */
    private static int[][] walls(int[] glyph, int cols, int rows) {
        HashSet<Integer> in = new HashSet<>();
        for (int i : glyph) in.add(i);
        int[] out = new int[glyph.length];
        int[] lids = new int[glyph.length];
        int[] inner = new int[glyph.length];
        int n = 0, m = 0, r = 0;
        for (int i : glyph) {
            int x = i % cols, y = i / cols;
            boolean up = y > 0 && in.contains(i - cols);
            boolean down = y < rows - 1 && in.contains(i + cols);
            boolean left = x > 0 && in.contains(i - 1);
            boolean right = x < cols - 1 && in.contains(i + 1);
            if (up && down && left && right) { inner[r++] = i; continue; } // room inside
            if (!up && left && right) {                                    // top edge: the opening
                lids[m++] = i;
                continue;
            }
            out[n++] = i;
        }
        return new int[][]{Arrays.copyOf(out, n), Arrays.copyOf(lids, m), Arrays.copyOf(inner, r)};
    }
}
