package ru.prime.anticheat.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ru.prime.anticheat.PrimeAnticheat;
import ru.prime.anticheat.data.PlayerData;
import ru.prime.anticheat.hologram.HologramManager;
import ru.prime.anticheat.monitor.MonitorManager;
import ru.prime.anticheat.util.ColorUtil;
import ru.prime.anticheat.util.Scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /pac violators - GUI leaderboard of online players with VL &gt; 0.
 *
 * Heads sorted by VL (then probability), lore with VL / last probability /
 * buffer. Left-click teleports to the violator, right-click toggles
 * /pac monitor on them. Contents refresh every 2 seconds while open.
 * Call event methods ONLY from the main thread (Bukkit events/tasks).
 */
public class ViolatorsMenu implements Listener {

    public static final int SIZE = 54;
    /** Inner head slots (inside the frame ring). */
    public static final int[] INNER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43,
    };
    public static final int PER_PAGE = INNER_SLOTS.length;
    /** Full frame ring (controls live on it). */
    public static final int[] FRAME_SLOTS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        9, 17, 18, 26, 27, 35, 36, 44,
        46, 47, 48, 50, 51, 52,
    };
    public static final int SLOT_PREV = 45;
    public static final int SLOT_REFRESH = 49;
    public static final int SLOT_NEXT = 53;
    public static final long REFRESH_PERIOD_TICKS = 40L;

    public final PrimeAnticheat plugin;

    // Viewer -> opened page (removed on inventory close).
    public final Map<UUID, Integer> pages = new HashMap<>();

    public Scheduler.Task refreshTask;

    public ViolatorsMenu(PrimeAnticheat plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (refreshTask != null) return;
        refreshTask = Scheduler.timer(plugin, this::refreshAll, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
    }

    public void stopAll() {
        if (refreshTask != null) {
            try {
                refreshTask.cancel();
            } catch (Exception ignored) {
            }
            refreshTask = null;
        }
        pages.clear();
    }

    // --- data (pure logic, unit-tested) ---

    /** One leaderboard row. */
    public static record ViolatorEntry(UUID uuid, String name, int vl, double probability,
                                       double buffer, List<Double> history) {
        public ViolatorEntry(UUID uuid, String name, int vl, double probability, double buffer,
                             List<Double> history) {
            this.uuid = uuid;
            this.name = name;
            this.vl = vl;
            this.probability = probability;
            this.buffer = buffer;
            this.history = history != null
                    ? Collections.unmodifiableList(new ArrayList<>(history))
                    : Collections.emptyList();
        }
    }

    /** Online players with VL &gt; 0, sorted by VL desc then probability desc. */
    public static List<ViolatorEntry> filterAndSort(Collection<PlayerData> all,
                                                   java.util.function.Function<UUID, Integer> vlOf,
                                                   java.util.function.Function<UUID, Boolean> onlineOf) {
        List<ViolatorEntry> out = new ArrayList<>();
        for (PlayerData data : all) {
            if (data == null) continue;
            if (!onlineOf.apply(data.uuid)) continue;
            int vl = vlOf.apply(data.uuid);
            if (vl <= 0) continue;
            out.add(new ViolatorEntry(data.uuid, data.name, vl,
                    data.lastProbability, data.getBuffer(), data.probHistorySnapshot()));
        }
        out.sort(Comparator.comparingInt((ViolatorEntry e) -> e.vl()).reversed()
                .thenComparing(Comparator.comparingDouble((ViolatorEntry e) -> e.probability()).reversed()));
        return out;
    }

    public static int pageCount(int total) {
        return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
    }

    public static List<ViolatorEntry> pageSlice(List<ViolatorEntry> entries, int page) {
        int pages = pageCount(entries.size());
        int p = Math.min(Math.max(0, page), pages - 1);
        int from = p * PER_PAGE;
        return entries.subList(from, Math.min(entries.size(), from + PER_PAGE));
    }

    /** Head lore: VL / last probability / history / buffer + hints (colored). */
    public static List<String> loreFor(ViolatorEntry entry, double bufferFlag) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(MonitorManager.VL_ICON + "VL: " + MonitorManager.VL_VALUE + entry.vl());
        int probPct = (int) Math.round(entry.probability() * 100);
        lore.add(MonitorManager.levelColor(probPct) + "Last: " + probPct + "%");
        lore.add("&#666666History: " + HologramManager.holoText(entry.history()));
        int bufPct = entry.buffer() <= 0 || bufferFlag <= 0 ? 0
                : (int) Math.min(100, Math.round(entry.buffer() / bufferFlag * 100));
        lore.add(MonitorManager.levelColor(bufPct) + "Buffer: "
                + String.format(Locale.ROOT, "%.1f", entry.buffer())
                + " / " + String.format(Locale.ROOT, "%.0f", bufferFlag));
        lore.add("");
        lore.add("&#666666Left-click: teleport");
        lore.add("&#666666Right-click: monitor");
        return ColorUtil.color(lore);
    }

    // --- live view ---

    public List<ViolatorEntry> collectEntries() {
        return filterAndSort(plugin.players.all(),
                uuid -> plugin.violations.getViolationLevel(uuid),
                uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    return p != null && p.isOnline();
                });
    }

    /** Holder ties the open inventory to its viewer (for clicks/refresh). */
    public static final class ViolatorsHolder implements InventoryHolder {
        public final UUID viewer;
        public Inventory inventory;

        public ViolatorsHolder(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void openGui(Player admin) {
        int page = pages.getOrDefault(admin.getUniqueId(), 0);
        ViolatorsHolder holder = new ViolatorsHolder(admin.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder,
                SIZE, ColorUtil.color(plugin.configs.msg("violators-title")));
        holder.inventory = inv;
        fill(inv, collectEntries(), page);
        admin.openInventory(inv);
    }

    public void fill(Inventory inv, List<ViolatorEntry> entries, int page) {
        inv.clear();
        int clamped = Math.min(Math.max(0, page), pageCount(entries.size()) - 1);
        List<ViolatorEntry> slice = pageSlice(entries, clamped);
        for (int i = 0; i < slice.size() && i < INNER_SLOTS.length; i++) {
            Player target = Bukkit.getPlayer(slice.get(i).uuid());
            if (target == null || !target.isOnline()) continue;
            inv.setItem(INNER_SLOTS[i], headFor(target, slice.get(i)));
        }
        // Full frame ring (bottom controls live on it).
        for (int slot : FRAME_SLOTS) inv.setItem(slot, filler());
        if (entries.isEmpty()) {
            inv.setItem(22, note(plugin.configs.msg("violators-empty")));
        }
        // Arrows are always visible and wrap around at the edges.
        inv.setItem(SLOT_PREV, control(Material.ARROW, "&#58A8FFPrevious page"));
        inv.setItem(SLOT_REFRESH, control(Material.PAPER, "&#58A8FFRefresh"));
        inv.setItem(SLOT_NEXT, control(Material.ARROW, "&#58A8FFNext page"));
    }

    public ItemStack headFor(Player target, ViolatorEntry entry) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(ColorUtil.color("&#58A8FF" + entry.name()));
            meta.setLore(loreFor(entry, plugin.configs.bufferFlag));
            head.setItemMeta(meta);
        }
        return head;
    }

    public static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        return pane;
    }

    public static ItemStack control(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack note(String text) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(text));
            item.setItemMeta(meta);
        }
        return item;
    }

    // --- events ---

    public void refreshAll() {
        if (pages.isEmpty()) return;
        // One shared snapshot for all viewers (not one scan per admin).
        List<ViolatorEntry> entries = collectEntries();
        for (Map.Entry<UUID, Integer> e : new HashMap<>(pages).entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null || !viewer.isOnline()) {
                pages.remove(e.getKey());
                continue;
            }
            if (!(viewer.getOpenInventory().getTopInventory().getHolder() instanceof ViolatorsHolder)) continue;
            fill(viewer.getOpenInventory().getTopInventory(), entries, e.getValue());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ViolatorsHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();
        ViolatorsHolder holder = (ViolatorsHolder) event.getInventory().getHolder();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        UUID viewerId = holder.viewer;
        int page = pages.getOrDefault(viewerId, 0);
        List<ViolatorEntry> entries = collectEntries();
        int total = pageCount(entries.size());
        if (slot == SLOT_PREV) {
            int prev = (page - 1 + total) % total;
            pages.put(viewerId, prev);
            fill(event.getInventory(), entries, prev);
            return;
        }
        if (slot == SLOT_NEXT) {
            int next = (page + 1) % total;
            pages.put(viewerId, next);
            fill(event.getInventory(), entries, next);
            return;
        }
        if (slot == SLOT_REFRESH) {
            fill(event.getInventory(), entries, page);
            return;
        }
        // Heads live on INNER_SLOTS in slice order.
        int index = -1;
        for (int i = 0; i < INNER_SLOTS.length; i++) {
            if (INNER_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        List<ViolatorEntry> slice = pageSlice(entries, page);
        if (index >= slice.size()) return;
        ViolatorEntry entry = slice.get(index);
        Player target = Bukkit.getPlayer(entry.uuid());
        if (target == null || !target.isOnline()) {
            fill(event.getInventory(), collectEntries(), page);
            return;
        }
        if (event.isRightClick()) {
            plugin.monitor.toggle(admin, target);
        } else {
            admin.closeInventory();
            try {
                Scheduler.teleport(plugin, admin, target.getLocation());
            } catch (Exception ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ViolatorsHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ViolatorsHolder) {
            pages.remove(event.getPlayer().getUniqueId());
        }
    }

    public void handleQuit(Player player) {
        pages.remove(player.getUniqueId());
    }
}
