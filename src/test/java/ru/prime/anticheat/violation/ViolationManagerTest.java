package ru.prime.anticheat.violation;

import org.bukkit.command.CommandSender;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class ViolationManagerTest {

    private static CommandSender fakeSender(String id) {
        return (CommandSender) Proxy.newProxyInstance(ViolationManagerTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("toString")) return "Sender(" + id + ")";
                    Class<?> r = m.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    return null;
                });
    }

    @Test
    public void checkVlIncrementAndIsolation() {
        Map<UUID, Map<String, Integer>> levels = new HashMap<>();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        assertEquals(1, ViolationManager.addCheckVL(levels, u1, "CrystalA"));
        assertEquals(2, ViolationManager.addCheckVL(levels, u1, "CrystalA"));
        assertEquals(1, ViolationManager.addCheckVL(levels, u1, "CrystalB"));
        assertEquals(1, ViolationManager.addCheckVL(levels, u2, "CrystalA"));
        assertEquals(Integer.valueOf(2), levels.get(u1).get("CrystalA"));
    }

    @Test
    public void checkDetails() {
        Map<UUID, Map<String, String>> details = new HashMap<>();
        UUID u = UUID.randomUUID();
        ViolationManager.putCheckDetails(details, u, "CrystalA", "place+attack");
        ViolationManager.putCheckDetails(details, u, "Nope", null);
        assertEquals("place+attack", details.get(u).get("CrystalA"));
        assertFalse(details.get(u).containsKey("Nope"));
    }

    @Test
    public void decayCheckLevels() {
        Map<UUID, Map<String, Integer>> levels = new HashMap<>();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        levels.put(u1, new HashMap<>(Map.of("CrystalA", 2)));
        levels.put(u2, new HashMap<>(Map.of("CrystalB", 2)));
        java.util.Set<UUID> combat = new java.util.HashSet<>();
        combat.add(u1);
        ViolationManager.decayCheckLevels(levels, 2, combat::contains);
        assertEquals(Integer.valueOf(2), levels.get(u1).get("CrystalA"));
        assertFalse(levels.containsKey(u2));
    }

    @Test
    public void mitigationGate() {
        UUID u = UUID.randomUUID();
        List<String> names = List.of("CrystalA", "CrystalB", "CrystalC");
        assertFalse(ViolationManager.isMitigated(new HashMap<>(), 1000L, u));
        assertTrue(ViolationManager.isListedCheck(names, "crystala"));
        assertFalse(ViolationManager.isListedCheck(names, "Aim"));
        assertFalse(ViolationManager.isListedCheck(null, "CrystalA"));

        Map<UUID, Long> until = new HashMap<>();
        until.put(u, 2500L);
        assertTrue(ViolationManager.isMitigated(until, 1000L, u));
        assertTrue(ViolationManager.isMitigated(until, 2499L, u));
        assertFalse(ViolationManager.isMitigated(until, 2500L, u));
        assertFalse(until.containsKey(u));
    }

    @Test
    public void resolveExecutorChoice() {
        CommandSender custom = fakeSender("custom");
        CommandSender console = fakeSender("console");
        assertSame(custom, ViolationManager.resolveExecutor(true, custom, console));
        assertSame(console, ViolationManager.resolveExecutor(false, custom, console));
        assertSame(console, ViolationManager.resolveExecutor(true, null, console));
    }

    @Test
    public void destructiveDetection() {
        assertTrue(ViolationManager.isDestructive("ban Steve"));
        assertTrue(ViolationManager.isDestructive("/kick Steve"));
        assertTrue(ViolationManager.isDestructive("essentials:ban Steve"));
        assertFalse(ViolationManager.isDestructive("say hello"));
        assertFalse(ViolationManager.isDestructive(null));
    }
}
