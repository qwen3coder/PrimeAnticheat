package ru.prime.anticheat.check.impl.crystal;

import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.check.PacketCheck;
import ru.prime.anticheat.data.PlayerData;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalC: inhuman break timings (metronome patterns).
 *
 * <p>Human clicks jitter by tens of milliseconds; automation hits with machine
 * precision. When the last N non-zero break intervals fit inside max_spread_ms
 * (and the pace is combat-like, not idle), it flags through the shared VL
 * pipeline. Same-millisecond multi-hits (sweep attacks) are skipped, not counted.
 *
 * <p>Runs fully on the main thread (damage event) - no scheduler hops.
 *
 * <p>config.yml:
 * <pre>
 * checks:
 *   crystalc:
 *     enabled: true
 *     window: 8
 *     max_spread_ms: 30
 *     max_mean_ms: 500
 *     cooldown_ms: 1000
 * </pre>
 */
public class CrystalC extends PacketCheck {

  public volatile int window = 7;
  public volatile long maxSpreadMs = 55;
  public volatile long maxMeanMs = 600;
  public volatile long cooldownMs = 0;

  /** Player -> recent break timestamps (ms). */
  public final Map<UUID, ArrayDeque<Long>> breakTimes = new ConcurrentHashMap<>();

  public CrystalC(PrimeAnticheat plugin) {
    super(plugin, "CrystalC");
  }

  @Override
  public void onPacket(com.github.retrooper.packetevents.event.PacketReceiveEvent event,
                       Player player, PlayerData data) {
    // Timing source is the damage event, not packets.
  }

  @Override
  protected void onReload() {
    window = Math.max(3, cfgInt("window", 7));
    maxSpreadMs = Math.max(0, cfgLong("max_spread_ms", 55));
    maxMeanMs = Math.max(0, cfgLong("max_mean_ms", 600));
    cooldownMs = cfgLong("cooldown_ms", 0);
  }

  /**
   * Pure verdict (unit-tested): the last `window` non-zero intervals between
   * consecutive breaks fit into maxSpreadMs with a combat-like mean.
   */
  public static boolean verdict(ArrayDeque<Long> times, int window, long maxSpreadMs, long maxMeanMs) {
    return ru.prime.anticheat.check.Timing.metronome(times, window, maxSpreadMs, maxMeanMs);
  }

  /** Crystal break (main thread). */
  public void onCrystalBreak(Player attacker) {
    if (!enabled) return;
    UUID uuid = attacker.getUniqueId();
    ArrayDeque<Long> times = breakTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
    long now = System.currentTimeMillis();
    times.addLast(now);
    while (times.size() > window + 1) times.pollFirst();
    if (!verdict(times, window, maxSpreadMs, maxMeanMs)) return;
    PlayerData data;
    try {
      data = plugin.players.get(uuid);
    } catch (Exception e) {
      return;
    }
    if (data == null) return;
    tryFlag(attacker, data, cooldownMs, "perfect break timings");
  }

  @Override
  public void handleQuit(UUID uuid) {
    super.handleQuit(uuid);
    breakTimes.remove(uuid);
  }
}
