package ru.prime.anticheat.alert;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AlertManagerTest {

    @Test
    public void probColorEndpoints() {
        assertEquals("&#22C55E", AlertManager.probColor(0.0));
        assertEquals("&#FF1A1A", AlertManager.probColor(1.0));
        assertEquals("&#22C55E", AlertManager.probColor(-2.0));
        assertEquals("&#FF1A1A", AlertManager.probColor(2.0));
    }

    @Test
    public void probColorStops() {
        assertEquals("&#FFEA00", AlertManager.probColor(1.0 / 3));
        assertEquals("&#FF8C00", AlertManager.probColor(2.0 / 3));
    }

    @Test
    public void probColorVividEverywhere() {
        for (int i = 0; i <= 24; i++) {
            String c = AlertManager.probColor(i / 24.0);
            assertTrue("bad color " + c, c.matches("&#[0-9A-F]{6}"));
            int r = Integer.parseInt(c.substring(2, 4), 16);
            int g = Integer.parseInt(c.substring(4, 6), 16);
            int b = Integer.parseInt(c.substring(6, 8), 16);
            int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            assertTrue("muddy color " + c, sat >= 100);
        }
    }

    @Test
    public void coloredHistoryFormat() {
        String s = AlertManager.coloredHistory(List.of(0.0, 0.42, 1.0));
        assertTrue(s.contains("0.000") && s.contains("0.420") && s.contains("1.000"));
        assertTrue(s.startsWith("&#22C55E0.000") && s.endsWith("&#FF1A1A1.000"));
        assertEquals(3, s.split("&#94A3B8, ", -1).length);
    }

    @Test
    public void packetFlagFormat() {
        String s = AlertManager.renderPacketFlag("Steve", "CrystalA", 3, " [place+attack]");
        assertTrue(s.contains("Steve") && s.contains("CrystalA") && s.contains("3"));
        assertTrue(s.contains("[place+attack]") && s.contains("§") && !s.contains("&"));
        assertFalse(s.contains("prob") || s.contains("buf"));
        String bare = AlertManager.renderPacketFlag("Alex", "CrystalB", 1, "");
        assertTrue(!bare.contains("[") && bare.contains("(VL"));
    }
}
