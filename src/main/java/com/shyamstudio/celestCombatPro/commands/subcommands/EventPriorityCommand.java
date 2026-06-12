package com.shyamstudio.celestCombatPro.commands.subcommands;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.commands.BaseCommand;
import com.shyamstudio.celestCombatPro.configs.EventPriorityManager;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;

import java.util.Map;

/**
 * Command to display current event priorities for debugging
 */
public class EventPriorityCommand extends BaseCommand {
    
    public EventPriorityCommand(CelestCombatPro plugin) {
        super(plugin);
    }
    
    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("celestcombat.admin.eventpriority")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        
        EventPriorityManager priorityManager = plugin.getCombatManager().getEventPriorityManager();
        
        sender.sendMessage(ChatColor.GOLD + "=== CelestCombat Event Priorities ===");
        sender.sendMessage(ChatColor.YELLOW + "Current event handler priorities:");
        sender.sendMessage("");
        
        // Show configured priorities
        Map<String, EventPriority> currentPriorities = priorityManager.getCurrentPriorities();
        for (Map.Entry<String, EventPriority> entry : currentPriorities.entrySet()) {
            String eventType = entry.getKey();
            EventPriority priority = entry.getValue();
            
            ChatColor color = getPriorityColor(priority);
            sender.sendMessage(ChatColor.WHITE + "• " + formatEventType(eventType) + ": " + 
                             color + priority.name());
        }
        
        sender.sendMessage("");
        sender.sendMessage(ChatColor.GRAY + "Priority order: LOWEST → LOW → NORMAL → HIGH → HIGHEST");
        sender.sendMessage(ChatColor.GRAY + "Use /celestcombat reload to apply config changes");
        
        // Show registered handlers if debug is enabled
        if (plugin.getConfig().getBoolean("debug", false)) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.AQUA + "=== Registered Dynamic Handlers ===");
            Map<String, EventPriority> registeredHandlers = plugin.getDynamicEventHandler().getRegisteredHandlers();
            for (Map.Entry<String, EventPriority> entry : registeredHandlers.entrySet()) {
                ChatColor color = getPriorityColor(entry.getValue());
                sender.sendMessage(ChatColor.WHITE + "• " + entry.getKey() + ": " + 
                                 color + entry.getValue().name());
            }
        }
        
        return true;
    }
    
    private String formatEventType(String eventType) {
        // Convert snake_case to Title Case
        String[] words = eventType.split("_");
        StringBuilder formatted = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            if (i > 0) formatted.append(" ");
            formatted.append(words[i].substring(0, 1).toUpperCase())
                     .append(words[i].substring(1).toLowerCase());
        }
        
        return formatted.toString();
    }
    
    private ChatColor getPriorityColor(EventPriority priority) {
        switch (priority) {
            case LOWEST:
                return ChatColor.DARK_RED;
            case LOW:
                return ChatColor.RED;
            case NORMAL:
                return ChatColor.YELLOW;
            case HIGH:
                return ChatColor.GREEN;
            case HIGHEST:
                return ChatColor.DARK_GREEN;
            default:
                return ChatColor.WHITE;
        }
    }
    
    public String getDescription() {
        return "Display current event handler priorities";
    }
    
    public String getUsage() {
        return "/celestcombat eventpriority";
    }
    
    @Override
    public String getPermission() {
        return "celestcombat.admin.eventpriority";
    }
    
    @Override
    public boolean isPlayerOnly() {
        return false; // Can be used by console
    }
}