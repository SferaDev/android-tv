package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HomeNavTest {
    private static final int H = HomeNav.HEADER;

    @Test public void downAndUpWalkTheRowsAndUpFromTheFirstReachesTheHeader() {
        int[] rows = {5, 3, 8};
        assertEquals(1, HomeNav.nextRow(rows, 0, true));
        assertEquals(2, HomeNav.nextRow(rows, 1, true));
        assertEquals(2, HomeNav.nextRow(rows, 2, true));
        assertEquals(1, HomeNav.nextRow(rows, 2, false));
        assertEquals(0, HomeNav.nextRow(rows, 1, false));
        assertEquals(H, HomeNav.nextRow(rows, 0, false));
    }

    @Test public void emptyRowsAreSkippedBothWays() {
        int[] rows = {0, 4, 0, 2, 0};
        assertEquals(3, HomeNav.nextRow(rows, 1, true));
        assertEquals(3, HomeNav.nextRow(rows, 3, true));
        assertEquals(1, HomeNav.nextRow(rows, 3, false));
        assertEquals(H, HomeNav.nextRow(rows, 1, false));
    }

    @Test public void everyMoveLandsOnARowWithCardsOrTheHeader() {
        for (int mask = 0; mask < 32; mask++) {
            int[] rows = new int[5];
            for (int r = 0; r < 5; r++) rows[r] = (mask >> r & 1) == 1 ? r + 1 : 0;
            for (int from = 0; from < 5; from++) {
                if (rows[from] == 0) continue;
                for (boolean down : new boolean[] {false, true}) {
                    int to = HomeNav.nextRow(rows, from, down);
                    String m = "mask=" + mask + " from=" + from + " down=" + down;
                    if (to == H) {
                        assertTrue(m, !down);
                        for (int r = 0; r < from; r++) assertEquals(m, 0, rows[r]);
                        continue;
                    }
                    assertTrue(m, rows[to] > 0);
                    if (to == from) {
                        assertTrue(m, down);
                        for (int r = from + 1; r < 5; r++) assertEquals(m, 0, rows[r]);
                    } else {
                        assertEquals(m, down, to > from);
                        // Nothing with cards is jumped over.
                        for (int r = Math.min(from, to) + 1; r < Math.max(from, to); r++) assertEquals(m, 0, rows[r]);
                    }
                }
            }
        }
    }

    @Test public void restoreKeepsTheRememberedRowWhileItHasCards() {
        assertEquals(2, HomeNav.restoreRow(new int[] {3, 3, 3}, 2));
        assertEquals(0, HomeNav.restoreRow(new int[] {3, 3, 0}, 2));
        assertEquals(1, HomeNav.restoreRow(new int[] {0, 3, 0}, 2));
        assertEquals(1, HomeNav.restoreRow(new int[] {0, 3, 3}, 7));
        assertEquals(1, HomeNav.restoreRow(new int[] {0, 3, 3}, H));
        assertEquals(H, HomeNav.restoreRow(new int[] {0, 0, 0}, 1));
    }

    @Test public void rememberedColumnIsClampedToTheRow() {
        assertEquals(0, HomeNav.column(0, 5));
        assertEquals(3, HomeNav.column(3, 5));
        assertEquals(4, HomeNav.column(9, 5));
        assertEquals(0, HomeNav.column(-2, 5));
        assertEquals(0, HomeNav.column(4, 1));
    }

    @Test public void columnsStepWithoutWrapping() {
        for (int length = 1; length <= 6; length++) {
            for (int c = 0; c < length; c++) {
                assertEquals(c == length - 1 ? c : c + 1, HomeNav.nextColumn(c, length, true));
                assertEquals(c == 0 ? 0 : c - 1, HomeNav.nextColumn(c, length, false));
            }
        }
    }

    @Test public void gridUpFromTheFirstLineGoesToTheHeader() {
        for (int p = 0; p < 6; p++) assertEquals(H, HomeNav.gridVertical(p, 20, 6, false));
        assertEquals(1, HomeNav.gridVertical(7, 20, 6, false));
    }

    @Test public void gridDownIntoAShortLastLineLandsOnItsLastTile() {
        // 14 tiles in 6 columns: lines 0-5, 6-11, 12-13.
        assertEquals(12, HomeNav.gridVertical(6, 14, 6, true));
        assertEquals(13, HomeNav.gridVertical(7, 14, 6, true));
        assertEquals(13, HomeNav.gridVertical(11, 14, 6, true));
        assertEquals(13, HomeNav.gridVertical(13, 14, 6, true));
        assertEquals(12, HomeNav.gridVertical(12, 14, 6, true));
    }

    @Test public void gridMovesMatchLinesAcrossSizes() {
        for (int columns = 1; columns <= 7; columns++) {
            for (int count = 1; count <= 3 * columns + 2; count++) {
                int lines = (count + columns - 1) / columns;
                for (int p = 0; p < count; p++) {
                    String m = "columns=" + columns + " count=" + count + " p=" + p;
                    int line = p / columns;
                    int up = HomeNav.gridVertical(p, count, columns, false);
                    assertEquals(m, line == 0 ? H : p - columns, up);
                    int down = HomeNav.gridVertical(p, count, columns, true);
                    if (line == lines - 1) assertEquals(m, p, down);
                    else {
                        assertEquals(m, line + 1, down / columns);
                        assertEquals(m, Math.min(p + columns, count - 1), down);
                    }
                }
            }
        }
    }

    @Test public void firstFromSkipsRowsAboveAndEmptyOnes() {
        int[] lengths = {4, 0, 3, 5};
        assertEquals(2, HomeNav.firstFrom(lengths, 1));
        assertEquals(0, HomeNav.firstFrom(lengths, 0));
        assertEquals(H, HomeNav.firstFrom(new int[]{4, 0, 0}, 1));
    }

    @Test public void nowPlayingAboveTheStripsIsOneUpFromTheFirstAndTheHeaderIsOneUpFromIt() {
        // Now playing (cover and three controls), Continue watching empty, Recent apps, Games.
        int[] lengths = {4, 0, 6, 2};
        assertEquals(0, HomeNav.nextRow(lengths, 2, false));
        assertEquals(H, HomeNav.nextRow(lengths, 0, false));
        assertEquals(2, HomeNav.nextRow(lengths, 0, true));
        lengths[0] = 0;
        assertEquals(H, HomeNav.nextRow(lengths, 2, false));
    }
}
