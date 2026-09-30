package ru.prime.anticheat.check.impl;

import org.junit.Test;
import ru.prime.anticheat.check.impl.crystal.CrystalC;

import java.util.ArrayDeque;

import static org.junit.Assert.*;

public class CrystalCVerdictTest {

    private static ArrayDeque<Long> times(long... ts) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        for (long t : ts) q.addLast(t);
        return q;
    }

    private boolean at(ArrayDeque<Long> q, long now) {
        return CrystalC.verdict(q, 8, 30L, 500L);
    }

    @Test
    public void metronomeFlags() {
        ArrayDeque<Long> q = times(0L);
        for (int i = 1; i <= 8; i++) q.addLast(q.getLast() + 100L);
        assertTrue(at(q, 800L));
    }

    @Test
    public void jitterPasses() {
        assertFalse(at(times(0L, 95L, 210L, 300L, 430L, 520L, 660L, 750L, 880L), 880L));
    }

    @Test
    public void sweepZerosSkipped() {
        assertTrue(at(times(0L, 0L, 100L, 200L, 200L, 300L, 400L, 500L, 600L, 700L, 800L), 800L));
    }

    @Test
    public void tooFewOrSlowPass() {
        assertFalse(at(times(0L, 100L, 200L), 200L));
        ArrayDeque<Long> slow = times(0L);
        for (int i = 1; i <= 8; i++) slow.addLast(slow.getLast() + 2000L);
        assertFalse(at(slow, 16000L));
    }

    @Test
    public void spreadBoundary() {
        ArrayDeque<Long> edge = times(0L);
        for (long iv : new long[]{100L, 100L, 100L, 100L, 100L, 100L, 100L, 130L}) {
            edge.addLast(edge.getLast() + iv);
        }
        assertTrue(at(edge, edge.getLast()));
        ArrayDeque<Long> edge2 = times(0L);
        for (long iv : new long[]{100L, 100L, 100L, 100L, 100L, 100L, 100L, 131L}) {
            edge2.addLast(edge2.getLast() + iv);
        }
        assertFalse(at(edge2, edge2.getLast()));
    }
}
