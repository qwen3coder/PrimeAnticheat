package ru.prime.anticheat.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and dispatcher for {@link PacketCheck}s.
 * Empty registry = zero overhead (the listener bails out before resolving).
 */
public class PacketChecks {

    public final PrimeAnticheat plugin;

    public final List<PacketCheck> checks = new CopyOnWriteArrayList<>();

    public PacketChecks(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public void register(PacketCheck check) {
        if (check == null || checks.contains(check)) return;
        checks.add(check);
        try {
            check.reloadConfig();
        } catch (Throwable t) {
            try {
                plugin.getLogger().warning("Packet check '" + check.name
                        + "' reload threw: " + t);
            } catch (Exception ignored) {
            }
        }
    }

    public void unregister(PacketCheck check) {
        checks.remove(check);
    }

    public boolean isEmpty() {
        return checks.isEmpty();
    }

    /** Re-read every check's config (call on /pac reload, main thread). */
    public void reloadAll() {
        for (PacketCheck check : checks) {
            try {
                check.reloadConfig();
            } catch (Throwable t) {
                try {
                    plugin.getLogger().warning("Packet check '" + check.name
                            + "' reload threw: " + t);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Fan-out to every enabled check. One throwing check never breaks the others. */
    public void dispatch(PacketReceiveEvent event, Player player, PlayerData data) {
        for (PacketCheck check : checks) {
            try {
                if (!check.enabled) continue;
                check.onPacket(event, player, data);
            } catch (Throwable t) {
                try {
                    plugin.getLogger().warning("Packet check '" + check.name
                            + "' threw: " + t);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
