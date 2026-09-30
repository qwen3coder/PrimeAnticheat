package ru.prime.anticheat.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.command.PrimeCommand;
import ru.prime.anticheat.ipinfo.IpInfoService;
import ru.prime.anticheat.util.Scheduler;

/**
 * Bukkit minimum: creation/removal of PlayerData.
 */
public class ConnectionListener implements Listener {

    public final PrimeAnticheat plugin;

    public ConnectionListener(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    /**
     * Login-time IP check (this event is already async, so blocking lookup
     * is fine): resolve once, cache for the whole session, and enforce the
     * ASN blacklist by cancelling the join. Lookup failures fail open.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!plugin.configs.ipinfoEnabled) return;
        java.net.InetAddress addr = e.getAddress();
        String ip = addr == null ? null : addr.getHostAddress();
        if (ip == null || IpInfoService.isLocal(ip)) return;
        boolean checkOnJoin = plugin.configs.ipinfoCheckOnJoin;
        java.util.List<String> blacklist = plugin.configs.ipinfoAsnBlacklist;
        boolean enforce = blacklist != null && !blacklist.isEmpty();
        if (!checkOnJoin && !enforce) return;
        IpInfoService.IpInfo info;
        try {
            info = IpInfoService.lookup(ip);
        } catch (Exception ex) {
            plugin.getLogger().warning("IP lookup failed for " + ip + ": "
                    + (ex.getMessage() == null ? "error" : ex.getMessage()));
            return;
        }
        IpInfoService.rememberPlayer(e.getUniqueId(), info);
        if (enforce && IpInfoService.matchesBlacklist(info, blacklist)) {
            String name = e.getName();
            String kick = plugin.configs.msg("ipinfo-asn-blocked",
                    "{PLAYER}", name, "{IP}", info.ip,
                    "{ASN}", info.as, "{ORG}", info.org);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kick);
            IpInfoService.forgetPlayer(e.getUniqueId());
            plugin.getLogger().warning("Blocked login: " + name + " (" + info.ip
                    + ", " + info.as + ", " + info.org + ") - blacklisted ASN");
            if (plugin.alerts != null) {
                Scheduler.run(plugin, () -> plugin.alerts.broadcast(plugin.configs.msg(
                        "ipinfo-asn-denied", "{PLAYER}", name,
                        "{IP}", info.ip, "{ASN}", info.as, "{ORG}", info.org)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        setupPlayer(e.getPlayer());
    }

    /**
     * Full join setup: PlayerData + staff subscriptions + hologram visibility.
     * Also called for everyone already online in onEnable, because neither
     * /reload nor PlugMan fire PlayerJoinEvent (without this the plugin would
     * silently ignore all online players after a reload).
     */
    public void setupPlayer(org.bukkit.entity.Player player) {
        plugin.players.getOrCreate(player);
        // Bukkit-level visibility first: without it /help and execution leak.
        PrimeCommand.syncVisibility(plugin, player);
        // Enable alerts for staff right away
        if (player.hasPermission("primeanticheat.alerts")
                || player.hasPermission("primeanticheat.admin")) {
            plugin.alerts.enableAlerts(player);
        }
        // Probs debug and holograms follow their own nodes (admin implies them).
        if (player.hasPermission("primeanticheat.admin")
                || player.hasPermission("primeanticheat.probs")) {
            plugin.alerts.enableProbs(player);
        }
        if (plugin.holos != null) {
            if (player.hasPermission("primeanticheat.admin")
                    || player.hasPermission("primeanticheat.holo")) {
                plugin.holos.enableHolo(player);
            }
            plugin.holos.syncJoin(player);
        }
        // IP snapshot fallback for players already online on reload/PlugMan
        // load (no pre-login fired for them): cached lookups cost no request.
        if (plugin.configs.ipinfoEnabled && plugin.configs.ipinfoCheckOnJoin
                && IpInfoService.playerInfo(player.getUniqueId()) == null) {
            String ip = addressOf(player);
            java.util.UUID uuid = player.getUniqueId();
            Scheduler.runAsync(plugin, () -> IpInfoService.prefetch(ip, uuid));
        }
    }

    /** Raw address string or null (offline proxies, local test servers). */
    public static String addressOf(org.bukkit.entity.Player player) {
        try {
            java.net.InetSocketAddress addr = player.getAddress();
            if (addr == null || addr.getAddress() == null) return null;
            return addr.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Hide /pac from client-side "/" suggestions for anyone without a staff
     * node (vanilla clients get the command tree separately from tab-complete).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (PrimeCommand.isStaff(e.getPlayer())) return;
        stripRawCommands(e.getCommands());
    }

    /** Remove our command and aliases from a command-name collection. */
    public static void stripRawCommands(java.util.Collection<String> commands) {
        commands.remove("primeanticheat");
        commands.remove("pac");
        commands.remove("primeac");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        PrimeCommand.clearVisibility(e.getPlayer());
        plugin.players.remove(e.getPlayer());
        if (plugin.packetListener != null) plugin.packetListener.handleQuit(e.getPlayer());
        if (plugin.sessions != null) plugin.sessions.removeAimProcessor(e.getPlayer().getUniqueId());
        if (plugin.alerts != null) plugin.alerts.handleQuit(e.getPlayer());
        if (plugin.monitor != null) plugin.monitor.handleQuit(e.getPlayer());
        if (plugin.holos != null) plugin.holos.handleQuit(e.getPlayer());
        if (plugin.violators != null) plugin.violators.handleQuit(e.getPlayer());
        if (plugin.violations != null) plugin.violations.handleQuit(e.getPlayer());
        IpInfoService.forgetPlayer(e.getPlayer().getUniqueId());
    }
}
