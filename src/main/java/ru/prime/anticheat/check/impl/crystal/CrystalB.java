package ru.prime.anticheat.check.impl.crystal;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.check.PacketCheck;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Packets;
import ru.prime.anticheat.util.Scheduler;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalB: inhuman crystal place/break rate in a sliding window.
 *
 * <p>Legit crystal PvP separates actions by ping and reaction time; sustained
 * bursts above the limit are automation. Single-window hits feed the shared
 * VL pipeline (with cooldown), so only repetition punishes - lag bursts decay.
 *
 * <p>Netty only records packet kinds; crystal and item verification runs on
 * the main thread in a follow-up task. State is per-player.
 *
 * <p>config.yml:
 * <pre>
 * checks:
 *   crystalb:
 *     enabled: true
 *     window_ms: 1000
 *     max_actions: 8
 *     cooldown_ms: 1000
 * </pre>
 */
public class CrystalB extends PacketCheck {

  public volatile long windowMs = 300;
  public volatile int maxActions = 5;
  public volatile long cooldownMs = 0;

  /** Player -> verified action timestamps (ms), pruned to the window. */
  public final Map<UUID, ArrayDeque<Long>> actionTimes = new ConcurrentHashMap<>();

  public CrystalB(PrimeAnticheat plugin) {
    super(plugin, "CrystalB");
  }

  @Override
  protected void onReload() {
    windowMs = cfgLong("window_ms", 300);
    maxActions = cfgInt("max_actions", 5);
    cooldownMs = cfgLong("cooldown_ms", 0);
  }

  /**
   * Pure verdict (unit-tested): prune to (now - windowMs, now], then over limit?
   */
  public static boolean overLimit(ArrayDeque<Long> times, long nowMs, long windowMs, int maxActions) {
    while (!times.isEmpty() && nowMs - times.peekFirst() > windowMs) times.pollFirst();
    return times.size() > maxActions;
  }

  @Override
  public void onPacket(PacketReceiveEvent event, Player player, PlayerData data) {
    UUID uuid = player.getUniqueId();
    if (!Packets.isBlockPlace(event)) return;
    int entityId = -1;
    // Verification (items, entities) must run on the main thread.
    Scheduler.run(plugin, () -> {
      try {
        Player online = Bukkit.getPlayer(uuid);
        if (online == null || !online.isOnline()) return;
        if (online.getInventory().getItemInMainHand().getType() != Material.END_CRYSTAL
            && online.getInventory().getItemInOffHand().getType() != Material.END_CRYSTAL) return;
        recordAction(uuid);
      } catch (Exception ignored) {
      }
    });
  }

  /**
   * Crystal break (main thread, call from the damage event - by the time a
   * follow-up task would run, the crystal entity is already dead and gone).
   */
  public void onCrystalBreak(Player attacker) {
    if (!enabled) return;
    recordAction(attacker.getUniqueId());
  }

  /** Prune to the window, flag when over the limit. Main thread only. */
  public void recordAction(UUID uuid) {
    ArrayDeque<Long> times = actionTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
    long now = System.currentTimeMillis();
    times.addLast(now);
    if (!overLimit(times, now, windowMs, maxActions)) return;
    PlayerData data;
    Player online;
    try {
      online = Bukkit.getPlayer(uuid);
      data = plugin.players.get(uuid);
    } catch (Exception e) {
      return;
    }
    if (online == null || data == null) return;
    tryFlag(online, data, cooldownMs, "crystal actions too fast");
  }

  @Override
  public void handleQuit(UUID uuid) {
    super.handleQuit(uuid);
    actionTimes.remove(uuid);
  }
}
