package ru.prime.anticheat.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import ru.prime.anticheat.util.Scheduler;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.alert.AlertManager;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Probability holograms above players' heads, visible to subscribed admins only.
 *
 * Compatibility notes (this is why it is built this way):
 * - Vanilla TextDisplay entities (1.19.4+), no NMS, no armor stands with custom
 *   names - those break on some versions and can even kick newer clients.
 * - Per-viewer visibility via hideEntity/showEntity: regular players never get
 *   the entity at all, so nothing can glitch or kick them.
 * - Position follows the player via teleport every tick (no riding tricks).
 *
 * Text: colored percent history like "0%, 1%, 1%, 3%, 0%" (green to red).
 * Call all methods ONLY from the main thread.
 */
public class HologramManager {

    /** Height above the player's feet (above the nickname plate). */
    public static final double HEIGHT_ABOVE = 2.5;

    /** Skip teleport when the target moved less than this (squared, ~2cm). */
    public static final double TELEPORT_MIN_DIST_SQ = 0.0004;

    public final PrimeAnticheat plugin;

    // Tracked player -> its display entity.
    public final Map<UUID, TextDisplay> holos = new HashMap<>();
    // Admins that currently see holograms (/pac holo toggle).
    public final Set<UUID> viewers = new HashSet<>();
    // Last teleported position per target (teleport skip).
    public final Map<UUID, Location> lastPos = new HashMap<>();
    // Last rendered history version per target (text rebuild skip).
    public final Map<UUID, Integer> lastTextVersion = new HashMap<>();

    public Scheduler.Task task;

