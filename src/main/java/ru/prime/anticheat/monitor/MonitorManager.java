package ru.prime.anticheat.monitor;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.prime.anticheat.util.Scheduler;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.ColorUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /pac monitor - live target tracking in the admin hotbar:
 * ◉ prob%  ⚑ vl  ▰ buf%  (without labels, icons only).
 *
 * Repeating the command stops it. All tasks are sync (hotbar is the main thread).
 */
public class MonitorManager {

    /** prob/buffer color thresholds: >=80 negative, >=50 orange, >=30 primary, otherwise positive. */
    public static final String COLOR_HIGH = "&#F43F5E";
    public static final String COLOR_MID = "&#FFA500";
    public static final String COLOR_LOW = "&#58A8FF";
    public static final String COLOR_OK = "&#00FF00";

    public static final String VL_ICON = "&#007AFF";
    public static final String VL_VALUE = "&#58A8FF";

    public final PrimeAnticheat plugin;

    public final Map<UUID, UUID> tracking = new ConcurrentHashMap<>();
    public final Map<UUID, Scheduler.Task> tasks = new ConcurrentHashMap<>();

    public MonitorManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    /** Tracking toggle. true = started tracking. */
    public boolean toggle(Player admin, Player target) {
        if (tracking.containsKey(admin.getUniqueId())) {
            stop(admin.getUniqueId());
            admin.sendMessage(plugin.configs.prefixLine + plugin.configs.msg("monitor-stopped"));
            return false;
        }
        start(admin, target);
        admin.sendMessage(plugin.configs.prefixLine
                + plugin.configs.msg("monitor-started", "{PLAYER}", target.getName()));
        return true;
    }

    public void start(Player admin, Player target) {
        stop(admin.getUniqueId());
        tracking.put(admin.getUniqueId(), target.getUniqueId());
        tasks.put(admin.getUniqueId(), Scheduler.timer(plugin, () -> {
            tick(admin.getUniqueId(), target.getUniqueId(), target.getName());
        }, 0L, 10L));
    }

    public void tick(UUID adminId, UUID targetId, String targetName) {
        Player admin = Bukkit.getPlayer(adminId);
        if (admin == null || !admin.isOnline()) {
            stop(adminId);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            sendActionBar(admin, ColorUtil.color("&#F43F5E" + targetName + " &#94A3B8offline"));
            stop(adminId);
            return;
        }
        PlayerData data = plugin.players.get(targetId);
        if (data == null) {
            sendActionBar(admin, ColorUtil.color("&#58A8FF" + targetName + " &#94A3B8no data"));
            return;
        }
        int vl = plugin.violations.getViolationLevel(targetId);
        sendActionBar(admin, format(data.name, data.lastProbability, vl, data.getBuffer()));
    }

    /** Hotbar line: colored icons + values, without "% to flag" texts. */
    public String format(String name, double probability, int vl, double buffer) {
        int probPct = (int) Math.round(probability * 100);
        double flag = plugin.configs.bufferFlag;
        int bufPct = buffer <= 0 || flag <= 0 ? 0
                : (int) Math.min(100, Math.round(buffer / flag * 100));
        String probColor = levelColor(probPct);
        String bufColor = levelColor(bufPct);
        return ColorUtil.color(probColor + "◉ " + probPct + "%  "
                + VL_ICON + "⚑ " + VL_VALUE + vl + "  "
                + bufColor + "▰ " + bufPct + "%");
    }

    public static String levelColor(int pct) {
        if (pct >= 80) return COLOR_HIGH;
        if (pct >= 50) return COLOR_MID;
        if (pct >= 30) return COLOR_LOW;
        return COLOR_OK;
    }

    public static void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    public void stop(UUID adminId) {
        tracking.remove(adminId);
        Scheduler.Task task = tasks.remove(adminId);
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    public void stopAll() {
        for (UUID adminId : tasks.keySet().toArray(new UUID[0])) {
            stop(adminId);
        }
        tracking.clear();
    }

    /** Admin quit - stop tracking (target quit is handled by the next tick). */
    public void handleQuit(Player player) {
        stop(player.getUniqueId());
    }
}
