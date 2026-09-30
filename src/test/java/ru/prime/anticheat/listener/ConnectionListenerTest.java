package ru.prime.anticheat.listener;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ConnectionListenerTest {

    @Test
    public void stripRawCommands() {
        List<String> cmds = new ArrayList<>(Arrays.asList(
                "primeanticheat", "pac", "primeac", "help", "gamemode", "minecraft:help"));
        ConnectionListener.stripRawCommands(cmds);
        assertFalse(cmds.contains("primeanticheat"));
        assertFalse(cmds.contains("pac"));
        assertFalse(cmds.contains("primeac"));
        assertTrue(cmds.contains("help"));
        assertTrue(cmds.contains("gamemode"));
        assertTrue(cmds.contains("minecraft:help"));
    }
}
