package ru.prime.anticheat.hologram;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class HologramManagerTest {

    @Test
    public void emptyHistoryPlaceholder() {
        assertEquals("&#666666···", HologramManager.holoText(List.of()));
    }

    @Test
    public void percentFormat() {
        String s = HologramManager.holoText(List.of(0.0, 0.01, 0.01, 0.03, 0.0));
        assertTrue(s.contains("0%") && s.contains("1%") && s.contains("3%"));
        assertFalse(s.matches("(?s).*\\d\\.\\d+%.*"));
        assertTrue(s.startsWith("&#22C55E0%") && s.endsWith("&#22C55E0%"));
        assertEquals(5, s.split("&#94A3B8, ", -1).length);
    }

    @Test
    public void fullCheatRed() {
        assertEquals("&#FF1A1A100%", HologramManager.holoText(List.of(1.0)));
    }
}
