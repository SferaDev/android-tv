package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class CrashGuardTest {
    @Test
    public void threeCrashesInsideTheWindowTrip() {
        List<Long> t = CrashGuard.record(Collections.emptyList(), 1_000);
        assertFalse(CrashGuard.tripped(t));
        t = CrashGuard.record(t, 30_000);
        assertFalse(CrashGuard.tripped(t));
        t = CrashGuard.record(t, 100_000);
        assertTrue(CrashGuard.tripped(t));
    }

    @Test
    public void oldCrashesFallOutOfTheWindow() {
        List<Long> t = CrashGuard.record(Arrays.asList(0L, 10_000L), 200_000);
        assertEquals(Collections.singletonList(200_000L), t);
        assertFalse(CrashGuard.tripped(t));
    }

    @Test
    public void clockGoingBackwardsDropsFutureEntries() {
        assertEquals(Collections.singletonList(5L), CrashGuard.record(Arrays.asList(9_000_000L), 5));
    }

    @Test
    public void fileRoundTripSkipsTornLines() {
        assertEquals(Arrays.asList(1L, 2L), CrashGuard.parse("1\n2\n3x"));
        assertEquals("1\n2\n", CrashGuard.format(Arrays.asList(1L, 2L)));
        assertTrue(CrashGuard.parse("").isEmpty());
    }
}
