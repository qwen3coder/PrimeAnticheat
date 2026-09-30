package ru.prime.anticheat.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.DataSession;
import ru.prime.anticheat.data.Label;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.data.TickData;
import ru.prime.anticheat.util.ColorUtil;
import ru.prime.anticheat.util.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * /primeanticheat (/pac): reload + DataCollector + alerts/monitor/probs/holo/execution/status/violators/ipinfo/link.
 *
 * Fully hidden from regular players: Bukkit permission gate (visibility node,
 * auto-granted with any staff node), empty tab-complete, vanilla
 * "Unknown command" for everything, and no client "/" suggestions.
 * Every subcommand has its own permission node (primeanticheat.admin implies
 * them all); without any node the sender "does not see" the command.
 */
public class PrimeCommand implements CommandExecutor, TabCompleter {

    /** Vanilla unknown-command text - without our prefix, to stay hidden. */
    public static final String VANILLA_UNKNOWN = "Unknown command. Type \"/help\" for help.";

    public final PrimeAnticheat plugin;

    public PrimeCommand(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public String prefix() {
        return plugin.configs.prefixLine;
    }

    public String msg(String key, String... r) {
        return plugin.configs.msg(key, r);
    }

    /** Staff: admin or the alerts permission. Everyone else "does not see" the command. */
    public static boolean isStaff(CommandSender sender) {
        return has(sender, "admin") || has(sender, "alerts") || has(sender, "monitor")
                || has(sender, "probs") || has(sender, "holo") || has(sender, "status")
                || has(sender, "violators") || has(sender, "ipinfo")
                || has(sender, "datacollect") || has(sender, "link") || has(sender, "execute")
                || has(sender, "reload");
    }

    /** Node permission with admin fallback (admin implies every node). */
    public static boolean has(CommandSender sender, String node) {
        return sender.hasPermission("primeanticheat.admin")
                || sender.hasPermission("primeanticheat." + node);
    }

    /**
     * Bukkit-level visibility gate (plugin.yml permission). Granted automatically
     * to anyone with a staff node, so /help and execution never reach regular
     * players. Safe to call often (permission changes apply live).
     */
    public static final String VISIBILITY_NODE = "primeanticheat.command";

    public static final Map<UUID, PermissionAttachment> VISIBILITY = new ConcurrentHashMap<>();

    public static void syncVisibility(PrimeAnticheat plugin, Player player) {
        UUID uuid = player.getUniqueId();
        if (isStaff(player)) {
            if (VISIBILITY.containsKey(uuid)) return;
            try {
                VISIBILITY.put(uuid, player.addAttachment(plugin, VISIBILITY_NODE, true));
            } catch (Exception ignored) {
            }
            return;
        }
        PermissionAttachment att = VISIBILITY.remove(uuid);
        if (att != null) {
            try {
                player.removeAttachment(att);
            } catch (Exception ignored) {
            }
        }
    }

    public static void clearVisibility(Player player) {
        PermissionAttachment att = VISIBILITY.remove(player.getUniqueId());
        if (att != null) {
            try {
                player.removeAttachment(att);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isStaff(sender)) {
            sender.sendMessage(VANILLA_UNKNOWN);
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                return reload(sender);
            case "datacollect":
                if (!checkNode(sender, "datacollect")) return true;
                handleDataCollect(sender, args);
                return true;
            case "alerts":
                handleAlerts(sender);
                return true;
            case "probs":
                if (!checkNode(sender, "probs")) return true;
                handleProbs(sender);
                return true;
            case "holo":
                if (!checkNode(sender, "holo")) return true;
                handleHolo(sender);
                return true;
            case "monitor":
                handleMonitor(sender, args);
                return true;
            case "executecommand":
                if (!checkNode(sender, "execute")) return true;
                handleExecuteCommand(sender, args);
                return true;
            case "status":
                if (!checkNode(sender, "status")) return true;
                handleStatus(sender);
                return true;
            case "violators":
                if (!checkNode(sender, "violators")) return true;
                handleViolators(sender);
                return true;
            case "ipinfo":
                if (!checkNode(sender, "ipinfo")) return true;
                handleIpInfo(sender, args);
                return true;
            case "link":
                if (!checkNode(sender, "link")) return true;
                handleLink(sender, args);
                return true;
            default:
                sender.sendMessage(prefix() + msg("unknown-command", "{ARGS}", args[0]));
                sendUsage(sender);
                return true;
        }
    }

    /** Node gate with admin fallback: denies with no-permission otherwise. */
    public boolean checkNode(CommandSender sender, String node) {
        if (has(sender, node)) return true;
        sender.sendMessage(plugin.configs.msg("no-permission"));
        return false;
    }

    public boolean reload(CommandSender sender) {
        if (!checkNode(sender, "reload")) return true;
        plugin.configs.reloadAll();
        plugin.checks.start(); // pick up the new period
        if (plugin.violations != null) plugin.violations.reload(); // ladder + decay
        if (plugin.holos != null) plugin.holos.restart(); // hologram refresh period
        if (plugin.packetChecks != null) plugin.packetChecks.reloadAll(); // packet check configs
        if (plugin.inference != null) {
            plugin.inference.updateConfig(plugin.configs.inferenceUrl,
                    plugin.configs.inferenceToken, plugin.configs.inferenceDebug);
            plugin.inference.setTimeoutSec(plugin.configs.inferenceTimeoutSec);
        }
        sender.sendMessage(plugin.configs.msg("reloaded"));
        return true;
    }

    // --- alerts ---

    public void handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.configs.msg("player-only"));
            return;
        }
        Player player = (Player) sender;
        if (!has(player, "alerts")) {
            sender.sendMessage(plugin.configs.msg("no-permission"));
            return;
        }
        plugin.alerts.toggleAlerts(player);
    }

    // --- probs ---

    /** Probability history debug: toggle for admins (player only, like alerts). */
    public void handleProbs(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.configs.msg("player-only"));
            return;
        }
        plugin.alerts.toggleProbs((Player) sender);
    }

    // --- holo ---

    /** Probability holograms above heads: toggle for admins (player only). */
    public void handleHolo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.configs.msg("player-only"));
            return;
        }
        plugin.holos.toggleHolo((Player) sender);
    }

    // --- monitor ---

    public void handleMonitor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.configs.msg("player-only"));
            return;
        }
        Player admin = (Player) sender;
        if (!has(admin, "monitor")) {
            sender.sendMessage(plugin.configs.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("usage-monitor"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(prefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return;
        }
        plugin.monitor.toggle(admin, target);
    }

    // --- datacollect ---

    /** Dataset collection group: start/stop/status/restore (args shifted to the subcommand). */
    public void handleDataCollect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendDataCollectUsage(sender);
            return;
        }
        String[] sub = Arrays.copyOfRange(args, 1, args.length);
        switch (sub[0].toLowerCase()) {
            case "start":
                handleStart(sender, sub);
                return;
            case "stop":
                handleStop(sender, sub);
                return;
            case "status":
                handleDataStatus(sender);
                return;
            case "restore":
                handleRestore(sender, sub);
                return;
            default:
                sendDataCollectUsage(sender);
        }
    }

    public void sendDataCollectUsage(CommandSender sender) {
        sender.sendMessage(prefix() + msg("usage-datacollect"));
        sender.sendMessage(msg("usage-collect-start"));
        sender.sendMessage(msg("usage-collect-stop"));
        sender.sendMessage(msg("usage-collect-status"));
        sender.sendMessage(msg("usage-collect-restore"));
    }

    // --- start ---

    public void handleStart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(prefix() + msg("usage-collect-start"));
            return;
        }
        Label sessionLabel = Label.fromString(args[2]);
        if (sessionLabel == null) {
            sender.sendMessage(prefix() + msg("invalid-label", "{LABEL}", args[2]));
            sender.sendMessage(prefix() + msg("valid-labels"));
            return;
        }
        Player player = Bukkit.getPlayer(args[1]);
        if (player == null) {
            sender.sendMessage(prefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return;
        }
        plugin.sessions.startSession(player, sessionLabel, parseComment(args, 3));
        sender.sendMessage(prefix() + msg("session-started",
                "{LABEL}", sessionLabel.name(), "{COUNT}", "1"));
    }

    // --- stop ---

    public void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("usage-collect-stop"));
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = plugin.sessions.getActiveSessionCount();
            if (count == 0) {
                sender.sendMessage(prefix() + msg("no-sessions-to-stop"));
                return;
            }
            // CSV writes run async - the summary arrives when saving is done.
            plugin.sessions.stopAllSessionsAsync(saved -> sender.sendMessage(prefix()
                    + msg("all-sessions-stopped", "{COUNT}", String.valueOf(saved))));
            return;
        }
        Player player = Bukkit.getPlayer(args[1]);
        if (player != null) {
            if (!plugin.sessions.hasActiveSession(player)) {
                sender.sendMessage(prefix() + msg("no-sessions-to-stop"));
                return;
            }
            plugin.sessions.stopSessionAsync(player.getUniqueId(), saved -> {
                if (!saved) {
                    sender.sendMessage(prefix() + msg("session-save-fail"));
                    return;
                }
                sender.sendMessage(prefix() + msg("session-stopped", "{PLAYER}", player.getName()));
            });
            return;
        }
        // Quit player: stop by the saved session nickname
        DataSession offline = plugin.sessions.findByName(args[1]);
        if (offline != null) {
            String nick = offline.playerName;
            plugin.sessions.stopSessionAsync(offline.uuid, saved -> {
                if (!saved) {
                    sender.sendMessage(prefix() + msg("session-save-fail"));
                    return;
                }
                sender.sendMessage(prefix() + msg("session-stopped", "{PLAYER}", nick));
            });
            return;
        }
        sender.sendMessage(prefix() + msg("player-not-found", "{PLAYER}", args[1]));
    }

    // --- datastatus ---

    public void handleDataStatus(CommandSender sender) {
        int active = plugin.sessions.getActiveSessionCount();
        sender.sendMessage(prefix() + msg("data-status-header"));
        sender.sendMessage(msg("active-sessions", "{COUNT}", String.valueOf(active)));
        if (active > 0) {
            sender.sendMessage(ColorUtil.color("&#94A3B8Collecting players:"));
            for (DataSession s : plugin.sessions.getActiveSessions()) {
                Player p = Bukkit.getPlayer(s.uuid);
                String name = p != null ? p.getName() : s.playerName;
                sender.sendMessage(ColorUtil.color("&#58A8FF  " + name + " &#94A3B8[&#007AFF" + s.label.name() + "&#94A3B8]"
                        + (s.comment.isEmpty() ? "" : " \"" + s.comment + "\"")));
                sender.sendMessage(ColorUtil.color("&#94A3B8    Ticks: &#58A8FF" + s.getTickCount()
                        + " &#94A3B8| In combat: " + (s.isInCombat() ? "&#00FF00Yes" : "&#F43F5ENo")));
            }
        } else {
            sender.sendMessage(msg("no-active-sessions"));
            sender.sendMessage(msg("start-hint"));
        }
    }

    // --- restore (former falsepositive) ---

    public void handleRestore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("restore-usage"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(prefix() + msg("player-not-found", "{PLAYER}", args[1]));
            return;
        }
        PlayerData data = plugin.players.get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(prefix() + msg("restore-no-data"));
            return;
        }
        // CSV write runs async - the result arrives when saving is done.
        List<TickData> history = data.getTickHistory();
        String nick = target.getName();
        Scheduler.runAsync(plugin, () -> {
            boolean ok = plugin.restorer.restoreData(nick, history);
            Scheduler.run(plugin, () -> sender.sendMessage(
                    prefix() + msg(ok ? "restore-success" : "restore-fail")));
        });
    }

    // --- executecommand ---

    /** Execute a command as the punishment sender (its name from violation.sender-name). */
    public void handleExecuteCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("usage-executecommand"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(args[i]);
        }
        String command = sb.toString();
        while (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) {
            sender.sendMessage(prefix() + msg("usage-executecommand"));
            return;
        }
        if (plugin.violations == null || plugin.violations.sender == null) {
            sender.sendMessage(prefix() + msg("executecommand-fail", "{ERROR}", "sender not ready"));
            return;
        }
        String issuer = sender instanceof Player ? sender.getName() : "CONSOLE(" + sender.getName() + ")";
        plugin.getLogger().info("[Execute] " + issuer + " runs as '"
                + plugin.violations.sender.getName() + "': " + command);
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(plugin.violations.sender, command);
        } catch (Exception e) {
            sender.sendMessage(prefix() + msg("executecommand-fail", "{ERROR}",
                    e.getMessage() == null ? "error" : e.getMessage()));
            return;
        }
        sender.sendMessage(prefix() + msg(ok ? "executecommand-success" : "executecommand-unknown",
                "{COMMAND}", command));
    }

    // --- status ---

    /** Cloud inference link status. */
    public void handleStatus(CommandSender sender) {
        if (plugin.inference == null) {
            sender.sendMessage(prefix() + msg("status-api", "{STATE}", "&#F43F5ENot initialized"));
            return;
        }
        boolean up = plugin.inference.isReady();
        sender.sendMessage(prefix() + msg("status-api", "{STATE}",
                up ? "&#00FF00Connected" : "&#F43F5EDisconnected"));
        sender.sendMessage(msg("status-server", "{URL}", plugin.inference.getServerUrl()));
        sender.sendMessage(msg("status-traffic",
                "{SENT}", String.valueOf(plugin.inference.getSentRequests()),
                "{RECEIVED}", String.valueOf(plugin.inference.getReceivedResponses())));
    }

    // --- violators ---

    /** Violators GUI: online VL leaderboard for staff (player only). */
    public void handleViolators(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.configs.msg("player-only"));
            return;
        }
        plugin.violators.openGui((Player) sender);
    }

    // --- ipinfo (free geo + VPN/Proxy, console supported) ---

    /** /pac ipinfo <nick|ip>: provider, geo and VPN/Proxy verdict. */
    public void handleIpInfo(CommandSender sender, String[] args) {
        if (!plugin.configs.ipinfoEnabled) {
            sender.sendMessage(prefix() + msg("ipinfo-disabled"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + msg("usage-ipinfo"));
            return;
        }
        String query = args[1];
        String name;
        String ip;
        if (ru.prime.anticheat.ipinfo.IpInfoService.isIpLiteral(query)) {
            name = query;
            ip = query;
        } else {
            Player target = Bukkit.getPlayer(query);
            if (target == null) {
                sender.sendMessage(prefix() + msg("player-not-found", "{PLAYER}", query));
                return;
            }
            name = target.getName();
            try {
                java.net.InetSocketAddress addr = target.getAddress();
                if (addr == null || addr.getAddress() == null) {
                    sender.sendMessage(prefix() + msg("ipinfo-error", "{ERROR}", "no address"));
                    return;
                }
                ip = addr.getAddress().getHostAddress();
            } catch (Exception e) {
                sender.sendMessage(prefix() + msg("ipinfo-error", "{ERROR}", "no address"));
                return;
            }
        }
        if (ru.prime.anticheat.ipinfo.IpInfoService.isLocal(ip)) {
            sender.sendMessage(prefix() + msg("ipinfo-header", "{NAME}", name, "{IP}", ip));
            sender.sendMessage(msg("ipinfo-local"));
            return;
        }
        // Join-time snapshot: instant answer, no request at all.
        if (!ru.prime.anticheat.ipinfo.IpInfoService.isIpLiteral(query)) {
            Player online = Bukkit.getPlayer(query);
            if (online != null) {
                ru.prime.anticheat.ipinfo.IpInfoService.IpInfo ready =
                        ru.prime.anticheat.ipinfo.IpInfoService.playerInfo(online.getUniqueId());
                if (ready != null && ready.ip.equals(ip)) {
                    renderIpInfo(sender, name, ready);
                    return;
                }
            }
        }
        final String fName = name;
        final String fIp = ip;
        final String fQuery = query;
        sender.sendMessage(prefix() + msg("ipinfo-checking", "{IP}", fIp));
        Scheduler.runAsync(plugin, () -> {
            ru.prime.anticheat.ipinfo.IpInfoService.IpInfo info;
            try {
                info = ru.prime.anticheat.ipinfo.IpInfoService.lookup(fIp);
            } catch (Exception e) {
                String err = e.getMessage() == null ? "error" : e.getMessage();
                Scheduler.run(plugin, () -> sender.sendMessage(
                        prefix() + msg("ipinfo-error", "{ERROR}", err)));
                return;
            }
            final ru.prime.anticheat.ipinfo.IpInfoService.IpInfo fInfo = info;
            Scheduler.run(plugin, () -> {
                // Manual lookup fills the join snapshot, so repeats are instant.
                if (!ru.prime.anticheat.ipinfo.IpInfoService.isIpLiteral(fQuery)) {
                    Player online = Bukkit.getPlayer(fQuery);
                    if (online != null && fInfo.ip.equals(fIp)) {
                        ru.prime.anticheat.ipinfo.IpInfoService.rememberPlayer(
                                online.getUniqueId(), fInfo);
                    }
                }
                renderIpInfo(sender, fName, fInfo);
            });
        });
    }

    /** Shared chat rendering for join snapshots and live lookups. */
    public void renderIpInfo(CommandSender sender, String name,
                             ru.prime.anticheat.ipinfo.IpInfoService.IpInfo info) {
        sender.sendMessage(prefix() + msg("ipinfo-header",
                "{NAME}", name, "{IP}", info.ip));
        sender.sendMessage(msg("ipinfo-geo",
                "{COUNTRY}", info.country, "{REGION}", info.region,
                "{CITY}", info.city, "{ZIP}", info.zip));
        sender.sendMessage(msg("ipinfo-isp",
                "{ISP}", info.isp, "{ORG}", info.org));
        sender.sendMessage(msg("ipinfo-net",
                "{AS}", info.as, "{TZ}", info.timezone));
        if (info.vpnSuspect) {
            sender.sendMessage(msg("ipinfo-vpn-yes", "{REASON}", info.vpnReason));
        } else {
            sender.sendMessage(msg("ipinfo-vpn-no"));
        }
        sender.sendMessage(msg("ipinfo-source", "{SOURCE}", info.source));
    }

    // --- link (API binding: token only) ---
    public void handleLink(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendLinkUsage(sender);
            return;
        }
        String[] sub = Arrays.copyOfRange(args, 1, args.length);
        switch (sub[0].toLowerCase()) {
            case "token":
                if (sub.length < 2) {
                    sender.sendMessage(prefix() + msg("token-usage"));
                    return;
                }
                plugin.link.handleSetToken(sender, sub[1]);
                return;
            default:
                sendLinkUsage(sender);
        }
    }

    public void sendLinkUsage(CommandSender sender) {
        sender.sendMessage(prefix() + msg("usage-link"));
        sender.sendMessage(msg("usage-link-token"));
    }

    // --- common ---

    public String parseComment(String[] args, int start) {
        if (start >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(args[i]);
        }
        String comment = sb.toString();
        if (comment.startsWith("\"") && comment.endsWith("\"") && comment.length() >= 2) {
            comment = comment.substring(1, comment.length() - 1);
        } else if (comment.startsWith("\"")) {
            comment = comment.substring(1);
        }
        return comment.trim();
    }

    public void sendUsage(CommandSender sender) {
        sender.sendMessage(prefix() + msg("usage-header"));
        sender.sendMessage(msg("usage-datacollect"));
        sender.sendMessage(msg("usage-alerts"));
        sender.sendMessage(msg("usage-monitor"));
        sender.sendMessage(msg("usage-probs"));
        sender.sendMessage(msg("usage-holo"));
        sender.sendMessage(msg("usage-executecommand"));
        sender.sendMessage(msg("usage-status"));
        sender.sendMessage(msg("usage-violators"));
        sender.sendMessage(msg("usage-ipinfo"));
        sender.sendMessage(msg("usage-link"));
        sender.sendMessage(msg("usage-reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Non-staff do not even see subcommand names
        if (!isStaff(sender)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            // Everyone only sees what they may actually run.
            List<String> subs = new ArrayList<>();
            if (has(sender, "datacollect")) subs.add("datacollect");
            if (has(sender, "alerts")) subs.add("alerts");
            if (has(sender, "monitor")) subs.add("monitor");
            if (has(sender, "probs")) subs.add("probs");
            if (has(sender, "holo")) subs.add("holo");
            if (has(sender, "execute")) subs.add("executecommand");
            if (has(sender, "status")) subs.add("status");
            if (has(sender, "violators")) subs.add("violators");
            if (has(sender, "ipinfo")) subs.add("ipinfo");
            if (has(sender, "link")) subs.add("link");
            if (has(sender, "reload")) subs.add("reload");
            out.addAll(filter(subs, args[0]));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("datacollect") || args[0].equalsIgnoreCase("link"))) {
            if (args[0].equalsIgnoreCase("datacollect")) {
                out.addAll(filter(List.of("start", "stop", "status", "restore"), args[1]));
            } else {
                out.addAll(filter(List.of("token"), args[1]));
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("monitor") && has(sender, "monitor")) {
                out.addAll(filter(onlineNames(), args[1]));
            } else if (sub.equals("ipinfo") && has(sender, "ipinfo")) {
                out.addAll(filter(onlineNames(), args[1]));
            } else if (sub.equals("executecommand") && has(sender, "execute")) {
                out.addAll(filter(onlineNames(), args[1]));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("datacollect") && has(sender, "datacollect")) {
            String sub = args[1].toLowerCase();
            if (sub.equals("start") || sub.equals("stop") || sub.equals("restore")) {
                List<String> targets = new ArrayList<>(onlineNames());
                if (sub.equals("stop")) targets.add("all");
                out.addAll(filter(targets, args[2]));
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("datacollect")
                && args[1].equalsIgnoreCase("start") && has(sender, "datacollect")) {
            out.addAll(filter(
                    Arrays.stream(Label.values()).map(Label::name).collect(Collectors.toList()), args[3]));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("datacollect")
                && args[1].equalsIgnoreCase("start") && has(sender, "datacollect")) {
            if (args[4].isEmpty() || args[4].startsWith("\"")) out.add("\"comment\"");
        }
        return out;
    }

    public List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    public static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }
}
