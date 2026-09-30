package ru.prime.anticheat.math;

import ru.prime.anticheat.data.TickData;

/**
 * delta -> accel -> jerk chain over yaw/pitch + GCD sensitivity mode.
 *  - deltaYaw is normalized to [-180, 180]
 *  - accel = delta - lastDelta, jerk = accel - lastAccel
 *  - GCD is computed only for 0 < |delta| < 5.0
 *  - mode is locked in at >15 samples and >15 matches
 */
public class AimProcessor {

    public static final int SIGNIFICANT_SAMPLES_THRESHOLD = 15;
    public static final float MAX_DELTA_FOR_GCD = 5.0f;
    public static final int MODE_WINDOW = 80;

    public final RunningMode xRotMode = new RunningMode(MODE_WINDOW);
    public final RunningMode yRotMode = new RunningMode(MODE_WINDOW);

    public float lastYaw;
    public float lastPitch;
    public float lastDeltaYaw;
    public float lastDeltaPitch;
    public float lastYawAccel;
    public float lastPitchAccel;
    public float currentYawAccel;
    public float currentPitchAccel;
    public float lastXRot;
    public float lastYRot;
    public double modeX;
    public double modeY;
    public boolean hasLastRotation;

    public void reset() {
        lastYaw = 0;
        lastPitch = 0;
        lastDeltaYaw = 0;
        lastDeltaPitch = 0;
        lastYawAccel = 0;
        lastPitchAccel = 0;
        currentYawAccel = 0;
        currentPitchAccel = 0;
        lastXRot = 0;
        lastYRot = 0;
        modeX = 0;
        modeY = 0;
        hasLastRotation = false;
        xRotMode.clear();
        yRotMode.clear();
    }

    public TickData process(float yaw, float pitch) {
        if (!hasLastRotation) {
            lastYaw = yaw;
            lastPitch = pitch;
            lastDeltaYaw = 0;
            lastDeltaPitch = 0;
            lastYawAccel = 0;
            lastPitchAccel = 0;
            currentYawAccel = 0;
            currentPitchAccel = 0;
            hasLastRotation = true;
            return TickData.zero();
        }

        float deltaYaw = normalizeAngle(yaw - lastYaw);
        float deltaPitch = pitch - lastPitch;
        float deltaYawAbs = Math.abs(deltaYaw);
        float deltaPitchAbs = Math.abs(deltaPitch);

        lastYawAccel = currentYawAccel;
        lastPitchAccel = currentPitchAccel;

        currentYawAccel = deltaYaw - lastDeltaYaw;
        currentPitchAccel = deltaPitch - lastDeltaPitch;

        float jerkYaw = currentYawAccel - lastYawAccel;
        float jerkPitch = currentPitchAccel - lastPitchAccel;

        double divisorX = GcdMath.gcd(deltaYawAbs, lastXRot);
        if (deltaYawAbs > 0 && deltaYawAbs < MAX_DELTA_FOR_GCD && divisorX > GcdMath.MINIMUM_DIVISOR) {
            xRotMode.add(divisorX);
            lastXRot = deltaYawAbs;
        }
        double divisorY = GcdMath.gcd(deltaPitchAbs, lastYRot);
        if (deltaPitchAbs > 0 && deltaPitchAbs < MAX_DELTA_FOR_GCD && divisorY > GcdMath.MINIMUM_DIVISOR) {
            yRotMode.add(divisorY);
            lastYRot = deltaPitchAbs;
        }
        updateModes();

        float gcdErrorYaw = gcdError(deltaYaw, modeX);
        float gcdErrorPitch = gcdError(deltaPitch, modeY);

        lastYaw = yaw;
        lastPitch = pitch;
        lastDeltaYaw = deltaYaw;
        lastDeltaPitch = deltaPitch;

        return new TickData(deltaYaw, deltaPitch, currentYawAccel, currentPitchAccel,
                jerkYaw, jerkPitch, gcdErrorYaw, gcdErrorPitch);
    }

    public static float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    public static double normalizeAngle(double angle) {
        angle %= 360.0;
        if (angle > 180.0) angle -= 360.0;
        else if (angle < -180.0) angle += 360.0;
        return angle;
    }

    private void updateModes() {
        if (xRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
            RunningMode.ModeResult m = xRotMode.getMode();
            if (m.value() != null && m.count() > SIGNIFICANT_SAMPLES_THRESHOLD) modeX = m.value();
        }
        if (yRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
            RunningMode.ModeResult m = yRotMode.getMode();
            if (m.value() != null && m.count() > SIGNIFICANT_SAMPLES_THRESHOLD) modeY = m.value();
        }
    }

    private static float gcdError(float delta, double mode) {
        if (mode == 0) return 0;
        double abs = Math.abs(delta);
        double rem = abs % mode;
        return (float) Math.min(rem, mode - rem);
    }

    /** Mouse sensitivity 0-200 from GCD mode, -1 if mode is not available yet. */
    public int getSensitivity() {
        if (modeY <= 0) return -1;
        double f = Math.cbrt(modeY / 1.2);
        return (int) Math.round(((f - 0.2) / 0.6) * 200);
    }
}
