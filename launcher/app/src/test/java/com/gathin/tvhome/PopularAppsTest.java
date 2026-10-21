package com.gathin.tvhome;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PopularAppsTest {
    @Test public void ranksByForegroundTimeAndDropsExcludedUnusedAndUnlaunchable() {
        Map<String, Long> time = new HashMap<>();
        time.put("a", 10L);
        time.put("b", 30L);
        time.put("c", 20L);
        time.put("self", 99L);
        time.put("idle", 0L);
        time.put("service", 50L);
        List<String> top = PopularApps.rank(time, Collections.singleton("self"), pkg -> !pkg.equals("service"), 10);
        assertEquals(Arrays.asList("b", "c", "a"), top);
        assertEquals(Arrays.asList("b", "c"), PopularApps.rank(time, Collections.singleton("self"), pkg -> !pkg.equals("service"), 2));
    }

    @Test public void equalTimesOrderByName() {
        Map<String, Long> time = new HashMap<>();
        time.put("z", 5L);
        time.put("m", 5L);
        assertEquals(Arrays.asList("m", "z"), PopularApps.rank(time, Collections.emptySet(), p -> true, 5));
    }

    @Test public void recentRanksByLastUseAndKeepsTheSameFilters() {
        Map<String, Long> last = new HashMap<>();
        last.put("old", 1_000L);
        last.put("new", 9_000L);
        last.put("mid", 5_000L);
        last.put("never", 0L);
        last.put("self", 99_000L);
        last.put("service", 50_000L);
        assertEquals(Arrays.asList("new", "mid", "old"),
                PopularApps.rank(last, Collections.singleton("self"), p -> !p.equals("service"), 10));
        assertEquals(Collections.singletonList("new"),
                PopularApps.rank(last, Collections.singleton("self"), p -> !p.equals("service"), 1));
    }

    @Test public void stripListsUsedAppsFirstThenEveryOtherAppInOrderOnce() {
        List<String> used = Arrays.asList("com.example.tv", "com.example.music", "launcher");
        List<String> every = Arrays.asList("com.example.alpha", "com.example.music", "com.example.tv", "launcher",
                "com.example.zeta", "com.example.alpha");
        assertEquals(Arrays.asList("com.example.tv", "com.example.music", "com.example.alpha", "com.example.zeta"),
                PopularApps.strip(used, every, Collections.singleton("launcher")));
        assertEquals(Arrays.asList("com.example.alpha", "com.example.zeta"),
                PopularApps.strip(Collections.<String>emptyList(), Arrays.asList("com.example.alpha", "com.example.zeta"),
                        Collections.<String>emptySet()));
    }

    @Test public void listenerIsFoundInEnabledListeners() {
        String me = "com.gathin.tvhome/com.gathin.tvhome.WatchListener";
        assertTrue(Grants.listenerEnabled("x.y/x.y.L:" + me, me));
        assertFalse(Grants.listenerEnabled("x.y/x.y.L", me));
        assertFalse(Grants.listenerEnabled(null, me));
        assertFalse(Grants.listenerEnabled("", me));
    }
}
