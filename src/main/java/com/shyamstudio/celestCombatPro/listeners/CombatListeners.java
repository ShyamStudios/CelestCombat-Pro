package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.DeathAnimationManager;
import com.shyamstudio.celestCombatPro.messages.MessageManager;
import com.shyamstudio.celestCombatPro.protection.NewbieProtectionManager;
import com.shyamstudio.celestCombatPro.rewards.KillRewardManager;
import com.shyamstudio.celestCombatPro.api.CelestCombatAPI;
import com.shyamstudio.celestCombatPro.api.CombatAPI;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final Map<UUID, UUID> lastDamageSource = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private static final long DAMAGE_RECORD_CLEANUP_THRESHOLD = TimeUnit.MINUTES.toMillis(5);

    // Cached combat-logout config (avoid repeated getConfig() calls per-event)
    private volatile boolean combatLogoutEnabled;
    private volatile boolean combatLogoutKillPlayer;
    private volatile boolean combatLogoutRewardAttacker;
    private volatile boolean exemptAdminKick;
    private volatile List<String> punishmentCommands = Collections.emptyList();
    private volatile List<String> attackerRewardCommands = Collections.emptyList();

    // Cached command blocking rules (avoid config lookups + list scans per command)
    private volatile String commandBlockMode = "whitelist";
    private volatile Set<String> blockedCommands = Collections.emptySet();
    private volatile List<String> blockedCommandWildcards = Collections.emptyList();
    private volatile Set<String> allowedCommands = Collections.emptySet();
    private volatile List<String> allowedCommandWildcards = Collections.emptyList();

    public CombatListeners(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageManager = plugin.getMessageService();
        loadConfig();
    }

    private void loadConfig() {
        this.combatLogoutEnabled     = plugin.getConfig().getBoolean("combat.combat_logout.enabled", true);
        this.combatLogoutKillPlayer  = plugin.getConfig().getBoolean("combat.combat_logout.kill_player", true);
        this.combatLogoutRewardAttacker = plugin.getConfig().getBoolean("combat.combat_logout.reward_attacker", true);
        this.exemptAdminKick         = plugin.getConfig().getBoolean("combat.exempt_admin_kick", true);
        this.punishmentCommands      = List.copyOf(plugin.getConfig().getStringList("combat.combat_logout.punishment_commands"));
        this.attackerRewardCommands  = List.copyOf(plugin.getConfig().getStringList("combat.combat_logout.attacker_reward_commands"));

        this.commandBlockMode = plugin.getConfig().getString("combat.command_block_mode", "whitelist").toLowerCase(Locale.ROOT);
        this.blockedCommands = compileExact(plugin.getConfig().getStringList("combat.blocked_commands"));
        this.blockedCommandWildcards = compileWildcards(plugin.getConfig().getStringList("combat.blocked_commands"));
        this.allowedCommands = compileExact(plugin.getConfig().getStringList("combat.allowed_commands"));
        this.allowedCommandWildcards = compileWildcards(plugin.getConfig().getStringList("combat.allowed_commands"));
    }

    private static Set<String> compileExact(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>(configured.size());
        for (String raw : configured) {
            if (raw == null) continue;
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !value.endsWith("*")) {
                result.add(value);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<String> compileWildcards(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(configured.size());
        for (String raw : configured) {
            if (raw == null) continue;
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.endsWith("*")) {
                value = value.substring(0, value.length() - 1);
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matchesRules(String command, Set<String> exact, List<String> wildcards) {
        if (exact.contains(command)) {
            return true;
        }
        for (int i = 0; i < wildcards.size(); i++) {
            if (command.startsWith(wildcards.get(i))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Only block explosion-style damage types inside safe zones
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            if (plugin.getWorldGuardHook() != null
                    && plugin.getWorldGuardHook().isLocationInSafeZone(player.getLocation())) {
                event.setCancelled(true);
                if (plugin.isDebugMode()) {
                    plugin.debug("Cancelled explosion damage in safe zone for: " + player.getName());
                }
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
        loadConfig();
        plugin.debug("CombatListeners managers reloaded successfully");
    }

    // NOTE: This method is registered dynamically with configurable priority
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

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
                    if (plugin.isDebugMode()) {
                        plugin.debug("Blocked PvP damage to protected newbie: " + victim.getName());
                    }
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
                CombatAPI api = CelestCombatAPI.getCombatAPI();
                if (api != null) {
                    api.tagPlayer(attacker, victim, cause);
                    api.tagPlayer(victim, attacker, cause);
                }

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
                if (plugin.isDebugMode()) {
                    plugin.debug("Blocked mob damage to protected newbie: " + victim.getName());
                }
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
        UUID playerUUID = player.getUniqueId();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        CombatAPI api = CelestCombatAPI.getCombatAPI();
        if (api == null) {
            return;
        }

        // Cooldowns do not survive a disconnect (matches previous timer cleanup behavior)
        plugin.getCombatManager().clearTransientCooldowns(playerUUID);

        if (!api.isInCombat(player)) {
            playerLoggedOutInCombat.put(playerUUID, false);
            return;
        }

        playerLoggedOutInCombat.put(playerUUID, true);

        if (!combatLogoutEnabled) {
            api.punishCombatLogout(player);
            return;
        }

        Player opponent = api.getCombatOpponent(player);
        String playerName = player.getName();

        // Queue punishment commands before the kill: killing fires PlayerDeathEvent
        // synchronously, which may already enqueue kill-reward commands.
        if (!punishmentCommands.isEmpty()) {
            String attackerName = opponent != null ? opponent.getName() : "Unknown";
            List<String> processed = new ArrayList<>(punishmentCommands.size());
            for (String cmd : punishmentCommands) {
                if (cmd == null || cmd.isBlank()) continue;
                processed.add(cmd
                        .replace("%player%", playerName)
                        .replace("%attacker%", attackerName));
            }
            Scheduler.dispatchCommands(plugin.getServer().getConsoleSender(), processed);
        }

        // kill_player still runs on the quitting player's own region thread
        if (combatLogoutKillPlayer) {
            api.punishCombatLogout(player);
        }

        if (combatLogoutRewardAttacker && opponent != null && opponent.isOnline()) {
            killRewardManager.giveKillReward(opponent, player);

            if (!attackerRewardCommands.isEmpty()) {
                List<String> processed = new ArrayList<>(attackerRewardCommands.size());
                for (String cmd : attackerRewardCommands) {
                    if (cmd == null || cmd.isBlank()) continue;
                    processed.add(cmd
                            .replace("%player%", opponent.getName())
                            .replace("%victim%", playerName));
                }
                Scheduler.dispatchCommands(plugin.getServer().getConsoleSender(), processed);
            }

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("victim", playerName);
            Scheduler.runEntity(opponent,
                    () -> messageManager.sendMessage(opponent, "combat_logout_attacker_reward", placeholders));

            deathAnimationManager.performDeathAnimation(player, opponent);
            api.removeFromCombatSilently(opponent);
        } else if (opponent == null) {
            deathAnimationManager.performDeathAnimation(player, null);
        }
    }

    // Add a listener for PlayerKickEvent to track admin kicks
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        // Handle newbie protection cleanup
        newbieProtectionManager.handlePlayerQuit(player);

        CombatAPI api = CelestCombatAPI.getCombatAPI();
        if (api == null) {
            return;
        }

        if (api.isInCombat(player)) {
            if (exemptAdminKick) {
                Player opponent = api.getCombatOpponent(player);
                api.removeFromCombatSilently(player);
                if (opponent != null) {
                    api.removeFromCombat(opponent);
                }
            } else {
                Player opponent = api.getCombatOpponent(player);
                playerLoggedOutInCombat.put(player.getUniqueId(), true);
                api.punishCombatLogout(player);
                if (opponent != null && opponent.isOnline()) {
                    killRewardManager.giveKillReward(opponent, player);
                    deathAnimationManager.performDeathAnimation(player, opponent);
                } else {
                    deathAnimationManager.performDeathAnimation(player, null);
                }
                api.removeFromCombatSilently(player);
                if (opponent != null) {
                    api.removeFromCombat(opponent);
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
            if (plugin.isDebugMode()) {
                plugin.debug("Removed newbie protection from " + victim.getName() + " due to death");
            }
        }

        CombatAPI api = CelestCombatAPI.getCombatAPI();

        // If player directly killed by another player
        if (killer != null && !killer.equals(victim)) {
            // Execute kill reward commands using KillRewardManager
            killRewardManager.giveKillReward(killer, victim);

            // Perform death animation
            deathAnimationManager.performDeathAnimation(victim, killer);

            if (api != null) {
                // Remove from combat - killer's combat timer is removed on kill
                api.removeFromCombatSilently(victim);
                api.removeFromCombatSilently(killer);
            }
        }
        // If player died by other causes but was in combat
        else if (api != null && api.isInCombat(victim)) {
            Player opponent = api.getCombatOpponent(victim);

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
            api.removeFromCombatSilently(victim);
            if (actualKiller != null) {
                api.removeFromCombatSilently(actualKiller);
            } else if (opponent != null) {
                api.removeFromCombatSilently(opponent);
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
            if (Boolean.TRUE.equals(playerLoggedOutInCombat.get(playerUUID))) {
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
    // NOTE: This method is registered dynamically with configurable priority
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        CombatAPI api = CelestCombatAPI.getCombatAPI();
        if (api == null || !api.isInCombat(player)) {
            return;
        }

        String fullCommand = event.getMessage().substring(1); // Remove leading "/"
        String command = fullCommand.split(" ", 2)[0].toLowerCase(Locale.ROOT);

        // Determine if the command should be blocked based on the cached mode/rules
        boolean shouldBlock;
        if ("blacklist".equals(commandBlockMode)) {
            shouldBlock = matchesRules(command, blockedCommands, blockedCommandWildcards);
        } else {
            // Whitelist mode - allow only commands in the list
            shouldBlock = !matchesRules(command, allowedCommands, allowedCommandWildcards);
        }

        // Block the command if necessary
        if (shouldBlock) {
            event.setCancelled(true);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("command", command);
            placeholders.put("time", String.valueOf(api.getRemainingCombatTime(player)));
            messageManager.sendMessage(player, "command_blocked_in_combat", placeholders);
        }
    }

    // NOTE: This method is registered dynamically with configurable priority
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // If player is trying to enable flight
        if (event.isFlying() && CelestCombatAPI.getCombatAPI() != null
                && CelestCombatAPI.getCombatAPI().shouldDisableFlight(player)) {
            // Only cancel if player is actually trying to fly (not just falling/knockback)
            // Check if player is on ground or has significant upward velocity (intentional flight)
            if (player.isOnGround() || player.getVelocity().getY() > 0) {
                event.setCancelled(true);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                messageManager.sendMessage(player, "combat_fly_disabled", placeholders);
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
