package ru.prime.anticheat.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Packets;

/**
 * Forwards every received packet to the registered {@link ru.prime.anticheat.check.PacketCheck}s.
 * Netty thread, MONITOR priority (observe only, never cancels).
 */
public class PacketCheckListener extends PacketListenerAbstract {

    public final PrimeAnticheat plugin;

    public PacketCheckListener(PrimeAnticheat plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            if (plugin.packetChecks.isEmpty()) return;
            Player player = Packets.playerOf(event);
            if (player == null) return;
            PlayerData data = plugin.players.get(player.getUniqueId());
            if (data == null) return; // early packets before the join event
            plugin.packetChecks.dispatch(event, player, data);
        } catch (Exception ignored) {
        }
    }
}
