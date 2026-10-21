package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class KeyMappingsTest {
    private static List<KeyMappings.Mapping> from(List<String> apps, int[] settings, int[] inputs) {
        return KeyMappings.from(apps, settings, inputs, name -> name.equals("KEYCODE_BUTTON_3") ? 190 : 0);
    }

    private static KeyMappings.Mapping m(int code, KeyMappings.Action action, String pkg) {
        return new KeyMappings.Mapping(code, action, pkg);
    }

    @Test public void readsAllThreeKeys() {
        List<KeyMappings.Mapping> list = from(Arrays.asList("KEYCODE_BUTTON_3:com.example.tube",
                "192:togglehome:com.example.home"), new int[]{176}, new int[]{193});
        assertEquals(Arrays.asList(m(176, KeyMappings.Action.SETTINGS, null), m(193, KeyMappings.Action.INPUTS, null),
                m(190, KeyMappings.Action.APP, "com.example.tube"),
                m(192, KeyMappings.Action.TOGGLE_HOME, "com.example.home")), list);
    }

    @Test public void aKeyInMoreThanOneListKeepsItsFirstAction() {
        List<KeyMappings.Mapping> list = from(Collections.singletonList("176:com.example.tube"), new int[]{176},
                new int[]{176});
        assertEquals(Collections.singletonList(m(176, KeyMappings.Action.SETTINGS, null)), list);
    }

    @Test public void putReplacesInPlaceAndAppendsNewKeys() {
        List<KeyMappings.Mapping> list = Arrays.asList(m(1, KeyMappings.Action.INPUTS, null),
                m(2, KeyMappings.Action.APP, "a.b"));
        list = KeyMappings.put(list, m(1, KeyMappings.Action.APP, "c.d"));
        list = KeyMappings.put(list, m(3, KeyMappings.Action.SETTINGS, null));
        assertEquals(Arrays.asList(m(1, KeyMappings.Action.APP, "c.d"), m(2, KeyMappings.Action.APP, "a.b"),
                m(3, KeyMappings.Action.SETTINGS, null)), list);
        assertEquals(Arrays.asList(m(1, KeyMappings.Action.APP, "c.d"), m(3, KeyMappings.Action.SETTINGS, null)),
                KeyMappings.remove(list, 2));
    }

    @Test public void writesBackToTheThreeKeys() {
        List<KeyMappings.Mapping> list = Arrays.asList(m(190, KeyMappings.Action.APP, "a.b"),
                m(192, KeyMappings.Action.TOGGLE_HOME, "c.d"), m(176, KeyMappings.Action.SETTINGS, null),
                m(193, KeyMappings.Action.INPUTS, null), m(83, KeyMappings.Action.SETTINGS, null));
        assertEquals(Arrays.asList("190:a.b", "192:togglehome:c.d"), KeyMappings.appKeys(list));
        assertArrayEquals(new int[]{176, 83}, KeyMappings.settingsKeycodes(list));
        assertArrayEquals(new int[]{193}, KeyMappings.inputKeycodes(list));
        List<KeyMappings.Mapping> back = from(KeyMappings.appKeys(list), KeyMappings.settingsKeycodes(list),
                KeyMappings.inputKeycodes(list));
        assertEquals(5, back.size());
        assertTrue(back.containsAll(list));
    }

    @Test public void navigationKeysCannotBeCaptured() {
        for (int code : new int[]{0, 3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 160, 164}) {
            assertFalse(String.valueOf(code), KeyMappings.capturable(code));
        }
        assertTrue(KeyMappings.capturable(190));
        assertTrue(KeyMappings.capturable(176));
    }

    @Test public void labels() {
        assertEquals("BUTTON 3 (190)", KeyMappings.label("KEYCODE_BUTTON_3", 190));
        assertEquals("4242", KeyMappings.label("4242", 4242));
        assertEquals("4242", KeyMappings.label(null, 4242));
    }
}
