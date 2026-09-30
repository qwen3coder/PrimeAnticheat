package ru.prime.anticheat.gui;

import org.junit.Test;
import ru.prime.anticheat.data.PlayerData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class ViolatorsMenuTest {

    private static List<PlayerData> datas(Object[][] rows, Map<UUID, Integer> vl,
                                          java.util.Set<UUID> offline) {
        List<PlayerData> all = new ArrayList<>();
        for (Object[] r : rows) {
            PlayerData d = new PlayerData(UUID.randomUUID(), (String) r[0]);
            d.lastProbability = (Double) r[2];
            d.buffer = (Double) r[3];
            vl.put(d.uuid, (Integer) r[1]);
            if (!((Boolean) r[4])) offline.add(d.uuid);
            all.add(d);
        }
        return all;
    }

    @Test
    public void filterSortAndLore() {
        Map<UUID, Integer> vl = new HashMap<>();
        java.util.Set<UUID> offline = new java.util.HashSet<>();
        Object[][] rows = {
            {"Legit", 0, 0.01, 0.0, true},
            {"CheaterA", 6, 0.95, 50.0, true},
            {"CheaterB", 6, 0.50, 30.0, true},
            {"CheaterC", 2, 0.99, 10.0, true},
            {"Offline", 9, 0.99, 90.0, false},
        };
        List<PlayerData> all = datas(rows, vl, offline);
        List<ViolatorsMenu.ViolatorEntry> out = ViolatorsMenu.filterAndSort(all,
                uuid -> vl.getOrDefault(uuid, 0),
                uuid -> !offline.contains(uuid));
        assertEquals(3, out.size());
        assertEquals("CheaterA", out.get(0).name());
        assertEquals("CheaterB", out.get(1).name());
        assertEquals("CheaterC", out.get(2).name());

        String joined = String.join("\n", ViolatorsMenu.loreFor(out.get(0), 50.0));
        assertTrue(joined.contains("6") && joined.contains("95%") && joined.contains("50.0"));
        assertTrue(joined.contains("teleport") && joined.contains("monitor"));
        assertFalse(joined.contains("&"));
        assertTrue(joined.contains("§"));
    }

    @Test
    public void loreHistoryLine() {
        List<Double> hist = new ArrayList<>(List.of(0.0, 0.01, 0.01, 0.03, 0.0));
        ViolatorsMenu.ViolatorEntry e = new ViolatorsMenu.ViolatorEntry(
                UUID.randomUUID(), "Cheater", 6, 0.03, 50.0, hist);
        String joined = String.join("\n", ViolatorsMenu.loreFor(e, 50.0));
        assertTrue(joined.contains("History:") && joined.contains("3%"));
        hist.clear();
        assertEquals(5, e.history().size());
    }

    @Test
    public void pagination() {
        assertEquals(1, ViolatorsMenu.pageCount(0));
        List<ViolatorsMenu.ViolatorEntry> big = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            big.add(new ViolatorsMenu.ViolatorEntry(UUID.randomUUID(), "P" + i,
                    1, 0.5, 1.0, new ArrayList<>()));
        }
        assertEquals(3, ViolatorsMenu.pageCount(60));
        assertEquals(28, ViolatorsMenu.pageSlice(big, 0).size());
        assertEquals(28, ViolatorsMenu.pageSlice(big, 1).size());
        assertEquals(4, ViolatorsMenu.pageSlice(big, 2).size());
        assertEquals(4, ViolatorsMenu.pageSlice(big, 99).size());
        assertEquals(28, ViolatorsMenu.pageSlice(big, -5).size());
    }

    @Test
    public void frameGeometry() {
        java.util.Set<Integer> all = new java.util.HashSet<>();
        for (int s : ViolatorsMenu.INNER_SLOTS) all.add(s);
        for (int s : ViolatorsMenu.FRAME_SLOTS) all.add(s);
        all.add(ViolatorsMenu.SLOT_PREV);
        all.add(ViolatorsMenu.SLOT_REFRESH);
        all.add(ViolatorsMenu.SLOT_NEXT);
        assertEquals(54, all.size());
        assertEquals(28, ViolatorsMenu.INNER_SLOTS.length);
    }
}
