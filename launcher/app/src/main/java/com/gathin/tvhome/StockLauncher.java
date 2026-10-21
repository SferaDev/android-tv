package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure helpers for finding the stock launcher and reading pm output. */
final class StockLauncher {
    static final String WIZARD = "com.google.android.tungsten.setupwraith";
    /** Fallback when no HOME activity qualifies, in order of preference. */
    static final List<String> KNOWN = Arrays.asList("com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher");

    private StockLauncher() { }

    static final class Candidate {
        final String pkg, activity;
        final boolean system;

        Candidate(String pkg, String activity, boolean system) {
            this.pkg = pkg;
            this.activity = activity;
            this.system = system;
        }
    }

    static boolean isWizard(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        return p.contains("setupwraith") || p.contains("setupwizard") || p.contains("provision");
    }

    /** The system HOME app that is neither ours, a setup wizard nor the Settings FallbackHome; null if none. */
    static String pick(List<Candidate> candidates, String ours) {
        List<String> found = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.system || c.pkg.equals(ours) || isWizard(c.pkg) || c.pkg.equals("com.android.settings")
                    || c.activity.contains("FallbackHome")) continue;
            if (!found.contains(c.pkg)) found.add(c.pkg);
        }
        for (String known : KNOWN) {
            if (found.contains(known)) return known;
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /** Package names from `pm list packages` output. */
    static Set<String> parsePackages(String output) {
        Set<String> out = new HashSet<>();
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) out.add(t.substring("package:".length()).trim());
        }
        return out;
    }

    /** Intended-disabled packages that are installed and enabled right now, so they need disabling again. */
    static List<String> toDisable(Set<String> intended, Set<String> enabledNow) {
        List<String> out = new ArrayList<>();
        for (String pkg : intended) {
            if (enabledNow.contains(pkg)) out.add(pkg);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Whether `dumpsys wifi | grep -o 'cname=[^ ]*'` output shows a saved network created by pkg. */
    static boolean ownsNetwork(String cnames, String pkg) {
        for (String line : cnames.split("\n")) {
            if (line.trim().equals("cname=" + pkg)) return true;
        }
        return false;
    }
}
