package ru.prime.anticheat.data;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class PlayerDataDamageReductionTest {

    private static PlayerData data() {
        return new PlayerData(UUID.randomUUID(), "Test");
    }

    @Test
    public void defaultMultiplierIsOne() {
        PlayerData d = data();
        assertEquals(1.0, d.getDamageMultiplier(System.currentTimeMillis()), 1e-9);
    }

    @Test
    public void activeReductionAppliesMultiplier() {
        PlayerData d = data();
        long now = System.currentTimeMillis();
        d.applyDamageReduction(0.0, 5000, now);
        assertEquals(0.0, d.getDamageMultiplier(now + 1), 1e-9);
        assertEquals(0.0, d.getDamageMultiplier(now + 4999), 1e-9);
    }

    @Test
    public void expiredReductionRestoresFullDamage() {
        PlayerData d = data();
        long now = System.currentTimeMillis();
        d.applyDamageReduction(0.5, 1000, now);
        assertEquals(0.5, d.getDamageMultiplier(now + 500), 1e-9);
        assertEquals(1.0, d.getDamageMultiplier(now + 1000), 1e-9);
        assertEquals(1.0, d.getDamageMultiplier(now + 9999), 1e-9);
    }

    @Test
    public void multiplierIsClampedToUnitInterval() {
        PlayerData d = data();
        long now = System.currentTimeMillis();
        d.applyDamageReduction(-1.0, 5000, now);
        assertEquals(0.0, d.getDamageMultiplier(now + 1), 1e-9);
        d.applyDamageReduction(2.0, 5000, now);
        assertEquals(1.0, d.getDamageMultiplier(now + 1), 1e-9);
    }

    @Test
    public void refreshExtendsWindow() {
        PlayerData d = data();
        long now = System.currentTimeMillis();
        d.applyDamageReduction(0.0, 1000, now);
        d.applyDamageReduction(0.0, 1000, now + 800);
        assertEquals(0.0, d.getDamageMultiplier(now + 1500), 1e-9);
        assertEquals(1.0, d.getDamageMultiplier(now + 1801), 1e-9);
    }

    @Test
    public void zeroDurationIsImmediateExpire() {
        PlayerData d = data();
        long now = System.currentTimeMillis();
        d.applyDamageReduction(0.0, 0, now);
        assertEquals(1.0, d.getDamageMultiplier(now), 1e-9);
        assertEquals(1.0, d.getDamageMultiplier(now + 1), 1e-9);
    }

    // --- consecutive streak ---

    @Test
    public void streakBelowNeedDoesNotTrigger() {
        PlayerData d = data();
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertEquals(2, d.damageReduceStreak);
    }

    @Test
    public void streakReachesNeedAndTriggers() {
        PlayerData d = data();
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertTrue(d.feedDamageReduction(0.95, 0.9, 3));
        assertEquals(3, d.damageReduceStreak);
    }

    @Test
    public void lowScoreResetsStreak() {
        PlayerData d = data();
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertFalse(d.feedDamageReduction(0.5, 0.9, 3));
        assertEquals(0, d.damageReduceStreak);
        assertFalse(d.feedDamageReduction(0.95, 0.9, 3));
        assertEquals(1, d.damageReduceStreak);
    }

    @Test
    public void needOfOneTriggersImmediately() {
        PlayerData d = data();
        assertTrue(d.feedDamageReduction(0.95, 0.9, 1));
    }

    @Test
    public void exactBoundaryCountsAsHigh() {
        PlayerData d = data();
        assertTrue(d.feedDamageReduction(0.9, 0.9, 1));
        assertEquals(1, d.damageReduceStreak);
    }
}
