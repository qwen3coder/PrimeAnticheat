package ru.prime.anticheat.manager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.*;

public class ConfigMigrationTest {

    static YamlConfiguration yaml(String s) {
        return YamlConfiguration.loadConfiguration(new StringReader(s));
    }

    static final String BUNDLED = ""
            + "config-version: 2\n"
            + "prefix: \"NEW\"\n"
            + "inference:\n"
            + "  token: \"\"\n"
            + "  timeout-sec: 30\n"
            + "brand-new-key: true\n";

    static final String DISK = ""
            + "config-version: 1\n"
            + "prefix: \"MINE\"\n"
            + "inference:\n"
            + "  token: \"secret\"\n"
            + "  timeout-sec: 99\n"
            + "checks:\n"
            + "  aim:\n"
            + "    punishments:\n"
            + "      3: \"kick %player%\"\n";

    @Test
    public void detectsVersionBumpAndMissingKeys() {
        assertTrue(ConfigManager.needsMigration(yaml(DISK), yaml(BUNDLED),
                new YamlConfiguration(), new YamlConfiguration()));
        assertFalse(ConfigManager.needsMigration(yaml(BUNDLED), yaml(BUNDLED),
                new YamlConfiguration(), null));
        // Same version but a key deleted by hand -> self-heal.
        assertTrue(ConfigManager.needsMigration(yaml("config-version: 2\n"),
                yaml(BUNDLED), new YamlConfiguration(), null));
    }

    @Test
    public void mergeKeepsSettingsAndAddsNewKeys() {
        YamlConfiguration out = ConfigManager.merge(yaml(DISK), yaml(BUNDLED));
        // User values survive (token, prefix, custom ladder, old version is replaced).
        assertEquals("MINE", out.getString("prefix"));
        assertEquals("secret", out.getString("inference.token"));
        assertEquals(99, out.getInt("inference.timeout-sec"));
        assertEquals("kick %player%", out.getString("checks.aim.punishments.3"));
        // New defaults arrive, version follows the bundle.
        assertEquals(true, out.getBoolean("brand-new-key"));
        assertEquals(2, out.getInt("config-version"));
    }

    @Test
    public void countsMissingKeys() {
        assertEquals(1, ConfigManager.countMissing(yaml(DISK), yaml(BUNDLED)));
        assertEquals(0, ConfigManager.countMissing(yaml(BUNDLED), yaml(BUNDLED)));
    }

    static final String BUNDLED_LADDER = ""
            + "config-version: 2\n"
            + "checks:\n"
            + "  aim:\n"
            + "    enabled: true\n"
            + "    punishments:\n"
            + "      3: \"kick %player%\"\n"
            + "      6: \"ban %player%\"\n";

    @Test
    public void customLadderReplacesBundledWholesale() {
        // The reported case: user ladder {999999} must not gain default rungs.
        YamlConfiguration disk = yaml("config-version: 1\nchecks:\n  aim:\n"
                + "    punishments:\n      999999: \"ban %player% KillAura\"\n");
        YamlConfiguration out = ConfigManager.merge(disk, yaml(BUNDLED_LADDER));
        assertNull(out.getString("checks.aim.punishments.3"));
        assertNull(out.getString("checks.aim.punishments.6"));
        assertEquals("ban %player% KillAura", out.getString("checks.aim.punishments.999999"));
        assertEquals(java.util.List.of("aim"),
                ConfigManager.customLadders(disk, yaml(BUNDLED_LADDER)));
    }

    @Test
    public void untouchedLadderFollowsBundle() {
        // Old defaults on disk, bundle adds rung 9 -> rung arrives.
        YamlConfiguration disk = yaml("config-version: 1\nchecks:\n  aim:\n"
                + "    punishments:\n      3: \"kick %player%\"\n      6: \"ban %player%\"\n");
        YamlConfiguration bundled = yaml((BUNDLED_LADDER
                + "      9: \"mute %player%\"\n").replace("config-version: 2", "config-version: 3"));
        YamlConfiguration out = ConfigManager.merge(disk, bundled);
        assertEquals("kick %player%", out.getString("checks.aim.punishments.3"));
        assertEquals("mute %player%", out.getString("checks.aim.punishments.9"));
        assertTrue(ConfigManager.customLadders(disk, bundled).isEmpty());
    }

