package ru.prime.anticheat.check.impl;

import org.junit.Test;
import ru.prime.anticheat.check.impl.crystal.CrystalA;

import static org.junit.Assert.*;

public class CrystalAVerdictTest {

    private static boolean v(int placeTick, int attackTick, boolean crystal, int[] pp, int[] cp) {
        return CrystalA.verdict(placeTick, attackTick, crystal, pp, cp);
    }

    @Test
    public void sameTickOnSpot() {
        assertTrue(v(100, 100, true, new int[]{10, 64, 10}, new int[]{10, 65, 10}));
    }

    @Test
    public void boundaryHolds() {
        assertTrue(v(100, 100, true, new int[]{10, 64, 10}, new int[]{11, 66, 11}));
    }

    @Test
    public void otherTickOrNotCrystalOrFar() {
        assertFalse(v(100, 101, true, new int[]{10, 64, 10}, new int[]{10, 65, 10}));
        assertFalse(v(100, 100, false, new int[]{10, 64, 10}, new int[]{10, 65, 10}));
        assertFalse(v(100, 100, true, new int[]{10, 64, 10}, new int[]{12, 65, 10}));
        assertFalse(v(100, 100, true, new int[]{10, 64, 10}, new int[]{10, 67, 10}));
        assertFalse(v(100, 100, true, new int[]{10, 64, 10}, new int[]{10, 65, 12}));
    }
}
