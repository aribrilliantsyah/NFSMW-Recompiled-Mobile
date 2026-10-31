package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SettingsCashTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void parseCashTakesTheRange() {
        assertEquals(0, Settings.parseCash("0"));
        assertEquals(1234567, Settings.parseCash("1234567"));
        assertEquals(1234567, Settings.parseCash(" $1,234,567 "));
        assertEquals(1234567, Settings.parseCash("1.234.567"));
        assertEquals(9999999, Settings.parseCash("9999999"));
    }

    @Test
    public void parseCashRefusesTheRest() {
        assertEquals(-1, Settings.parseCash(null));
        assertEquals(-1, Settings.parseCash(""));
        assertEquals(-1, Settings.parseCash("10000000"));
        assertEquals(-1, Settings.parseCash("-5"));
        assertEquals(-1, Settings.parseCash("12a"));
        assertEquals(-1, Settings.parseCash("99999999999999999999"));
    }

    @Test
    public void setCashRoundTripsThroughTheFile() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        TomlSettings.update(f, t -> t.put(Settings.K_CHEAT_SET_CASH, Settings.parseCash("1,234,567")));
        assertEquals(1234567, TomlSettings.load(f).getInt(Settings.K_CHEAT_SET_CASH, -1));
        // native's clear after applying, then the launcher's read
        TomlSettings.update(f, t -> t.put(Settings.K_CHEAT_SET_CASH, 0));
        assertEquals(0, Settings.within(TomlSettings.load(f).getInt(Settings.K_CHEAT_SET_CASH, 0), 0,
                                        Settings.MAX_CASH, 0));
    }
}
