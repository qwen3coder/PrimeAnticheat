package ru.prime.anticheat.manager;

import org.bukkit.entity.Player;
import ru.prime.anticheat.data.PlayerData;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlayerData storage. Thread-safe: read from Netty (packets)
 * and from the main thread (join/quit/checks) concurrently.
 */
public class PlayerDataManager {

    public final ConcurrentHashMap<UUID, PlayerData> map = new ConcurrentHashMap<>();

    public PlayerData getOrCreate(Player player) {
        PlayerData existing = map.get(player.getUniqueId());
        if (existing != null) {
            existing.player = player;
            return existing;
        }
        PlayerData data = new PlayerData(player.getUniqueId(), player.getName());
        data.player = player;
        map.put(data.uuid, data);
        return data;
    }

    public PlayerData get(UUID uuid) {
        return map.get(uuid);
    }

    public PlayerData get(Player player) {
        return map.get(player.getUniqueId());
    }

    public void remove(Player player) {
        map.remove(player.getUniqueId());
    }

    public void remove(UUID uuid) {
        map.remove(uuid);
    }

    public Collection<PlayerData> all() {
        return map.values();
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }
}
