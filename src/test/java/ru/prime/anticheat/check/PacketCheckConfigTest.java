package ru.prime.anticheat.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import ru.prime.anticheat.data.PlayerData;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PacketCheckConfigTest {

    static class CfgCheck extends PacketCheck {
        final org.bukkit.configuration.ConfigurationSection fake;
        boolean reloaded = false;

        CfgCheck(org.bukkit.configuration.ConfigurationSection fake) {
            super(null, "CrystalPlace");
            this.fake = fake;
        }

        public void onPacket(PacketReceiveEvent e, Player p, PlayerData d) {
        }

        protected org.bukkit.configuration.ConfigurationSection checkConfig() {
            return fake;
        }

        protected void onReload() {
            reloaded = true;
        }

        public int pInt(String k, int d) { return cfgInt(k, d); }
        public long pLong(String k, long d) { return cfgLong(k, d); }
        public double pDouble(String k, double d) { return cfgDouble(k, d); }
        public boolean pBool(String k, boolean d) { return cfgBoolean(k, d); }
        public String pStr(String k, String d) { return cfgString(k, d); }
        public List<String> pList(String k) { return cfgStringList(k); }
    }

    @Test
    public void keySanitized() {
        assertEquals("crystalplace", new CfgCheck(new MemoryConfiguration()).key());
    }

    @Test
    public void typedReadsAndReload() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("enabled", false);
        cfg.set("cooldown_ms", 500);
        cfg.set("max_reach", 4.5);
        cfg.set("mode", "strict");
        cfg.set("tags", List.of("a", "b"));
        cfg.set("garbage", "abc");
        CfgCheck c = new CfgCheck(cfg);
        c.reloadConfig();
        assertTrue(c.reloaded);
        assertFalse(c.enabled);
        assertEquals(500L, c.pLong("cooldown_ms", 0));
        assertEquals(4.5, c.pDouble("max_reach", 0), 1e-9);
        assertEquals("strict", c.pStr("mode", ""));
        assertEquals(List.of("a", "b"), c.pList("tags"));
        assertEquals(500, c.pInt("cooldown_ms", 0));
    }

    @Test
    public void missingAndGarbageDefault() {
        CfgCheck c = new CfgCheck(new MemoryConfiguration());
        c.reloadConfig();
        assertTrue(c.enabled);
        assertEquals(7, c.pInt("nope", 7));
        assertEquals(8L, c.pLong("nope", 8L));
        assertEquals(9.5, c.pDouble("nope", 9.5), 1e-9);
        assertFalse(c.pBool("nope", false));
        assertEquals("d", c.pStr("nope", "d"));
        assertTrue(c.pList("nope").isEmpty());
        assertEquals(7, c.pInt("garbage", 7));
    }

    @Test
    public void nullSectionDefaults() {
        assertEquals(1, PacketCheck.cfgInt(null, "x", 1));
        assertEquals(2L, PacketCheck.cfgLong(null, "x", 2L));
        assertEquals(3.5, PacketCheck.cfgDouble(null, "x", 3.5), 1e-9);
        assertFalse(PacketCheck.cfgBoolean(null, "x", false));
        assertEquals("d", PacketCheck.cfgString(null, "x", "d"));
        assertTrue(PacketCheck.cfgStringList(null, "x").isEmpty());
    }
}
