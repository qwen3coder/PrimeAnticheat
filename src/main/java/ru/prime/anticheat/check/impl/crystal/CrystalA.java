package ru.prime.anticheat.check.impl.crystal;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.check.PacketCheck;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Packets;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalA: end crystal place and attack inside the same server tick.
 *
 * <p>Netty only records packet data (place position, ticks); entity and
 * inventory reads run on the main thread in a follow-up task. State is
 * per-player (shared instance serves everyone).
 *
 * <p>config.yml:
 * <pre>
 * checks:
 *   crystala:
 *     enabled: true
 *     cooldown_ms: 1000
 * </pre>
 */
public class CrystalA extends PacketCheck {

  public volatile long cooldownMs = 1000;

  /** Player -> tick of the last block place. */
  public final Map<UUID, Integer> placeTick = new ConcurrentHashMap<>();
  /** Player -> placed block coords. */
  public final Map<UUID, int[]> placePos = new ConcurrentHashMap<>();

  public CrystalA(PrimeAnticheat plugin) {
    super(plugin, "CrystalA");
  }

  @Override
  protected void onReload() {
    cooldownMs = cfgLong("cooldown_ms", 1000);
  }

  /**
   * Pure verdict (unit-tested): same tick, broken entity is a crystal
   * within 1 block XZ / 2 blocks Y of the placed block.
   */
  public static boolean verdict(int placeTick, int attackTick, boolean isCrystal,
                         int[] placePos, int[] crystalPos) {
    if (placeTick != attackTick || !isCrystal) return false;
    return Math.abs(crystalPos[0] - placePos[0]) <= 1
        && Math.abs(crystalPos[1] - placePos[1]) <= 2
        && Math.abs(crystalPos[2] - placePos[2]) <= 1;
  }

  @Override
  public void onPacket(PacketReceiveEvent event, Player player, PlayerData data) {
    UUID uuid = player.getUniqueId();
    if (Packets.isBlockPlace(event)) {
      WrapperPlayClientPlayerBlockPlacement wrapper;
      try {
        wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
      } catch (Exception e) {
        return;
      }
      int tick;
      try {
        tick = player.getTicksLived();
      } catch (Exception e) {
        return;
      }
      placeTick.put(uuid, tick);
      placePos.put(uuid, new int[]{
          wrapper.getBlockPosition().getX(),
          wrapper.getBlockPosition().getY(),
          wrapper.getBlockPosition().getZ()});
      return;
    }

    if (!Packets.isInteractEntity(event)) return;
    return;
  }

  /**
   * Crystal break (main thread, entity still alive in the event).
   * Matches a same-tick place nearby and flags through the shared pipeline.
   */
  public void onCrystalBreak(Player attacker, EnderCrystal crystal) {
    if (!enabled) return;
    UUID uuid = attacker.getUniqueId();
    Integer placed = placeTick.get(uuid);
    int[] pos = placePos.get(uuid);
    if (placed == null || pos == null) return;
    int nowTick;
    try {
      nowTick = attacker.getTicksLived();
    } catch (Exception e) {
      return;
    }
    Material held;
    try {
      held = attacker.getInventory().getItemInMainHand().getType();
      if (held != Material.END_CRYSTAL) {
        held = attacker.getInventory().getItemInOffHand().getType();
      }
    } catch (Exception e) {
      return;
    }
    if (held != Material.END_CRYSTAL) return;
    int[] crystalPos;
    try {
      crystalPos = new int[]{crystal.getLocation().getBlockX(),
          crystal.getLocation().getBlockY(),
          crystal.getLocation().getBlockZ()};
    } catch (Exception e) {
      return;
    }
    if (!verdict(placed, nowTick, true, pos, crystalPos)) return;
    PlayerData data;
    try {
      data = plugin.players.get(uuid);
    } catch (Exception e) {
      return;
    }
    if (data == null) return;
    tryFlag(attacker, data, cooldownMs, "Place + attack in same tick");
  }

  @Override
  public void handleQuit(UUID uuid) {
    super.handleQuit(uuid);
    placeTick.remove(uuid);
    placePos.remove(uuid);
  }
}
