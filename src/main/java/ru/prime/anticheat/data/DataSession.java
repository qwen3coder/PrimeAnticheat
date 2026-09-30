package ru.prime.anticheat.data;

import ru.prime.anticheat.math.AimProcessor;
import ru.prime.anticheat.util.SecurityUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Single dataset collection session: writes TickData (delta/accel/jerk/gcd)
 * to CSV while the player is in combat. Combat is opened by an attack and held
 * for COMBAT_TIMEOUT rotation packets.
 */
public class DataSession {

    public static final int COMBAT_TIMEOUT = 40;

    /**
     * Safeguard against a forgotten session: a long continuous fight would record endlessly
     * (each attack refreshes the window). After the cap, recording stops, the session
     * keeps hanging until a manual stop (the CSV will save whatever was collected).
     */
    public static final int MAX_RECORDED_TICKS = 200_000;

    public final UUID uuid;
    public final String playerName;
    public final Label label;
    public final String comment;
    public final long startTime = System.currentTimeMillis();

    public final Queue<TickData> recordedTicks = new ConcurrentLinkedQueue<>();
    public final AimProcessor aimProcessor;

    public final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public int ticksSinceAttack = COMBAT_TIMEOUT;
    public boolean recordCapped;

    public DataSession(UUID uuid, String playerName, Label label, String comment, AimProcessor aimProcessor) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.label = label;
        this.comment = comment == null ? "" : comment;
        this.aimProcessor = aimProcessor;
    }

    public void processTick(float yaw, float pitch) {
        lock.writeLock().lock();
        try {
            TickData t = aimProcessor.process(yaw, pitch);
            if (ticksSinceAttack < COMBAT_TIMEOUT && !recordCapped) {
                recordedTicks.add(t);
                if (recordedTicks.size() >= MAX_RECORDED_TICKS) recordCapped = true;
            }
            ticksSinceAttack++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onAttack() {
        lock.writeLock().lock();
        try {
            ticksSinceAttack = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getTickCount() {
        lock.readLock().lock();
        try {
            return recordedTicks.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isInCombat() {
        lock.readLock().lock();
        try {
            return ticksSinceAttack < COMBAT_TIMEOUT;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String generateFileName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(startTime));
        String status = label.name();
        if (!comment.isEmpty()) {
            String sanitized = comment.replace(' ', '#').replaceAll("[/\\\\?%*:|\"<>']", "-");
            status = status + "_" + sanitized;
        }
        return String.format("%s_%s_%s.csv", status, SecurityUtil.sanitizeFileName(playerName), timestamp);
    }

    public String generateCsvContent() {
        lock.readLock().lock();
        try {
            if (recordedTicks.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append(TickData.getHeader()).append("\n");
            String status = "UNLABELED";
            if (label == Label.CHEAT) status = "CHEAT";
            else if (label == Label.LEGIT) status = "LEGIT";
            for (TickData t : new ArrayList<>(recordedTicks)) {
                sb.append(t.toCsv(status)).append("\n");
            }
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Saves the CSV to dataFolder/data[/sessionFolder], silently skips an empty session. */
    public void saveAndClose(File dataFolder, String sessionFolder) throws IOException {
        String csv = generateCsvContent();
        if (csv.isEmpty()) return;
        File dir = new File(dataFolder, "data");
        if (sessionFolder != null && !sessionFolder.isEmpty()) dir = new File(dir, sessionFolder);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, generateFileName());
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write(csv);
        }
    }
}
