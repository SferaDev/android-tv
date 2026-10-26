package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/** The apps pinned to Home's header (config top_bar_apps), picked in Settings, Home. */
final class HeaderApps {
    /** More would push the tabs and the clock into each other on a 16:9 header. */
    static final int MAX = 4;

    private HeaderApps() { }

    /** The list with pkg added at the end or taken out; unchanged when it is full and pkg is not in it. */
    static List<String> toggle(List<String> current, String pkg) {
        List<String> next = new ArrayList<>(current);
        if (!next.remove(pkg) && next.size() < MAX) next.add(pkg);
        return next;
    }
}
