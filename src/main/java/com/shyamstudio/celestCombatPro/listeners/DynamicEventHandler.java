package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.configs.EventPriorityManager;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Registers every listener method through a single code path so that configurable event
 * priorities can be applied and changed on reload.
 *
 * <p>All handler methods use the plugin's long-lived listener instances. Creating fresh
 * listener instances per registration (as the previous implementation did) split shared
 * combat state such as {@code lastDamageSource} across multiple objects.
 */
public class DynamicEventHandler {

    private final CelestCombatPro plugin;
    private final EventPriorityManager priorityManager;
    private final Map<String, RegisteredHandler> registeredHandlers = new HashMap<>();

    public DynamicEventHandler(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.priorityManager = plugin.getCombatManager().getEventPriorityManager();
    }

    /**
     * Register all dynamic event handlers with configurable priorities
     */
    public void registerHandlers() {
        unregisterHandlers();

        CombatListeners combatListeners = plugin.getCombatListeners();
        ItemRestrictionListener itemRestrictionListener = plugin.getItemRestrictionListener();
        EnderPearlListener enderPearlListener = plugin.getEnderPearlListener();

        if (combatListeners == null || itemRestrictionListener == null || enderPearlListener == null) {
            plugin.getLogger().severe("Cannot register event handlers: listeners are not initialized");
            return;
        }

        // Configurable priorities
        register("command_blocking", combatListeners, PlayerCommandPreprocessEvent.class,
                "onPlayerCommand", priorityManager.getPriority("command_blocking"), true);
        register("combat_damage", combatListeners, EntityDamageByEntityEvent.class,
                "onEntityDamageByEntity", priorityManager.getPriority("combat_damage"), true);
        register("flight_control", combatListeners, PlayerToggleFlightEvent.class,
                "onPlayerToggleFlight", priorityManager.getPriority("flight_control"), true);
        register("teleportation", enderPearlListener, PlayerTeleportEvent.class,
                "onEnderPearlTeleport", priorityManager.getPriority("teleportation"), true);
        register("item_usage", itemRestrictionListener, PlayerItemConsumeEvent.class,
                "onPlayerItemConsume", priorityManager.getPriority("item_usage"), true);

        // Fixed lifecycle priorities
        register("combat_safezone_damage", combatListeners, EntityDamageEvent.class,
                "onEntityDamage", EventPriority.HIGH, true);
        register("combat_join", combatListeners, PlayerJoinEvent.class,
                "onPlayerJoin", EventPriority.NORMAL, false);
        register("combat_quit", combatListeners, PlayerQuitEvent.class,
                "onPlayerQuit", EventPriority.HIGHEST, false);
        register("combat_kick", combatListeners, PlayerKickEvent.class,
                "onPlayerKick", EventPriority.MONITOR, false);
        register("combat_death", combatListeners, PlayerDeathEvent.class,
                "onPlayerDeath", EventPriority.MONITOR, false);
        register("item_move", itemRestrictionListener, PlayerMoveEvent.class,
                "onPlayerMoveEvent", EventPriority.HIGH, true);
        register("item_inventory", itemRestrictionListener, InventoryClickEvent.class,
                "onInventoryClick", EventPriority.HIGH, true);
        register("item_flight", itemRestrictionListener, PlayerToggleFlightEvent.class,
                "onPlayerToggleFlight", EventPriority.HIGH, true);
        register("pearl_use", enderPearlListener, PlayerInteractEvent.class,
                "onEnderPearlUse", EventPriority.HIGH, true);
        register("pearl_launch", enderPearlListener, ProjectileLaunchEvent.class,
                "onProjectileLaunch", EventPriority.HIGH, true);
        register("pearl_hit", enderPearlListener, ProjectileHitEvent.class,
                "onProjectileHit", EventPriority.MONITOR, true);
        register("pearl_quit", enderPearlListener, PlayerQuitEvent.class,
                "onPlayerQuit", EventPriority.MONITOR, false);

        if (plugin.isDebugMode()) {
            plugin.getLogger().info("Registered " + registeredHandlers.size() + " dynamic event handlers");
        }
    }

    /**
     * Unregister all dynamic event handlers
     */
    public void unregisterHandlers() {
        if (registeredHandlers.isEmpty()) {
            return;
        }
        Set<Listener> uniqueListeners = new HashSet<>();
        for (RegisteredHandler handler : registeredHandlers.values()) {
            uniqueListeners.add(handler.listener);
        }
        for (Listener listener : uniqueListeners) {
            HandlerList.unregisterAll(listener);
        }
        registeredHandlers.clear();

        if (plugin.isDebugMode()) {
            plugin.getLogger().info("Unregistered all dynamic event handlers");
        }
    }

    private <T extends Event> void register(String id, Listener listener, Class<T> eventClass,
                                            String methodName, EventPriority priority, boolean ignoreCancelled) {
        try {
            Method method = listener.getClass().getDeclaredMethod(methodName, eventClass);
            method.setAccessible(true);

            EventExecutor executor = (executingListener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    method.invoke(executingListener, event);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    plugin.getLogger().log(Level.SEVERE, "Error executing event handler '" + id + "'", cause);
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().log(Level.SEVERE, "Error invoking event handler '" + id + "'", e);
                }
            };

            Bukkit.getPluginManager().registerEvent(eventClass, listener, priority, executor, plugin, ignoreCancelled);
            registeredHandlers.put(id, new RegisteredHandler(listener, priority));

            if (plugin.isDebugMode()) {
                plugin.getLogger().info("Registered event handler '" + id + "' with priority " + priority.name());
            }
        } catch (NoSuchMethodException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to register event handler '" + id + "': method " + methodName + " not found", e);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register event handler '" + id + "'", t);
        }
    }

    /**
     * Get current priority for a specific handler
     */
    public EventPriority getCurrentPriority(String handlerType) {
        RegisteredHandler handler = registeredHandlers.get(handlerType);
        return handler != null ? handler.priority : null;
    }

    /**
     * Get debug information about registered handlers
     */
    public Map<String, EventPriority> getRegisteredHandlers() {
        Map<String, EventPriority> result = new HashMap<>();
        for (Map.Entry<String, RegisteredHandler> entry : registeredHandlers.entrySet()) {
            result.put(entry.getKey(), entry.getValue().priority);
        }
        return result;
    }

    /**
     * Internal class to track registered handlers
     */
    private static class RegisteredHandler {
        final Listener listener;
        final EventPriority priority;

        RegisteredHandler(Listener listener, EventPriority priority) {
            this.listener = listener;
            this.priority = priority;
        }
    }
}
