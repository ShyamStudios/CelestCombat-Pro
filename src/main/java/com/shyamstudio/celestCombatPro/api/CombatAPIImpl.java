package com.shyamstudio.celestCombatPro.api;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.api.events.*;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class CombatAPIImpl implements CombatAPI {
    
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    
    public CombatAPIImpl(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
    }
    
    @Override
    public boolean isInCombat(Player player) {
        return combatManager.isInCombat(player);
    }
    
    @Override
    public void tagPlayer(Player player, Player attacker, PreCombatEvent.CombatCause cause) {
        if (player == null || attacker == null) return;
        
        PreCombatEvent preCombatEvent = new PreCombatEvent(player, attacker, cause);
        Bukkit.getPluginManager().callEvent(preCombatEvent);
        
        if (preCombatEvent.isCancelled()) {
            return;
        }
        
        boolean wasAlreadyInCombat = isInCombat(player);
        combatManager.tagPlayer(player, attacker);
        
        CombatEvent combatEvent = new CombatEvent(player, attacker, cause, getCombatDuration(), wasAlreadyInCombat);
        Bukkit.getPluginManager().callEvent(combatEvent);
    }
    
    @Override
    public void removeFromCombat(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long totalCombatTime = System.currentTimeMillis() - (combatManager.getPlayersInCombat().get(player.getUniqueId()) - (getCombatDuration() * 1000));
        
        combatManager.removeFromCombat(player);
        
        CombatEndEvent combatEndEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.EXPIRED, totalCombatTime);
        Bukkit.getPluginManager().callEvent(combatEndEvent);
    }
    
    @Override
    public void removeFromCombatSilently(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long totalCombatTime = System.currentTimeMillis() - (combatManager.getPlayersInCombat().get(player.getUniqueId()) - (getCombatDuration() * 1000));
        
        combatManager.removeFromCombatSilently(player);
        
        CombatEndEvent combatEndEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.ADMIN_REMOVE, totalCombatTime);
        Bukkit.getPluginManager().callEvent(combatEndEvent);
    }
    
    @Override
    public Player getCombatOpponent(Player player) {
        return combatManager.getCombatOpponent(player);
    }
    
    @Override
    public int getRemainingCombatTime(Player player) {
        return combatManager.getRemainingCombatTime(player);
    }
    
    @Override
    public Map<UUID, Long> getPlayersInCombat() {
        return combatManager.getPlayersInCombat();
    }
    
    @Override
    public boolean isEnderPearlOnCooldown(Player player) {
        return combatManager.isEnderPearlOnCooldown(player);
    }
    
    @Override
    public void setEnderPearlCooldown(Player player) {
        if (player == null) return;
        
        long remainingCooldown = getRemainingEnderPearlCooldown(player);
        boolean inCombat = isInCombat(player);
        
        EnderPearlEvent event = new EnderPearlEvent(player, remainingCooldown, inCombat);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled()) {
            combatManager.setEnderPearlCooldown(player);
        }
    }
    
    @Override
    public int getRemainingEnderPearlCooldown(Player player) {
        return combatManager.getRemainingEnderPearlCooldown(player);
    }
    
    @Override
    public boolean isTridentOnCooldown(Player player) {
        return combatManager.isTridentOnCooldown(player);
    }
    
    @Override
    public void setTridentCooldown(Player player) {
        if (player == null) return;
        
        long remainingCooldown = getRemainingTridentCooldown(player);
        boolean inCombat = isInCombat(player);
        boolean banned = isTridentBanned(player);
        
        TridentEvent event = new TridentEvent(player, remainingCooldown, inCombat, banned);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled()) {
            combatManager.setTridentCooldown(player);
        }
    }
    
    @Override
    public int getRemainingTridentCooldown(Player player) {
        return combatManager.getRemainingTridentCooldown(player);
    }
    
    @Override
    public boolean isTridentBanned(Player player) {
        return combatManager.isTridentBanned(player);
    }
    
    @Override
    public void refreshCombatOnPearlLand(Player player) {
        combatManager.refreshCombatOnPearlLand(player);
    }
    
    @Override
    public void refreshCombatOnTridentLand(Player player) {
        combatManager.refreshCombatOnTridentLand(player);
    }
    
    @Override
    public boolean shouldDisableFlight(Player player) {
        return combatManager.shouldDisableFlight(player);
    }
    
    @Override
    public void punishCombatLogout(Player player) {
        if (player == null || !isInCombat(player)) return;
        
        Player lastAttacker = getCombatOpponent(player);
        long remainingTime = getRemainingCombatTime(player);
        
        CombatLogEvent event = new CombatLogEvent(player, lastAttacker, remainingTime);
        Bukkit.getPluginManager().callEvent(event);
        
        if (!event.isCancelled() && event.shouldPunish()) {
            combatManager.punishCombatLogout(player);
        }
        
        if (!event.isCancelled()) {
            CombatEndEvent endEvent = new CombatEndEvent(player, lastAttacker, CombatEndEvent.CombatEndReason.LOGOUT, 0);
            Bukkit.getPluginManager().callEvent(endEvent);
        }
    }
    
    @Override
    public void disconnectPlayerSafely(Player player) {
        if (player == null) return;
        
        // Kick the player without triggering combat log punishment
        // The combat tag remains active and will continue after reconnect
        player.kickPlayer("Disconnected safely by admin");
    }
    
    @Override
    public long getCombatDuration() {
        return plugin.getTimeFromConfig("combat.duration", "20s") / 20;
    }
    
    @Override
    public long getEnderPearlCooldownDuration() {
        return plugin.getTimeFromConfig("enderpearl_cooldown.duration", "10s") / 20;
    }
    
    @Override
    public long getTridentCooldownDuration() {
        return plugin.getTimeFromConfig("trident_cooldown.duration", "10s") / 20;
    }
    
    @Override
    public boolean isFlightDisabledInCombat() {
        return plugin.getConfig().getBoolean("combat.disable_flight", true);
    }
    
    @Override
    public boolean isEnderPearlCooldownEnabledInWorld(String worldName) {
        if (!plugin.getConfig().getBoolean("enderpearl_cooldown.enabled", true)) {
            return false;
        }
        
        if (plugin.getConfig().isConfigurationSection("enderpearl_cooldown.worlds")) {
            return plugin.getConfig().getBoolean("enderpearl_cooldown.worlds." + worldName, true);
        }
        
        return true;
    }
    
    @Override
    public boolean isTridentCooldownEnabledInWorld(String worldName) {
        if (!plugin.getConfig().getBoolean("trident_cooldown.enabled", true)) {
            return false;
        }
        
        if (plugin.getConfig().isConfigurationSection("trident_cooldown.worlds")) {
            return plugin.getConfig().getBoolean("trident_cooldown.worlds." + worldName, true);
        }
        
        return true;
    }
    
    @Override
    public boolean isTridentBannedInWorld(String worldName) {
        if (plugin.getConfig().isConfigurationSection("trident.banned_worlds")) {
            return plugin.getConfig().getBoolean("trident.banned_worlds." + worldName, false);
        }
        
        return false;
    }
}
