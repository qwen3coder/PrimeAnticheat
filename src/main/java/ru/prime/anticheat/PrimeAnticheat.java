package ru.prime.anticheat;

import org.bukkit.plugin.java.JavaPlugin;
import ru.prime.anticheat.alert.AlertManager;
import ru.prime.anticheat.check.AICheck;
import ru.prime.anticheat.collector.DataRestorer;
import ru.prime.anticheat.collector.SessionManager;
import ru.prime.anticheat.command.PrimeCommand;
import ru.prime.anticheat.connect.ConnectManager;
import ru.prime.anticheat.inference.InferenceClient;
import ru.prime.anticheat.inference.InferenceEngine;
import ru.prime.anticheat.listener.CombatListener;
import ru.prime.anticheat.check.impl.crystal.CrystalA;
import ru.prime.anticheat.check.impl.crystal.CrystalB;
import ru.prime.anticheat.check.impl.crystal.CrystalC;
import ru.prime.anticheat.check.impl.crystal.CrystalE;
import ru.prime.anticheat.check.PacketChecks;
import ru.prime.anticheat.listener.ConnectionListener;
import ru.prime.anticheat.listener.PacketCheckListener;
import ru.prime.anticheat.listener.RotationPacketListener;
import ru.prime.anticheat.monitor.MonitorManager;
import ru.prime.anticheat.hologram.HologramManager;
import ru.prime.anticheat.gui.ViolatorsMenu;
import ru.prime.anticheat.violation.ViolationManager;
import ru.prime.anticheat.manager.CheckManager;
import ru.prime.anticheat.manager.ConfigManager;
import ru.prime.anticheat.manager.PlayerDataManager;

/**
 * Entry point. Minimal Bukkit: only lifecycle, listeners, scheduler.
 * All networking goes through PacketEvents (external plugin, depend in plugin.yml).
 */
public final class PrimeAnticheat extends JavaPlugin {

    public static PrimeAnticheat instance;

    // No getters/setters - direct access, as requested
    public ConfigManager configs;
    public PlayerDataManager players;
    public CheckManager checks;
    public AICheck aiCheck;
    public AlertManager alerts;
    public ViolationManager violations;
    public MonitorManager monitor;
    public HologramManager holos;
    public ViolatorsMenu violators;
    public InferenceEngine inference;
    public ConnectManager link;
    public SessionManager sessions;
    public DataRestorer restorer;

    public RotationPacketListener packetListener;
    public PacketCheckListener packetCheckListener;
    public PacketChecks packetChecks;
    public CrystalA crystalA;
    public CrystalB crystalB;
    public CrystalC crystalC;
    public CrystalE crystalE;

    @Override
    public void onEnable() {
        instance = this;

        configs = new ConfigManager(this);
        configs.loadAll();

        players = new PlayerDataManager();
        alerts = new AlertManager(this);
        violations = new ViolationManager(this);
        monitor = new MonitorManager(this);
        holos = new HologramManager(this);
        holos.start();
        violators = new ViolatorsMenu(this);
        violators.start();
        inference = new InferenceClient(configs.inferenceUrl, configs.inferenceToken,
                configs.inferenceDebug, configs.inferenceTimeoutSec, getLogger());
        link = new ConnectManager(this);
        aiCheck = new AICheck(this);
        sessions = new SessionManager(this);
        restorer = new DataRestorer(this);
        checks = new CheckManager(this);

        ConnectionListener joiner = new ConnectionListener(this);
        getServer().getPluginManager().registerEvents(joiner, this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(violators, this);

        packetListener = new RotationPacketListener(this);
        packetChecks = new PacketChecks(this);
        crystalA = new CrystalA(this);
        packetChecks.register(crystalA);
        crystalB = new CrystalB(this);
        packetChecks.register(crystalB);
        crystalC = new CrystalC(this);
        packetChecks.register(crystalC);
        crystalE = new CrystalE(this);
        packetChecks.register(crystalE);
        packetCheckListener = new PacketCheckListener(this);
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(packetListener);
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(packetCheckListener);

        checks.start();

        if (getCommand("primeanticheat") != null) {
            PrimeCommand cmd = new PrimeCommand(this);
            getCommand("primeanticheat").setExecutor(cmd);
            getCommand("primeanticheat").setTabCompleter(cmd);
        }

        // PlugMan /reload support: PlayerJoinEvent does not fire for players
        // already online, so run the full join setup for them here.
        int restored = 0;
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            try {
                joiner.setupPlayer(online);
                restored++;
            } catch (Exception e) {
                getLogger().warning("Failed to restore " + online.getName() + ": " + e.getMessage());
            }
        }
        if (restored > 0) getLogger().info("Restored " + restored + " online players after (re)load.");

        getLogger().info("PrimeAnticheat enabled. PacketEvents: "
                + com.github.retrooper.packetevents.PacketEvents.getAPI().getVersion());
    }

    @Override
    public void onDisable() {
        if (checks != null) checks.stop();
        if (monitor != null) monitor.stopAll();
        if (holos != null) holos.stopAll();
        if (violators != null) violators.stopAll();
        if (inference != null) inference.close();
        if (violations != null) violations.shutdown();
        if (sessions != null) sessions.stopAllSessions();

        if (packetListener != null) {
            try {
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .unregisterListener(packetListener);
            } catch (Exception ignored) {
            }
        }
        if (packetCheckListener != null) {
            try {
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .unregisterListener(packetCheckListener);
            } catch (Exception ignored) {
            }
        }

        if (players != null) players.clear();
        instance = null;
    }

    public static PrimeAnticheat get() {
        return instance;
    }
}
