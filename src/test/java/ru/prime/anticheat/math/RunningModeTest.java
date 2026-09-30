package ru.prime.anticheat.math;

import org.junit.Test;

import static org.junit.Assert.*;

public class RunningModeTest {

    @Test
    public void modeIsMostFrequent() {
        RunningMode mode = new RunningMode(10);
        mode.add(1.0);
        mode.add(2.0);
        mode.add(1.0005); // within threshold -> same bucket as 1.0
        RunningMode.ModeResult result = mode.getMode();
        assertEquals(1.0, result.value(), 1e-9);
        assertEquals(2, result.count());
    }

    @Test
    public void windowCapEvictsOldest() {
        RunningMode mode = new RunningMode(2);
        mode.add(1.0);
        mode.add(2.0);
        mode.add(3.0);
        assertEquals(2, mode.size());
        assertEquals(1, mode.getMode().count());
    }

    @Test
    public void clearEmpties() {
        RunningMode mode = new RunningMode(10);
        mode.add(1.0);
        mode.clear();
        assertEquals(0, mode.size());
        assertNull(mode.getMode().value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyWindowRejected() {
        new RunningMode(0);
    }
}
