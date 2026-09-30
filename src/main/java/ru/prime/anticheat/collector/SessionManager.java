package ru.prime.anticheat.collector;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.DataSession;
import ru.prime.anticheat.data.Label;
import ru.prime.anticheat.math.AimProcessor;
import ru.prime.anticheat.util.Scheduler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Dataset collection session manager. Each player has their own AimProcessor
 * (separate from the combat one in PlayerData), reused between sessions,
 * removed on quit.
 */
public class SessionManager {

    public final PrimeAnticheat plugin;

    public final Map<UUID, DataSession> activeSessions = new ConcurrentHashMap<>();
    public final Map<UUID, AimProcessor> playerAimProcessors = new ConcurrentHashMap<>();

    public volatile String currentSessionFolder = null;

    public SessionManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public AimProcessor getOrCreateAimProcessor(UUID playerId) {
        return playerAimProcessors.computeIfAbsent(playerId, id -> new AimProcessor());
    }

    public void removeAimProcessor(UUID playerId) {
        playerAimProcessors.remove(playerId);
    }

    /** Session start; if one was active - save and close it first (async, fire-and-forget). */
    public DataSession startSession(Player player, Label label, String comment) {
        if (hasActiveSession(player)) stopSessionAsync(player.getUniqueId(), saved -> { });
        DataSession session = new DataSession(
                player.getUniqueId(), player.getName(), label, comment,
                getOrCreateAimProcessor(player.getUniqueId()));
        activeSessions.put(player.getUniqueId(), session);
        plugin.getLogger().info("Started data collection for " + player.getName() + " [" + label + "]");
        return session;
    }

    public void stopSession(Player player) {
        stopSession(player.getUniqueId());
    }

    public void stopSession(UUID playerId) {
        DataSession session = activeSessions.remove(playerId);
        if (session == null) return;
        try {
            session.saveAndClose(plugin.getDataFolder(), currentSessionFolder);
            plugin.getLogger().info("Saved " + session.getTickCount()
                    + " ticks for " + session.playerName);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save session for " + session.playerName, e);
        }
    }

    /**
     * Same as stopSession, but the CSV write runs async (never freezes the
     * main thread on big sessions). The callback runs on the MAIN thread with
     * true when the file was saved (false = no session or IO error).
     */
    public void stopSessionAsync(UUID playerId, Consumer<Boolean> done) {
        DataSession session = activeSessions.remove(playerId);
        if (session == null) {
            done.accept(false);
            return;
        }
        File dataFolder = plugin.getDataFolder();
        String folder = currentSessionFolder;
        Scheduler.runAsync(plugin, () -> {
            boolean saved = false;
            try {
                session.saveAndClose(dataFolder, folder);
                plugin.getLogger().info("Saved " + session.getTickCount()
                        + " ticks for " + session.playerName);
                saved = true;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to save session for " + session.playerName, e);
            }
            boolean result = saved;
            Scheduler.run(plugin, () -> done.accept(result));
        });
    }

    /**
     * Async variant of stopAllSessions for commands (disable keeps the sync one).
     * The callback runs on the MAIN thread with the number of saved sessions.
     */
    public void stopAllSessionsAsync(Consumer<Integer> done) {
        List<DataSession> sessions = new ArrayList<>(activeSessions.values());
        for (DataSession s : sessions) activeSessions.remove(s.uuid, s);
        if (sessions.isEmpty()) {
            done.accept(0);
            return;
        }
        File dataFolder = plugin.getDataFolder();
        String folder = currentSessionFolder;
        currentSessionFolder = null;
        Scheduler.runAsync(plugin, () -> {
            int saved = 0;
            for (DataSession session : sessions) {
                try {
                    session.saveAndClose(dataFolder, folder);
                    plugin.getLogger().info("Saved " + session.getTickCount()
                            + " ticks for " + session.playerName);
                    saved++;
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to save session for " + session.playerName, e);
                }
            }
            int result = saved;
            Scheduler.run(plugin, () -> done.accept(result));
        });
    }

    public void stopAllSessions() {
        for (UUID id : activeSessions.keySet().toArray(new UUID[0])) {
            stopSession(id);
        }
        currentSessionFolder = null;
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public boolean hasActiveSession(UUID playerId) {
        return activeSessions.containsKey(playerId);
    }

    public DataSession getSession(UUID playerId) {
        return activeSessions.get(playerId);
    }

    public DataSession getSession(Player player) {
        return activeSessions.get(player.getUniqueId());
    }

    /** Session lookup by nickname (needed to stop a quit player). */
    public DataSession findByName(String name) {
        for (DataSession s : activeSessions.values()) {
            if (s.playerName.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    public Collection<DataSession> getActiveSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public void onAttack(Player player) {
        DataSession session = activeSessions.get(player.getUniqueId());
        if (session != null) session.onAttack();
    }

    public void onTick(Player player, float yaw, float pitch) {
        DataSession session = activeSessions.get(player.getUniqueId());
        if (session != null) session.processTick(yaw, pitch);
    }

    /** Folder where sessions are saved (usually plugins/PrimeAnticheat/data). */
    public File getDataDir() {
        File dir = new File(plugin.getDataFolder(), "data");
        if (currentSessionFolder != null && !currentSessionFolder.isEmpty()) {
            dir = new File(dir, currentSessionFolder);
        }
        return dir;
    }
}
