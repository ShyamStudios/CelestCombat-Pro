package com.shyamstudio.celestCombatPro.api;

import com.shyamstudio.celestCombatPro.api.events.PreCombatEvent;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public interface CombatAPI {
    
    boolean isInCombat(Player player);
    
    void tagPlayer(Player player, Player attacker, PreCombatEvent.CombatCause cause);
    
    void removeFromCombat(Player player);
    
    void removeFromCombatSilently(Player player);
    
    Player getCombatOpponent(Player player);
    
    int getRemainingCombatTime(Player player);
    
    Map<UUID, Long> getPlayersInCombat();
    
    boolean isEnderPearlOnCooldown(Player player);
    
    void setEnderPearlCooldown(Player player);
    
    int getRemainingEnderPearlCooldown(Player player);
    
    boolean isTridentOnCooldown(Player player);
    
    void setTridentCooldown(Player player);
    
    int getRemainingTridentCooldown(Player player);
    
    boolean isTridentBanned(Player player);
    
    void refreshCombatOnPearlLand(Player player);
    
    void refreshCombatOnTridentLand(Player player);
    
    boolean shouldDisableFlight(Player player);
    
    void punishCombatLogout(Player player);
    
    /**
     * Disconnects a player without treating it as combat logging.
     * The player's combat tag remains active and will continue after reconnect.
     * 
     * @param player The player to disconnect
     */
    void disconnectPlayerSafely(Player player);
    
    long getCombatDuration();
    
    long getEnderPearlCooldownDuration();
    
    long getTridentCooldownDuration();
    
    boolean isFlightDisabledInCombat();
    
    boolean isEnderPearlCooldownEnabledInWorld(String worldName);
    
    boolean isTridentCooldownEnabledInWorld(String worldName);
    
    boolean isTridentBannedInWorld(String worldName);
}
