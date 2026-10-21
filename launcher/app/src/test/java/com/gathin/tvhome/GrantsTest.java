package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.*;

public class GrantsTest {
    private static final String PKG = "com.gathin.tvhome";
    private static final String INPUT = PKG + "/" + PKG + ".InputKeyService";

    @Test public void componentListedInLongOrShortForm() {
        assertTrue(Grants.componentListed("x.y/x.y.S:" + INPUT, PKG, PKG + ".InputKeyService"));
        assertTrue(Grants.componentListed(PKG + "/.InputKeyService", PKG, PKG + ".InputKeyService"));
        assertFalse(Grants.componentListed(PKG + "/.HomeKeyService", PKG, PKG + ".InputKeyService"));
        assertFalse(Grants.componentListed(null, PKG, PKG + ".InputKeyService"));
    }

    @Test public void withComponentsAddsOnceAndKeepsOthers() {
        assertEquals(INPUT, Grants.withComponents("null", INPUT));
        assertEquals(INPUT, Grants.withComponents("", INPUT));
        assertEquals("x.y/x.y.S:" + INPUT, Grants.withComponents("x.y/x.y.S", INPUT));
        assertEquals("x.y/x.y.S:" + INPUT, Grants.withComponents("x.y/x.y.S:" + INPUT, INPUT));
        assertNull(Grants.withComponents("x.y/x.y.S;reboot", INPUT));
    }
}
