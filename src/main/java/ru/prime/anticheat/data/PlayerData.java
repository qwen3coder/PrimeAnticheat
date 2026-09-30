package ru.prime.anticheat.data;

import ru.prime.anticheat.math.AimProcessor;
import ru.prime.anticheat.math.BufferCalculator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All player Aim-check state in one place. Fields are public intentionally.
 *
 * Threads: processTick - Netty (packets), onAttack/onTeleport/onServerTick -
 * main thread, snapshot/shouldSend/updateBuffer - async sending.
 * All tick state under one lock (AimProcessor too - reset from
 * the main thread would race with process from Netty).
 */
public class PlayerData {

    public final UUID uuid;
    public final String name;

    public volatile org.bukkit.entity.Player player;

    public final AimProcessor aim = new AimProcessor();
    public final ArrayDeque<TickData> ticks = new ArrayDeque<>();
    public final Object lock = new Object();

    // Full tick history for false-positive analysis
    public static final int MAX_TICK_HISTORY = 5000;
    public final ArrayDeque<TickData> history = new ArrayDeque<>();

    // Combat by wall-clock: ms timestamp of the last attack on a player (0 = no combat).
    // The tick window is valid for combat-time ms after the hit.
    public volatile long lastAttackTime;
    public int ticksStep;

    // Violation buffer based on model probability
    public double buffer;

    public volatile long joinTime = System.currentTimeMillis();
    public volatile long lastPacketTime;
    public volatile int totalRotations;

    public volatile double lastProbability;
    public volatile long lastFlagTime;
    public volatile long lastCheckTime;

    // Last scored probabilities for /pac probs debug (up to PROBS_HISTORY).
    // Every PROBS_HISTORY-th score returns a snapshot for the report.
    public static final int PROBS_HISTORY = 5;
    public final ArrayDeque<Double> probHistory = new ArrayDeque<>();
    public int probsSinceReport;
    /** Bumped on every scored probability (hologram text refresh). */
    public volatile int probsVersion;

    // In-flight WS request: do not send a new one while the old one is pending
    public volatile boolean inferenceInFlight;
    public volatile long lastInferTime;

    // Damage reduction after N consecutive high AI scores (ai.damage-reduction).
    public volatile int damageReduceStreak;
    public volatile long damageReduceUntil;
    public volatile double damageReduceMultiplier = 1.0;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    /**
     * Feed one score into the consecutive-high streak.
     * Returns true when the streak just reached "need" and reduction should be applied.
     * A score below threshold resets the streak (no trigger).
     */
    public boolean feedDamageReduction(double score, double threshold, int need) {
        if (score < threshold) {
            damageReduceStreak = 0;
            return false;
        }
        int next = damageReduceStreak + 1;
        damageReduceStreak = next;
        return next >= need;
    }

    /** Outgoing damage multiplier: 1.0 when no active reduction, else the stored factor. */
    public double getDamageMultiplier(long now) {
        if (now >= damageReduceUntil) return 1.0;
        double m = damageReduceMultiplier;
        return m < 0.0 ? 0.0 : (m > 1.0 ? 1.0 : m);
    }

    /** Activate/refresh the reduction window once the streak threshold is reached. */
    public void applyDamageReduction(double multiplier, long durationMs, long now) {
        double m = multiplier < 0.0 ? 0.0 : (multiplier > 1.0 ? 1.0 : multiplier);
        damageReduceMultiplier = m;
        damageReduceUntil = now + Math.max(0, durationMs);
    }

    /** Every rotation packet. Returns the computed TickData. */
    public TickData processTick(float yaw, float pitch, int sequence) {
        synchronized (lock) {
            TickData t = aim.process(yaw, pitch);
            while (ticks.size() >= sequence) ticks.pollFirst();
            ticks.addLast(t);
            if (history.size() >= MAX_TICK_HISTORY) history.pollFirst();
            history.addLast(t);
            ticksStep++;
            return t;
        }
    }

    /** New attack: if there was no combat - old ticks are discarded (new measurement). */
    public void onAttack(long combatMs) {
        synchronized (lock) {
            if (!isInCombatLocked(combatMs)) {
                ticks.clear();
                aim.reset();
            }
            lastAttackTime = System.currentTimeMillis();
        }
    }

    public void onTeleport() {
        synchronized (lock) {
            aim.reset();
            ticks.clear();
            lastAttackTime = 0;
            ticksStep = 0;
        }
    }

    /** Every server tick: leaving combat clears the window. */
    public void onServerTick(long combatMs) {
        synchronized (lock) {
            if (!isInCombatLocked(combatMs) && !ticks.isEmpty()) {
                ticks.clear();
                ticksStep = 0;
            }
        }
    }

    public boolean isInCombat(long combatMs) {
        synchronized (lock) {
            return isInCombatLocked(combatMs);
        }
    }

    public boolean isInCombatLocked(long combatMs) {
        return lastAttackTime != 0 && System.currentTimeMillis() - lastAttackTime <= combatMs;
    }

    /** Window is full + step rotations have passed since the last send. */
    public boolean shouldSend(int step, int sequence) {
        synchronized (lock) {
            return ticksStep >= step && ticks.size() >= sequence;
        }
    }

    public void resetStepCounter() {
        synchronized (lock) {
            ticksStep = 0;
        }
    }

    /** Copy of the window for inference (last sequence ticks). */
    public List<TickData> snapshot(int sequence) {
        synchronized (lock) {
            if (ticks.size() < sequence) return null;
            List<TickData> all = new ArrayList<>(ticks);
            return all.subList(all.size() - sequence, all.size());
        }
    }

    public int tickCount() {
        synchronized (lock) {
            return ticks.size();
        }
    }

    /** Copy of the full history (for DataRestorer/false-positive). */
    public List<TickData> getTickHistory() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    public void updateBuffer(double probability, double multiplier, double decrease,
                             double threshold, double decreaseThreshold) {
        synchronized (lock) {
            buffer = BufferCalculator.updateBuffer(buffer, probability, multiplier,
                    decrease, threshold, decreaseThreshold);
        }
    }

    /**
     * Record one scored probability. Returns a copy of the recent history
     * every PROBS_HISTORY-th call (for the /pac probs report), else null.
     */
    public List<Double> recordProbability(double probability) {
        synchronized (lock) {
            probHistory.addLast(probability);
            while (probHistory.size() > PROBS_HISTORY) probHistory.pollFirst();
            probsVersion++;
            probsSinceReport++;
            if (probsSinceReport < PROBS_HISTORY) return null;
            probsSinceReport = 0;
            return new ArrayList<>(probHistory);
        }
    }

    /** Copy of the recent probability history (for holograms). */
    public List<Double> probHistorySnapshot() {
        synchronized (lock) {
            return new ArrayList<>(probHistory);
        }
    }

    public boolean shouldFlag(double flagThreshold) {
        synchronized (lock) {
            return BufferCalculator.shouldFlag(buffer, flagThreshold);
        }
    }

    public double getBuffer() {
        synchronized (lock) {
            return buffer;
        }
    }

    public void resetBuffer(double resetValue) {
        synchronized (lock) {
            buffer = BufferCalculator.resetBuffer(resetValue);
        }
    }
}
