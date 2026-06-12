package com.shyamstudio.celestCombatPro.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class CombatLogEvent extends PlayerEvent implements Cancellable {
    
    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;
    private final Player lastAttacker;
    private final long remainingCombatTime;
    private boolean shouldPunish = true;
    
    public CombatLogEvent(Player player, Player lastAttacker, long remainingCombatTime) {
        super(player);
        this.lastAttacker = lastAttacker;
        this.remainingCombatTime = remainingCombatTime;
    }
    
    public Player getLastAttacker() {
        return lastAttacker;
    }
    
    public long getRemainingCombatTime() {
        return remainingCombatTime;
    }
    
    public boolean shouldPunish() {
        return shouldPunish;
    }
    
    public void setShouldPunish(boolean shouldPunish) {
        this.shouldPunish = shouldPunish;
    }
    
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
    
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
    
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
    
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
