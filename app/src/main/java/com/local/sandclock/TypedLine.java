package com.local.sandclock;

import java.util.ArrayList;
import java.util.List;

/**
 * A stats line that types itself in letter by letter, holds, then un-types from the end, so it
 * never pops in or out in one frame. Shared by every scene's messages.
 */
final class TypedLine {
    static final int TYPE_MS = 80, ERASE_MS = 45;

    private TypedLine() { }

    /** Letters showing `elapsed` ms into a line shown for `durMs` in all. */
    static int visible(int len, long elapsed, long durMs) {
        if (elapsed < 0 || elapsed >= durMs) return 0;
        int in = (int) Math.min(len, elapsed / TYPE_MS + 1);
        long left = durMs - elapsed;
        int out = (int) Math.min(len, left / ERASE_MS + 1);
        return Math.max(0, Math.min(in, out));
    }

    /** The grid cells of each character of msg (spaces have none), left to right. */
    static int[][] cells(String msg, int left, int top, int scale, int cols, int rows) {
        List<int[]> out = new ArrayList<>();
        int x = left;
        for (int k = 0; k < msg.length(); k++) {
            char ch = msg.charAt(k);
            out.add(ch == ' ' ? new int[0] : PixelFont.cells(ch, x, top, scale, cols, rows));
            x += PixelFont.advance(ch, scale);
        }
        return out.toArray(new int[0][]);
    }

    /** Left edge that centres msg on a grid `cols` wide. */
    static int centred(String msg, int scale, int cols) {
        return Math.max(0, (cols - PixelFont.width(msg) * scale) / 2);
    }
}
