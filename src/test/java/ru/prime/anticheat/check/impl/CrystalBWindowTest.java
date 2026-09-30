package ru.prime.anticheat.check.impl;

import org.junit.Test;
import ru.prime.anticheat.check.impl.crystal.CrystalB;

import java.util.ArrayDeque;

import static org.junit.Assert.*;

public class CrystalBWindowTest {

    private static ArrayDeque<Long> times(long... ts) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long t : ts) q.addLast(t);
        return q;
    }

    @Test
    public void underLimitPasses() {
        assertFalse(CrystalB.overLimit(times(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L), 700L, 1000L, 8));
    }

    @Test
    public void overLimitFlags() {
        assertTrue(CrystalB.overLimit(times(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 750L), 750L, 1000L, 8));
    }

    @Test
    public void stalePruned() {
        ArrayDeque<Long> q = times(0L, 100L, 200L, 300L, 400L, 100000L, 100100L, 100200L, 100300L, 100400L, 100500L);
        assertFalse(CrystalB.overLimit(q, 100600L, 1000L, 8));
        assertEquals(6, q.size());
    }

    @Test
    public void burstFlags() {
        assertTrue(CrystalB.overLimit(times(500L, 500L, 500L, 500L, 500L, 500L, 500L, 500L, 500L), 500L, 1000L, 8));
    }
}
