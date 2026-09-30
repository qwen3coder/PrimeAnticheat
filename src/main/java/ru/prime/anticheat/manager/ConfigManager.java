package ru.prime.anticheat.manager;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.util.ColorUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Two configs: config.yml and messages.yml.
 * No encapsulation: fields are read directly (cfg.threshold etc.).
 */
public class ConfigManager {

    public final PrimeAnticheat plugin;

    public FileConfiguration config;
    public FileConfiguration messages;

    // Cache of frequent values. volatile: read from Netty/async threads,
    // written in reload from the main thread - without volatile stale values are visible after /pac reload.
    public volatile String prefix;
    /** Prefix + " » " separator for chat lines. */
    public volatile String prefixLine;
    public volatile boolean debug;

    public volatile boolean mlEnabled;
    public volatile int checkPeriodTicks;

    // --- Cloud inference (inference.url/token/debug) ---
    public volatile String inferenceUrl;
    public volatile String inferenceToken;
    public volatile boolean inferenceDebug;
    public volatile int inferenceTimeoutSec;

    // --- Aim ML window and buffer
    public volatile int sequence;
    public volatile int step;
    /** How many ms after a hit a player is considered in combat (send window). */
    public volatile long combatTimeMs;
    public volatile double alertThreshold;
    public volatile double bufferFlag;
    public volatile double bufferResetOnFlag;
    public volatile double bufferMultiplier;
    public volatile double bufferDecrease;
    public volatile double bufferDecreaseThreshold;

    // --- damage reduction after N consecutive high AI scores (ai.damage-reduction) ---
    public volatile boolean damageReductionEnabled;
    public volatile double damageReductionThreshold;
    public volatile int damageReductionConsecutive;
    public volatile double damageReductionMultiplier;
    public volatile long damageReductionDurationMs;

    // --- VL / ladder ---
    // Punishment ladders per check: checks.<name>.punishments (case-insensitive).
    public final java.util.Map<String, java.util.TreeMap<Integer, String>> ladders =
            new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public volatile long punishmentCooldownMs;
    /** Punishment sender name (colored, default = prefix). */
    public volatile String punishSenderName;
    /** Ladder commands run as the custom named sender; off = plain console. */
    public volatile boolean customExecutor;
    public volatile boolean vlDecayEnabled;
    public volatile int vlDecayIntervalSec;
    public volatile int vlDecayAmount;

    // --- mitigation (instant crystal lockout while flagged) ---
    public volatile boolean mitigationEnabled;
    public volatile java.util.List<String> mitigationChecks;
    public volatile long mitigationLockoutMs;

    // --- alerts ---
    public volatile boolean alertConsole;
    public volatile boolean alertSoundEnabled;
    public volatile String alertSoundType;
    public volatile float alertSoundVolume;
    public volatile float alertSoundPitch;

    // --- holograms ---
    /** Hologram position/text refresh period in ticks (1 = every tick). */
    public volatile int hologramPeriodTicks;

    // --- ipinfo (login/command lookup, optional ipinfo.io token, ASN blacklist) ---
    public volatile boolean ipinfoEnabled;
    public volatile boolean ipinfoCheckOnJoin;
    public volatile int ipinfoTimeoutSec;
    public volatile int ipinfoCacheMinutes;
    public volatile String ipinfoToken;
    /** Immutable snapshot, reloaded with the config (read from async threads). */
    public volatile java.util.List<String> ipinfoAsnBlacklist = java.util.List.of();

    public volatile boolean aimEnabled;
    public volatile String bypassPermission;

    /** Permission-based bypass; "none"/empty = check disabled (nobody bypasses, not even OP). */
    public boolean hasBypass(Player player) {
        if (player == null || !bypassActive(bypassPermission)) return false;
        return player.hasPermission(bypassPermission);
    }

    public static boolean bypassActive(String perm) {
        if (perm == null) return false;
        String clean = perm.trim();
        return !clean.isEmpty() && !clean.equalsIgnoreCase("none");
    }

