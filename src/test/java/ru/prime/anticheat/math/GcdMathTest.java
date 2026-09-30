package ru.prime.anticheat.math;

import org.junit.Test;

import static org.junit.Assert.*;

public class GcdMathTest {

    @Test
    public void exactDivisor() {
        assertEquals(0.03, GcdMath.gcd(0.06, 0.03), 1e-9);
    }

    @Test
    public void zeroReturnsZero() {
        assertEquals(0.0, GcdMath.gcd(0.0, 0.05), 1e-9);
    }

    @Test
    public void belowMinimumReturnsLarger() {
        // Both below MINIMUM_DIVISOR - loop skipped, larger value returned.
        assertEquals(0.001, GcdMath.gcd(0.001, 0.0005), 1e-9);
    }

    @Test
    public void orderIndependent() {
        assertEquals(GcdMath.gcd(0.12, 0.08), GcdMath.gcd(0.08, 0.12), 1e-12);
    }
}
