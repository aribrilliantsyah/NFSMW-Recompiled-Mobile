package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Test;

/** The CONTROLS rows Opacity and Size: mostwanted.toml keys per mode, the old preference as the opacity's default. */
public class TouchLookTest {
    private static TomlSettings empty() { return TomlSettings.load(new File("does-not-exist.toml")); }

    @Test
    public void defaultsAndLegacyOpacity() {
        TomlSettings t = empty();
        assertEquals(70, Settings.touchOpacity(t, 1, 70));
        assertEquals(40, Settings.touchOpacity(t, 1, 40));       // the editor's old preference
        assertEquals(70, Settings.touchOpacity(t, 1, 5));        // out of range: the default
        assertEquals(100, Settings.touchSize(t, 0));
        t.put(Settings.touchOpacityKey(1), 90);
        assertEquals(90, Settings.touchOpacity(t, 1, 40));       // the file wins
        assertEquals(40, Settings.touchOpacity(t, 0, 40));       // per mode
        t.put(Settings.touchSizeKey(0), 999);
        assertEquals(100, Settings.touchSize(t, 0));
    }

    @Test
    public void stepsInTenthsWithinRange() {
        assertEquals(80, Settings.stepTouchOpacity(70, 1));
        assertEquals(80, Settings.stepTouchOpacity(73, 1));
        assertEquals(100, Settings.stepTouchOpacity(100, 1));
        assertEquals(15, Settings.stepTouchOpacity(20, -1));
        assertEquals(110, Settings.stepTouchSize(100, 1));
        assertEquals(50, Settings.stepTouchSize(50, -1));
        assertEquals(220, Settings.stepTouchSize(220, 1));
    }
}
