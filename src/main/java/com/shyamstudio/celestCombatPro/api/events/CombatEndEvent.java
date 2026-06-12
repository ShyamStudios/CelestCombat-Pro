package com.shyamstudio.celestCombatPro.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class CombatEndEvent extends PlayerEvent {
    
    private static final HandlerList handlers = new HandlerList();
    private final Player lastAttacker;
    private final CombatEndReason reason;
    private final long totalCombatTime;
    
    public CombatEndEvent(Player player, Player lastAttacker, CombatEndReason reason, long totalCombatTime) {
        super(player);
        this.lastAttacker = lastAttacker;
        this.reason = reason;
        this.totalCombatTime = totalCombatTime;
    }
    
    public Player getLastAttacker() {
        return lastAttacker;
    }
    
    public CombatEndReason getReason() {
        return reason;
    }
    
    public long getTotalCombatTime() {
        return totalCombatTime;
    }
    
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
    
    public static HandlerList getHandlerList() {
        return handlers;
    }
    
    public enum CombatEndReason {
        EXPIRED,
        DEATH,
        LOGOUT,
        COMMAND,
        WORLD_CHANGE,
        ADMIN_REMOVE,
        PLUGIN_DISABLE
    }
}
