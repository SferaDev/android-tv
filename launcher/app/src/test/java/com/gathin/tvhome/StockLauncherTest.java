package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class StockLauncherTest {
    private static final String OURS = "com.gathin.tvhome";

    private static StockLauncher.Candidate c(String pkg, String activity, boolean system) {
        return new StockLauncher.Candidate(pkg, activity, system);
    }

    @Test
    public void picksTheStockLauncherOverEverythingElse() {
        List<StockLauncher.Candidate> list = Arrays.asList(
                c(OURS, "com.gathin.tvhome.MainActivity", false),
                c("com.android.settings", "com.android.settings.FallbackHome", true),
                c("com.google.android.tungsten.setupwraith", "Home", true),
                c("com.google.android.apps.tv.launcherx", "Home", true));
        assertEquals("com.google.android.apps.tv.launcherx", StockLauncher.pick(list, OURS));
    }

    @Test
    public void ignoresUserInstalledAndOursAndFallbackHome() {
        List<StockLauncher.Candidate> list = Arrays.asList(
                c(OURS, "Main", true),
                c("org.other.home", "Main", false),
                c("com.vendor.fallback", "x.FallbackHome", true));
        assertNull(StockLauncher.pick(list, OURS));
    }

    @Test
    public void takesAnUnknownSystemLauncherWhenNoKnownOneExists() {
        assertEquals("com.vendor.tvhome",
                StockLauncher.pick(Collections.singletonList(c("com.vendor.tvhome", "Main", true)), OURS));
    }

    @Test
    public void parsesPmOutput() {
        assertEquals(new HashSet<>(Arrays.asList("a.b", "c.d")), StockLauncher.parsePackages("package:a.b\r\npackage:c.d\n\n"));
    }

    @Test
    public void onlyEnabledIntendedPackagesAreDisabledAgain() {
        List<String> out = StockLauncher.toDisable(new HashSet<>(Arrays.asList("a.b", "c.d", "e.f")),
                new HashSet<>(Arrays.asList("c.d", "x.y", "a.b")));
        assertEquals(Arrays.asList("a.b", "c.d"), out);
    }

    @Test
    public void detectsTheWifiOwner() {
        String out = "cname=android.uid.system:1000\ncname=" + StockLauncher.WIZARD + "\n";
        assertTrue(StockLauncher.ownsNetwork(out, StockLauncher.WIZARD));
        assertFalse(StockLauncher.ownsNetwork("cname=android.uid.system:1000\n", StockLauncher.WIZARD));
        assertFalse(StockLauncher.ownsNetwork("cname=" + StockLauncher.WIZARD + ".x\n", StockLauncher.WIZARD));
    }
}
