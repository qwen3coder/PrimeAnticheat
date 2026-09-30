package ru.prime.anticheat.math;

/**
 * GCD for finding the mouse sensitivity divisor.
 * Numerically repeats the classic Grim/Shard: Euclidean algorithm
 * with floor subtraction, stopping below MINIMUM_DIVISOR.
 */
public final class GcdMath {

    /** ((0.2^3 * 8) * 0.15) - 1e-3 = 0.0086: minimum significant divisor. */
    public static final double MINIMUM_DIVISOR = ((Math.pow(0.2f, 3) * 8) * 0.15) - 1e-3;

    private GcdMath() {
    }

    public static double gcd(double a, double b) {
        if (a == 0) return 0;
        if (a < b) {
            double t = a;
            a = b;
            b = t;
        }
        while (b > MINIMUM_DIVISOR) {
            double t = a - (Math.floor(a / b) * b);
            a = b;
            b = t;
        }
        return a;
    }
}
