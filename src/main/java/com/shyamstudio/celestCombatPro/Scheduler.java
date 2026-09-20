package com.shyamstudio.celestCombatPro;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Central scheduling layer for CelestCombat-Pro.
 *
 * <p>Every scheduler call in the plugin must go through this class so that the correct
 * execution context is chosen for both traditional Paper/Leaf servers and Folia/Canvas
 * region-threaded servers:
 *
 * <ul>
 *     <li>{@link #runGlobal(Runnable)} — global state, console command dispatch, plugin-wide tasks</li>
 *     <li>{@link #runRegion(Location, Runnable)} — world/region bound work (particles, block changes)</li>
 *     <li>{@link #runEntity(Entity, Runnable)} — entity/player bound work (inventory, velocity, messages)</li>
 *     <li>{@link #runAsync(Runnable)} — pure computation and file/network I/O that never touches Bukkit state</li>
 * </ul>
 */
public final class Scheduler {

    private static final String PLUGIN_NAME = "CelestCombat-Pro";

    private static final boolean FOLIA = detectFolia();

    private static volatile Plugin plugin;

    private Scheduler() {
    }

    /**
     * Binds the scheduler to the running plugin instance. Called from {@code onEnable()}.
     */
    public static void init(Plugin pluginInstance) {
        plugin = pluginInstance;
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Plugin plugin() {
        Plugin current = plugin;
        if (current != null) {
            return current;
        }
        current = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (current != null) {
            plugin = current;
        }
        return current;
    }

    private static boolean hasPlugin() {
        Plugin current = plugin();
        if (current == null || !current.isEnabled()) {
            plugin = null;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Global (main thread / global region)
    // ------------------------------------------------------------------

    public static Task runGlobal(Runnable runnable) {
        if (!hasPlugin()) {
            logUnavailable("global");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(Bukkit.getGlobalRegionScheduler().run(plugin(), task -> runnable.run()));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule global task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTask(plugin(), runnable));
    }

    public static Task runGlobalLater(Runnable runnable, long delayTicks) {
        if (!hasPlugin()) {
            logUnavailable("delayed global");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(Bukkit.getGlobalRegionScheduler().runDelayed(plugin(),
                        task -> runnable.run(), Math.max(1L, delayTicks)));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule delayed global task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskLater(plugin(), runnable, delayTicks));
    }

    public static Task runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (!hasPlugin()) {
            logUnavailable("repeating global");
            return Task.EMPTY;
        }
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            try {
                return new Task(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin(),
                        task -> runnable.run(), Math.max(1L, delayTicks), period));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule repeating global task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delayTicks, period));
    }

    // ------------------------------------------------------------------
    // Async
    // ------------------------------------------------------------------

    public static Task runAsync(Runnable runnable) {
        if (!hasPlugin()) {
            logUnavailable("async");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(Bukkit.getAsyncScheduler().runNow(plugin(), task -> runnable.run()));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule async task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskAsynchronously(plugin(), runnable));
    }

    public static Task runAsyncLater(Runnable runnable, long delayTicks) {
        if (!hasPlugin()) {
            logUnavailable("delayed async");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                long delayMs = Math.max(50L, delayTicks * 50L);
                return new Task(Bukkit.getAsyncScheduler().runDelayed(plugin(),
                        task -> runnable.run(), delayMs, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule delayed async task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin(), runnable, delayTicks));
    }

    public static Task runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (!hasPlugin()) {
            logUnavailable("repeating async");
            return Task.EMPTY;
        }
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            try {
                long delayMs = Math.max(50L, delayTicks * 50L);
                long periodMs = Math.max(50L, period * 50L);
                return new Task(Bukkit.getAsyncScheduler().runAtFixedRate(plugin(),
                        task -> runnable.run(), delayMs, periodMs, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.SEVERE, "Failed to schedule repeating async task on Folia", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin(), runnable, delayTicks, period));
    }

    // ------------------------------------------------------------------
    // Entity (player, projectile, mob) bound
    // ------------------------------------------------------------------

    /**
     * Runs a task on the thread owning {@code entity}. If the entity is already retired the
     * task is dropped — entity state must never be touched after retirement anyway.
     */
    public static Task runEntity(Entity entity, Runnable runnable) {
        if (entity == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("entity");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(entity.getScheduler().run(plugin(), task -> runnable.run(), null));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule entity task, skipping to avoid thread violation", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTask(plugin(), runnable));
    }

    public static Task runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        if (entity == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("delayed entity");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(entity.getScheduler().runDelayed(plugin(), task -> runnable.run(), null,
                        Math.max(1L, delayTicks)));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule delayed entity task, skipping to avoid thread violation", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskLater(plugin(), runnable, delayTicks));
    }

    public static Task runEntityTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        if (entity == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("repeating entity");
            return Task.EMPTY;
        }
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            try {
                return new Task(entity.getScheduler().runAtFixedRate(plugin(), task -> runnable.run(), null,
                        Math.max(1L, delayTicks), period));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule repeating entity task, skipping to avoid thread violation", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delayTicks, period));
    }

    // ------------------------------------------------------------------
    // Region (world) bound
    // ------------------------------------------------------------------

    public static Task runRegion(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("region");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(Bukkit.getRegionScheduler().run(plugin(), location, task -> runnable.run()));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule region task for world "
                                + location.getWorld().getName() + " (unloaded?), task skipped", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTask(plugin(), runnable));
    }

    public static Task runRegionLater(Location location, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("delayed region");
            return Task.EMPTY;
        }
        if (FOLIA) {
            try {
                return new Task(Bukkit.getRegionScheduler().runDelayed(plugin(), location,
                        task -> runnable.run(), Math.max(1L, delayTicks)));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule delayed region task for world "
                                + location.getWorld().getName() + " (unloaded?), task skipped", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskLater(plugin(), runnable, delayTicks));
    }

    public static Task runRegionTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) {
            return Task.EMPTY;
        }
        if (!hasPlugin()) {
            logUnavailable("repeating region");
            return Task.EMPTY;
        }
        long period = Math.max(1L, periodTicks);
        if (FOLIA) {
            try {
                return new Task(Bukkit.getRegionScheduler().runAtFixedRate(plugin(), location,
                        task -> runnable.run(), Math.max(1L, delayTicks), period));
            } catch (Throwable t) {
                plugin().getLogger().log(Level.WARNING,
                        "Failed to schedule repeating region task for world "
                                + location.getWorld().getName() + " (unloaded?), task skipped", t);
                return Task.EMPTY;
            }
        }
        return new Task(Bukkit.getScheduler().runTaskTimer(plugin(), runnable, delayTicks, period));
    }

    // ------------------------------------------------------------------
    // Command dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatches a single command from the correct context. Console command dispatch is a
     * global operation on Folia and must never run on a region thread.
     */
    public static void dispatchCommand(CommandSender sender, String command) {
        dispatchCommands(sender, List.of(command));
    }

    /**
     * Dispatches commands sequentially inside a single global task. This guarantees
     * ordering, runs each command exactly once, and keeps the region thread free.
     */
    public static void dispatchCommands(CommandSender sender, List<String> commands) {
        if (sender == null || commands == null || commands.isEmpty()) {
            return;
        }
        List<String> queue = List.copyOf(commands);
        runGlobal(() -> {
            for (String command : queue) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                try {
                    Bukkit.dispatchCommand(sender, command);
                } catch (Throwable t) {
                    Plugin current = plugin();
                    if (current != null) {
                        current.getLogger().log(Level.WARNING,
                                "Failed to dispatch command '" + command + "'", t);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Cancels every task owned by the plugin. Region-scoped tasks are tied to their region
     * and entity tasks to their entity, both of which are retired with the server state,
     * while global/async tasks are cancelled directly.
     */
    public static void cancelAll(Plugin owner) {
        if (owner == null) {
            return;
        }
        try {
            if (FOLIA) {
                Bukkit.getGlobalRegionScheduler().cancelTasks(owner);
                Bukkit.getAsyncScheduler().cancelTasks(owner);
            } else {
                Bukkit.getScheduler().cancelTasks(owner);
            }
        } catch (Throwable t) {
            owner.getLogger().log(Level.WARNING, "Failed to cancel plugin tasks", t);
        }
    }

    private static void logUnavailable(String type) {
        Plugin current = plugin;
        if (current != null) {
            current.getLogger().warning("Cannot schedule " + type + " task: plugin is not enabled");
        }
    }

    /**
     * Platform-neutral task handle wrapping either a BukkitTask or a Folia ScheduledTask.
     */
    public static final class Task {

        public static final Task EMPTY = new Task(null);

        private final Object task;

        private Task(Object task) {
            this.task = task;
        }

        public void cancel() {
            Object current = task;
            if (current == null) {
                return;
            }
            try {
                if (FOLIA) {
                    if (current instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask) {
                        scheduledTask.cancel();
                    }
                } else if (current instanceof BukkitTask bukkitTask) {
                    bukkitTask.cancel();
                }
            } catch (Throwable t) {
                Plugin currentPlugin = plugin();
                if (currentPlugin != null) {
                    currentPlugin.getLogger().log(Level.WARNING, "Failed to cancel task", t);
                }
            }
        }

        public boolean isCancelled() {
            Object current = task;
            if (current == null) {
                return true;
            }
            if (FOLIA) {
                if (current instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask) {
                    return scheduledTask.isCancelled();
                }
            } else if (current instanceof BukkitTask bukkitTask) {
                return bukkitTask.isCancelled();
            }
            return true;
        }

        public Object getTask() {
            return task;
        }
    }
}
