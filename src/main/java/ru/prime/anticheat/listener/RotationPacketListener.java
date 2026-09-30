package ru.prime.anticheat.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Packets;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only network layer. For every flying packet with rotation it computes
 * TickData (delta/accel/jerk/gcd) and accumulates the window. Blocks nothing.
 *
 *  - cancelled packets are processed too (another plugin may have cancelled
 *    movement, but we still need the player's mouse input);
 *  - 1.17.x duplicate (pos+rot with the same position) is skipped.
 */
public class RotationPacketListener extends PacketListenerAbstract {

    public static final double DUPLICATE_POS_THRESHOLD_SQ = 1.0E-7D * 1.0E-7D;

    public final PrimeAnticheat plugin;

    public final Map<UUID, Vector3d> lastPos = new ConcurrentHashMap<>();
    public final Map<UUID, Boolean> lastGround = new ConcurrentHashMap<>();

    public RotationPacketListener(PrimeAnticheat plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

            Player player;
            try {
                player = (Player) event.getPlayer();
            } catch (ClassCastException e) {
                return;
            }
            if (player == null) return;

            WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);

            if (is117Duplicate(player, packet)) return;
            updateMovementState(player, packet);

            if (!packet.hasRotationChanged()) return;

            float yaw = packet.getLocation().getYaw();
            float pitch = packet.getLocation().getPitch();

            PlayerData data = plugin.players.get(player.getUniqueId());
            if (data == null) return; // early packets before the join event

            data.processTick(yaw, pitch, plugin.configs.sequence);
            data.lastPacketTime = System.currentTimeMillis();
            data.totalRotations++;

            // Dataset collection session (own AimProcessor, ticks only in combat)
            if (plugin.sessions != null) plugin.sessions.onTick(player, yaw, pitch);
        } catch (Exception ignored) {
        }
    }

    /** Clients 1.17-1.21 send an extra pos+rot with the same position - this is not mouse movement. */
    public boolean is117Duplicate(Player player, WrapperPlayClientPlayerFlying packet) {
        if (!packet.hasPositionChanged() || !packet.hasRotationChanged()) return false;

        ClientVersion v = Packets.clientVersion(player);
        if (v == null) return false;
        if (v.isOlderThan(ClientVersion.V_1_17)
                || v.isNewerThanOrEquals(ClientVersion.V_1_21)) return false;

        Vector3d prev = lastPos.get(player.getUniqueId());
        Boolean prevGround = lastGround.get(player.getUniqueId());
        if (prev == null || prevGround == null) return false;
        if (packet.isOnGround() != prevGround) return false;
        return prev.distanceSquared(packet.getLocation().getPosition()) < DUPLICATE_POS_THRESHOLD_SQ;
    }

    public void updateMovementState(Player player, WrapperPlayClientPlayerFlying packet) {
        if (!packet.hasPositionChanged()) return;
        lastPos.put(player.getUniqueId(), packet.getLocation().getPosition());
        lastGround.put(player.getUniqueId(), packet.isOnGround());
    }

    public void handleQuit(Player player) {
        if (player == null) return;
        lastPos.remove(player.getUniqueId());
        lastGround.remove(player.getUniqueId());
        Packets.forget(player.getUniqueId());
    }
}
