package ru.prime.anticheat.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base for per-player packet checks (AutoCrystal and friends).
 *
 * <p>onPacket runs on a Netty thread for EVERY received packet: keep it fast,
 * allocation-free on the hot path and never touch Bukkit entity state or send
 * messages from it. Use {@link #flag} / {@link #tryFlag} to punish - they hop
 * to the main thread and feed the shared VL / alerts / ladder pipeline.
 *
 * <p>Per-check player state lives here (a map keyed by UUID); the engine never
 * clears it, drop entries on quit if your state must not linger.
 *
 * <pre>
 * public class CrystalPlaceCheck extends PacketCheck {
 *     public CrystalPlaceCheck(PrimeAnticheat plugin) { super(plugin, "CrystalPlace"); }
 *
 *     public void onPacket(PacketReceiveEvent e, Player p, PlayerData d) {
 *         if (!Packets.isBlockPlace(e)) return;
 *         // ... your logic ...
 *         tryFlag(p, d, cfgLong("cooldown_ms", 500), "suspicious place");
 *     }
 *
 *     protected void onReload() {
 *         maxReach = cfgDouble("max_reach", 4.5); // re-read on /pac reload
 *     }
 * }
 * // config.yml:
 * // checks:
 * //   crystalplace:
 * //     enabled: true
 * //     cooldown_ms: 500
 * //     max_reach: 4.5
 * // register once (onEnable): plugin.packetChecks.register(new CrystalPlaceCheck(plugin));
 * </pre>
 */
public abstract class PacketCheck {

    public final PrimeAnticheat plugin;
    public final String name;

    /** Last flag timestamps per player (tryFlag cooldown). */
    public final Map<UUID, Long> lastFlag = new ConcurrentHashMap<>();

    /** Kill-switch from checks.&lt;name&gt;.enabled (re-read on /pac reload). */
    public volatile boolean enabled = true;

    protected PacketCheck(PrimeAnticheat plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    /** One received packet for one online player with data. Netty thread! */
    public abstract void onPacket(PacketReceiveEvent event, Player player, PlayerData data);

    /** Lower-cased config key: checks.&lt;key&gt; (letters and digits only). */
    public String key() {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** Own config section checks.&lt;key&gt; (never null, empty when missing). */
    protected ConfigurationSection checkConfig() {
        try {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("checks." + key());
            if (sec != null) return sec;
        } catch (Exception ignored) {
        }
        return new MemoryConfiguration();
    }

    // Typed getters (exception-safe, defaults on missing/garbage). Hot path
    // must not call these per packet - read once in onReload() instead.
    protected int cfgInt(String key, int def) {
        return cfgInt(checkConfig(), key, def);
    }

    protected long cfgLong(String key, long def) {
        return cfgLong(checkConfig(), key, def);
    }

    protected double cfgDouble(String key, double def) {
        return cfgDouble(checkConfig(), key, def);
    }

    protected boolean cfgBoolean(String key, boolean def) {
        return cfgBoolean(checkConfig(), key, def);
    }

    protected String cfgString(String key, String def) {
        return cfgString(checkConfig(), key, def);
    }

    protected List<String> cfgStringList(String key) {
        return cfgStringList(checkConfig(), key);
    }

    public static int cfgInt(ConfigurationSection sec, String key, int def) {
        try {
            return sec == null ? def : sec.getInt(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static long cfgLong(ConfigurationSection sec, String key, long def) {
        try {
            return sec == null ? def : sec.getLong(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static double cfgDouble(ConfigurationSection sec, String key, double def) {
        try {
            return sec == null ? def : sec.getDouble(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean cfgBoolean(ConfigurationSection sec, String key, boolean def) {
        try {
            return sec == null ? def : sec.getBoolean(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static String cfgString(ConfigurationSection sec, String key, String def) {
        try {
            if (sec == null) return def;
            String value = sec.getString(key, def);
            return value != null ? value : def;
        } catch (Exception e) {
            return def;
        }
    }

    public static List<String> cfgStringList(ConfigurationSection sec, String key) {
        try {
            if (sec == null) return new ArrayList<>();
            List<String> value = sec.getStringList(key);
            return value != null ? value : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Re-read config (called on /pac reload and should be called once after
     * register). Override {@link #onReload()} to cache values into fields.
     */
    public void reloadConfig() {
        enabled = cfgBoolean("enabled", true);
        try {
            onReload();
        } catch (Exception e) {
            try {
                plugin.getLogger().warning("Packet check '" + name + "' reload failed: " + e);
            } catch (Exception ignored) {
            }
        }
    }

    /** Cache cfg* values into fields here (runs on the main thread). */
    protected void onReload() {
    }

    /**
     * Punish now: +1 VL through the shared pipeline (flag alert + ladder).
     * Thread-safe, lands on the main thread.
     */
    protected void flag(Player player, PlayerData data, String details) {
        UUID uuid = player.getUniqueId();
        try {
            if (plugin.configs.debug) {
                plugin.getLogger().info("[PacketCheck:" + name + "] " + data.name
                        + " flagged (" + details + ")");
            }
        } catch (Exception ignored) {
        }
        try {
            Scheduler.run(plugin, () -> {
                try {
                    if (plugin.violations == null) return;
                    PlayerData live = plugin.players.get(uuid);
                    if (live == null) return;
                    plugin.violations.handleFlag(live, PacketCheck.this.name,
                            details, 1.0, live.getBuffer());
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * flag() at most once per cooldownMs per player (returns false when
     * swallowed by the cooldown). Prevents VL spam from packet bursts.
     */
    protected boolean tryFlag(Player player, PlayerData data, long cooldownMs, String details) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long prev = lastFlag.get(uuid);
        if (prev != null && now - prev < cooldownMs) return false;
        lastFlag.put(uuid, now);
        flag(player, data, details);
        return true;
    }

    /** Drop per-player state (call from a quit handler when needed). */
    public void handleQuit(UUID uuid) {
        lastFlag.remove(uuid);
    }
}
