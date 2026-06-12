package com.shyamstudio.celestCombatPro.configs;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import org.bukkit.event.EventPriority;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages event priorities from configuration
 * Caches priorities on plugin start/reload for performance
 */
public class EventPriorityManager {
    
    private final CelestCombatPro plugin;
    private final Map<String, EventPriority> cachedPriorities;
    
    // Default priorities as fallback
    private static final Map<String, EventPriority> DEFAULT_PRIORITIES = new HashMap<>();
    static {
        DEFAULT_PRIORITIES.put("command_blocking", EventPriority.LOW);
        DEFAULT_PRIORITIES.put("combat_damage", EventPriority.HIGH);
        DEFAULT_PRIORITIES.put("player_movement", EventPriority.HIGH);
        DEFAULT_PRIORITIES.put("teleportation", EventPriority.HIGH);
        DEFAULT_PRIORITIES.put("item_usage", EventPriority.HIGH);
        DEFAULT_PRIORITIES.put("flight_control", EventPriority.HIGH);
    }
    
    public EventPriorityManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.cachedPriorities = new HashMap<>();
        loadPriorities();
    }
    
    /**
     * Load and cache all event priorities from config
     * Called on plugin start and reload
     */
    public void loadPriorities() {
        cachedPriorities.clear();
        
        for (String key : DEFAULT_PRIORITIES.keySet()) {
            String configPath = "event_priorities." + key;
            String priorityString = plugin.getConfig().getString(configPath);
            
            EventPriority priority = parsePriority(priorityString, key);
            cachedPriorities.put(key, priority);
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Loaded event priority: " + key + " = " + priority.name());
            }
        }
    }
    
    /**
     * Get cached event priority for a specific event type
     * @param eventType The event type key (e.g., "command_blocking")
     * @return The cached EventPriority, or default if not found
     */
    public EventPriority getPriority(String eventType) {
        return cachedPriorities.getOrDefault(eventType, DEFAULT_PRIORITIES.get(eventType));
    }
    
    /**
     * Parse priority string to EventPriority enum
     * @param priorityString The priority string from config
     * @param eventType The event type for logging purposes
     * @return The parsed EventPriority or default
     */
    private EventPriority parsePriority(String priorityString, String eventType) {
        if (priorityString == null || priorityString.trim().isEmpty()) {
            EventPriority defaultPriority = DEFAULT_PRIORITIES.get(eventType);
            plugin.getLogger().warning("No priority configured for " + eventType + ", using default: " + defaultPriority.name());
            return defaultPriority;
        }
        
        try {
            return EventPriority.valueOf(priorityString.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            EventPriority defaultPriority = DEFAULT_PRIORITIES.get(eventType);
            plugin.getLogger().log(Level.WARNING, 
                "Invalid event priority '" + priorityString + "' for " + eventType + 
                ". Valid values: LOWEST, LOW, NORMAL, HIGH, HIGHEST. Using default: " + defaultPriority.name(), e);
            return defaultPriority;
        }
    }
    
    /**
     * Get all available priority options for validation
     * @return Array of valid priority strings
     */
    public static String[] getValidPriorities() {
        return new String[]{"LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST"};
    }
    
    /**
     * Check if a priority string is valid
     * @param priority The priority string to check
     * @return true if valid, false otherwise
     */
    public static boolean isValidPriority(String priority) {
        if (priority == null) return false;
        try {
            EventPriority.valueOf(priority.toUpperCase().trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * Get debug information about current priorities
     * @return Map of event types to their current priorities
     */
    public Map<String, EventPriority> getCurrentPriorities() {
        return new HashMap<>(cachedPriorities);
    }
}