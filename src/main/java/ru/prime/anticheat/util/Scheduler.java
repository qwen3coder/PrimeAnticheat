package ru.prime.anticheat.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Scheduler + teleport abstraction for Bukkit/Paper/Folia with one codebase.
 *
 * <p>Spigot API has no Folia classes, so every Folia call goes through
 * reflection (touched only when FOLIA is true). On plain Bukkit/Paper the
 * exact same calls as before run - zero behavior change. On Folia the
 * platform schedulers are used instead of throwing
 * UnsupportedOperationException.
 *
 * <p>NOTE: this layer fixes scheduling + teleports. Entity/world reads should
 * still happen on the right thread; verify on a live Folia server before
 * advertising support.
 */
public final class Scheduler {

    private Scheduler() {
    }

    public static final boolean FOLIA = detectFolia();

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Unified cancellable handle (BukkitTask or Folia ScheduledTask). */
    public interface Task {
        void cancel();
    }

    private static final Task DONE = () -> {
    };

    private static Task wrapCancellable(Object handle) {
        return () -> {
            try {
                handle.getClass().getMethod("cancel").invoke(handle);
            } catch (Exception ignored) {
            }
        };
    }

    /** One-shot on the global/main thread. */
    public static Task run(JavaPlugin plugin, Runnable runnable) {
        if (!FOLIA) {
            try {
                return wrapCancellable(Bukkit.getScheduler().runTask(plugin, runnable));
            } catch (Exception e) {
                runnable.run();
                return DONE;
            }
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method execute = scheduler.getClass().getMethod("execute",
                    org.bukkit.plugin.Plugin.class, Runnable.class);
            execute.invoke(scheduler, plugin, runnable);
            return DONE;
        } catch (Exception e) {
            runnable.run();
            return DONE;
        }
    }

    /** Repeating task on the global/main thread, ticks. */
    public static Task timer(JavaPlugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            try {
                return wrapCancellable(
                        Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
            } catch (Exception e) {
                return DONE;
            }
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method runAtFixedRate = scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class);
            Consumer<Object> consumer = task -> runnable.run();
            Object handle = runAtFixedRate.invoke(scheduler, plugin, consumer, delayTicks, periodTicks);
            return wrapCancellable(handle);
        } catch (Exception e) {
            return DONE;
        }
    }

    /** Repeating async task, ticks. */
    public static Task timerAsync(JavaPlugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            try {
                return wrapCancellable(Bukkit.getScheduler()
                        .runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
            } catch (Exception e) {
                return DONE;
            }
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Method runAtFixedRate = scheduler.getClass().getMethod("runAtFixedRate",
                    org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class);
            Consumer<Object> consumer = task -> runnable.run();
            Object handle = runAtFixedRate.invoke(scheduler, plugin, consumer, delayTicks, periodTicks);
            return wrapCancellable(handle);
        } catch (Exception e) {
            return DONE;
        }
    }

    /** One-shot async task. */
    public static Task runAsync(JavaPlugin plugin, Runnable runnable) {
        if (!FOLIA) {
            try {
                return wrapCancellable(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
            } catch (Exception e) {
                runnable.run();
                return DONE;
            }
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Method runNow = scheduler.getClass().getMethod("runNow",
                    org.bukkit.plugin.Plugin.class, Consumer.class);
            Consumer<Object> consumer = task -> runnable.run();
            Object handle = runNow.invoke(scheduler, plugin, consumer);
            return wrapCancellable(handle);
        } catch (Exception e) {
            runnable.run();
            return DONE;
        }
    }

    /**
     * Teleport that works on both platforms. On Folia entity teleports must run
     * on the entity's region thread - a direct teleport() would break.
     */
    public static void teleport(JavaPlugin plugin, Entity entity, Location location) {
        if (!FOLIA) {
            entity.teleport(location);
            return;
        }
        try {
            Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            Method execute = scheduler.getClass().getMethod("execute",
                    org.bukkit.plugin.Plugin.class, Runnable.class, Runnable.class, long.class);
            execute.invoke(scheduler, plugin,
                    (Runnable) () -> entity.teleport(location), null, 1L);
        } catch (Exception e) {
            entity.teleport(location);
        }
    }
}
