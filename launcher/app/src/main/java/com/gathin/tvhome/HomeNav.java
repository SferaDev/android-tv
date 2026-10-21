package com.gathin.tvhome;

/**
 * D-pad moves on the home screen: the header, the Home tab's rows (each its own length, empty ones hidden) and
 * the Apps tab's grid. Positions in reading order; the caller maps physical Left and Right for right-to-left.
 */
final class HomeNav {
    /** Up from the first row or the grid's first line: focus goes to the header. */
    static final int HEADER = -1;

    private HomeNav() { }

    /** The next row with cards above or below {@code row}; HEADER above the first, {@code row} itself below the last. */
    static int nextRow(int[] lengths, int row, boolean down) {
        for (int r = down ? row + 1 : row - 1; r >= 0 && r < lengths.length; r += down ? 1 : -1) {
            if (lengths[r] > 0) return r;
        }
        return down ? row : HEADER;
    }

    /** The row to return to: the remembered one while it has cards, else the first that has; HEADER when all are empty. */
    static int restoreRow(int[] lengths, int remembered) {
        if (remembered >= 0 && remembered < lengths.length && lengths[remembered] > 0) return remembered;
        for (int r = 0; r < lengths.length; r++) if (lengths[r] > 0) return r;
        return HEADER;
    }

    /** The first row from {@code from} down that has cards; HEADER when none has. */
    static int firstFrom(int[] lengths, int from) {
        for (int r = Math.max(0, from); r < lengths.length; r++) if (lengths[r] > 0) return r;
        return HEADER;
    }

    /** A remembered column clamped to a row of {@code length} cards. */
    static int column(int remembered, int length) {
        return Math.max(0, Math.min(remembered, length - 1));
    }

    /** One step along a row; the ends hold instead of wrapping. */
    static int nextColumn(int column, int length, boolean forward) {
        int next = forward ? column + 1 : column - 1;
        return next < 0 || next >= length ? column : next;
    }

    /**
     * Up or Down in a grid of {@code count} tiles: HEADER from the first line, the same position at the last; Down
     * into a shorter last line lands on its last tile.
     */
    static int gridVertical(int position, int count, int columns, boolean down) {
        if (!down) return position < columns ? HEADER : position - columns;
        int lastLineStart = ((count - 1) / columns) * columns;
        if (position >= lastLineStart) return position;
        return Math.min(position + columns, count - 1);
    }
}
