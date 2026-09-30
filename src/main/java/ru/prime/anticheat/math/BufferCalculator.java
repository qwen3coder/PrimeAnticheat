package ru.prime.anticheat.math;

/**
 * Violation buffer based on model probability:
 * growth above threshold, decay below decreaseThreshold (scaled:
 * the more confident the model is in legitimacy, the faster it decays).
 */
public final class BufferCalculator {

    private BufferCalculator() {
    }

    public static double increase(double probability, double multiplier, double threshold) {
        if (probability <= threshold) return 0.0;
        return (probability - threshold) * multiplier;
    }

    public static double decrease(double currentBuffer, double amount) {
        return Math.max(0.0, currentBuffer - amount);
    }

    public static double updateBuffer(double currentBuffer, double probability,
                                      double multiplier, double decreaseAmount,
                                      double threshold, double decreaseThreshold) {
        if (probability > threshold) {
            return currentBuffer + increase(probability, multiplier, threshold);
        } else if (probability < decreaseThreshold) {
            return decrease(currentBuffer,
                    scaledDecrease(probability, decreaseAmount, decreaseThreshold));
        }
        return currentBuffer;
    }

    public static double scaledDecrease(double probability, double maxAmount, double decreaseThreshold) {
        if (decreaseThreshold <= 0.0) return maxAmount;
        double p = Math.max(0.0, Math.min(decreaseThreshold, probability));
        return maxAmount * (decreaseThreshold - p) / decreaseThreshold;
    }

    public static boolean shouldFlag(double buffer, double flagThreshold) {
        return buffer >= flagThreshold;
    }

    public static double resetBuffer(double resetValue) {
        return Math.max(0.0, resetValue);
    }
}
