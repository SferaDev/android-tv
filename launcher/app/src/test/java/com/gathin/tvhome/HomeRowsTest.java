package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class HomeRowsTest {
    @Test public void sanitizeDropsUnknownAndRepeated() {
        assertEquals(Arrays.asList("recent_apps", "continue_watching"),
                HomeRows.sanitize(Arrays.asList("recent_apps", "nope", "continue_watching", "recent_apps")));
    }

    @Test public void hidingAndShowingAgainKeepsTheOrder() {
        assertEquals(Collections.singletonList("continue_watching"), HomeRows.toggle(HomeRows.ALL, "recent_apps", false));
        assertEquals(HomeRows.ALL, HomeRows.toggle(Collections.singletonList("continue_watching"), "recent_apps", true));
        assertEquals(Collections.singletonList("continue_watching"),
                HomeRows.toggle(Collections.<String>emptyList(), "continue_watching", true));
        assertEquals(HomeRows.ALL, HomeRows.toggle(Collections.singletonList("recent_apps"), "continue_watching", true));
    }

    @Test public void unknownRowIsIgnored() {
        assertEquals(HomeRows.ALL, HomeRows.toggle(HomeRows.ALL, "weather", true));
    }
}
