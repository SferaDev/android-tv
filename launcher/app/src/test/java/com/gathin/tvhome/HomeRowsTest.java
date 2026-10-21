package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class HomeRowsTest {
    @Test public void sanitizeDropsUnknownAndRepeated() {
        assertEquals(Collections.singletonList("recent_apps"),
                HomeRows.sanitize(Arrays.asList("recent_apps", "nope", "recent_apps")));
    }

    @Test public void hidingAndShowingAgain() {
        assertTrue(HomeRows.toggle(HomeRows.ALL, "recent_apps", false).isEmpty());
        assertEquals(HomeRows.ALL, HomeRows.toggle(Collections.<String>emptyList(), "recent_apps", true));
    }

    @Test public void unknownRowIsIgnored() {
        assertEquals(HomeRows.ALL, HomeRows.toggle(HomeRows.ALL, "weather", true));
    }
}
