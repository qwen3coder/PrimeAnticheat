package ru.prime.anticheat.violation;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.penalty.PunishmentLadder;
import ru.prime.anticheat.util.Scheduler;
import ru.prime.anticheat.util.SecurityUtil;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VL system. Call all methods from the main thread.
 *
 * Flag: VL+1 -> alert -> ladder command by floor(VL) -> 5s cooldown
 * for destructive commands -> console execution.
 * Out of combat, VL decays on a timer (decay).
 */
public class ViolationManager {

    public static final Set<String> DESTRUCTIVE_TOKENS = Set.of(
            "kick", "ban", "tempban", "banip", "ban-ip", "ipban", "mute", "tempmute", "jail", "punish");

    public final PrimeAnticheat plugin;

    public final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    public final Map<UUID, Long> lastPunishmentTime = new ConcurrentHashMap<>();

    /** Per-check VL: player -> (check -> level). Sum drives the ladder. */
    public final Map<UUID, Map<String, Integer>> checkLevels = new ConcurrentHashMap<>();
    /** Per-check last flag details: player -> (check -> details). */
    public final Map<UUID, Map<String, String>> checkDetails = new ConcurrentHashMap<>();

    /** Mitigation lockout expiry: player -> timestamp (ms) while crystal-locked. */
    public final Map<UUID, Long> mitigationUntil = new ConcurrentHashMap<>();

    /** Per-check ladders (checks.<name>.punishments), case-insensitive. */
    public final Map<String, PunishmentLadder> ladders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public PunishmentSender sender;
    public Scheduler.Task decayTask;

    public ViolationManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-read the ladders and restart decay (call from /pac reload). */
    public void reload() {
        ladders.clear();
        for (Map.Entry<String, java.util.TreeMap<Integer, String>> e : plugin.configs.ladders.entrySet()) {
            ladders.put(e.getKey(), new PunishmentLadder(e.getValue()));
        }
        sender = new PunishmentSender(Bukkit.getConsoleSender(), plugin.configs.punishSenderName);
        restartDecay();
    }

    // --- flags ---

    public void handleFlag(PlayerData data, String check, double probability, double buffer) {
        handleFlag(data, check, "", probability, buffer);
    }

    public void handleFlag(PlayerData data, String check, String details,
                           double probability, double buffer) {
        int vl = violationLevels.merge(data.uuid, 1, Integer::sum);
        int checkVl = addCheckVL(checkLevels, data.uuid, check);
        putCheckDetails(checkDetails, data.uuid, check, details);
        data.lastFlagTime = System.currentTimeMillis();

        // Mitigation: every flag from a listed check locks crystal actions
        // for another lockout_ms (time-based, independent of VL decay).
        if (plugin.configs.mitigationEnabled && isListedCheck(plugin.configs.mitigationChecks, check)) {
            mitigationUntil.put(data.uuid,
                    System.currentTimeMillis() + Math.max(0, plugin.configs.mitigationLockoutMs));
        }

        plugin.alerts.sendAlert(data.name, check, details, probability, buffer, vl);
        plugin.getLogger().info("[VL] flag " + data.name + " [" + check + ":" + checkVl + "] VL=" + vl
                + " prob=" + String.format(Locale.ROOT, "%.2f", probability)
                + " buf=" + String.format(Locale.ROOT, "%.1f", buffer));

        // Per-check ladder fired by the check's own VL (global VL stays a sum for display).
        PunishmentLadder ladder = check == null ? null : ladders.get(check);
        String command = ladder == null ? null : ladder.commandFor(checkVl).orElse(null);
        if (command == null) return;

        if (isDestructive(command)) {
            long now = System.currentTimeMillis();
            Long prev = lastPunishmentTime.get(data.uuid);
            if (prev != null && (now - prev) < plugin.configs.punishmentCooldownMs) {
                plugin.getLogger().info("[VL] " + data.name + " punishment on cooldown, skipping");
                return;
            }
            lastPunishmentTime.put(data.uuid, now);
        }
        execute(command, data, probability, buffer, checkVl);
    }

