package ru.prime.anticheat.collector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.prime.anticheat.data.DataSession;
import ru.prime.anticheat.data.Label;
import ru.prime.anticheat.math.AimProcessor;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.*;

public class DataSessionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void rotationRecordsOnlyInCombat() {
        DataSession session = new DataSession(UUID.randomUUID(), "Steve",
                Label.CHEAT, "", new AimProcessor());
        session.processTick(10.0f, 5.0f);
        assertEquals(0, session.getTickCount());
        session.onAttack();
        session.processTick(11.0f, 5.5f);
        session.processTick(12.0f, 6.0f);
        assertEquals(2, session.getTickCount());
    }

    @Test
    public void csvRoundtrip() throws Exception {
        DataSession session = new DataSession(UUID.randomUUID(), "Steve",
                Label.LEGIT, "", new AimProcessor());
        session.onAttack();
        session.processTick(10.0f, 5.0f);
        String csv = session.generateCsvContent();
        assertTrue(csv.startsWith("is_cheating,delta_yaw"));
        assertTrue(csv.contains("\n0,"));

        File dir = folder.getRoot();
        session.saveAndClose(dir, null);
        File dataDir = new File(dir, "data");
        assertTrue(dataDir.isDirectory());
        assertEquals(1, dataDir.listFiles().length);
        String saved = new String(Files.readAllBytes(dataDir.listFiles()[0].toPath()));
        assertEquals(csv, saved);
    }

    @Test
    public void emptySessionSavesNothing() throws Exception {
        DataSession session = new DataSession(UUID.randomUUID(), "Steve",
                Label.UNLABELED, "", new AimProcessor());
        assertEquals("", session.generateCsvContent());
        File dir = folder.getRoot();
        session.saveAndClose(dir, null);
        assertFalse(new File(dir, "data").exists());
    }
}
