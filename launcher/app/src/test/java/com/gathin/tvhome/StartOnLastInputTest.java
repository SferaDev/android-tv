package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class StartOnLastInputTest {
    @Test public void newestInputOrChannelIsPicked() {
        RecentTv.Entry input = new RecentTv.Entry(RecentTv.INPUT, "hdmi1", 200);
        RecentTv.Entry channel = new RecentTv.Entry(RecentTv.CHANNEL, "7", 100);
        assertEquals("hdmi1", StartOnLastInput.pick(Arrays.asList(input, channel)).id);
        assertEquals("7", StartOnLastInput.pick(Collections.singletonList(channel)).id);
    }

    @Test public void nothingToPickWhenEmptyOrUnknownKind() {
        assertNull(StartOnLastInput.pick(Collections.emptyList()));
        assertNull(StartOnLastInput.pick(Collections.singletonList(new RecentTv.Entry("other", "x", 1))));
    }

    @Test public void dueOncePerBootInsideTheWindow() {
        assertTrue(StartOnLastInput.due(30_000, 12, 11));
        assertFalse(StartOnLastInput.due(30_000, 12, 12));
        assertFalse(StartOnLastInput.due(StartOnLastInput.BOOT_WINDOW_MS + 1, 12, 11));
        assertFalse(StartOnLastInput.due(30_000, -1, 11));
    }
}