    public HologramManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        long period = Math.max(1, plugin.configs.hologramPeriodTicks);
        task = Scheduler.timer(plugin, this::tick, period, period);
    }

    /** Re-read the refresh period (call from /pac reload) - entities and viewers stay. */
    public void restart() {
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
            task = null;
        }
        start();
    }

    public void stopAll() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TextDisplay d : holos.values()) {
            try {
                if (d != null && !d.isDead()) d.remove();
            } catch (Exception ignored) {
            }
        }
        holos.clear();
        viewers.clear();
        lastPos.clear();
        lastTextVersion.clear();
    }

    // --- subscriptions ---

    public boolean toggleHolo(Player admin) {
        UUID uuid = admin.getUniqueId();
        if (viewers.contains(uuid)) {
            viewers.remove(uuid);
            for (TextDisplay d : holos.values()) {
                try {
                    admin.hideEntity(plugin, d);
                } catch (Exception ignored) {
                }
            }
            admin.sendMessage(plugin.configs.msg("holo-disabled"));
            return false;
        }
        viewers.add(uuid);
        showAllTo(admin);
        admin.sendMessage(plugin.configs.msg("holo-enabled"));
        return true;
    }

    public void enableHolo(Player admin) {
        if (!viewers.add(admin.getUniqueId())) return;
        showAllTo(admin);
    }

    /** Show every hologram to the viewer except the one above themselves. */
    public void showAllTo(Player viewer) {
        UUID self = viewer.getUniqueId();
        for (Map.Entry<UUID, TextDisplay> entry : holos.entrySet()) {
            if (entry.getKey().equals(self)) continue;
            try {
                viewer.showEntity(plugin, entry.getValue());
            } catch (Exception ignored) {
            }
        }
    }

    public boolean hasHolo(Player admin) {
        return viewers.contains(admin.getUniqueId());
    }

    public void handleQuit(Player player) {
        viewers.remove(player.getUniqueId());
        lastPos.remove(player.getUniqueId());
        lastTextVersion.remove(player.getUniqueId());
        TextDisplay d = holos.remove(player.getUniqueId());
        if (d != null) {
            try {
                if (!d.isDead()) d.remove();
            } catch (Exception ignored) {
            }
        }
    }

    /** Fresh join: viewers see everyone else's holograms, others see nothing. */
    public void syncJoin(Player player) {
        boolean see = viewers.contains(player.getUniqueId())
                && AlertManager.canReceiveHolo(player);
        if (see) {
            showAllTo(player);
            return;
        }
        for (TextDisplay d : holos.values()) {
            try {
                player.hideEntity(plugin, d);
            } catch (Exception ignored) {
            }
        }
    }

    // --- tick ---

    public int permRecheckClock;

    public void tick() {
        // Forget offline targets (their entities go with them).
        holos.entrySet().removeIf(entry -> {
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target != null && target.isOnline()) return false;
            lastPos.remove(entry.getKey());
            lastTextVersion.remove(entry.getKey());
            try {
                TextDisplay d = entry.getValue();
                if (d != null && !d.isDead()) d.remove();
            } catch (Exception ignored) {
            }
            return true;
        });
        // Viewers who lost the permission stop seeing holograms (LuckPerms etc.).
        if (++permRecheckClock >= 100) {
            permRecheckClock = 0;
            for (java.util.Iterator<UUID> it = viewers.iterator(); it.hasNext();) {
                UUID uuid = it.next();
                Player viewer = Bukkit.getPlayer(uuid);
                if (viewer != null && AlertManager.canReceiveHolo(viewer)) continue;
                if (viewer != null) {
                    for (TextDisplay d : holos.values()) {
                        try {
                            viewer.hideEntity(plugin, d);
                        } catch (Exception ignored) {
                        }
                    }
                }
                it.remove();
            }
        }
        if (viewers.isEmpty()) {
            // Nobody watches - drop entities instead of teleporting them.
            if (!holos.isEmpty()) {
                for (TextDisplay d : holos.values()) {
                    try {
                        if (d != null && !d.isDead()) d.remove();
                    } catch (Exception ignored) {
                    }
                }
                holos.clear();
            }
            return;
        }
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target == null || !target.isOnline()) continue;
            UUID id = target.getUniqueId();
            TextDisplay d = holos.get(id);
            boolean fresh = false;
            if (d == null || d.isDead()) {
                d = spawn(target);
                holos.put(id, d);
                fresh = true;
            }
            // Re-assert per-viewer visibility every tick.
            // When the admin client unloads the display (out of tracking
            // distance, chunk unload, world switch, respawn) and loads it
            // again, Bukkit does not always resend it without a fresh
            // showEntity - without this the holo stays invisible until
            // /pac holo is toggled off and on again.
            // spawn() already showed fresh entities, so skip them here.
            if (!fresh) {
                for (UUID viewerId : viewers) {
                    if (viewerId.equals(id)) continue;
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer == null || !viewer.isOnline()) continue;
                    if (!AlertManager.canReceiveHolo(viewer)) continue;
                    try {
                        viewer.showEntity(plugin, d);
                    } catch (Exception ignored) {
                    }
                }
            }
            // Teleport only when the target actually moved (AFK costs nothing).
            Location want = target.getLocation().add(0, HEIGHT_ABOVE, 0);
            Location prev = lastPos.get(id);
            if (prev == null || prev.getWorld() != want.getWorld()
                    || prev.distanceSquared(want) > TELEPORT_MIN_DIST_SQ) {
                try {
                    Scheduler.teleport(plugin, d, want);
                    lastPos.put(id, want.clone());
                } catch (Exception ignored) {
                    continue;
                }
            }
            // Rebuild the text only when new scores arrived.
            PlayerData data = plugin.players.get(id);
            int version = data != null ? data.probsVersion : -1;
            Integer sent = lastTextVersion.get(id);
            if (sent == null || sent.intValue() != version) {
                List<Double> history = data != null ? data.probHistorySnapshot()
                        : Collections.emptyList();
                try {
                    d.setText(ColorUtil.color(holoText(history)));
                    lastTextVersion.put(id, version);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public TextDisplay spawn(Player target) {
        // Consumer runs BEFORE the entity is added to the world/tracked,
        // so it is never visible to anyone until we explicitly show it.
        TextDisplay d = target.getWorld().spawn(
                target.getLocation().add(0, HEIGHT_ABOVE, 0), TextDisplay.class, entity -> {
            try {
                entity.setVisibleByDefault(false);
            } catch (Exception ignored) {
            }
        });
        d.setBillboard(Display.Billboard.CENTER);
        d.setAlignment(TextDisplay.TextAlignment.CENTER);
        d.setShadowed(true);
        d.setSeeThrough(false);
        try {
            d.setBrightness(new Display.Brightness(15, 15));
        } catch (Exception ignored) {
        }
        d.setTeleportDuration(3);
        PlayerData data = plugin.players.get(target.getUniqueId());
        List<Double> history = data != null ? data.probHistorySnapshot()
                : Collections.emptyList();
        d.setText(ColorUtil.color(holoText(history)));
        // Show only to authorized viewers (never to the target themselves).
        UUID self = target.getUniqueId();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean see = viewers.contains(viewer.getUniqueId())
                    && AlertManager.canReceiveHolo(viewer)
                    && !viewer.getUniqueId().equals(self);
            if (see) {
                try {
                    viewer.showEntity(plugin, d);
                } catch (Exception ignored) {
                }
            }
        }
        return d;
    }

    /** "0%, 1%, 1%, 3%, 0%" - each value colored green (legit) to saturated red. */
    public static String holoText(List<Double> history) {
        if (history == null || history.isEmpty()) return "&#666666···";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append("&#94A3B8, ");
            double p = Math.min(1.0, Math.max(0.0, history.get(i)));
            sb.append(AlertManager.probColor(p))
                    .append(String.format(Locale.ROOT, "%.0f%%", p * 100));
        }
        return sb.toString();
    }
}
