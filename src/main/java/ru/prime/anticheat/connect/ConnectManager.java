package ru.prime.anticheat.connect;

import org.bukkit.command.CommandSender;
import ru.prime.anticheat.PrimeAnticheat;

/**
 * Binding the server to the API via command:
 * /pac link token &lt;key&gt; saves the key directly
 * (config.yml + instant reconnect, console supported).
 */
public class ConnectManager {

    public final PrimeAnticheat plugin;

    public ConnectManager(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    // --- /pac link token ---

    public void handleSetToken(CommandSender sender, String key) {
        if (!isValidToken(key)) {
            sender.sendMessage(plugin.configs.prefixLine + plugin.configs.msg("token-usage"));
            return;
        }
        String token = key.trim();
        applyToken(token);
        sender.sendMessage(plugin.configs.prefixLine
                + plugin.configs.msg("token-saved", "{KEY}", token));
    }

    /** Save the token to config.yml and apply without a restart. */
    public void applyToken(String token) {
        plugin.getConfig().set("inference.token", token);
        plugin.saveConfig();
        plugin.configs.reloadAll();
        if (plugin.inference != null) {
            plugin.inference.updateConfig(plugin.configs.inferenceUrl, token, plugin.configs.inferenceDebug);
        }
    }

    public static boolean isValidToken(String token) {
        return token != null && !token.trim().isEmpty();
    }
}
