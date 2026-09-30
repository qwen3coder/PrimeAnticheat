package ru.prime.anticheat.listener;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;

/**
 * Combat management for Aim ML:
 *  - player -> player hit opens the combat window (sequence ticks of collection);
 *  - teleport resets the processor and the window (coordinate jump is not aim).
 */
public class CombatListener implements Listener {

    public final PrimeAnticheat plugin;

    public CombatListener(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    /**
     * Cut outgoing damage while the attacker's AI score is high
     * (ai.damage-reduction). HIGH so the multiplier lands before MONITOR
     * observers and most other combat plugins finish with the event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageReduce(EntityDamageByEntityEvent e) {
        if (!plugin.configs.damageReductionEnabled) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        PlayerData data = plugin.players.get(attacker.getUniqueId());
        if (data == null) return;
        double mult = data.getDamageMultiplier(System.currentTimeMillis());
        if (mult >= 1.0) return;
        double reduced = e.getDamage() * mult;
        e.setDamage(reduced);
        if (plugin.configs.debug) {
            plugin.getLogger().info("[DamageReduce] " + attacker.getName()
                    + " dealt " + String.format(java.util.Locale.ROOT, "%.2f", reduced)
                    + " (x" + String.format(java.util.Locale.ROOT, "%.2f", mult) + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        // Crystal break: the entity is still alive here (packet checks verify it).
        if (e.getEntity() instanceof EnderCrystal crystal) {
            // Mitigation first: flagged players can't touch crystals at all.
            if (plugin.violations != null
                    && plugin.violations.isMitigated(attacker.getUniqueId())) {
                e.setCancelled(true);
                if (plugin.configs.debug) {
                    plugin.getLogger().info("[Mitigation] blocked crystal hit by "
                            + attacker.getName());
                }
                return;
            }
            try {
                if (plugin.crystalA != null) plugin.crystalA.onCrystalBreak(attacker, crystal);
            } catch (Exception ignored) {
            }
            try {
                if (plugin.crystalB != null) plugin.crystalB.onCrystalBreak(attacker);
            } catch (Exception ignored) {
            }
            try {
                if (plugin.crystalC != null) plugin.crystalC.onCrystalBreak(attacker);
            } catch (Exception ignored) {
            }
            try {
                if (plugin.crystalE != null) plugin.crystalE.onCrystalBreak(attacker);
            } catch (Exception ignored) {
            }
            return;
        }
        if (!(e.getEntity() instanceof Player)) return; // PvP only
        if (!plugin.configs.mlEnabled || !plugin.configs.aimEnabled) return;

        PlayerData data = plugin.players.get(attacker.getUniqueId());
        if (data == null) return;
        data.onAttack(plugin.configs.combatTimeMs);
        if (plugin.sessions != null) plugin.sessions.onAttack(attacker);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getItem() == null || e.getItem().getType() != Material.END_CRYSTAL) return;
        if (plugin.violations == null) return;
        if (!plugin.violations.isMitigated(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        if (plugin.configs.debug) {
            plugin.getLogger().info("[Mitigation] blocked crystal place by "
                    + e.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData data = plugin.players.get(e.getPlayer().getUniqueId());
        if (data == null) return;
        data.onTeleport();
    }
}
