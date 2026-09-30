package ru.prime.anticheat.math;

import org.junit.Test;
import ru.prime.anticheat.data.TickData;

import static org.junit.Assert.*;

public class AimProcessorTest {

    @Test
    public void normalizeAngleWraps() {
        assertEquals(-170.0f, AimProcessor.normalizeAngle(190.0f), 1e-6f);
        assertEquals(170.0f, AimProcessor.normalizeAngle(-190.0f), 1e-6f);
        assertEquals(0.0f, AimProcessor.normalizeAngle(720.0f), 1e-6f);
        assertEquals(179.0, AimProcessor.normalizeAngle(179.0), 1e-9);
    }

    @Test
    public void firstTickIsZero() {
        AimProcessor proc = new AimProcessor();
        TickData tick = proc.process(10.0f, 5.0f);
        assertEquals(0.0f, tick.deltaYaw, 1e-6f);
        assertEquals(0.0f, tick.deltaPitch, 1e-6f);
    }

    @Test
    public void deltaChain() {
        AimProcessor proc = new AimProcessor();
        proc.process(0.0f, 0.0f);
        TickData second = proc.process(10.0f, 0.0f);
        assertEquals(10.0f, second.deltaYaw, 1e-6f);
        TickData third = proc.process(25.0f, 0.0f);
        assertEquals(15.0f, third.deltaYaw, 1e-6f);
        // accel = 15 - 10
        assertEquals(5.0f, third.accelYaw, 1e-6f);
    }
}
