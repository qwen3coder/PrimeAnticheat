package ru.prime.anticheat.collector;

import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.TickData;
import ru.prime.anticheat.util.SecurityUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

/**
 * Saving player tick history to restored_data/ for FP analysis.
 * History without a known label - written as LEGIT (false-positive analysis).
 */
public class DataRestorer {

    public final PrimeAnticheat plugin;
    public final File restoredDataFolder;

    public DataRestorer(PrimeAnticheat plugin) {
        this.plugin = plugin;
        this.restoredDataFolder = new File(plugin.getDataFolder(), "restored_data");
        if (!restoredDataFolder.exists()) restoredDataFolder.mkdirs();
    }

    public boolean restoreData(String playerName, List<TickData> history) {
        if (history == null || history.isEmpty()) return false;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File file = new File(restoredDataFolder,
                SecurityUtil.sanitizeFileName(playerName) + "_" + timestamp + ".csv");

        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println(TickData.getHeader());
            for (TickData t : history) {
                w.println(t.toCsv("LEGIT"));
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to restore data for " + playerName, e);
            return false;
        }
    }
}
