package com.gathin.tvhome;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameHostsTest {
    @Test public void addressesAndNamesAreValidShellAndUrlMetacharactersAreNot() {
        assertTrue(GameHosts.valid("192.0.2.10"));
        assertTrue(GameHosts.valid("gaming-pc.local"));
        assertTrue(GameHosts.valid("[2001:db8::1]"));
        assertFalse(GameHosts.valid(""));
        assertFalse(GameHosts.valid(null));
        assertFalse(GameHosts.valid("pc; reboot"));
        assertFalse(GameHosts.valid("pc/path"));
    }

    @Test public void ipv4IsPreferredOverIpv6() throws Exception {
        InetAddress v6 = InetAddress.getByName("2001:db8::1");
        InetAddress v4 = InetAddress.getByName("192.0.2.10");
        assertEquals("192.0.2.10", GameHosts.pick(Arrays.asList(v6, v4)));
        assertEquals(v6.getHostAddress(), GameHosts.pick(Arrays.asList(v6)));
    }
}
