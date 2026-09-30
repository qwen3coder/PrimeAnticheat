package ru.prime.anticheat.alert;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.util.ColorUtil;

import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Staff alerts. Call all methods ONLY from the main thread
 * (AICheck enters here via runTask).
 *
 * Single level: full flag with VL.
 */
public class AlertManager {

    public final PrimeAnticheat plugin;

    public final Set<UUID> playersWithAlerts = new CopyOnWriteArraySet<>();

    // /pac probs debug subscriptions (admins only, like alerts).
    public final Set<UUID> playersWithProbs = new CopyOnWriteArraySet<>();

    public AlertManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    // --- subscriptions ---

    public boolean toggleAlerts(Player player) {
        UUID uuid = player.getUniqueId();
        if (playersWithAlerts.contains(uuid)) {
            playersWithAlerts.remove(uuid);
            player.sendMessage(plugin.configs.msg("alerts-disabled"));
            return false;
        }
        playersWithAlerts.add(uuid);
        player.sendMessage(plugin.configs.msg("alerts-enabled"));
        return true;
    }

    public void enableAlerts(Player player) {
        playersWithAlerts.add(player.getUniqueId());
    }

    public boolean hasAlerts(Player player) {
        return playersWithAlerts.contains(player.getUniqueId());
    }

    public void handleQuit(Player player) {
        playersWithAlerts.remove(player.getUniqueId());
        playersWithProbs.remove(player.getUniqueId());
    }

    public static boolean canReceive(Player player) {
        return player.isOp()
                || player.hasPermission("primeanticheat.alerts")
                || player.hasPermission("primeanticheat.admin");
    }

    // --- probs debug (every 5 scored requests, admins only) ---

    public boolean toggleProbs(Player player) {
        UUID uuid = player.getUniqueId();
        if (playersWithProbs.contains(uuid)) {
            playersWithProbs.remove(uuid);
            player.sendMessage(plugin.configs.msg("probs-disabled"));
            return false;
        }
        playersWithProbs.add(uuid);
        player.sendMessage(plugin.configs.msg("probs-enabled"));
        return true;
    }

    public void enableProbs(Player player) {
        playersWithProbs.add(player.getUniqueId());
    }

    public boolean hasProbs(Player player) {
        return playersWithProbs.contains(player.getUniqueId());
    }

    public static boolean canReceiveProbs(Player player) {
        return player.isOp() || player.hasPermission("primeanticheat.admin")
                || player.hasPermission("primeanticheat.probs");
    }

    public static boolean canReceiveHolo(Player player) {
        return player.isOp() || player.hasPermission("primeanticheat.admin")
                || player.hasPermission("primeanticheat.holo");
    }

    /** Probability history (last 5 scores) for subscribed admins. */
    public void sendProbs(String name, List<Double> history) {
        String message = plugin.configs.msg("probs-history",
                "{PLAYER}", name,
                "{HISTORY}", coloredHistory(history));
        String full = plugin.configs.prefixLine + message;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && playersWithProbs.contains(p.getUniqueId()) && canReceiveProbs(p)) {
                p.sendMessage(full);
            }
        }
    }

    /** "0.001, 0.420, ..." — each value colored green (legit) to saturated red. */
    public static String coloredHistory(List<Double> history) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append("&#94A3B8, ");
            double p = history.get(i);
            sb.append(probColor(p)).append(String.format(Locale.ROOT, "%.3f", p));
        }
        return sb.toString();
    }

    /**
     * HEX gradient in four vivid stops: green -&gt; yellow -&gt; orange -&gt; red.
     * Piecewise RGB lerp between neighbours only, so the middle never turns
     * muddy brown (a straight green-to-red lerp would pass through #91703C).
     */
    public static String probColor(double probability) {
        double p = Math.min(1.0, Math.max(0.0, probability)) * (SCALE.length - 1);
        int seg = Math.min(SCALE.length - 2, (int) p);
        double f = p - seg;
        int r = (int) Math.round(SCALE[seg][0] + (SCALE[seg + 1][0] - SCALE[seg][0]) * f);
        int g = (int) Math.round(SCALE[seg][1] + (SCALE[seg + 1][1] - SCALE[seg][1]) * f);
        int b = (int) Math.round(SCALE[seg][2] + (SCALE[seg + 1][2] - SCALE[seg][2]) * f);
        return String.format(Locale.ROOT, "&#%02X%02X%02X", r, g, b);
    }

    /** Gradient stops: green, yellow, orange, saturated red. */
    private static final int[][] SCALE = {
        {0x22, 0xC5, 0x5E},
        {0xFF, 0xEA, 0x00},
        {0xFF, 0x8C, 0x00},
        {0xFF, 0x1A, 0x1A},
    };

    // --- sending ---

    /** Full flag: buffer crossed the threshold, VL already increased. */
    public void sendAlert(String name, String check, String details,
                          double probability, double buffer, int vl) {
        String tail = (details == null || details.isEmpty()) ? "" : " [" + details + "]";
        String message;
        if ("Aim".equals(check)) {
            message = plugin.configs.msg("alert-format-vl",
                    "{PLAYER}", name,
                    "{CHECK}", check,
                    "{PROBABILITY}", String.format(Locale.ROOT, "%.2f", probability),
                    "{BUFFER}", String.format(Locale.ROOT, "%.1f", buffer),
                    "{VL}", String.valueOf(vl),
                    "{DETAILS}", tail);
        } else {
            // Packet checks have no prob/buf - own template (hardcoded fallback
            // for old messages.yml files that lack the packet-flag key).
            String raw = plugin.configs.raw("packet-flag");
            if (raw.equals("packet-flag")) {
                message = renderPacketFlag(name, check, vl, tail);
            } else {
                message = plugin.configs.msg("packet-flag",
                        "{PLAYER}", name,
                        "{CHECK}", check,
                        "{VL}", String.valueOf(vl),
                        "{DETAILS}", tail);
            }
        }
        broadcast(message);
    }

    /** Packet flag without prob/buf (pure, unit-tested). */
    public static String renderPacketFlag(String name, String check, int vl, String tail) {
        return ColorUtil.color("&#58A8FF" + name + " &#94A3B8failed &#007AFF" + check
                + " &#94A3B8(VL &#58A8FF" + vl + "&#94A3B8)" + tail);
    }

    public void broadcast(String message) {
        String full = plugin.configs.prefixLine + message;
        boolean soundOn = plugin.configs.alertSoundEnabled;
        Sound sound = soundOn ? resolveSound(plugin.configs.alertSoundType) : null;
        float volume = plugin.configs.alertSoundVolume;
        float pitch = plugin.configs.alertSoundPitch;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || !playersWithAlerts.contains(p.getUniqueId()) || !canReceive(p)) continue;
            p.sendMessage(full);
            if (sound != null) {
                try {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                } catch (Exception ignored) {
                }
            }
        }
        if (plugin.configs.alertConsole) {
            String stripped = ColorUtil.strip(full);
            if (!stripped.isEmpty()) plugin.getLogger().info(stripped);
        }
    }

    public Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (Exception unknown) {
            try {
                plugin.getLogger().warning("Unknown alert sound '" + name + "' - using BLOCK_NOTE_BLOCK_PLING");
                return Sound.valueOf("BLOCK_NOTE_BLOCK_PLING");
            } catch (Exception missing) {
                plugin.getLogger().warning("Invalid sound type: " + name);
                return null;
            }
        }
    }
}
