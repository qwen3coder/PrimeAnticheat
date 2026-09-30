package ru.prime.anticheat.data;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * One rotation tick: delta / accel / jerk for yaw+pitch + gcd error.
 * Field order = ML model input order (deltaYaw, deltaPitch,
 * accelYaw, accelPitch, jerkYaw, jerkPitch; gcd error is for debug/CSV).
 */
public final class TickData {

    public final float deltaYaw;
    public final float deltaPitch;
    public final float accelYaw;
    public final float accelPitch;
    public final float jerkYaw;
    public final float jerkPitch;
    public final float gcdErrorYaw;
    public final float gcdErrorPitch;

    public TickData(float deltaYaw, float deltaPitch,
                    float accelYaw, float accelPitch,
                    float jerkYaw, float jerkPitch,
                    float gcdErrorYaw, float gcdErrorPitch) {
        this.deltaYaw = deltaYaw;
        this.deltaPitch = deltaPitch;
        this.accelYaw = accelYaw;
        this.accelPitch = accelPitch;
        this.jerkYaw = jerkYaw;
        this.jerkPitch = jerkPitch;
        this.gcdErrorYaw = gcdErrorYaw;
        this.gcdErrorPitch = gcdErrorPitch;
    }

    public static TickData zero() {
        return new TickData(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static String getHeader() {
        return "is_cheating,delta_yaw,delta_pitch,accel_yaw,accel_pitch,jerk_yaw,jerk_pitch,"
                + "gcd_error_yaw,gcd_error_pitch";
    }

    public String toCsv(String status) {
        int cheating = status.equalsIgnoreCase("CHEAT") ? 1 : 0;
        StringJoiner j = new StringJoiner(",");
        j.add(String.valueOf(cheating));
        j.add(fmt(deltaYaw));
        j.add(fmt(deltaPitch));
        j.add(fmt(accelYaw));
        j.add(fmt(accelPitch));
        j.add(fmt(jerkYaw));
        j.add(fmt(jerkPitch));
        j.add(fmt(gcdErrorYaw));
        j.add(fmt(gcdErrorPitch));
        return j.toString();
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.6f", v);
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "TickData[dYaw=%.4f, dPitch=%.4f, aYaw=%.4f, aPitch=%.4f, jYaw=%.4f, jPitch=%.4f, gcdYaw=%.4f, gcdPitch=%.4f]",
                deltaYaw, deltaPitch, accelYaw, accelPitch, jerkYaw, jerkPitch, gcdErrorYaw, gcdErrorPitch);
    }
}
