package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.combat.DeathAnimationManager;
import com.shyamstudio.celestCombatPro.messages.MessageManager;
import com.shyamstudio.celestCombatPro.protection.NewbieProtectionManager;
import com.shyamstudio.celestCombatPro.rewards.KillRewardManager;
import com.shyamstudio.celestCombatPro.api.CelestCombatAPI;
import com.shyamstudio.celestCombatPro.api.events.PreCombatEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CombatListeners implements Listener {
    private final CelestCombatPro plugin;
    private NewbieProtectionManager newbieProtectionManager;
    private KillRewardManager killRewardManager;
    private DeathAnimationManager deathAnimationManager;
    private MessageManager messageManager;

    private final Map<UUID, Boolean> playerLoggedOutInCombat = new ConcurrentHashMap<>();
    // Add a map to track the last damage source for each player
    private final Map<UUID, UUID> lastDamageSource = new ConcurrentHashMap<>();
    // Add a map to cleanup stale damage records
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    // Cleanup threshold (5 minutes)
    private static final long DAMAGE_RECORD_CLEANUP_THRESHOLD = TimeUnit.MINUTES.toMillis(5);

    public CombatListeners(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageManager = plugin.getMessageService();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        // Only block explosion-style damage types inside safe zones
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            if (CelestCombatPro.getInstance().getWorldGuardHook() != null
                    && CelestCombatPro.getInstance().getWorldGuardHook().isLocationInSafeZone(player.getLocation())) {
                event.setCancelled(true);
                plugin.debug("Cancelled explosion damage in safe zone for: " + player.getName());
            }
        }
    }

    /**
     * Reload all manager references to apply configuration changes
     */
    public void reload() {
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageManager = plugin.getMessageService();

        plugin.debug("CombatListeners managers reloaded successfully");
    }

    // NOTE: This method is now registered dynamically with configurable priority
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player victim = (Player) event.getEntity();
        Player attacker = null;
        Entity damager = event.getDamager();

        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        // Handle newbie protection checks
        if (attacker != null) {
            // Check if victim has newbie protection from PvP
            if (newbieProtectionManager.shouldProtectFromPvP() &&
                    newbieProtectionManager.hasProtection(victim)) {

                // Handle the protection (sends messages and potentially removes protection)
                if (newbieProtectionManager.handleDamageReceived(victim, attacker)) {
                    event.setCancelled(true);
                    plugin.debug("Blocked PvP damage to protected newbie: " + victim.getName());
                    return;
                }
            }

            // Handle when protected player deals damage (removes protection if configured)
            if (newbieProtectionManager.hasProtection(attacker)) {
                newbieProtectionManager.handleDamageDealt(attacker);
            }

            // Continue with normal combat logic if damage wasn't blocked
            if (!attacker.equals(victim)) {
                // Track this as the most recent damage source
                UUID victimId = victim.getUniqueId();
                lastDamageSource.put(victimId, attacker.getUniqueId());
                lastDamageTime.put(victimId, System.currentTimeMillis());

                // Determine combat cause
                PreCombatEvent.CombatCause cause = damager instanceof Projectile 
                    ? PreCombatEvent.CombatCause.PROJECTILE 
                    : PreCombatEvent.CombatCause.PLAYER_ATTACK;

                // Combat tag both players using API
                CelestCombatAPI.getCombatAPI().tagPlayer(attacker, victim, cause);
                CelestCombatAPI.getCombatAPI().tagPlayer(victim, attacker, cause);

                // Perform cleanup of stale records periodically
                if (lastDamageTime.size() > 100) {
                    cleanupStaleDamageRecords();
                }
            }
        } else {
            // Check if victim has newbie protection from mobs
            if (newbieProtectionManager.shouldProtectFromMobs() &&
                    newbieProtectionManager.hasProtection(victim)) {
                event.setCancelled(true);
                plugin.debug("Blocked mob damage to protected newbie: " + victim.getName());
            }
        }
    }

    private void cleanupStaleDamageRecords() {
        long currentTime = System.currentTimeMillis();
        lastDamageTime.entrySet().removeIf(entry ->
                (currentTime - entry.getValue()) > DAMAGE_RECORD_CLEANUP_THRESHOLD);

        // Also clean up damage sources for players that don't have a timestamp anymore
        lastDamageSource.keySet().removeIf(uuid -> !lastDamageTime.containsKey(uuid));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            playerLoggedOutInCombat.put(player.getUniqueId(), true);

            // Check if combat logout punishment is enabled
            if (plugin.getConfig().getBoolean("combat.combat_logout.enabled", true)) {
                // Get the opponent
                Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(player);
                
                // Kill the player if configured
                if (plugin.getConfig().getBoolean("combat.combat_logout.kill_player", true)) {
                    CelestCombatAPI.getCombatAPI().punishCombatLogout(player);
                }
                
                // Execute punishment commands
                List<String> punishmentCommands = plugin.getConfig().getStringList("combat.combat_logout.punishment_commands");
                for (String cmd : punishmentCommands) {
                    String finalCmd = cmd
                            .replace("%player%", player.getName())
                            .replace("%attacker%", opponent != null ? opponent.getName() : "Unknown");
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalCmd);
                }
                
                // Reward the attacker if configured and opponent exists
                if (plugin.getConfig().getBoolean("combat.combat_logout.reward_attacker", true) && opponent != null && opponent.isOnline()) {
                    // Give kill rewards
                    killRewardManager.giveKillReward(opponent, player);
                    
                    // Execute attacker reward commands
                    List<String> rewardCommands = plugin.getConfig().getStringList("combat.combat_logout.attacker_reward_commands");
                    for (String cmd : rewardCommands) {
                        String finalCmd = cmd
                                .replace("%player%", opponent.getName())
                                .replace("%victim%", player.getName());
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalCmd);
                    }
                    
                    // Send message to attacker
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("victim", player.getName());
                    messageManager.sendMessage(opponent, "combat_logout_attacker_reward", placeholders);
                    
                    // Perform death animation
                    deathAnimationManager.performDeathAnimation(player, opponent);
                    
                    // Remove opponent from combat
                    CelestCombatAPI.getCombatAPI().removeFromCombatSilently(opponent);
                } else if (opponent == null) {
                    // No opponent, just perform death animation
                    deathAnimationManager.performDeathAnimation(player, null);
                }
            } else {
                // Punishment disabled, just punish normally
                CelestCombatAPI.getCombatAPI().punishCombatLogout(player);
            }

        } else {
            playerLoggedOutInCombat.put(player.getUniqueId(), false);
        }
    }

    // Add a listener for PlayerKickEvent to track admin kicks
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            // Check if exempt_admin_kick is enabled and this was an admin kick
            if (plugin.getConfig().getBoolean("combat.exempt_admin_kick", true)) {

                // Don't punish, just remove from combat
                Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(player);
                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(player);

                if (opponent != null) {
                    CelestCombatAPI.getCombatAPI().removeFromCombat(opponent);
                }
            } else {
                // Regular kick, treat as combat logout
                Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(player);
                playerLoggedOutInCombat.put(player.getUniqueId(), true);

                // Punish for combat logging
                CelestCombatAPI.getCombatAPI().punishCombatLogout(player);

                if (opponent != null && opponent.isOnline()) {
                    killRewardManager.giveKillReward(opponent, player);
                    deathAnimationManager.performDeathAnimation(player, opponent);
                } else {
                    deathAnimationManager.performDeathAnimation(player, null);
                }

                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(player);
                if (opponent != null) {
                    CelestCombatAPI.getCombatAPI().removeFromCombat(opponent);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        UUID victimId = victim.getUniqueId();

        // Remove newbie protection on death (if they had it)
        if (newbieProtectionManager.hasProtection(victim)) {
            newbieProtectionManager.removeProtection(victim, false);
            plugin.debug("Removed newbie protection from " + victim.getName() + " due to death");
        }

        // If player directly killed by another player
        if (killer != null && !killer.equals(victim)) {
            // Execute kill reward commands using KillRewardManager
            killRewardManager.giveKillReward(killer, victim);

            // Perform death animation
            deathAnimationManager.performDeathAnimation(victim, killer);

            // Remove from combat - killer's combat timer is removed on kill
            CelestCombatAPI.getCombatAPI().removeFromCombatSilently(victim);
            CelestCombatAPI.getCombatAPI().removeFromCombatSilently(killer);
        }
        // If player died by other causes but was in combat
        else if (CelestCombatAPI.getCombatAPI().isInCombat(victim)) {
            Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(victim);

            // Check if we have an opponent or a recent damage source
            Player actualKiller = null;
            if (opponent != null && opponent.isOnline()) {
                // Give rewards to the combat opponent
                killRewardManager.giveKillReward(opponent, victim);
                deathAnimationManager.performDeathAnimation(victim, opponent);
                actualKiller = opponent;
            } else if (lastDamageSource.containsKey(victimId)) {
                // Try to get the last player who damaged this player
                UUID lastAttackerUuid = lastDamageSource.get(victimId);
                Player lastAttacker = plugin.getServer().getPlayer(lastAttackerUuid);

                if (lastAttacker != null && lastAttacker.isOnline() && !lastAttacker.equals(victim)) {
                    killRewardManager.giveKillReward(lastAttacker, victim);
                    deathAnimationManager.performDeathAnimation(victim, lastAttacker);
                    actualKiller = lastAttacker;
                } else {
                    // No valid attacker found
                    deathAnimationManager.performDeathAnimation(victim, null);
                }
            } else {
                // No attacker information available
                deathAnimationManager.performDeathAnimation(victim, null);
            }

            // Clean up combat state - remove killer's combat timer on kill
            CelestCombatAPI.getCombatAPI().removeFromCombatSilently(victim);
            if (actualKiller != null) {
                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(actualKiller);
            } else if (opponent != null) {
                CelestCombatAPI.getCombatAPI().removeFromCombatSilently(opponent);
            }

            // Clean up damage tracking
            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        } else {
            // Player died outside of combat
            deathAnimationManager.performDeathAnimation(victim, null);

            // Clean up any stale damage tracking
            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Handle newbie protection for new players
        newbieProtectionManager.handlePlayerJoin(player);

        if (playerLoggedOutInCombat.containsKey(playerUUID)) {
            if (playerLoggedOutInCombat.get(playerUUID)) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                messageManager.sendMessage(player, "player_died_combat_logout", placeholders);
            }
            // Clean up the map to prevent memory leaks
            playerLoggedOutInCombat.remove(playerUUID);
        }

        // Clean up any stale damage records for this player
        lastDamageSource.remove(playerUUID);
        lastDamageTime.remove(playerUUID);
    }

    // Use LOW priority to ensure command blocking happens BEFORE other plugins (like EssentialsX GUI)
    // process the command. This prevents plugins that don't respect cancelled events from bypassing
    // the command restrictions during combat.
    // NOTE: This method is now registered dynamically with configurable priority
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (CelestCombatAPI.getCombatAPI().isInCombat(player)) {
            String fullCommand = event.getMessage().substring(1); // Remove leading "/"
            String command = fullCommand.split(" ")[0].toLowerCase();

            // Get command blocking mode from config
            String blockMode = plugin.getConfig().getString("combat.command_block_mode", "whitelist").toLowerCase();

            // Determine if the command should be blocked based on the mode
            boolean shouldBlock = false;

            if ("blacklist".equalsIgnoreCase(blockMode)) {
                // Blacklist mode - block commands in the list
                List<String> blockedCommands = plugin.getConfig().getStringList("combat.blocked_commands");

                for (String blockedCmd : blockedCommands) {
                    String blockedCmdLower = blockedCmd.toLowerCase();
                    if (command.equalsIgnoreCase(blockedCmdLower) ||
                            (blockedCmdLower.endsWith("*") && command.startsWith(blockedCmdLower.substring(0, blockedCmdLower.length() - 1)))) {
                        shouldBlock = true;
                        break;
                    }
                }
            } else {
                // Whitelist mode - allow only commands in the list
                List<String> allowedCommands = plugin.getConfig().getStringList("combat.allowed_commands");
                shouldBlock = true; // Block by default

                for (String allowedCmd : allowedCommands) {
                    String allowedCmdLower = allowedCmd.toLowerCase();
                    if (command.equalsIgnoreCase(allowedCmdLower) ||
                            (allowedCmdLower.endsWith("*") && command.startsWith(allowedCmdLower.substring(0, allowedCmdLower.length() - 1)))) {
                        shouldBlock = false; // Command is allowed
                        break;
                    }
                }
            }

            // Block the command if necessary
            if (shouldBlock) {
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("command", command);
                placeholders.put("time", String.valueOf(CelestCombatAPI.getCombatAPI().getRemainingCombatTime(player)));
                messageManager.sendMessage(player, "command_blocked_in_combat", placeholders);
            }
        }
    }

    // NOTE: This method is now registered dynamically with configurable priority
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // If player is trying to enable flight
        if (event.isFlying() && CelestCombatAPI.getCombatAPI().shouldDisableFlight(player)) {
            // Only cancel if player is actually trying to fly (not just falling/knockback)
            // Check if player is on ground or has significant upward velocity (intentional flight)
            if (player.isOnGround() || player.getVelocity().getY() > 0) {
                event.setCancelled(true);
            }
        }
    }

    // Method to clean up any lingering data when the plugin disables
    public void shutdown() {
        playerLoggedOutInCombat.clear();
        lastDamageSource.clear();
        lastDamageTime.clear();
    }
}