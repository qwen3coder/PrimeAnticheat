package ru.prime.anticheat.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class SchedulerTest {

    static class Cancellable {
        boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    public void bukkitPlatformDetected() {
        assertFalse(Scheduler.FOLIA);
    }

    @Test
    public void doneHandleIsNoOp() throws Exception {
        java.lang.reflect.Field f = Scheduler.class.getDeclaredField("DONE");
        f.setAccessible(true);
        Scheduler.Task done = (Scheduler.Task) f.get(null);
        done.cancel();
    }

    @Test
    public void wrapperDelegatesCancel() throws Exception {
        java.lang.reflect.Method m = Scheduler.class.getDeclaredMethod("wrapCancellable", Object.class);
        m.setAccessible(true);
        Cancellable inner = new Cancellable();
        Scheduler.Task task = (Scheduler.Task) m.invoke(null, inner);
        assertFalse(inner.cancelled);
        task.cancel();
        assertTrue(inner.cancelled);
    }
}
