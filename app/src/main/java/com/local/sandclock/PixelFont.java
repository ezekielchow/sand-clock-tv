package com.local.sandclock;

import java.util.HashMap;
import java.util.Map;

/**
 * A classic 5×7 bitmap font (digits, colon and the capitals used in dates), drawn straight
 * into the sand grid. Every glyph is made of square blocks with flat tops, so sand heaps up
 * on it like on a real ledge.
 */
final class PixelFont {
    static final int H = 7;
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void g(char c, String... rows) { GLYPHS.put(c, rows); }

    static {
        g('0', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        g('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        g('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        g('3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.");
        g('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        g('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        g('6', "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###.");
        g('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        g('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        g('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..");
        // Colon: two wide square blocks with room either side, so each can hold a heap.
        g(':', "....", ".##.", ".##.", "....", ".##.", ".##.", "....");
        g(' ', "...", "...", "...", "...", "...", "...", "...");
        g('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        g('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        g('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
        g('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
        g('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
        g('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
        g('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####");
        g('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        g('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        g('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
        g('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
        g('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
        g('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
        g('N', "#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#");
        g('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        g('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
        g('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
        g('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
        g('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        g('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        g('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
        g('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#.");
        g('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
    }

    private static String[] glyph(char c) {
        String[] g = GLYPHS.get(Character.toUpperCase(c));
        return g != null ? g : GLYPHS.get(' ');
    }

    /** Width of text in font pixels (1-pixel gap between characters). */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i))[0].length() + (i > 0 ? 1 : 0);
        return w;
    }

    /** Horizontal advance of one character, in grid cells. */
    static int advance(char ch, int scale) {
        return (glyph(ch)[0].length() + 1) * scale;
    }

    /** Grid cells covered by one character at (left, top). */
    static int[] cells(char ch, int left, int top, int scale, int cols, int rows) {
        String[] g = glyph(ch);
        int[] out = new int[g.length * g[0].length() * scale * scale];
        int n = 0;
        for (int r = 0; r < H; r++) {
            for (int col = 0; col < g[r].length(); col++) {
                if (g[r].charAt(col) != '#') continue;
                for (int dy = 0; dy < scale; dy++) {
                    int y = top + r * scale + dy;
                    if (y < 0 || y >= rows) continue;
                    for (int dx = 0; dx < scale; dx++) {
                        int x = left + col * scale + dx;
                        if (x >= 0 && x < cols) out[n++] = y * cols + x;
                    }
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Sets the cells covered by text, each font pixel a scale×scale block. */
    static void draw(boolean[] mask, int cols, int rows, String s, int left, int top, int scale) {
        int x = left;
        for (int i = 0; i < s.length(); i++) {
            String[] g = glyph(s.charAt(i));
            for (int r = 0; r < H; r++) {
                for (int col = 0; col < g[r].length(); col++) {
                    if (g[r].charAt(col) != '#') continue;
                    for (int dy = 0; dy < scale; dy++) {
                        int y = top + r * scale + dy;
                        if (y < 0 || y >= rows) continue;
                        for (int dx = 0; dx < scale; dx++) {
                            int cx = x + col * scale + dx;
                            if (cx >= 0 && cx < cols) mask[y * cols + cx] = true;
                        }
                    }
                }
            }
            x += (g[0].length() + 1) * scale;
        }
    }
}
