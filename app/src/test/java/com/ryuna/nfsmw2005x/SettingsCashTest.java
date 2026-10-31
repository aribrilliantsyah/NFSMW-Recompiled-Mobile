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
    public void requestRoundTripsZeroMaxAndCancel() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        assertEquals(-1, Settings.pendingCash(TomlSettings.load(f)));  // no file, no request
        TomlSettings.update(f, t -> Settings.requestCash(t, 1234567));
        assertEquals(1234567, Settings.pendingCash(TomlSettings.load(f)));
        TomlSettings.update(f, t -> Settings.requestCash(t, 0));  // zero is an amount, not none
        assertEquals(0, Settings.pendingCash(TomlSettings.load(f)));
        assertEquals(true, TomlSettings.load(f).getBool(Settings.K_CHEAT_SET_CASH_PENDING, false));
        TomlSettings.update(f, t -> Settings.requestCash(t, Settings.MAX_CASH));
        assertEquals(Settings.MAX_CASH, Settings.pendingCash(TomlSettings.load(f)));
        TomlSettings.update(f, t -> Settings.requestCash(t, -1));  // cancel
        assertEquals(-1, Settings.pendingCash(TomlSettings.load(f)));
    }

    @Test
    public void gameAnswerAndOldFilesAreNoRequest() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        TomlSettings.update(f, t -> Settings.requestCash(t, 500000));
        // the game's answer (AckCashRequest): the flag false, the amount left
        TomlSettings.update(f, t -> t.put(Settings.K_CHEAT_SET_CASH_PENDING, false));
        assertEquals(-1, Settings.pendingCash(TomlSettings.load(f)));
        // the first build's file: an amount with no flag
        File old = new File(tmp.getRoot(), "old.toml");
        TomlSettings.update(old, t -> t.put(Settings.K_CHEAT_SET_CASH, 0));
        assertEquals(-1, Settings.pendingCash(TomlSettings.load(old)));
        // an amount out of range is none
        TomlSettings.update(old, t -> { t.put(Settings.K_CHEAT_SET_CASH, 10_000_000); t.put(
                Settings.K_CHEAT_SET_CASH_PENDING, true); });
        assertEquals(-1, Settings.pendingCash(TomlSettings.load(old)));
    }
}