    public ConfigManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig(); // config.yml from resources
        saveDefault("messages.yml");
        reloadAll();
    }

    public void reloadAll() {
        migrateIfNeeded();
        plugin.reloadConfig();
        config = plugin.getConfig();

        File msgFile = new File(plugin.getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(msgFile);

        prefix = color(config.getString("prefix", "&#0079FFP&#0A7EFFr&#1484FFi&#1D89FFm&#278EFFe"));
        prefixLine = prefix + color(" &8» ");
        debug = config.getBoolean("debug", false);

        mlEnabled = config.getBoolean("ml.enabled", true);
        checkPeriodTicks = Math.max(5, config.getInt("ml.check-period-ticks", 10));

        inferenceUrl = config.getString("inference.url", "");
        inferenceToken = config.getString("inference.token", "");
        inferenceDebug = config.getBoolean("inference.debug", false);
        inferenceTimeoutSec = Math.max(5, config.getInt("inference.timeout-sec", 30));

        sequence = Math.min(200, Math.max(5, config.getInt("ai.sequence", 120)));
        step = Math.max(1, config.getInt("ai.step", 30));
        combatTimeMs = Math.max(500, config.getInt("ai.combat-time-ms", 2000));
        alertThreshold = config.getDouble("ai.alert-threshold", 0.5);
        bufferFlag = config.getDouble("ai.buffer.flag", 50.0);
        bufferResetOnFlag = config.getDouble("ai.buffer.reset-on-flag", 25.0);
        bufferMultiplier = config.getDouble("ai.buffer.multiplier", 100.0);
        bufferDecrease = config.getDouble("ai.buffer.decrease", 0.25);
        bufferDecreaseThreshold = config.getDouble("ai.buffer.decrease-threshold", 0.10);
        damageReductionEnabled = config.getBoolean("ai.damage-reduction.enabled", false);
        damageReductionThreshold = config.getDouble("ai.damage-reduction.threshold", 0.9);
        damageReductionConsecutive = Math.max(1, config.getInt("ai.damage-reduction.consecutive", 3));
        damageReductionMultiplier = config.getDouble("ai.damage-reduction.multiplier", 0.0);
        damageReductionDurationMs = Math.max(0, config.getLong("ai.damage-reduction.duration-ms", 5000));

        aimEnabled = config.getBoolean("checks.aim.enabled", true);
        bypassPermission = config.getString("bypass-permission", "primeanticheat.bypass");

        ladders.clear();
        org.bukkit.configuration.ConfigurationSection checksSection =
                config.getConfigurationSection("checks");
        if (checksSection != null) {
            for (String name : checksSection.getKeys(false)) {
                java.util.TreeMap<Integer, String> rungs =
                        ru.prime.anticheat.penalty.PunishmentLadder.parseRungs(
                                checksSection.getConfigurationSection(name + ".punishments"));
                if (!rungs.isEmpty()) ladders.put(name, rungs);
            }
        }

        punishmentCooldownMs = Math.max(0, config.getInt("violation.punishment-cooldown-sec", 5)) * 1000L;
        punishSenderName = color(config.getString("violation.sender-name", "%prefix%").replace("%prefix%", prefix));
        customExecutor = config.getBoolean("violation.custom-executor", true);
        vlDecayEnabled = config.getBoolean("violation.vl-decay.enabled", true);
        vlDecayIntervalSec = Math.max(5, config.getInt("violation.vl-decay.interval-sec", 60));
        vlDecayAmount = Math.max(1, config.getInt("violation.vl-decay.amount", 1));
        mitigationEnabled = config.getBoolean("mitigation.enabled", true);
        mitigationChecks = config.getStringList("mitigation.checks");
        if (mitigationChecks == null || mitigationChecks.isEmpty()) {
            mitigationChecks = java.util.List.of("CrystalA", "CrystalB", "CrystalC");
        }
        mitigationLockoutMs = Math.max(0, config.getLong("mitigation.lockout_ms", 1500));

        alertConsole = config.getBoolean("alerts.console", true);
        alertSoundEnabled = config.getBoolean("alerts.sound.enabled", true);
        alertSoundType = config.getString("alerts.sound.type", "BLOCK_NOTE_BLOCK_PLING");
        alertSoundVolume = (float) config.getDouble("alerts.sound.volume", 1.0);
        alertSoundPitch = (float) config.getDouble("alerts.sound.pitch", 1.0);
        hologramPeriodTicks = Math.max(1, config.getInt("holograms.update-period-ticks", 1));
        ipinfoEnabled = config.getBoolean("ipinfo.enabled", true);
        ipinfoCheckOnJoin = config.getBoolean("ipinfo.check-on-join", true);
        ipinfoTimeoutSec = Math.max(2, config.getInt("ipinfo.timeout-sec", 5));
        ipinfoCacheMinutes = Math.max(1, config.getInt("ipinfo.cache-minutes", 60));
        ipinfoToken = config.getString("ipinfo.token", "");
        if (ipinfoToken == null) ipinfoToken = "";
        ru.prime.anticheat.ipinfo.IpInfoService.configure(ipinfoTimeoutSec, ipinfoCacheMinutes, ipinfoToken);
        try {
            ipinfoAsnBlacklist = java.util.List.copyOf(config.getStringList("ipinfo.asn-blacklist"));
        } catch (Exception e) {
            ipinfoAsnBlacklist = java.util.List.of();
        }
    }

    // --- auto-update: version compare -> backup -> merge, never lose settings ---

    /** Keep the last N backups per file, delete the oldest. */
    public static final int MAX_BACKUPS = 10;

    /**
     * Compares the disk files with the bundled defaults (config-version first,
     * then missing keys). On difference: backs the old files up to
     * {@code backups/}, adds only the missing lines (with bundled comments -
     * existing lines, comments, styles and ladders are never touched) and
     * logs to console.
     *
     * @return true when something was migrated.
     */
    public boolean migrateIfNeeded() {
        try {
            File dir = plugin.getDataFolder();
            File cfgFile = new File(dir, "config.yml");
            if (!cfgFile.exists()) return false; // fresh install, defaults just copied
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(cfgFile);
            YamlConfiguration bundled = loadBundled("config.yml");
            if (bundled == null) return false;
            File msgFile = new File(dir, "messages.yml");
            YamlConfiguration diskMsg = msgFile.exists()
                    ? YamlConfiguration.loadConfiguration(msgFile) : new YamlConfiguration();
            YamlConfiguration bundledMsg = loadBundled("messages.yml");
            if (!needsMigration(disk, bundled, diskMsg, bundledMsg)) return false;

            int diskVer = disk.getInt("config-version", 0);
            int newVer = bundled.getInt("config-version", 0);
            java.util.List<String> missingCfg = insertableMissing(disk, bundled);
            java.util.List<String> missingMsg = bundledMsg == null
                    ? java.util.List.of() : insertableMissing(diskMsg, bundledMsg);
            int added = missingCfg.size() + missingMsg.size();
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                    .format(new java.util.Date());

            backupFile(cfgFile, "config-" + stamp + ".yml");
            if (!migrateFileText(cfgFile, disk, bundled, missingCfg, newVer, "config.yml")) {
                merge(disk, bundled).save(cfgFile); // fallback: always converges
            }
            if (bundledMsg != null) {
                if (msgFile.exists()) backupFile(msgFile, "messages-" + stamp + ".yml");
                if (!migrateFileText(msgFile, diskMsg, bundledMsg, missingMsg, null, "messages.yml")) {
                    merge(diskMsg, bundledMsg).save(msgFile);
                }
            }
            pruneBackups();
            String report = "Config updated: v" + diskVer + " -> v" + newVer
                    + " (+" + added + " new keys, settings and comments kept).";
            java.util.List<String> kept = customLadders(disk, bundled);
            if (!kept.isEmpty()) report += " Custom ladders kept: " + String.join(", ", kept) + ".";
            report += " Backup: backups/config-" + stamp + ".yml";
            plugin.getLogger().info(report);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Config auto-update failed, keeping current files: "
                    + (e.getMessage() == null ? "error" : e.getMessage()));
            return false;
        }
    }

    /** True when the bundled version is newer or insertable keys are missing. */
    static boolean needsMigration(YamlConfiguration disk, YamlConfiguration bundled,
                                  YamlConfiguration diskMsg, YamlConfiguration bundledMsg) {
        int diskVer = disk.getInt("config-version", 0);
        int newVer = bundled.getInt("config-version", 0);
        if (newVer > 0 && diskVer < newVer) return true;
        if (!insertableMissing(disk, bundled).isEmpty()) return true;
        return bundledMsg != null && !insertableMissing(diskMsg, bundledMsg).isEmpty();
    }

    /** Any leaf key present in ref but absent in base? */
    static boolean hasMissingKeys(YamlConfiguration base, YamlConfiguration ref) {
        for (String key : ref.getKeys(true)) {
            if (ref.isConfigurationSection(key)) continue;
            if (!base.contains(key)) return true;
        }
        return false;
    }

    /** How many bundled leaf keys are absent on disk (for the console report). */
    static int countMissing(YamlConfiguration base, YamlConfiguration ref) {
        int n = 0;
        for (String key : ref.getKeys(true)) {
            if (ref.isConfigurationSection(key)) continue;
            if (!base.contains(key)) n++;
        }
        return n;
    }

    /**
     * Missing leaves that the text migration may insert. Customized ladder
     * rungs are excluded - the user's ladder stays byte-identical.
     */
    static java.util.List<String> insertableMissing(YamlConfiguration disk, YamlConfiguration bundled) {
        java.util.Set<String> custom = new java.util.HashSet<>(customLadders(disk, bundled));
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) continue;
            if (key.equals("config-version")) continue; // handled by applyVersion
            if (disk.contains(key)) continue;
            if (isCustomLadderLeaf(key, custom)) continue;
            out.add(key);
        }
        return out;
    }

    /** checks.&lt;name&gt;.punishments[.rung] with a customized &lt;name&gt;. */
    static boolean isCustomLadderLeaf(String key, java.util.Set<String> custom) {
        if (!key.startsWith("checks.")) return false;
        String[] parts = key.split("\\.", 4);
        return parts.length >= 3 && custom.contains(parts[1]) && parts[2].equals("punishments");
    }

    /**
     * Text migration of one file: only appends/inserts missing lines (with
     * bundled comments) and bumps the version line. Existing lines -
     * comments, flow styles, ladders - are never modified. Verifies the
     * result parses and contains every missing key, otherwise returns false
     * and the caller falls back to the Bukkit merge.
     */
    boolean migrateFileText(File file, YamlConfiguration disk, YamlConfiguration bundled,
                            java.util.List<String> missing, Integer forceVersion,
                            String bundledName) {
        try {
            String diskText = java.nio.file.Files.readString(file.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String bundledText = readBundledText(bundledName);
            if (bundledText == null) return false;
            String out = migrateText(disk, bundled, diskText, bundledText, missing, forceVersion);
            YamlConfiguration verify = YamlConfiguration.loadConfiguration(
                    new java.io.StringReader(out));
            for (String k : missing) {
                if (!verify.contains(k)) return false;
            }
            if (forceVersion != null && verify.getInt("config-version", -1) != forceVersion) {
                return false;
            }
            java.nio.file.Files.writeString(file.toPath(), out,
                    java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String readBundledText(String name) {
        try (java.io.InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure text merge: for every missing leaf, copy its block (adjacent
     * comments + key line + deeper continuations like list items) from the
     * bundled text into the matching disk section, or append a missing
     * section wholesale at the end. Then bump the version line.
     */
    static String migrateText(YamlConfiguration disk, YamlConfiguration bundled,
                              String diskText, String bundledText,
                              java.util.List<String> missing, Integer forceVersion) {
        java.util.List<String> diskLines =
                new java.util.ArrayList<>(java.util.Arrays.asList(diskText.split("\r?\n", -1)));
        java.util.List<String> bundledLines =
                new java.util.ArrayList<>(java.util.Arrays.asList(bundledText.split("\r?\n", -1)));
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String leaf : missing) {
            String target = nearestAncestorSection(disk, leaf);
            String source;
            if (target.isEmpty()) {
                source = leaf.contains(".") ? leaf.substring(0, leaf.indexOf('.')) : leaf;
            } else {
                String rel = leaf.substring(target.length() + 1);
                String first = rel.contains(".") ? rel.substring(0, rel.indexOf('.')) : rel;
                source = target + "." + first;
            }
            if (!done.add(source)) continue; // whole subtree already copied
            java.util.List<String> block = extractBlock(bundledLines, source);
            if (block == null || block.isEmpty()) continue; // verification triggers fallback
            if (target.isEmpty()) appendBlock(diskLines, block);
            else insertIntoSection(diskLines, target, block);
        }
        if (forceVersion != null) applyVersion(diskLines, forceVersion);
        while (!diskLines.isEmpty() && diskLines.get(diskLines.size() - 1).isBlank()) {
            diskLines.remove(diskLines.size() - 1);
        }
        return String.join("\n", diskLines) + "\n";
    }

    /** Nearest disk section above the leaf ("" = top level). */
    static String nearestAncestorSection(YamlConfiguration disk, String leaf) {
        int dot = leaf.lastIndexOf('.');
        while (dot > 0) {
            String prefix = leaf.substring(0, dot);
            if (disk.isConfigurationSection(prefix)) return prefix;
            dot = prefix.lastIndexOf('.');
        }
        return "";
    }

    /** One scanned line: indent, key (null for blank/comment/list), dotted path. */
    static final class LineInfo {
        int idx;
        int indent;
        String key;
        String path;
        boolean blank;
        boolean comment;
        boolean listItem;
    }

    static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    static java.util.List<LineInfo> indexLines(java.util.List<String> lines) {
        java.util.List<LineInfo> out = new java.util.ArrayList<>();
        java.util.Deque<LineInfo> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String t = raw.trim();
            LineInfo li = new LineInfo();
            li.idx = i;
            li.indent = indentOf(raw);
            li.blank = t.isEmpty();
            li.comment = t.startsWith("#");
            li.listItem = t.startsWith("- ") || t.equals("-");
            if (!li.blank && !li.comment && !li.listItem) {
                int c = t.indexOf(':');
                if (c > 0) li.key = t.substring(0, c).trim();
            }
            if (li.key != null) {
                while (!stack.isEmpty() && stack.peek().indent >= li.indent) stack.pop();
                StringBuilder path = new StringBuilder();
                for (java.util.Iterator<LineInfo> it = stack.descendingIterator(); it.hasNext();) {
                    if (path.length() > 0) path.append('.');
                    path.append(it.next().key);
                }
                if (path.length() > 0) path.append('.');
                path.append(li.key);
                li.path = path.toString();
                stack.push(li);
            } else if (li.listItem) {
                StringBuilder path = new StringBuilder();
                for (java.util.Iterator<LineInfo> it = stack.descendingIterator(); it.hasNext();) {
                    if (path.length() > 0) path.append('.');
                    path.append(it.next().key);
                }
                li.path = path.length() == 0 ? null : path.toString();
            }
            out.add(li);
        }
        return out;
    }

    /**
     * Bundled block for a path: adjacent comments above + the key line +
     * deeper continuations (list items, nested lines). Stops at the next
     * key on the same or higher level.
     */
    static java.util.List<String> extractBlock(java.util.List<String> lines, String source) {
        java.util.List<LineInfo> idx = indexLines(lines);
        int h = -1;
        for (LineInfo li : idx) {
            if (source.equals(li.path) && li.key != null) {
                h = li.idx;
                break;
            }
        }
        if (h < 0) return null;
        int hIndent = idx.get(h).indent;
        java.util.List<String> block = new java.util.ArrayList<>();
        int c = h - 1;
        java.util.List<String> comments = new java.util.ArrayList<>();
        while (c >= 0 && idx.get(c).comment) {
            comments.add(0, lines.get(c));
            c--;
        }
        block.addAll(comments);
        block.add(lines.get(h));
        for (int j = h + 1; j < lines.size(); j++) {
            LineInfo li = idx.get(j);
            if (li.blank) {
                block.add(lines.get(j));
                continue;
            }
            if (li.indent <= hIndent) break;
            block.add(lines.get(j));
        }
        while (!block.isEmpty() && block.get(block.size() - 1).isBlank()) {
            block.remove(block.size() - 1);
        }
        return block;
    }

    /** Insert a block at the end of an existing section (before the next one). */
    static boolean insertIntoSection(java.util.List<String> lines, String target,
                                     java.util.List<String> block) {
        java.util.List<LineInfo> idx = indexLines(lines);
        int h = -1;
        int hIndent = 0;
        for (LineInfo li : idx) {
            if (target.equals(li.path) && li.key != null) {
                h = li.idx;
                hIndent = li.indent;
                break;
            }
        }
        if (h < 0) return false;
        int childIndent = hIndent + 2;
        boolean firstContent = true;
        int lastContent = h;
        for (int j = h + 1; j < idx.size(); j++) {
            LineInfo li = idx.get(j);
            if (li.key != null && li.indent <= hIndent) break;
            if ((li.key != null || li.listItem) && li.indent > hIndent) {
                if (firstContent) {
                    childIndent = li.indent;
                    firstContent = false;
                }
                lastContent = j;
            }
        }
        int bundledKeyIndent = 0;
        for (String bl : block) {
            if (!bl.isBlank() && !bl.trim().startsWith("#")) {
                bundledKeyIndent = indentOf(bl);
                break;
            }
        }
        int delta = childIndent - bundledKeyIndent;
        java.util.List<String> shifted = new java.util.ArrayList<>();
        for (String bl : block) {
            shifted.add(shiftLine(bl, delta));
        }
        lines.addAll(lastContent + 1, shifted);
        return true;
    }

    static String shiftLine(String line, int delta) {
        if (line.isBlank() || delta == 0) return line;
        if (delta > 0) return " ".repeat(delta) + line;
        int strip = Math.min(-delta, indentOf(line));
        return line.substring(strip);
    }

    /** Append a top-level block at the end of the file with a blank separator. */
    static void appendBlock(java.util.List<String> lines, java.util.List<String> block) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        if (!lines.isEmpty()) lines.add("");
        lines.addAll(block);
    }

    /** Replace the version line, or append it when absent. */
    static void applyVersion(java.util.List<String> lines, int version) {
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("^\\s*config-version\\s*:.*$");
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).matches()) {
                lines.set(i, "config-version: " + version);
                return;
            }
        }
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
        lines.add("config-version: " + version);
    }

    /**
     * New defaults first, then every old value on top (tokens, ladders,
     * custom text). Unknown/obsolete old keys are kept too - losing settings
     * is worse than leftover lines, the backup has the original anyway.
     *
     * <p>Exception: punishment ladders ({@code checks.<name>.punishments})
     * are atomic. A customized map replaces the bundled one wholesale,
     * otherwise default rungs would leak into the user's ladder. A ladder
     * identical to the bundled one counts as untouched and follows the bundle
     * (so future default rungs still arrive).
     */
    static YamlConfiguration merge(YamlConfiguration disk, YamlConfiguration bundled) {
        YamlConfiguration out = new YamlConfiguration();
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) continue;
            out.set(key, bundled.get(key));
        }
        for (String key : disk.getKeys(true)) {
            if (disk.isConfigurationSection(key)) continue;
            Object v = disk.get(key);
            if (v != null) out.set(key, v);
        }
        for (String name : customLadders(disk, bundled)) {
            String path = "checks." + name + ".punishments";
            org.bukkit.configuration.ConfigurationSection sec = disk.getConfigurationSection(path);
            if (sec == null) continue;
            // Wipe the leaf-union first (default rungs are already in),
            // then recreate as a real section (a raw Map value would not
            // be traversable via getString("...rung") nor save correctly).
            out.set(path, null);
            out.createSection(path, sec.getValues(false));
        }
        // Schema version always follows the bundle, otherwise every restart
        // would migrate again (backup + log spam).
        out.set("config-version", bundled.getInt("config-version", 0));
        return out;
    }

    /**
     * Check names whose punishments map the user customized: present on disk
     * and different from the bundled one (or absent from the bundle -
     * a fully custom check). A ladder where every rung matches the bundle
     * counts as untouched and follows it (future default rungs still arrive).
     */
    static java.util.List<String> customLadders(YamlConfiguration disk, YamlConfiguration bundled) {
        java.util.List<String> out = new java.util.ArrayList<>();
        org.bukkit.configuration.ConfigurationSection diskChecks = disk.getConfigurationSection("checks");
        if (diskChecks == null) return out;
        for (String name : diskChecks.getKeys(false)) {
            String path = "checks." + name + ".punishments";
            if (!disk.isConfigurationSection(path)) continue;
            if (ladderCustomized(disk.getConfigurationSection(path),
                    bundled.getConfigurationSection(path))) {
                out.add(name);
            }
        }
        return out;
    }

    /** Extra rung or changed command -> customized; subset of bundle -> untouched. */
    static boolean ladderCustomized(org.bukkit.configuration.ConfigurationSection diskPun,
                                    org.bukkit.configuration.ConfigurationSection bundledPun) {
        if (diskPun == null) return false;
        if (bundledPun == null) return true;
        java.util.Map<String, Object> d = diskPun.getValues(false);
        java.util.Map<String, Object> b = bundledPun.getValues(false);
        for (java.util.Map.Entry<String, Object> e : d.entrySet()) {
            if (!b.containsKey(e.getKey())) return true;
            if (!String.valueOf(e.getValue()).equals(String.valueOf(b.get(e.getKey())))) return true;
        }
        return false;
    }

    YamlConfiguration loadBundled(String name) {
        try (java.io.InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    void backupFile(File src, String backupName) {
        try {
            File backups = new File(plugin.getDataFolder(), "backups");
            if (!backups.exists()) backups.mkdirs();
            java.nio.file.Files.copy(src.toPath(),
                    new File(backups, backupName).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Config backup failed (" + backupName + "): "
                    + (e.getMessage() == null ? "error" : e.getMessage()));
        }
    }

    void pruneBackups() {
        try {
            File backups = new File(plugin.getDataFolder(), "backups");
            File[] files = backups.listFiles((d, name) -> name.endsWith(".yml"));
            if (files == null || files.length <= MAX_BACKUPS) return;
            java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - MAX_BACKUPS; i++) {
                try {
                    files[i].delete();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public String msg(String key) {
        return ColorUtil.render(raw(key), prefix);
    }

    public String raw(String key) {
        return messages.getString(key, key);
    }

    public String msg(String key, PlayerDataPlaceholders ph) {
        String s = raw(key).replace("%prefix%", prefix);
        if (ph != null) s = ph.apply(s);
        return color(s);
    }

    /** "{PLACEHOLDER}", value pairs. Substitutions BEFORE coloring, otherwise colors in values show up as text. */
    public String msg(String key, String... replacements) {
        return ColorUtil.render(raw(key), prefix, replacements);
    }

    /** Compatibility: colors via ColorUtil (HEX + &-codes). */
    public static String color(String s) {
        return ColorUtil.color(s);
    }

    private void saveDefault(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
    }

    /** Creates messages.yml if the user deleted it - so reload does not fail. */
    public void ensureFiles() {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists()) dir.mkdirs();
            File msg = new File(dir, "messages.yml");
            if (!msg.exists()) plugin.saveResource("messages.yml", false);
        } catch (Exception e) {
            plugin.getLogger().warning("ensureFiles failed: " + e.getMessage());
        }
    }

    public void saveMessages() {
        try {
            messages.save(new File(plugin.getDataFolder(), "messages.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("save messages.yml failed: " + e.getMessage());
        }
    }

    /** Mini-placeholders without external dependencies. */
    public static final class PlayerDataPlaceholders {
        public String player = "";
        public String score = "";
        public String vl = "";

        public String apply(String s) {
            return s.replace("%player%", player)
                    .replace("%score%", score)
                    .replace("%vl%", vl);
        }
    }
}
