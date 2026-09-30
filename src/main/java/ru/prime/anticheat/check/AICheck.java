package ru.prime.anticheat.check;

import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.data.TickData;
import ru.prime.anticheat.util.Scheduler;
import ru.prime.anticheat.util.SecurityUtil;

import java.util.List;

/**
 * Strict Aim ML check via cloud inference (WebSocket).
 *
 * Model input - a window of sequence ticks, each tick 6 features in
 * flatbuffers-schema order (deltaYaw, deltaPitch, accelYaw, accelPitch,
 * jerkYaw, jerkPitch). Gcd error does not go into the model, only into CSV/logs.
 *
 * Probability feeds the violation buffer. Flag crossing -
 * VL+1 and the punishment ladder.
 *
 * Threads: sending from the CheckManager async task, the response arrives on the
 * OkHttp thread, all Bukkit touches - back to sync as one chunk
 * (AlertManager/ViolationManager sync only).
 */
public class AICheck {

    public final PrimeAnticheat plugin;

    public AICheck(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    /** true if the request actually went to the engine (the step counter can be reset). */
    public boolean check(PlayerData data) {
        if (plugin.inference == null || !plugin.inference.isReady()) return false;

        List<TickData> window = data.snapshot(plugin.configs.sequence);
        if (window == null) return false;

        // Gate: one pending request per player.
        // The engine timeout resets the flag itself via callback; below is a stuck-gate safeguard.
        long now = System.currentTimeMillis();
        if (data.inferenceInFlight) {
            long maxAge = (long) (plugin.configs.inferenceTimeoutSec + 10) * 1000L;
            if (now - data.lastInferTime < maxAge) return false;
            if (plugin.configs.debug) plugin.getLogger().warning("[AI] Stale inference gate for " + data.name + " - reset");
            data.inferenceInFlight = false;
        }
        data.inferenceInFlight = true;
        data.lastInferTime = now;

        plugin.inference.predict(window, data.name, data.uuid.toString()).thenAccept(resp -> {
            try {
                if (resp.hasError()) {
                    if (plugin.configs.debug) {
                        plugin.getLogger().info("[AI] " + data.name + " error: " + resp.getError());
                    }
                    return;
                }
                double score = resp.getProbability();
                if (!SecurityUtil.isValidProbability(score)) {
                    plugin.getLogger().warning("[AI] Bad probability " + score + " for " + data.name + " - dropped");
                    return;
                }
                onScore(data, score);
            } finally {
                data.inferenceInFlight = false;
            }
        });
        return true;
    }

    /** Engine response parsing: buffer, steps, flag. Bukkit part - in sync. */
    public void onScore(PlayerData data, double score) {
        data.lastProbability = score;
        data.lastCheckTime = System.currentTimeMillis();

        data.updateBuffer(score,
                plugin.configs.bufferMultiplier,
                plugin.configs.bufferDecrease,
                plugin.configs.alertThreshold,
                plugin.configs.bufferDecreaseThreshold);

        if (plugin.configs.debug) {
            plugin.getLogger().info("[AI] " + data.name + " p=" + String.format(java.util.Locale.ROOT, "%.3f", score)
                    + " buf=" + String.format(java.util.Locale.ROOT, "%.2f", data.getBuffer()));
        }

        // Soft mitigation: N consecutive scores above threshold cut outgoing
        // damage for damage-reduction.duration-ms (streak resets on a low score).
        if (plugin.configs.damageReductionEnabled) {
            boolean trigger = data.feedDamageReduction(score,
                    plugin.configs.damageReductionThreshold,
                    plugin.configs.damageReductionConsecutive);
            if (trigger) {
                data.applyDamageReduction(plugin.configs.damageReductionMultiplier,
                        plugin.configs.damageReductionDurationMs, System.currentTimeMillis());
                if (plugin.configs.debug) {
                    plugin.getLogger().info("[AI] " + data.name + " damage reduced x"
                            + String.format(java.util.Locale.ROOT, "%.2f",
                            plugin.configs.damageReductionMultiplier)
                            + " for " + plugin.configs.damageReductionDurationMs
                            + "ms (streak " + data.damageReduceStreak + ")");
                }
            } else if (plugin.configs.debug && data.damageReduceStreak > 0) {
                plugin.getLogger().info("[AI] " + data.name + " damage-reduction streak="
                        + data.damageReduceStreak + "/" + plugin.configs.damageReductionConsecutive);
            }
        }

        // /pac probs debug: report the recent history every 5th scored request.
        // Player messages must go from the main thread - hop via runTask.
        List<Double> probs = data.recordProbability(score);
        if (probs != null && plugin.alerts != null && !plugin.alerts.playersWithProbs.isEmpty()) {
            Scheduler.run(plugin, () -> plugin.alerts.sendProbs(data.name, probs));
        }

        double flag = plugin.configs.bufferFlag;
        if (!data.shouldFlag(flag)) return;

        double buf = data.getBuffer();
        Scheduler.run(plugin, () -> {
            data.resetBuffer(plugin.configs.bufferResetOnFlag);
            plugin.violations.handleFlag(data, "Aim", "", score, buf);
        });
    }
}
