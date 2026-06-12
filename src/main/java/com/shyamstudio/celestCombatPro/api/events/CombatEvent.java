package com.shyamstudio.celestCombatPro.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class CombatEvent extends PlayerEvent {
    
    private static final HandlerList handlers = new HandlerList();
    private final Player attacker;
    private final PreCombatEvent.CombatCause cause;
    private final long combatDuration;
    private final boolean wasAlreadyInCombat;
    
    public CombatEvent(Player player, Player attacker, PreCombatEvent.CombatCause cause, long combatDuration, boolean wasAlreadyInCombat) {
        super(player);
        this.attacker = attacker;
        this.cause = cause;
        this.combatDuration = combatDuration;
        this.wasAlreadyInCombat = wasAlreadyInCombat;
    }
    
    public Player getAttacker() {
        return attacker;
    }
    
    public PreCombatEvent.CombatCause getCause() {
        return cause;
    }
    
    public long getCombatDuration() {
        return combatDuration;
    }
    
    public boolean wasAlreadyInCombat() {
        return wasAlreadyInCombat;
    }
    
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
    
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