    public void execute(String command, PlayerData data, double probability, double buffer, int vl) {
        String resolved = command
                .replace("%player%", data.name)
                .replace("{PLAYER}", data.name)
                .replace("%score%", String.format(Locale.ROOT, "%.2f", probability))
                .replace("{PROBABILITY}", String.format(Locale.ROOT, "%.2f", probability))
                .replace("{BUFFER}", String.format(Locale.ROOT, "%.1f", buffer))
                .replace("{VL}", String.valueOf(vl));

        plugin.getLogger().info("[VL] EXECUTE " + data.name + " VL=" + vl + " cmd='" + resolved + "'");

        Player online = data.player;
        if (online == null || !online.isOnline()) {
            try {
                online = Bukkit.getPlayer(data.uuid);
            } catch (Exception ignored) {
            }
        }

        // A nickname with spaces/exotic characters must not be substituted into a console command -
        // "kick Steve Notch ..." would punish another player. Kick directly via the API.
        if (!SecurityUtil.isSafeCommandName(data.name)) {
            plugin.getLogger().warning("[VL] Unsafe name '" + data.name + "' - direct kick instead of '" + resolved + "'");
            if (online != null && online.isOnline()) {
                String reason = plugin.configs.msg("kick-reason",
                        "{SCORE}", String.format(Locale.ROOT, "%.2f", probability));
                online.kickPlayer(reason);
            }
            return;
        }

        Bukkit.dispatchCommand(
                resolveExecutor(plugin.configs.customExecutor, sender, Bukkit.getConsoleSender()),
                resolved);
    }

    /**
     * Who runs ladder commands: the custom named sender, or the plain console
     * when the toggle is off (or the sender is missing). Some commands check
     * for the real console explicitly and refuse a wrapped sender.
     */
    public static CommandSender resolveExecutor(boolean custom, CommandSender sender,
                                                CommandSender console) {
        if (custom && sender != null) return sender;
        return console;
    }

    /** Whether the command is destructive (first token kick/ban/mute/...). */
    public static boolean isDestructive(String command) {
        if (command == null) return false;
        String s = command.trim().toLowerCase(Locale.ROOT);
        int space = s.indexOf(' ');
        String first = space == -1 ? s : s.substring(0, space);
        if (first.startsWith("/")) first = first.substring(1);
        int colon = first.indexOf(':');
        if (colon != -1) first = first.substring(colon + 1);
        return DESTRUCTIVE_TOKENS.contains(first);
    }

    // --- VL ---

    public int getViolationLevel(UUID uuid) {
        return violationLevels.getOrDefault(uuid, 0);
    }

    public void resetViolationLevel(UUID uuid) {
        violationLevels.remove(uuid);
    }

    public void decreaseViolationLevel(UUID uuid, int amount) {
        violationLevels.computeIfPresent(uuid, (k, v) -> v - amount <= 0 ? null : v - amount);
    }

    // --- per-check VL / details (pure static cores, unit-tested) ---

    /** Increment a check's VL, return the new level. */
    public static int addCheckVL(Map<UUID, Map<String, Integer>> levels, UUID uuid, String check) {
        Map<String, Integer> perCheck = levels.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        int next = perCheck.getOrDefault(check, 0) + 1;
        perCheck.put(check, next);
        return next;
    }

    public static void putCheckDetails(Map<UUID, Map<String, String>> details,
                                       UUID uuid, String check, String text) {
        if (text == null) return;
        details.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(check, text);
    }

