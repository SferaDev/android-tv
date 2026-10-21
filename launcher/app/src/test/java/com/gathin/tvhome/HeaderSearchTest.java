package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class HeaderSearchTest {
    @Test public void assistantWinsWhenItResolves() {
        assertEquals("android.intent.action.ASSIST", HeaderSearch.pick(HeaderSearch.ACTIONS, a -> true));
    }

    @Test public void fallsBackInOrder() {
        assertEquals(HeaderSearch.GLOBAL_SEARCH,
                HeaderSearch.pick(HeaderSearch.ACTIONS, a -> !a.equals("android.intent.action.ASSIST")));
        assertEquals("android.intent.action.WEB_SEARCH",
                HeaderSearch.pick(HeaderSearch.ACTIONS, a -> a.equals("android.intent.action.WEB_SEARCH")));
    }

    @Test public void nullWhenNothingResolves() {
        assertNull(HeaderSearch.pick(Arrays.asList("a", "b"), a -> false));
    }
}
