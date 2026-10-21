package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class A11yTest {
    @Test public void cardLabelJoinsTitleAndApp() {
        assertEquals("Pilot, Example Player", A11y.cardLabel("Pilot", "Example Player"));
    }

    @Test public void cardLabelDropsMissingOrRepeatedApp() {
        assertEquals("Pilot", A11y.cardLabel("Pilot", null));
        assertEquals("Pilot", A11y.cardLabel("Pilot", ""));
        assertEquals("Example Player", A11y.cardLabel("Example Player", "Example Player"));
    }

    @Test public void cardLabelFallsBackToTheApp() {
        assertEquals("Example Player", A11y.cardLabel("", "Example Player"));
    }
}
