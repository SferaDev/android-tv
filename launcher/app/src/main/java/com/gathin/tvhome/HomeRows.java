package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The data rows on Home (config.xml home_rows): which are shown, top to bottom. */
final class HomeRows {
    static final String CONTINUE_WATCHING = "continue_watching";
    static final String RECENT_APPS = "recent_apps";
    static final String GAMES = "games";
    static final String RECENT_TV = "recent_tv";
    static final List<String> ALL = Collections.unmodifiableList(
            Arrays.asList(CONTINUE_WATCHING, RECENT_APPS, GAMES, RECENT_TV));

    private HomeRows() { }

    /** Known ids only, each once, in the given order. */
    static List<String> sanitize(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String id : rows) if (ALL.contains(id) && !out.contains(id)) out.add(id);
        return out;
    }

    /** Shows or hides one row; a row shown again goes back to its place in {@link #ALL} order. */
    static List<String> toggle(List<String> rows, String id, boolean shown) {
        List<String> current = sanitize(rows);
        if (!ALL.contains(id) || shown == current.contains(id)) return current;
        if (!shown) {
            current.remove(id);
            return current;
        }
        int at = 0;
        for (String other : current) {
            if (ALL.indexOf(other) < ALL.indexOf(id)) at = current.indexOf(other) + 1;
        }
        current.add(at, id);
        return current;
    }
}
