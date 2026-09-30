package ru.prime.anticheat.penalty;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
/**
 * Punishment ladder VL -> command. The command of the highest threshold
 * that is <= the current VL is taken (exact match wins, otherwise the rung below).
 */
public final class PunishmentLadder {

    public final TreeMap<Integer, String> rungs;

    public PunishmentLadder(Map<Integer, String> commands) {
        this.rungs = new TreeMap<>(commands == null ? Collections.emptyMap() : commands);
    }

    /** Parse a punishments section (VL -> command); skips bad keys, never null. */
    public static TreeMap<Integer, String> parseRungs(ConfigurationSection section) {
        TreeMap<Integer, String> rungs = new TreeMap<>();
        if (section == null) return rungs;
        for (String key : section.getKeys(false)) {
            try {
                String cmd = section.getString(key);
                if (cmd != null && !cmd.isEmpty()) rungs.put(Integer.parseInt(key.trim()), cmd);
            } catch (NumberFormatException ignored) {
            }
        }
        return rungs;
    }

    public boolean isEmpty() {
        return rungs.isEmpty();
    }

    public Optional<String> commandFor(int vl) {
        Map.Entry<Integer, String> rung = rungs.floorEntry(vl);
        return rung == null ? Optional.empty() : Optional.of(rung.getValue());
    }

    public Optional<Integer> maxThreshold() {
        return rungs.isEmpty() ? Optional.empty() : Optional.of(rungs.lastKey());
    }

    public Optional<String> maxCommand() {
        return rungs.isEmpty() ? Optional.empty() : Optional.of(rungs.lastEntry().getValue());
    }
}
