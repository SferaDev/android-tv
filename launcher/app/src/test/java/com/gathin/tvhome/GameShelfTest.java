package com.gathin.tvhome;

import org.junit.Test;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class GameShelfTest {
    private static List<String> sort(String... names) {
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        collator.setStrength(Collator.SECONDARY);
        return GameShelf.alphabetical(Arrays.asList(names), n -> n, collator);
    }

    @Test public void namesSortCaseInsensitivelyWithDesktopAndBigPictureLast() {
        assertEquals(Arrays.asList("hades", "Hollow Knight", "Ōkami", "Portal 2", "Desktop", "Steam Big Picture"),
                sort("Steam Big Picture", "Portal 2", "Desktop", "Ōkami", "Hollow Knight", "hades"));
    }

    @Test public void onlyTheSpecialEntries() {
        assertEquals(Arrays.asList("Desktop", "Steam Big Picture"), sort("Steam Big Picture", "Desktop"));
        assertEquals(Collections.emptyList(), sort());
    }
}
