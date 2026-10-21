package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** The Games tab's order: by name, with the whole-desktop and Big Picture streams after the games. */
final class GameShelf {
    /** Moonlight's own apps rather than games, in the order they go last. */
    static final List<String> LAST = Arrays.asList("Desktop", "Steam Big Picture");

    private GameShelf() { }

    static <T> List<T> alphabetical(List<T> games, Function<T, String> name, Comparator<? super String> collator) {
        List<T> sorted = new ArrayList<>(games);
        sorted.sort((a, b) -> {
            int la = LAST.indexOf(name.apply(a)), lb = LAST.indexOf(name.apply(b));
            if (la >= 0 || lb >= 0) {
                if (la < 0) return -1;
                if (lb < 0) return 1;
                return Integer.compare(la, lb);
            }
            return collator.compare(name.apply(a), name.apply(b));
        });
        return sorted;
    }
}