    /**
     * Decay every check's VL by amount unless the player is in combat.
     * inCombat maps player -> true to skip. Empty entries are removed.
     */
    public static void decayCheckLevels(Map<UUID, Map<String, Integer>> levels, int amount,
                                        java.util.function.Function<UUID, Boolean> inCombat) {
        for (Iterator<Map.Entry<UUID, Map<String, Integer>>> it = levels.entrySet().iterator();
             it.hasNext();) {
            Map.Entry<UUID, Map<String, Integer>> entry = it.next();
            try {
                if (inCombat.apply(entry.getKey())) continue;
            } catch (Exception ignored) {
            }
            Iterator<Map.Entry<String, Integer>> jt = entry.getValue().entrySet().iterator();
            while (jt.hasNext()) {
                Map.Entry<String, Integer> check = jt.next();
                int next = check.getValue() - amount;
                if (next <= 0) jt.remove();
                else check.setValue(next);
            }
            if (entry.getValue().isEmpty()) it.remove();
        }
    }

    public int getCheckVL(UUID uuid, String check) {
        Map<String, Integer> perCheck = checkLevels.get(uuid);
        return perCheck == null ? 0 : perCheck.getOrDefault(check, 0);
    }

    public String getCheckDetails(UUID uuid, String check) {
        Map<String, String> perCheck = checkDetails.get(uuid);
        return perCheck == null ? "" : perCheck.getOrDefault(check, "");
    }

    public void resetCheckLevels(UUID uuid) {
        checkLevels.remove(uuid);
        checkDetails.remove(uuid);
    }

    /**
     * Instant mitigation: true while the player's flag lockout is active.
     * Each flag from a listed check extends it; expiry auto-cleans.
     */
    public boolean isMitigated(UUID uuid) {
        if (!plugin.configs.mitigationEnabled) return false;
        return isMitigated(mitigationUntil, System.currentTimeMillis(), uuid);
    }

    /** Pure core (unit-tested): locked while now < until (expired entries pruned). */
    public static boolean isMitigated(Map<UUID, Long> until, long now, UUID uuid) {
        Long untilTs = until.get(uuid);
        if (untilTs == null) return false;
        if (now >= untilTs) {
            until.remove(uuid);
            return false;
        }
        return true;
    }

    /** Pure core (unit-tested): does the check belong to the mitigation list? */
    public static boolean isListedCheck(java.util.List<String> names, String check) {
        if (names == null || check == null) return false;
        for (String name : names) {
            if (name != null && check.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // --- decay ---

    public void restartDecay() {
        stopDecay();
        if (!plugin.configs.vlDecayEnabled) return;
        long period = (long) plugin.configs.vlDecayIntervalSec * 20L;
        decayTask = Scheduler.timer(plugin, this::processDecay, period, period);
    }

    public void stopDecay() {
        if (decayTask != null) {
            try {
                decayTask.cancel();
            } catch (Exception ignored) {
            }
            decayTask = null;
        }
    }

    /** VL decay out of combat (public for tests). */
    public void processDecay() {
        int amount = plugin.configs.vlDecayAmount;
        long combatMs = plugin.configs.combatTimeMs;
        for (Map.Entry<UUID, Integer> e : violationLevels.entrySet()) {
            PlayerData data = plugin.players.get(e.getKey());
            if (data != null && data.isInCombat(combatMs)) continue;
            int next = e.getValue() - amount;
            if (next <= 0) violationLevels.remove(e.getKey());
            else violationLevels.put(e.getKey(), next);
        }
        // Per-check VLs decay with the same rules (shared vl-decay.* settings).
        decayCheckLevels(checkLevels, amount, uuid -> {
            PlayerData data = plugin.players.get(uuid);
            return data != null && data.isInCombat(combatMs);
        });
    }

    // --- lifecycle ---

    public void handleQuit(Player player) {
        lastPunishmentTime.remove(player.getUniqueId());
        mitigationUntil.remove(player.getUniqueId());
    }

    public void clearAll() {
        violationLevels.clear();
        lastPunishmentTime.clear();
        checkLevels.clear();
        checkDetails.clear();
        mitigationUntil.clear();
    }

    public void shutdown() {
        stopDecay();
        clearAll();
    }
}
