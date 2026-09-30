package ru.prime.anticheat.check.impl;

import org.junit.Test;
import ru.prime.anticheat.check.impl.crystal.CrystalE;

import java.util.ArrayDeque;

import static org.junit.Assert.*;

public class CrystalEVerdictTest {

    private static ArrayDeque<Long> times(long... ts) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long t : ts) q.addLast(t);
        return q;
    }

    @Test
    public void recentSwingDetection() {
        assertFalse(CrystalE.hasRecentSwing(times(), 1000L, 150L));
        assertTrue(CrystalE.hasRecentSwing(times(900L), 1000L, 150L));
        assertTrue(CrystalE.hasRecentSwing(times(850L), 1000L, 150L));
        assertFalse(CrystalE.hasRecentSwing(times(800L), 1000L, 150L));
    }

    @Test
    public void readOnlyNoPrune() {
        ArrayDeque<Long> q = times(100L, 900L);
        CrystalE.hasRecentSwing(q, 1000L, 150L);
        assertEquals(2, q.size());
    }
}
