package ru.prime.anticheat.manager;

import org.bukkit.Bukkit;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.command.PrimeCommand;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Two timers:
 *  - sync every tick: combat counters (leaving combat extinguishes the tick window);
 *  - async every check-period: sending the full window (sequence ticks,
 *    step rotations elapsed) to AICheck. Networking is neither on Netty nor on the main thread.
 */
public class CheckManager {

    public final PrimeAnticheat plugin;
    public Scheduler.Task counterTask;
    public Scheduler.Task senderTask;

    public CheckManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        counterTask = Scheduler.timer(plugin, this::counters, 1, 1);
        int period = plugin.configs.checkPeriodTicks;
        senderTask = Scheduler.timerAsync(plugin, this::send, period, period);
    }

    public void stop() {
        if (counterTask != null) {
            counterTask.cancel();
            counterTask = null;
        }
        if (senderTask != null) {
            senderTask.cancel();
            senderTask = null;
        }
    }

    private void counters() {
        // /pac visibility follows permission grants live (LuckPerms etc.).
        if (++visibilityClock >= 1200) {
            visibilityClock = 0;
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                try {
                    PrimeCommand.syncVisibility(plugin, online);
                } catch (Exception ignored) {
                }
            }
        }
        if (!plugin.configs.mlEnabled || !plugin.configs.aimEnabled) return;
        long combatMs = plugin.configs.combatTimeMs;
        for (PlayerData data : plugin.players.all()) {
            try {
                data.onServerTick(combatMs);
            } catch (Exception e) {
                if (plugin.configs.debug) plugin.getLogger().warning("counter fail: " + e.getMessage());
            }
        }
    }

    public int visibilityClock;

    private void send() {
        if (!plugin.configs.mlEnabled || !plugin.configs.aimEnabled) return;
        // Without a live API we do not send windows (fail-closed on send)
        if (plugin.inference == null || !plugin.inference.isReady()) {
            debugSummary("not-connected", 0, 0, 0, 0, 0, null);
            return;
        }
        int sequence = plugin.configs.sequence;
        int step = plugin.configs.step;
        long combatMs = plugin.configs.combatTimeMs;

        int sent = 0, noCombat = 0, noWindow = 0, skipped = 0;
        List<String> skipReasons = plugin.configs.debug || plugin.configs.inferenceDebug
                ? new ArrayList<>() : null;
        for (PlayerData data : plugin.players.all()) {
            try {
                if (!data.isInCombat(combatMs)) {
                    noCombat++;
                    continue;
                }
                if (plugin.configs.hasBypass(data.player)) {
                    skipped++;
                    if (skipReasons != null) skipReasons.add(data.name + ":bypass");
                    continue;
                }
                if (!data.shouldSend(step, sequence)) {
                    noWindow++;
                    continue;
                }

                // Reset the counter only if the request was actually sent,
                // otherwise the gate would wastefully eat up the accumulated ticks
                if (plugin.aiCheck.check(data)) {
                    data.resetStepCounter();
                    sent++;
                }
            } catch (Exception e) {
                if (plugin.configs.debug) plugin.getLogger().warning("check send fail: " + e.getMessage());
            }
        }
        debugSummary("ok", sent, noCombat, noWindow, skipped, plugin.players.size(), skipReasons);
    }

    public long lastDebugSummary;

    /** Summary of why nothing was sent (debug only, at most once per 30s). */
    public void debugSummary(String state, int sent, int noCombat, int noWindow, int skipped, int total,
                             List<String> skipReasons) {
        if (!plugin.configs.debug && !plugin.configs.inferenceDebug) return;
        long now = System.currentTimeMillis();
        if (now - lastDebugSummary < 30000) return;
        lastDebugSummary = now;
        if (state.equals("not-connected")) {
            plugin.getLogger().info("[AI-Debug] send tick: API not connected, players=" + total);
        } else if (sent == 0 && total > 0) {
            plugin.getLogger().info("[AI-Debug] send tick: sent=0 players=" + total
                    + " noCombat=" + noCombat + " noWindow=" + noWindow + " skipped=" + skipped);
            if (skipReasons != null && !skipReasons.isEmpty()) {
                plugin.getLogger().info("[AI-Debug] skipped: " + String.join(", ", skipReasons)
                        + " (bypass = hasPermission " + plugin.configs.bypassPermission
                        + ", OPs have it implicitly!)");
            }
        }
    }
}
