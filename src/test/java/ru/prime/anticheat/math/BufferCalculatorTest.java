package ru.prime.anticheat.math;

import org.junit.Test;

import static org.junit.Assert.*;

public class BufferCalculatorTest {

    @Test
    public void increaseAboveThreshold() {
        assertEquals(40.0, BufferCalculator.increase(0.9, 100.0, 0.5), 1e-9);
    }

    @Test
    public void increaseAtOrBelowThresholdIsZero() {
        assertEquals(0.0, BufferCalculator.increase(0.5, 100.0, 0.5), 1e-9);
        assertEquals(0.0, BufferCalculator.increase(0.1, 100.0, 0.5), 1e-9);
    }

    @Test
    public void decreaseFloorsAtZero() {
        assertEquals(7.0, BufferCalculator.decrease(10.0, 3.0), 1e-9);
        assertEquals(0.0, BufferCalculator.decrease(2.0, 5.0), 1e-9);
    }

    @Test
    public void updateBufferZones() {
        // Growth zone.
        assertEquals(140.0, BufferCalculator.updateBuffer(100.0, 0.9, 100.0, 0.25, 0.5, 0.1), 1e-9);
        // Decay zone (scaled by confidence).
        double decayed = BufferCalculator.updateBuffer(100.0, 0.05, 100.0, 0.25, 0.5, 0.1);
        assertTrue(decayed < 100.0 && decayed >= 0.0);
        // Dead zone between thresholds - untouched.
        assertEquals(100.0, BufferCalculator.updateBuffer(100.0, 0.3, 100.0, 0.25, 0.5, 0.1), 1e-9);
    }

    @Test
    public void scaledDecreaseEdges() {
        assertEquals(0.25, BufferCalculator.scaledDecrease(0.5, 0.25, 0.0), 1e-9);
        assertEquals(0.25, BufferCalculator.scaledDecrease(0.0, 0.25, 0.1), 1e-9);
        assertEquals(0.0, BufferCalculator.scaledDecrease(0.1, 0.25, 0.1), 1e-9);
    }

    @Test
    public void flagAndReset() {
        assertTrue(BufferCalculator.shouldFlag(50.0, 50.0));
        assertFalse(BufferCalculator.shouldFlag(49.9, 50.0));
        assertEquals(25.0, BufferCalculator.resetBuffer(25.0), 1e-9);
        assertEquals(0.0, BufferCalculator.resetBuffer(-5.0), 1e-9);
    }
}
