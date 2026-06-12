package com.shyamstudio.celestCombatPro.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class PreCombatEvent extends PlayerEvent implements Cancellable {
    
    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;
    private final Player attacker;
    private final CombatCause cause;
    
    public PreCombatEvent(Player player, Player attacker, CombatCause cause) {
        super(player);
        this.attacker = attacker;
        this.cause = cause;
    }

    public Player getAttacker() {
        return attacker;
    }

    public CombatCause getCause() {
        return cause;
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

    public enum CombatCause {
        PLAYER_ATTACK,
        PROJECTILE,
        EXPLOSION,
        MAGIC,
        ENTITY_ATTACK,
        FIRE,
        LAVA,
        THORNS,
        POTION,
        UNKNOWN
    }
}
