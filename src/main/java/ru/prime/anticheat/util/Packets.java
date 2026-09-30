package ru.prime.anticheat.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketReceiveEvent helpers for checks. Everything here is null-safe and
 * Netty-safe: no Bukkit entity state reads, no allocations beyond one wrapper
 * where details are requested. All methods tolerate foreign/unknown packets.
 */
public final class Packets {

    private Packets() {
    }

    // --- sender ---

    /** The Bukkit player behind the event, or null (console/unknown). */
    public static Player playerOf(PacketReceiveEvent event) {
        try {
            Object sender = event.getPlayer();
            if (sender instanceof Player) return (Player) sender;
        } catch (Exception ignored) {
        }
        return null;
    }

    // --- client version (cached: never changes mid-session) ---

    public static final Map<UUID, ClientVersion> VERSIONS = new ConcurrentHashMap<>();

    /** Cached client version, resolved once per player. Null when unknown. */
    public static ClientVersion clientVersion(Player player) {
        UUID uuid = player.getUniqueId();
        ClientVersion cached = VERSIONS.get(uuid);
        if (cached != null) return cached;
        ClientVersion resolved;
        try {
            resolved = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        } catch (Exception e) {
            return null;
        }
        if (resolved == null) return null;
        VERSIONS.put(uuid, resolved);
        return resolved;
    }

    /** Drop the cached version (call on quit). */
    public static void forget(UUID uuid) {
        VERSIONS.remove(uuid);
    }

    // --- type predicates ---

    public static boolean isType(PacketReceiveEvent event, PacketType.Play.Client type) {
        try {
            return type.equals(event.getPacketType());
        } catch (Exception e) {
            return false;
        }
    }

    /** Interact entity: attack, interact, interact-at. */
    public static boolean isInteractEntity(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.INTERACT_ENTITY);
    }

    /** Arm swing animation. */
    public static boolean isSwing(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.ANIMATION);
    }

    /** Block placement (use item on block). */
    public static boolean isBlockPlace(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT);
    }

    /** Inventory click. */
    public static boolean isWindowClick(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.CLICK_WINDOW);
    }

    /** Keep-alive response. */
    public static boolean isKeepAlive(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.KEEP_ALIVE);
    }

    /** Pong response. */
    public static boolean isPong(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.PONG);
    }

    /** Entity action (sprint/sneak/jump...). */
    public static boolean isEntityAction(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.ENTITY_ACTION);
    }

    /** Hotbar slot change. */
    public static boolean isHeldItemChange(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.HELD_ITEM_CHANGE);
    }

    /** Block digging states. */
    public static boolean isDigging(PacketReceiveEvent event) {
        return isType(event, PacketType.Play.Client.PLAYER_DIGGING);
    }

    // --- interact details (allocate one wrapper, exceptions -> nulls) ---

    /** ATTACK / INTERACT / INTERACT_AT, or null. */
    public static WrapperPlayClientInteractEntity.InteractAction interactAction(PacketReceiveEvent event) {
        try {
            return new WrapperPlayClientInteractEntity(event).getAction();
        } catch (Exception e) {
            return null;
        }
    }

    /** True attack on an entity (not a generic interact). */
    public static boolean isAttack(PacketReceiveEvent event) {
        return isInteractEntity(event)
                && interactAction(event) == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
    }

    /** Attacked/interacted entity id, or -1. */
    public static int interactedEntityId(PacketReceiveEvent event) {
        try {
            return new WrapperPlayClientInteractEntity(event).getEntityId();
        } catch (Exception e) {
            return -1;
        }
    }
}
