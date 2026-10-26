package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class HeaderAppsTest {
    @Test public void addsAtTheEndAndRemoves() {
        assertEquals(Arrays.asList("a", "b"), HeaderApps.toggle(Collections.singletonList("a"), "b"));
        assertEquals(Collections.singletonList("b"), HeaderApps.toggle(Arrays.asList("a", "b"), "a"));
    }

    @Test public void aFullListTakesNoMore() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), HeaderApps.toggle(Arrays.asList("a", "b", "c", "d"), "e"));
        assertEquals(Arrays.asList("a", "b", "c"), HeaderApps.toggle(Arrays.asList("a", "b", "c", "d"), "d"));
    }
}
