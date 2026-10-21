package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class HomeRowsTest {
    @Test public void sanitizeDropsUnknownAndRepeated() {
        assertEquals(Arrays.asList("games", "continue_watching"),
                HomeRows.sanitize(Arrays.asList("games", "nope", "continue_watching", "games")));
    }

    @Test public void hidingAndShowingAgainKeepsTheOrder() {
        assertEquals(Arrays.asList("continue_watching", "games", "recent_tv"),
                HomeRows.toggle(HomeRows.ALL, "recent_apps", false));
        assertEquals(HomeRows.ALL,
                HomeRows.toggle(Arrays.asList("continue_watching", "games", "recent_tv"), "recent_apps", true));
        assertEquals(Collections.singletonList("continue_watching"),
                HomeRows.toggle(Collections.<String>emptyList(), "continue_watching", true));
        assertEquals(Arrays.asList("recent_apps", "games"),
                HomeRows.toggle(Collections.singletonList("games"), "recent_apps", true));
    }

    @Test public void unknownRowIsIgnored() {
        assertEquals(HomeRows.ALL, HomeRows.toggle(HomeRows.ALL, "weather", true));
    }
}
