package ru.prime.anticheat.check;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Shared interval statistics for timing checks (metronome patterns).
 * Pure functions, thread-safe on any deque the caller synchronizes externally
 * (callers use concurrent deques and tolerate approximate racing reads).
 */
public final class Timing {

    private Timing() {
    }

    /**
     * Do the last `window` non-zero intervals between consecutive timestamps
     * fit into maxSpreadMs with a combat-like mean? Same-millisecond duplicates
     * (multi-hits of one action) are skipped, not counted.
     */
    public static boolean metronome(ArrayDeque<Long> times, int window,
                                    long maxSpreadMs, long maxMeanMs) {
        if (times.size() < 2 || window < 1) return false;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        int n = 0;
        Iterator<Long> it = times.descendingIterator();
        long last = it.next();
        while (it.hasNext() && n < window) {
            long t = it.next();
            long interval = last - t;
            last = t;
            if (interval <= 0) continue;
            n++;
            if (interval < min) min = interval;
            if (interval > max) max = interval;
            sum += interval;
        }
        if (n < window) return false;
        double mean = (double) sum / n;
        return (max - min) <= maxSpreadMs && mean <= maxMeanMs;
    }
}
