package ru.prime.anticheat.command;

import org.bukkit.command.CommandSender;
import org.junit.Test;
import ru.prime.anticheat.check.PacketCheck;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PrimeCommandTest {

    static CommandSender sender(Set<String> perms) {
        return (CommandSender) Proxy.newProxyInstance(PrimeCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, m, args) -> {
                    String n = m.getName();
                    if (n.equals("hasPermission") && args.length == 1) {
                        Object p = args[0];
                        String name = p instanceof String ? (String) p
                                : ((org.bukkit.permissions.Permission) p).getName();
                        return perms.contains(name);
                    }
                    if (n.equals("isOp")) return false;
                    if (n.equals("getName")) return "Test";
                    if (n.equals("toString")) return "TestSender";
                    Class<?> r = m.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class || r == float.class || r == double.class) return 0;
                    return null;
                });
    }

    @Test
    public void hiddenWithoutNodes() {
        assertFalse(PrimeCommand.isStaff(sender(new HashSet<>())));
        assertFalse(PrimeCommand.isStaff(sender(new HashSet<>(List.of("primeanticheat.bypass")))));
    }

    @Test
    public void everyNodeGrantsVisibility() {
        for (String n : new String[]{"admin", "alerts", "monitor", "probs", "holo", "status",
                "violators", "ipinfo", "datacollect", "link", "execute", "reload"}) {
            assertTrue("node " + n, PrimeCommand.isStaff(
                    sender(new HashSet<>(List.of("primeanticheat." + n)))));
        }
    }

    @Test
    public void adminImpliesAll() {
        CommandSender admin = sender(new HashSet<>(List.of("primeanticheat.admin")));
        for (String n : new String[]{"alerts", "monitor", "probs", "holo", "status",
                "violators", "ipinfo", "datacollect", "link", "execute", "reload"}) {
            assertTrue("node " + n, PrimeCommand.has(admin, n));
        }
        CommandSender plain = sender(new HashSet<>());
        assertFalse(PrimeCommand.has(plain, "monitor"));
    }
}
