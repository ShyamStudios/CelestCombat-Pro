package com.shyamstudio.celestCombatPro.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class EnderPearlEvent extends PlayerEvent implements Cancellable {
    
    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;
    private final long remainingCooldown;
    private final boolean inCombat;
    
    public EnderPearlEvent(Player player, long remainingCooldown, boolean inCombat) {
        super(player);
        this.remainingCooldown = remainingCooldown;
        this.inCombat = inCombat;
    }
    
    public long getRemainingCooldown() {
        return remainingCooldown;
    }
    
    public boolean isInCombat() {
        return inCombat;
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
