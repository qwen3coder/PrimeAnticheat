package ru.prime.anticheat.math;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Sliding mode of GCD divisors. Values differing by less than
 * THRESHOLD are considered the same divisor (sensitivity
 * yields the same step with float error).
 */
public class RunningMode {

    private static final double THRESHOLD = 1e-3;

    public final Queue<Double> addList;
    public final Map<Double, Integer> popularityMap;
    public final int maxSize;

    public RunningMode(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("mode of empty list");
        this.addList = new ArrayDeque<>(maxSize);
        this.popularityMap = new HashMap<>();
        this.maxSize = maxSize;
    }

    public int size() {
        return addList.size();
    }

    public void add(double value) {
        pop();
        for (Map.Entry<Double, Integer> e : popularityMap.entrySet()) {
            if (Math.abs(e.getKey() - value) < THRESHOLD) {
                e.setValue(e.getValue() + 1);
                addList.add(e.getKey());
                return;
            }
        }
        popularityMap.put(value, 1);
        addList.add(value);
    }

    private void pop() {
        if (addList.size() >= maxSize) {
            Double oldest = addList.poll();
            if (oldest != null) {
                Integer count = popularityMap.get(oldest);
                if (count != null) {
                    if (count == 1) popularityMap.remove(oldest);
                    else popularityMap.put(oldest, count - 1);
                }
            }
        }
    }

    /** Most frequent divisor + how many times seen (null if empty). */
    public ModeResult getMode() {
        int max = 0;
        Double best = null;
        for (Map.Entry<Double, Integer> e : popularityMap.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return new ModeResult(best, max);
    }

    public void clear() {
        addList.clear();
        popularityMap.clear();
    }

    public record ModeResult(Double value, int count) {
    }
}
