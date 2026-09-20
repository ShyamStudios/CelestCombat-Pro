package com.shyamstudio.celestCombatPro.combat;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.configs.EventPriorityManager;
import com.shyamstudio.celestCombatPro.Scheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatManager {
    private final CelestCombatPro plugin;
    @Getter private final Map<UUID, Long> playersInCombat;
    private final Map<UUID, UUID> combatOpponents;

    // Single countdown task instead of per-player tasks
    private Scheduler.Task globalCountdownTask;
    private static final long COUNTDOWN_INTERVAL = 20L; // 1 second in ticks
    private final Set<UUID> displayScratch = new HashSet<>();

    @Getter private final Map<UUID, Long> enderPearlCooldowns;
    @Getter private final Map<UUID, Long> tridentCooldowns = new ConcurrentHashMap<>();

    // Combat configuration cache to avoid repeated config lookups
    private volatile long combatDurationTicks;
    private volatile long combatDurationSeconds;
    private volatile long combatDurationMillis;
    private volatile boolean disableFlightInCombat;
    private volatile long enderPearlCooldownTicks;
    private volatile long enderPearlCooldownSeconds;
    private volatile Map<String, Boolean> worldEnderPearlSettings = new ConcurrentHashMap<>();
    private volatile boolean enderPearlInCombatOnly;
    private volatile boolean enderPearlEnabled;
    private volatile boolean refreshCombatOnPearlLand;

    // Trident configuration cache
    private volatile long tridentCooldownTicks;
    private volatile long tridentCooldownSeconds;
    private volatile Map<String, Boolean> worldTridentSettings = new ConcurrentHashMap<>();
    private volatile boolean tridentInCombatOnly;
    private volatile boolean tridentEnabled;
    private volatile boolean refreshCombatOnTridentLand;
    private volatile Map<String, Boolean> worldTridentBannedSettings = new ConcurrentHashMap<>();

    // UXM Claims configuration cache
    private volatile boolean uxmClaimsEnabled;
    private volatile Map<String, Boolean> worldUXMClaimsSettings = new ConcurrentHashMap<>();

    // Ender pearl fix configuration cache
    private volatile boolean enderPearlFixEnabled;
    private volatile boolean preventBlockStuck;
    private volatile boolean preventMicroTeleport;
    private volatile boolean preventBarrierGlitch;
    private volatile boolean preventTightSpaces;
    private volatile double minTeleportDistance;
    private volatile int maxBlockCheckRadius;
    private volatile int maxSurroundingBlocks;

    // Event priority manager for configurable event priorities
    @Getter private final EventPriorityManager eventPriorityManager;

    public CombatManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.playersInCombat = new ConcurrentHashMap<>();
        this.combatOpponents = new ConcurrentHashMap<>();
        this.enderPearlCooldowns = new ConcurrentHashMap<>();

        // Initialize event priority manager
        this.eventPriorityManager = new EventPriorityManager(plugin);

        loadConfigValues();

        // Load per-world settings
        loadWorldTridentSettings();

        // Load per-world settings
        loadWorldEnderPearlSettings();

        // Load UXM Claims settings
        loadUXMClaimsSettings();

        // Load ender pearl fix settings
        loadEnderPearlFixSettings();

        // Start the global countdown timer
        startGlobalCountdownTimer();
    }

    private void loadConfigValues() {
        this.combatDurationTicks = plugin.getTimeFromConfig("combat.duration", "20s");
        this.combatDurationSeconds = combatDurationTicks / 20;
        this.combatDurationMillis = combatDurationSeconds * 1000L;
        this.disableFlightInCombat = plugin.getConfig().getBoolean("combat.disable_flight", true);

        this.enderPearlCooldownTicks = plugin.getTimeFromConfig("enderpearl_cooldown.duration", "10s");
        this.enderPearlCooldownSeconds = enderPearlCooldownTicks / 20;
        this.enderPearlEnabled = plugin.getConfig().getBoolean("enderpearl_cooldown.enabled", true);
        this.enderPearlInCombatOnly = plugin.getConfig().getBoolean("enderpearl_cooldown.in_combat_only", true);
        this.refreshCombatOnPearlLand = plugin.getConfig().getBoolean("enderpearl.refresh_combat_on_land", false);

        this.tridentCooldownTicks = plugin.getTimeFromConfig("trident_cooldown.duration", "10s");
        this.tridentCooldownSeconds = tridentCooldownTicks / 20;
        this.tridentEnabled = plugin.getConfig().getBoolean("trident_cooldown.enabled", true);
        this.tridentInCombatOnly = plugin.getConfig().getBoolean("trident_cooldown.in_combat_only", true);
        this.refreshCombatOnTridentLand = plugin.getConfig().getBoolean("trident.refresh_combat_on_land", false);
    }

    private void loadWorldTridentSettings() {
        worldTridentSettings.clear();
        worldTridentBannedSettings.clear();

        // Load cooldown settings per world
        if (plugin.getConfig().isConfigurationSection("trident_cooldown.worlds")) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection("trident_cooldown.worlds")).getKeys(false)) {
                boolean enabled = plugin.getConfig().getBoolean("trident_cooldown.worlds." + worldName, true);
                worldTridentSettings.put(worldName, enabled);
            }
        }

        // Load banned settings per world
        if (plugin.getConfig().isConfigurationSection("trident.banned_worlds")) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection("trident.banned_worlds")).getKeys(false)) {
                boolean banned = plugin.getConfig().getBoolean("trident.banned_worlds." + worldName, false);
                worldTridentBannedSettings.put(worldName, banned);
            }
        }
    }

    public void reloadConfig() {
        // Reload event priorities first
        eventPriorityManager.loadPriorities();

        // Update cached configuration values
        loadConfigValues();

        loadWorldEnderPearlSettings();
        loadWorldTridentSettings();

        // Load UXM Claims settings
        loadUXMClaimsSettings();

        // Load ender pearl fix settings
        loadEnderPearlFixSettings();
    }

    // Add this method to load world-specific settings
    private void loadWorldEnderPearlSettings() {
        worldEnderPearlSettings.clear();

        if (plugin.getConfig().isConfigurationSection("enderpearl_cooldown.worlds")) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection("enderpearl_cooldown.worlds")).getKeys(false)) {
                boolean enabled = plugin.getConfig().getBoolean("enderpearl_cooldown.worlds." + worldName, true);
                worldEnderPearlSettings.put(worldName, enabled);
            }
        }
    }

    private void startGlobalCountdownTimer() {
        if (globalCountdownTask != null) {
            globalCountdownTask.cancel();
        }
        globalCountdownTask = Scheduler.runGlobalTimer(this::tick, 1L, COUNTDOWN_INTERVAL);
    }

    private void tick() {
        long currentTime = System.currentTimeMillis();

        Set<UUID> display = displayScratch;
        display.clear();

        for (Map.Entry<UUID, Long> entry : playersInCombat.entrySet()) {
            UUID playerUUID = entry.getKey();
            long combatEndTime = entry.getValue();

            if (currentTime > combatEndTime) {
                // Atomic removal guarantees the expiry (and its message) happens exactly once
                if (playersInCombat.remove(playerUUID, combatEndTime)) {
                    combatOpponents.remove(playerUUID);
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        Scheduler.runEntity(player,
                                () -> plugin.getMessageService().sendMessage(player, "combat_expired"));
                    }
                }
            } else {
                display.add(playerUUID);
            }
        }

        // Cooldown-only players still need countdown feedback
        display.addAll(enderPearlCooldowns.keySet());
        display.addAll(tridentCooldowns.keySet());

        for (UUID playerUUID : display) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                Scheduler.runEntity(player,
                        () -> updatePlayerCountdown(player, System.currentTimeMillis()));
            }
        }

        enderPearlCooldowns.entrySet().removeIf(entry -> currentTime > entry.getValue());
        tridentCooldowns.entrySet().removeIf(entry -> currentTime > entry.getValue());
    }

    private void updatePlayerCountdown(Player player, long currentTime) {
        if (player == null || !player.isOnline()) return;

        UUID playerUUID = player.getUniqueId();
        Long combatEnd = playersInCombat.get(playerUUID);
        boolean inCombat = combatEnd != null && currentTime <= combatEnd;
        Long pearlEnd = enderPearlCooldowns.get(playerUUID);
        boolean hasPearlCooldown = pearlEnd != null && currentTime <= pearlEnd;
        Long tridentEnd = tridentCooldowns.get(playerUUID);
        boolean hasTridentCooldown = tridentEnd != null && currentTime <= tridentEnd;

        if (!inCombat && !hasPearlCooldown && !hasTridentCooldown) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());

        if (inCombat) {
            int remainingCombatTime = getRemainingCombatTime(playerUUID, currentTime);
            placeholders.put("combat_time", String.valueOf(remainingCombatTime));

            if (hasPearlCooldown && hasTridentCooldown) {
                // All three cooldowns active - show combined message
                placeholders.put("pearl_time", String.valueOf(getRemainingEnderPearlCooldown(playerUUID, currentTime)));
                placeholders.put("trident_time", String.valueOf(getRemainingTridentCooldown(playerUUID, currentTime)));
                plugin.getMessageService().sendMessage(player, "combat_pearl_trident_countdown", placeholders);
            } else if (hasPearlCooldown) {
                // Combat + pearl cooldown active
                placeholders.put("pearl_time", String.valueOf(getRemainingEnderPearlCooldown(playerUUID, currentTime)));
                plugin.getMessageService().sendMessage(player, "combat_pearl_countdown", placeholders);
            } else if (hasTridentCooldown) {
                // Combat + trident cooldown active
                placeholders.put("trident_time", String.valueOf(getRemainingTridentCooldown(playerUUID, currentTime)));
                plugin.getMessageService().sendMessage(player, "combat_trident_countdown", placeholders);
            } else {
                // Only combat cooldown active
                if (remainingCombatTime > 0) {
                    placeholders.put("time", String.valueOf(remainingCombatTime));
                    plugin.getMessageService().sendMessage(player, "combat_countdown", placeholders);
                }
            }
        } else if (hasPearlCooldown && hasTridentCooldown) {
            // Both pearl and trident cooldowns but no combat
            placeholders.put("pearl_time", String.valueOf(getRemainingEnderPearlCooldown(playerUUID, currentTime)));
            placeholders.put("trident_time", String.valueOf(getRemainingTridentCooldown(playerUUID, currentTime)));
            plugin.getMessageService().sendMessage(player, "pearl_trident_countdown", placeholders);
        } else if (hasPearlCooldown) {
            // Only pearl cooldown active
            int remainingPearlTime = getRemainingEnderPearlCooldown(playerUUID, currentTime);
            if (remainingPearlTime > 0) {
                placeholders.put("time", String.valueOf(remainingPearlTime));
                plugin.getMessageService().sendMessage(player, "pearl_only_countdown", placeholders);
            }
        } else if (hasTridentCooldown) {
            // Only trident cooldown active
            int remainingTridentTime = getRemainingTridentCooldown(playerUUID, currentTime);
            if (remainingTridentTime > 0) {
                placeholders.put("time", String.valueOf(remainingTridentTime));
                plugin.getMessageService().sendMessage(player, "trident_only_countdown", placeholders);
            }
        }
    }

    public void tagPlayer(Player player, Player attacker) {
        if (player == null || attacker == null) return;

        if (player.hasPermission("celestcombat.bypass.tag")) {
            return;
        }

        UUID playerUUID = player.getUniqueId();
        UUID attackerUUID = attacker.getUniqueId();
        long newEndTime = System.currentTimeMillis() + combatDurationMillis;

        Long currentEndTime = playersInCombat.get(playerUUID);
        boolean alreadyInCombat = currentEndTime != null;
        boolean alreadyInCombatWithAttacker = alreadyInCombat &&
                attackerUUID.equals(combatOpponents.get(playerUUID));

        if (alreadyInCombatWithAttacker && newEndTime <= currentEndTime) {
            return; // Don't reset the timer if it would make it shorter
        }

        combatOpponents.put(playerUUID, attackerUUID);
        playersInCombat.put(playerUUID, newEndTime);

        // Flight/Elytra handling touches live entity state — run it on the player's thread
        Scheduler.runEntity(player, () -> applyCombatEntryEffects(player, !alreadyInCombat));
    }

    private void applyCombatEntryEffects(Player player, boolean newCombatSession) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (disableFlightInCombat && player.isFlying()) {
            player.setFlying(false);
            plugin.getMessageService().sendMessage(player, "combat_fly_disabled",
                    Collections.singletonMap("player", player.getName()));
        }

        // Handle Elytra removal when entering combat (only for new combat sessions)
        if (newCombatSession && plugin.getItemRestrictionListener() != null) {
            plugin.getItemRestrictionListener().handleCombatStart(player);
        }
    }

    public void punishCombatLogout(Player player) {
        if (player == null) return;

        player.setHealth(0);
        removeFromCombat(player);
    }

    public void removeFromCombat(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        // Atomic remove: ensures expiry handling and messaging happens exactly once even
        // when the global timer and a region thread race on the same entry.
        if (playersInCombat.remove(playerUUID) == null) {
            return; // Player is not in combat
        }
        combatOpponents.remove(playerUUID);

        if (player.isOnline()) {
            Scheduler.runEntity(player, () -> plugin.getMessageService().sendMessage(player, "combat_expired"));
        }
    }

    public void removeFromCombatSilently(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();
        playersInCombat.remove(playerUUID);
        combatOpponents.remove(playerUUID);
    }

    /**
     * Drops ender pearl/trident cooldown entries for a player that is no longer online.
     * Called on quit so the countdown timer never has to resolve offline players.
     */
    public void clearTransientCooldowns(UUID playerUUID) {
        if (playerUUID == null) return;
        enderPearlCooldowns.remove(playerUUID);
        tridentCooldowns.remove(playerUUID);
    }

    private static boolean isWorldDisabled(Map<String, Boolean> settings, String worldName) {
        return settings != null && Boolean.FALSE.equals(settings.get(worldName));
    }

    public Player getCombatOpponent(Player player) {
        if (player == null) return null;

        UUID playerUUID = player.getUniqueId();
        if (!playersInCombat.containsKey(playerUUID)) return null;

        UUID opponentUUID = combatOpponents.get(playerUUID);
        if (opponentUUID == null) return null;

        return Bukkit.getPlayer(opponentUUID);
    }

    public boolean isInCombat(Player player) {
        if (player == null) return false;

        UUID playerUUID = player.getUniqueId();
        Long combatEndTime = playersInCombat.get(playerUUID);
        if (combatEndTime == null) {
            return false;
        }

        if (System.currentTimeMillis() > combatEndTime) {
            removeFromCombat(player);
            return false;
        }

        return true;
    }

    public int getRemainingCombatTime(Player player) {
        return getRemainingCombatTime(player, System.currentTimeMillis());
    }

    private int getRemainingCombatTime(Player player, long currentTime) {
        return player == null ? 0 : getRemainingCombatTime(player.getUniqueId(), currentTime);
    }

    private int getRemainingCombatTime(UUID playerUUID, long currentTime) {
        Long endTime = playersInCombat.get(playerUUID);
        if (endTime == null) return 0;
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }

    public void updateMutualCombat(Player player1, Player player2) {
        if (player1 != null && player1.isOnline() && player2 != null && player2.isOnline()) {
            tagPlayer(player1, player2);
            tagPlayer(player2, player1);
        }
    }

    // Ender pearl cooldown methods
    public void setEnderPearlCooldown(Player player) {
        if (player == null) return;

        // Only set cooldown if enabled in config
        if (!enderPearlEnabled) {
            return;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (isWorldDisabled(worldEnderPearlSettings, worldName)) {
            return; // Don't set cooldown in this world
        }

        // Check if we should only apply cooldown in combat
        if (enderPearlInCombatOnly && !isInCombat(player)) {
            return;
        }

        enderPearlCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + (enderPearlCooldownSeconds * 1000L));
    }

    public boolean isEnderPearlOnCooldown(Player player) {
        if (player == null) return false;

        // If all ender pearl cooldowns are disabled globally, always return false
        if (!enderPearlEnabled) {
            return false;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (isWorldDisabled(worldEnderPearlSettings, worldName)) {
            return false; // Cooldown disabled for this specific world
        }

        // Check if we should only apply cooldown in combat
        if (enderPearlInCombatOnly && !isInCombat(player)) {
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        Long cooldownEndTime = enderPearlCooldowns.get(playerUUID);

        if (cooldownEndTime == null) {
            return false;
        }

        if (System.currentTimeMillis() > cooldownEndTime) {
            enderPearlCooldowns.remove(playerUUID);
            return false;
        }

        return true;
    }
    public void refreshCombatOnPearlLand(Player player) {
        if (player == null || !refreshCombatOnPearlLand) return;

        // Only refresh if player is already in combat
        if (!isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + combatDurationMillis;
        long currentEndTime = playersInCombat.getOrDefault(playerUUID, 0L);

        // Only extend the combat time, don't shorten it
        if (newEndTime > currentEndTime) {
            playersInCombat.put(playerUUID, newEndTime);

            // Debug message if debug is enabled
            plugin.debug("Refreshed combat time for " + player.getName() + " due to pearl landing");
        }
    }

    public int getRemainingEnderPearlCooldown(Player player) {
        return getRemainingEnderPearlCooldown(player, System.currentTimeMillis());
    }

    private int getRemainingEnderPearlCooldown(Player player, long currentTime) {
        return player == null ? 0 : getRemainingEnderPearlCooldown(player.getUniqueId(), currentTime);
    }

    private int getRemainingEnderPearlCooldown(UUID playerUUID, long currentTime) {
        Long endTime = enderPearlCooldowns.get(playerUUID);
        if (endTime == null) return 0;
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }

    public boolean shouldDisableFlight(Player player) {
        return player != null && disableFlightInCombat && isInCombat(player);
    }

    public void setTridentCooldown(Player player) {
        if (player == null) return;

        // Only set cooldown if enabled in config
        if (!tridentEnabled) {
            return;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (isWorldDisabled(worldTridentSettings, worldName)) {
            return; // Don't set cooldown in this world
        }

        // Check if we should only apply cooldown in combat
        if (tridentInCombatOnly && !isInCombat(player)) {
            return;
        }

        tridentCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + (tridentCooldownSeconds * 1000L));
    }

    public boolean isTridentOnCooldown(Player player) {
        if (player == null) return false;

        // If all trident cooldowns are disabled globally, always return false
        if (!tridentEnabled) {
            return false;
        }

        // Check world-specific settings
        String worldName = player.getWorld().getName();
        if (isWorldDisabled(worldTridentSettings, worldName)) {
            return false; // Cooldown disabled for this specific world
        }

        // Check if we should only apply cooldown in combat
        if (tridentInCombatOnly && !isInCombat(player)) {
            return false;
        }

        UUID playerUUID = player.getUniqueId();
        Long cooldownEndTime = tridentCooldowns.get(playerUUID);

        if (cooldownEndTime == null) {
            return false;
        }

        if (System.currentTimeMillis() > cooldownEndTime) {
            tridentCooldowns.remove(playerUUID);
            return false;
        }

        return true;
    }

    public boolean isTridentBanned(Player player) {
        if (player == null) return false;

        // Check world-specific ban settings
        String worldName = player.getWorld().getName();
        return worldTridentBannedSettings.getOrDefault(worldName, false);
    }

    public void refreshCombatOnTridentLand(Player player) {
        if (player == null || !refreshCombatOnTridentLand) return;

        // Only refresh if player is already in combat
        if (!isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        long newEndTime = System.currentTimeMillis() + combatDurationMillis;
        long currentEndTime = playersInCombat.getOrDefault(playerUUID, 0L);

        // Only extend the combat time, don't shorten it
        if (newEndTime > currentEndTime) {
            playersInCombat.put(playerUUID, newEndTime);

            // Debug message if debug is enabled
            plugin.debug("Refreshed combat time for " + player.getName() + " due to trident landing");
        }
    }

    public int getRemainingTridentCooldown(Player player) {
        return getRemainingTridentCooldown(player, System.currentTimeMillis());
    }

    private int getRemainingTridentCooldown(Player player, long currentTime) {
        return player == null ? 0 : getRemainingTridentCooldown(player.getUniqueId(), currentTime);
    }

    private int getRemainingTridentCooldown(UUID playerUUID, long currentTime) {
        Long endTime = tridentCooldowns.get(playerUUID);
        if (endTime == null) return 0;
        return (int) Math.ceil(Math.max(0, (endTime - currentTime) / 1000.0));
    }

    public long getCombatDurationSeconds() {
        return combatDurationSeconds;
    }

    public long getEnderPearlCooldownSeconds() {
        return enderPearlCooldownSeconds;
    }

    public long getTridentCooldownSeconds() {
        return tridentCooldownSeconds;
    }

    public void shutdown() {
        // Cancel the global countdown task
        if (globalCountdownTask != null) {
            globalCountdownTask.cancel();
            globalCountdownTask = null;
        }

        playersInCombat.clear();
        combatOpponents.clear();
        enderPearlCooldowns.clear();
        tridentCooldowns.clear();
        displayScratch.clear();
    }

    private void loadUXMClaimsSettings() {
        this.uxmClaimsEnabled = plugin.getConfig().getBoolean("uxm_claims_protection.enabled", true);
        worldUXMClaimsSettings.clear();

        if (plugin.getConfig().isConfigurationSection("uxm_claims_protection.worlds")) {
            for (String worldName : Objects.requireNonNull(plugin.getConfig().getConfigurationSection("uxm_claims_protection.worlds")).getKeys(false)) {
                boolean enabled = plugin.getConfig().getBoolean("uxm_claims_protection.worlds." + worldName, uxmClaimsEnabled);
                worldUXMClaimsSettings.put(worldName, enabled);
            }
        }
    }

    private void loadEnderPearlFixSettings() {
        this.enderPearlFixEnabled = plugin.getConfig().getBoolean("enderpearl_fix.enabled", true);
        this.preventBlockStuck = plugin.getConfig().getBoolean("enderpearl_fix.prevent_block_stuck", true);
        this.preventMicroTeleport = plugin.getConfig().getBoolean("enderpearl_fix.prevent_micro_teleport", true);
        this.preventBarrierGlitch = plugin.getConfig().getBoolean("enderpearl_fix.prevent_barrier_glitch", true);
        this.preventTightSpaces = plugin.getConfig().getBoolean("enderpearl_fix.prevent_tight_spaces", true);
        this.minTeleportDistance = plugin.getConfig().getDouble("enderpearl_fix.min_teleport_distance", 1.2);
        this.maxBlockCheckRadius = plugin.getConfig().getInt("enderpearl_fix.max_block_check_radius", 3);
        this.maxSurroundingBlocks = plugin.getConfig().getInt("enderpearl_fix.max_surrounding_blocks", 6);
    }

    // Getter methods for cached config values
    public boolean isUXMClaimsEnabled() {
        return uxmClaimsEnabled;
    }

    public boolean isUXMClaimsEnabledInWorld(String worldName) {
        return worldUXMClaimsSettings.getOrDefault(worldName, uxmClaimsEnabled);
    }

    public boolean isEnderPearlFixEnabled() {
        return enderPearlFixEnabled;
    }

    public boolean shouldPreventBlockStuck() {
        return preventBlockStuck;
    }

    public boolean shouldPreventMicroTeleport() {
        return preventMicroTeleport;
    }

    public boolean shouldPreventBarrierGlitch() {
        return preventBarrierGlitch;
    }

    public boolean shouldPreventTightSpaces() {
        return preventTightSpaces;
    }

    public double getMinTeleportDistance() {
        return minTeleportDistance;
    }

    public int getMaxBlockCheckRadius() {
        return maxBlockCheckRadius;
    }

    public int getMaxSurroundingBlocks() {
        return maxSurroundingBlocks;
    }
}