    @Test
    public void changedRungCommandCountsAsCustom() {
        YamlConfiguration disk = yaml("config-version: 1\nchecks:\n  aim:\n"
                + "    punishments:\n      3: \"warn %player%\"\n      6: \"ban %player%\"\n");
        YamlConfiguration out = ConfigManager.merge(disk, yaml(BUNDLED_LADDER));
        assertEquals("warn %player%", out.getString("checks.aim.punishments.3"));
        assertEquals(java.util.List.of("aim"),
                ConfigManager.customLadders(disk, yaml(BUNDLED_LADDER)));
    }

    // --- text migration: existing lines stay byte-identical ---

    static final String TEXT_BUNDLED = ""
            + "prefix: \"NEW\"\n"
            + "\n"
            + "# Schema version.\n"
            + "config-version: 2\n"
            + "\n"
            + "# Aim window.\n"
            + "ai:\n"
            + "  sequence: 120\n"
            + "  # Brand new option.\n"
            + "  new-opt: true\n"
            + "\n"
            + "checks:\n"
            + "  aim:\n"
            + "    enabled: true\n"
            + "    punishments:\n"
            + "      3: \"kick %player%\"\n"
            + "      6: \"ban %player%\"\n"
            + "\n"
            + "# Whole new section.\n"
            + "newsec:\n"
            + "  flag: true\n"
            + "  items:\n"
            + "    - a\n"
            + "    - b\n";

    static final String TEXT_DISK = ""
            + "# My own header comment.\n"
            + "prefix: \"MINE\"\n"
            + "\n"
            + "ai:\n"
            + "  sequence: 60\n"
            + "\n"
            + "checks:\n"
            + "  aim:\n"
            + "    enabled: true\n"
            + "    punishments:\n"
            + "      999999: \"ban %player% KillAura\"\n"
            + "\n"
            + "ipinfo:\n"
            + "  # keep my flow style below\n"
            + "  asn-blacklist: [\"Cloudflare\"]\n";

    static String migrateTextFixture() {
        YamlConfiguration disk = yaml(TEXT_DISK);
        YamlConfiguration bundled = yaml(TEXT_BUNDLED);
        java.util.List<String> missing = ConfigManager.insertableMissing(disk, bundled);
        return ConfigManager.migrateText(disk, bundled, TEXT_DISK, TEXT_BUNDLED, missing, 2);
    }

    @Test
    public void textMergeKeepsExistingLinesVerbatim() {
        String out = migrateTextFixture();
        // Own comment, own values, flow-style list, custom ladder: untouched.
        assertTrue(out.contains("# My own header comment.\n"));
        assertTrue(out.contains("prefix: \"MINE\"\n"));
        assertTrue(out.contains("  sequence: 60\n"));
        assertTrue(out.contains("  asn-blacklist: [\"Cloudflare\"]\n"));
        assertTrue(out.contains("      999999: \"ban %player% KillAura\"\n"));
        assertFalse(out.contains("kick %player%"));
        // No ladder rungs were inserted for the customized ladder.
        YamlConfiguration verify = yaml(out);
        assertNull(verify.getString("checks.aim.punishments.3"));
    }

    @Test
    public void textMergeInsertsMissingKeysWithComments() {
        String out = migrateTextFixture();
        // New key lands inside its section, with the bundled comment.
        assertTrue(out.contains("  # Brand new option.\n  new-opt: true\n"));
        // Whole missing section appended at the end, with comments and list items.
        assertTrue(out.contains("# Whole new section.\nnewsec:\n  flag: true\n"
                + "  items:\n    - a\n    - b\n"));
        // Version bumped (was absent on disk).
        assertTrue(out.contains("config-version: 2"));
        YamlConfiguration verify = yaml(out);
        assertEquals(true, verify.getBoolean("ai.new-opt"));
        assertEquals("b", verify.getStringList("newsec.items").get(1));
    }

    @Test
    public void textMergeResultParsesAndCoversMissing() {
        YamlConfiguration disk = yaml(TEXT_DISK);
        YamlConfiguration bundled = yaml(TEXT_BUNDLED);
        java.util.List<String> missing = ConfigManager.insertableMissing(disk, bundled);
        assertFalse(missing.isEmpty());
        YamlConfiguration verify = yaml(migrateTextFixture());
        for (String k : missing) {
            assertTrue("still missing: " + k, verify.contains(k));
        }
    }

    @Test
    public void ladderRungsExcludedFromInsertable() {
        YamlConfiguration disk = yaml(TEXT_DISK);
        YamlConfiguration bundled = yaml(TEXT_BUNDLED);
        java.util.List<String> missing = ConfigManager.insertableMissing(disk, bundled);
        for (String k : missing) {
            assertFalse("ladder rung must not be inserted: " + k,
                    k.startsWith("checks.aim.punishments"));
        }
    }
}
