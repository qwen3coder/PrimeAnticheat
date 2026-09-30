package ru.prime.anticheat.data;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class PlayerDataTest {

    @Test
    public void recordProbabilitySnapshotCadence() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        for (int i = 1; i <= 4; i++) {
            assertNull(data.recordProbability(i / 10.0));
        }
        List<Double> snap = data.recordProbability(0.5);
        assertNotNull(snap);
        assertEquals(5, snap.size());
        assertEquals(0.1, snap.get(0), 1e-9);
    }

    @Test
    public void historySlides() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        for (int i = 1; i <= 10; i++) data.recordProbability(i / 10.0);
        List<Double> snap = data.probHistorySnapshot();
        assertEquals(5, snap.size());
        assertEquals(0.6, snap.get(0), 1e-9);
        assertEquals(1.0, snap.get(4), 1e-9);
    }
}
