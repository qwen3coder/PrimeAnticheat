package ru.prime.anticheat.penalty;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class PunishmentLadderTest {

    private static PunishmentLadder ladder() {
        TreeMap<Integer, String> rungs = new TreeMap<>();
        rungs.put(3, "kick %player%");
        rungs.put(6, "ban %player%");
        return new PunishmentLadder(rungs);
    }

    @Test
    public void emptyLadderFiresNothing() {
        assertFalse(new PunishmentLadder(null).commandFor(100).isPresent());
    }

    @Test
    public void belowMinimumFiresNothing() {
        assertFalse(ladder().commandFor(2).isPresent());
    }

    @Test
    public void exactAndFloorThresholds() {
        assertEquals("kick %player%", ladder().commandFor(3).orElse(null));
        assertEquals("kick %player%", ladder().commandFor(5).orElse(null));
        assertEquals("ban %player%", ladder().commandFor(6).orElse(null));
    }

    @Test
    public void aboveMaximumSticks() {
        assertEquals("ban %player%", ladder().commandFor(100).orElse(null));
    }

    @Test
    public void parseRungsSkipsGarbage() {
        MemoryConfiguration sec = new MemoryConfiguration();
        sec.set("3", "kick %player%");
        sec.set("6", "ban %player%");
        sec.set("soon", "nothing");
        sec.set("empty", "");
        TreeMap<Integer, String> rungs = PunishmentLadder.parseRungs(sec);
        assertEquals(Map.of(3, "kick %player%", 6, "ban %player%"), rungs);
        assertTrue(PunishmentLadder.parseRungs(null).isEmpty());
    }

    @Test
    public void shippedConfigHasAllLadders() {
        File resource = new File("src/main/resources/config.yml");
        assertTrue(resource.isFile());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(resource);
        for (String check : new String[]{"aim", "crystala", "crystalb", "crystalc",
                "crystale"}) {
            TreeMap<Integer, String> rungs = PunishmentLadder.parseRungs(
                    yaml.getConfigurationSection("checks." + check + ".punishments"));
            assertFalse("no ladder: " + check, rungs.isEmpty());
        }
    }
}
