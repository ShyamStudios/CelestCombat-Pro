package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.configs.EventPriorityManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Handles dynamic registration of event handlers with configurable priorities
 * This allows event priorities to be changed via configuration and reloaded
 */
public class DynamicEventHandler {
    
    private final CelestCombatPro plugin;
    private final EventPriorityManager priorityManager;
    private final Map<String, RegisteredHandler> registeredHandlers;
    
    public DynamicEventHandler(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.priorityManager = plugin.getCombatManager().getEventPriorityManager();
        this.registeredHandlers = new HashMap<>();
    }
    
    /**
     * Register all dynamic event handlers with configurable priorities
     */
    public void registerHandlers() {
        // Unregister existing handlers first
        unregisterHandlers();
        
        // Register command blocking handler
        registerCommandHandler();
        
        // Register other handlers that need configurable priorities
        registerCombatDamageHandler();
        registerPlayerMovementHandler();
        registerTeleportationHandler();
        registerItemUsageHandler();
        registerFlightControlHandler();
        
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Registered " + registeredHandlers.size() + " dynamic event handlers");
        }
    }
    
    /**
     * Unregister all dynamic event handlers
     */
    public void unregisterHandlers() {
        for (RegisteredHandler handler : registeredHandlers.values()) {
            HandlerList.unregisterAll(handler.listener);
        }
        registeredHandlers.clear();
        
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Unregistered all dynamic event handlers");
        }
    }
    
    private void registerCommandHandler() {
        EventPriority priority = priorityManager.getPriority("command_blocking");
        CombatListeners listener = new CombatListeners(plugin);
        
        try {
            Method method = CombatListeners.class.getDeclaredMethod("onPlayerCommand", 
                org.bukkit.event.player.PlayerCommandPreprocessEvent.class);
            
            EventExecutor executor = (l, event) -> {
                try {
                    if (event instanceof org.bukkit.event.player.PlayerCommandPreprocessEvent) {
                        method.invoke(l, event);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing command handler", e);
                }
            };
            
            Bukkit.getPluginManager().registerEvent(
                org.bukkit.event.player.PlayerCommandPreprocessEvent.class,
                listener,
                priority,
                executor,
                plugin,
                true // ignoreCancelled
            );
            
            registeredHandlers.put("command_blocking", new RegisteredHandler(listener, priority));
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Registered command blocking handler with priority: " + priority.name());
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register command handler", e);
        }
    }
    
    private void registerCombatDamageHandler() {
        EventPriority priority = priorityManager.getPriority("combat_damage");
        CombatListeners listener = new CombatListeners(plugin);
        
        try {
            // Register EntityDamageByEntityEvent handler
            Method method = CombatListeners.class.getDeclaredMethod("onEntityDamageByEntity", 
                org.bukkit.event.entity.EntityDamageByEntityEvent.class);
            
            EventExecutor executor = (l, event) -> {
                try {
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
                        method.invoke(l, event);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing combat damage handler", e);
                }
            };
            
            Bukkit.getPluginManager().registerEvent(
                org.bukkit.event.entity.EntityDamageByEntityEvent.class,
                listener,
                priority,
                executor,
                plugin,
                true // ignoreCancelled
            );
            
            registeredHandlers.put("combat_damage", new RegisteredHandler(listener, priority));
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Registered combat damage handler with priority: " + priority.name());
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register combat damage handler", e);
        }
    }
    
    private void registerPlayerMovementHandler() {
        // This would be for movement-related handlers if needed
        // Currently not implemented as movement handlers are in protection hooks
    }
    
    private void registerTeleportationHandler() {
        EventPriority priority = priorityManager.getPriority("teleportation");
        EnderPearlListener listener = new EnderPearlListener(plugin, plugin.getCombatManager());
        
        try {
            Method method = EnderPearlListener.class.getDeclaredMethod("onEnderPearlTeleport", 
                org.bukkit.event.player.PlayerTeleportEvent.class);
            
            EventExecutor executor = (l, event) -> {
                try {
                    if (event instanceof org.bukkit.event.player.PlayerTeleportEvent) {
                        method.invoke(l, event);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing teleportation handler", e);
                }
            };
            
            Bukkit.getPluginManager().registerEvent(
                org.bukkit.event.player.PlayerTeleportEvent.class,
                listener,
                priority,
                executor,
                plugin,
                true // ignoreCancelled
            );
            
            registeredHandlers.put("teleportation", new RegisteredHandler(listener, priority));
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Registered teleportation handler with priority: " + priority.name());
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register teleportation handler", e);
        }
    }
    
    private void registerItemUsageHandler() {
        EventPriority priority = priorityManager.getPriority("item_usage");
        ItemRestrictionListener listener = new ItemRestrictionListener(plugin, plugin.getCombatManager());
        
        try {
            Method method = ItemRestrictionListener.class.getDeclaredMethod("onPlayerItemConsume", 
                org.bukkit.event.player.PlayerItemConsumeEvent.class);
            
            EventExecutor executor = (l, event) -> {
                try {
                    if (event instanceof org.bukkit.event.player.PlayerItemConsumeEvent) {
                        method.invoke(l, event);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing item usage handler", e);
                }
            };
            
            Bukkit.getPluginManager().registerEvent(
                org.bukkit.event.player.PlayerItemConsumeEvent.class,
                listener,
                priority,
                executor,
                plugin,
                true // ignoreCancelled
            );
            
            registeredHandlers.put("item_usage", new RegisteredHandler(listener, priority));
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Registered item usage handler with priority: " + priority.name());
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register item usage handler", e);
        }
    }
    
    private void registerFlightControlHandler() {
        EventPriority priority = priorityManager.getPriority("flight_control");
        CombatListeners listener = new CombatListeners(plugin);
        
        try {
            Method method = CombatListeners.class.getDeclaredMethod("onPlayerToggleFlight", 
                org.bukkit.event.player.PlayerToggleFlightEvent.class);
            
            EventExecutor executor = (l, event) -> {
                try {
                    if (event instanceof org.bukkit.event.player.PlayerToggleFlightEvent) {
                        method.invoke(l, event);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing flight control handler", e);
                }
            };
            
            Bukkit.getPluginManager().registerEvent(
                org.bukkit.event.player.PlayerToggleFlightEvent.class,
                listener,
                priority,
                executor,
                plugin,
                true // ignoreCancelled
            );
            
            registeredHandlers.put("flight_control", new RegisteredHandler(listener, priority));
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Registered flight control handler with priority: " + priority.name());
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register flight control handler", e);
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