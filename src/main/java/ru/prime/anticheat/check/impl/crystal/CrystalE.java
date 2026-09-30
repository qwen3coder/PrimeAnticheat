package ru.prime.anticheat.check.impl.crystal;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.check.PacketCheck;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.util.Packets;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * CrystalE: breaks without swings (NoSwing automation).
 *
 * <p>Every legit crystal break is preceded by an arm swing in the same moment.
 * Cheats that suppress swing packets break crystals "out of thin air". A streak
 * of such breaks flags through the shared VL pipeline. Same-tick sweep clusters
 * share one swing and reset the streak instead.
 *
 * <p>Swings are recorded on Netty, breaks evaluated on the main thread
 * (damage event); both structures are concurrent.
 *
 * <p>config.yml:
 * <pre>
 * checks:
 *   crystale:
 *     enabled: true
 *     swing_match_ms: 150
 *     max_unswung: 3
 *     cooldown_ms: 2000
 * </pre>
 */
public class CrystalE extends PacketCheck {

  public volatile long swingMatchMs = 150;
  public volatile int maxUnswung = 3;
  public volatile long cooldownMs = 0;

  /** Player -> swing timestamps (ms), Netty side. */
  public final Map<UUID, Deque<Long>> swingTimes = new ConcurrentHashMap<>();
  /** Player -> consecutive breaks with no swing nearby. */
  public final Map<UUID, Integer> unswungStreak = new ConcurrentHashMap<>();

  public CrystalE(PrimeAnticheat plugin) {
    super(plugin, "CrystalE");
  }

  @Override
  public void onPacket(PacketReceiveEvent event, Player player, PlayerData data) {
    if (!Packets.isSwing(event)) return;
    Deque<Long> swings = swingTimes.computeIfAbsent(player.getUniqueId(),
        k -> new ConcurrentLinkedDeque<>());
    long now = System.currentTimeMillis();
    swings.addLast(now);
    prune(swings, now, SWING_KEEP_MS);
  }

  @Override
  protected void onReload() {
    swingMatchMs = Math.max(0, cfgLong("swing_match_ms", 150));
    maxUnswung = Math.max(1, cfgInt("max_unswung", 3));
    cooldownMs = cfgLong("cooldown_ms", 0);
  }

  /** Memory bound for swing history (scoreboard, not detection). */
  public static final long SWING_KEEP_MS = 60000;

  /** Drop timestamps older than the horizon. */
  static void prune(Deque<Long> times, long nowMs, long horizonMs) {
    while (!times.isEmpty() && nowMs - times.peekFirst() > horizonMs) times.pollFirst();
  }

  /**
   * Pure (unit-tested): is there a swing within matchMs before now?
   * Read-only - never mutates the deque.
   */
  public static boolean hasRecentSwing(Deque<Long> swings, long nowMs, long matchMs) {
    Long last = swings.peekLast();
    return last != null && nowMs - last <= matchMs;
  }

  /** Crystal break (main thread). */
  public void onCrystalBreak(Player attacker) {
    if (!enabled) return;
    UUID uuid = attacker.getUniqueId();
    long now = System.currentTimeMillis();
    Deque<Long> swings = swingTimes.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
    // NoSwing: break with no swing nearby - streak it (sweep clusters share
    // one swing and reset the streak instead).
    if (hasRecentSwing(swings, now, swingMatchMs)) {
      unswungStreak.remove(uuid);
      return;
    }
    int streak = unswungStreak.getOrDefault(uuid, 0) + 1;
    unswungStreak.put(uuid, streak);
    if (streak < maxUnswung) return;
    PlayerData data;
    try {
      data = plugin.players.get(uuid);
    } catch (Exception e) {
      return;
    }
    if (data == null) return;
    tryFlag(attacker, data, cooldownMs, "break without swing x" + streak);
  }

  @Override
  public void handleQuit(UUID uuid) {
    super.handleQuit(uuid);
    swingTimes.remove(uuid);
    unswungStreak.remove(uuid);
  }
}
