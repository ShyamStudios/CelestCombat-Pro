package com.shyamstudio.celestCombatPro.rewards;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KillRewardManager {
    private final CelestCombatPro plugin;
    private final File cooldownFile;
    private final Object saveLock = new Object();

    // Cooldown storage - using String keys for better performance than UUID concatenation
    @Getter private final Map<String, Long> killRewardCooldowns = new ConcurrentHashMap<>();

    // Configuration cache (written on reload, read on region threads)
    private volatile boolean enabled;
    private volatile List<String> rewardCommands;
    private volatile boolean useGlobalCooldown;
    private volatile boolean useSamePlayerCooldown;
    private volatile long globalCooldownDuration;
    private volatile long samePlayerCooldownDuration;

    // Tasks
    private Scheduler.Task cleanupTask;
    private Scheduler.Task saveTask;

    // Constants
    private static final long CLEANUP_INTERVAL = 12000L; // 10 minutes in ticks
    private static final long SAVE_INTERVAL = 6000L; // 5 minutes in ticks
    private static final String GLOBAL_COOLDOWN_PREFIX = "global:";
    private static final String PLAYER_COOLDOWN_PREFIX = "player:";

    public KillRewardManager(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.cooldownFile = new File(plugin.getDataFolder(), "kill_cooldowns_data.yml");

        // Load configuration
        loadConfig();

        // Load cooldown data
        loadCooldownData();

        // Start background tasks
        startCleanupTask();
        startAutoSaveTask();
    }

    /**
     * Loads configuration values from the main config
     */
    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();

        this.enabled = config.getBoolean("kill_rewards.enabled", true);
        this.rewardCommands = config.getStringList("kill_rewards.commands");
        this.useGlobalCooldown = config.getBoolean("kill_rewards.cooldown.use_global_cooldown", false);
        this.useSamePlayerCooldown = config.getBoolean("kill_rewards.cooldown.use_same_player_cooldown", true);

        this.globalCooldownDuration = plugin.getTimeFromConfigInMilliseconds("kill_rewards.cooldown.duration", "1d");
        this.samePlayerCooldownDuration = plugin.getTimeFromConfigInMilliseconds("kill_rewards.cooldown.same_player_duration", "1d");

        plugin.debug("KillRewardManager config loaded - Enabled: " + enabled +
                ", Global cooldown: " + useGlobalCooldown +
                ", Same player cooldown: " + useSamePlayerCooldown);
    }

    /**
     * Loads cooldown data from the YAML file
     */
    private void loadCooldownData() {
        if (!cooldownFile.exists()) {
            try {
                cooldownFile.getParentFile().mkdirs();
                cooldownFile.createNewFile();
                plugin.debug("Created new kill_cooldowns_data.yml file");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create kill_cooldowns_data.yml: " + e.getMessage());
                return;
            }
        }

        YamlConfiguration cooldownConfig = YamlConfiguration.loadConfiguration(cooldownFile);

        // Load cooldowns from file
        int loadedCount = 0;
        long currentTime = System.currentTimeMillis();

        for (String key : cooldownConfig.getKeys(false)) {
            long expirationTime = cooldownConfig.getLong(key);

            // Only load non-expired cooldowns
            if (expirationTime > currentTime) {
                killRewardCooldowns.put(key, expirationTime);
                loadedCount++;
            }
        }

        plugin.getLogger().info("Loaded " + loadedCount + " active kill reward cooldowns");
    }

    /**
     * Saves cooldown data to the YAML file
     */
    public void saveCooldownData() {
        saveCooldownData(false);
    }

    /**
     * Saves cooldown data to the YAML file
     * @param synchronous if true, saves synchronously (used during shutdown)
     */
    public void saveCooldownData(boolean synchronous) {
        // Snapshot under lock so concurrent saves never share mutable YAML state
        Map<String, Long> snapshot;
        synchronized (saveLock) {
            snapshot = new HashMap<>(killRewardCooldowns);
        }

        final YamlConfiguration output = new YamlConfiguration();
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            // Only save non-expired cooldowns
            if (entry.getValue() > currentTime) {
                output.set(entry.getKey(), entry.getValue());
            }
        }

        // Save to file
        if (synchronous || !plugin.isEnabled()) {
            // Save synchronously during shutdown or if plugin is disabled
            try {
                output.save(cooldownFile);
                plugin.debug("Saved kill reward cooldowns to file (synchronous)");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save kill_cooldowns_data.yml: " + e.getMessage());
            }
        } else {
            // Save asynchronously during normal operation
            Scheduler.runAsync(() -> {
                try {
                    output.save(cooldownFile);
                    plugin.debug("Saved kill reward cooldowns to file");
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to save kill_cooldowns_data.yml: " + e.getMessage());
                }
            });
        }
    }


    /**
     * Checks if a player is on cooldown for kill rewards
     */
    public boolean isOnCooldown(Player killer, Player victim) {
        if (!enabled || killer == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();

        if (useGlobalCooldown) {
            // Check global cooldown
            String globalKey = GLOBAL_COOLDOWN_PREFIX + killer.getUniqueId();
            Long cooldownEnd = killRewardCooldowns.get(globalKey);
            return cooldownEnd != null && currentTime < cooldownEnd;
        } else if (useSamePlayerCooldown && victim != null) {
            // Check same-player cooldown
            String playerKey = PLAYER_COOLDOWN_PREFIX + killer.getUniqueId() + ":" + victim.getUniqueId();
            Long cooldownEnd = killRewardCooldowns.get(playerKey);
            return cooldownEnd != null && currentTime < cooldownEnd;
        }

        return false;
    }

    /**
     * Sets the appropriate cooldown for a killer
     */
    public void setCooldown(Player killer, Player victim) {
        if (!enabled || killer == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        if (useGlobalCooldown) {
            // Set global cooldown
            String globalKey = GLOBAL_COOLDOWN_PREFIX + killer.getUniqueId();
            long expirationTime = currentTime + globalCooldownDuration;
            killRewardCooldowns.put(globalKey, expirationTime);

            plugin.debug("Set global kill reward cooldown for " + killer.getName() + " until " +
                    new Date(expirationTime));
        } else if (useSamePlayerCooldown && victim != null) {
            // Set same-player cooldown
            String playerKey = PLAYER_COOLDOWN_PREFIX + killer.getUniqueId() + ":" + victim.getUniqueId();
            long expirationTime = currentTime + samePlayerCooldownDuration;
            killRewardCooldowns.put(playerKey, expirationTime);

            plugin.debug("Set same-player kill reward cooldown for " + killer.getName() +
                    " -> " + victim.getName() + " until " + new Date(expirationTime));
        }
    }

    /**
     * Executes reward commands for the killer
     */
    private void executeRewardCommands(Player killer, Player victim) {
        if (rewardCommands == null || rewardCommands.isEmpty()) {
            return;
        }

        // Resolve placeholders while the players are guaranteed to be valid references
        String killerName = killer.getName();
        String victimName = victim != null ? victim.getName() : "Unknown";
        List<String> processedCommands = new ArrayList<>(rewardCommands.size());
        for (String command : rewardCommands) {
            if (command == null || command.isBlank()) continue;
            processedCommands.add(command
                    .replace("%killer%", killerName)
                    .replace("%victim%", victimName));
        }
        if (processedCommands.isEmpty()) {
            return;
        }

        // Console commands are global operations on Folia: single ordered global task
        Scheduler.runGlobal(() -> {
            boolean anyCommandSuccessful = false;
            for (String processedCommand : processedCommands) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                    plugin.debug("Executed kill reward command: " + processedCommand);
                    anyCommandSuccessful = true;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to execute kill reward command '" +
                            processedCommand + "': " + e.getMessage());
                }
            }

            // Send success message to killer if at least one command succeeded
            if (anyCommandSuccessful) {
                Scheduler.runEntity(killer, () -> sendKillRewardMessage(killer, killerName, victimName));
            }
        });
    }

    /**
     * Sends kill reward message to the killer
     */
    private void sendKillRewardMessage(Player killer, String killerName, String victimName) {
        if (killer == null || !killer.isOnline()) {
            return;
        }

        try {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("killer", killerName);
            placeholders.put("victim", victimName);

            plugin.getMessageService().sendMessage(killer, "kill_reward_received", placeholders);
            plugin.debug("Sent kill reward message to " + killerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send kill reward message to " + killerName + ": " + e.getMessage());
        }
    }

    /**
     * Atomically claims the applicable cooldown for this kill.
     *
     * @return true when no active cooldown existed (the reward may be given)
     */
    private boolean claimCooldown(Player killer, Player victim) {
        long currentTime = System.currentTimeMillis();

        if (useGlobalCooldown) {
            String globalKey = GLOBAL_COOLDOWN_PREFIX + killer.getUniqueId();
            long expirationTime = currentTime + globalCooldownDuration;
            Long result = killRewardCooldowns.merge(globalKey, expirationTime,
                    (existing, replacement) -> currentTime < existing ? existing : replacement);
            return result != null && result == expirationTime;
        }

        if (useSamePlayerCooldown && victim != null) {
            String playerKey = PLAYER_COOLDOWN_PREFIX + killer.getUniqueId() + ":" + victim.getUniqueId();
            long expirationTime = currentTime + samePlayerCooldownDuration;
            Long result = killRewardCooldowns.merge(playerKey, expirationTime,
                    (existing, replacement) -> currentTime < existing ? existing : replacement);
            return result != null && result == expirationTime;
        }

        return true;
    }

    /**
     * Processes a kill event and applies rewards
     * This is the main method called by CombatListeners
     */
    public void giveKillReward(Player killer, Player victim) {
        if (!enabled || killer == null || victim == null || killer.equals(victim)) {
            plugin.debug("Kill reward skipped - enabled: " + enabled +
                    ", killer: " + (killer != null ? killer.getName() : "null") +
                    ", victim: " + (victim != null ? victim.getName() : "null") +
                    ", same player: " + (killer != null && killer.equals(victim)));
            return;
        }

        // Atomic cooldown claim prevents duplicate command execution on concurrent events
        if (!claimCooldown(killer, victim)) {
            plugin.debug("Kill reward cooldown active for " + killer.getName() + " -> " + victim.getName());
            return;
        }

        plugin.debug("Processing kill reward for " + killer.getName() + " -> " + victim.getName());

        // Execute reward commands (which will also send the message)
        executeRewardCommands(killer, victim);
    }

    /**
     * Gets the remaining cooldown time for a player
     */
    public long getRemainingCooldown(Player killer, Player victim) {
        if (!enabled || killer == null) {
            return 0;
        }

        long currentTime = System.currentTimeMillis();
        Long cooldownEnd = null;

        if (useGlobalCooldown) {
            String globalKey = GLOBAL_COOLDOWN_PREFIX + killer.getUniqueId();
            cooldownEnd = killRewardCooldowns.get(globalKey);
        } else if (useSamePlayerCooldown && victim != null) {
            String playerKey = PLAYER_COOLDOWN_PREFIX + killer.getUniqueId() + ":" + victim.getUniqueId();
            cooldownEnd = killRewardCooldowns.get(playerKey);
        }

        if (cooldownEnd == null || currentTime >= cooldownEnd) {
            return 0;
        }

        return cooldownEnd - currentTime;
    }

    /**
     * Starts the cleanup task to remove expired cooldowns
     */
    private void startCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        cleanupTask = Scheduler.runAsyncTimer(() -> {
            long currentTime = System.currentTimeMillis();
            int removedCount = 0;

            // Remove expired cooldowns
            Iterator<Map.Entry<String, Long>> iterator = killRewardCooldowns.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (currentTime >= entry.getValue()) {
                    iterator.remove();
                    removedCount++;
                }
            }

            if (removedCount > 0) {
                plugin.debug("Cleaned up " + removedCount + " expired kill reward cooldowns");
            }
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
    }

    /**
     * Starts the auto-save task
     */
    private void startAutoSaveTask() {
        if (saveTask != null) {
            saveTask.cancel();
        }

        saveTask = Scheduler.runAsyncTimer(this::saveCooldownData, SAVE_INTERVAL, SAVE_INTERVAL);
    }

    /**
     * Manually clears all cooldowns for a player
     */
    public void clearPlayerCooldowns(Player player) {
        if (player == null) return;

        String playerUUID = player.getUniqueId().toString();
        killRewardCooldowns.entrySet().removeIf(entry ->
                entry.getKey().contains(playerUUID));

        plugin.debug("Cleared all kill reward cooldowns for " + player.getName());
    }

    /**
     * Shutdown method to clean up resources
     */
    public void shutdown() {
        // Cancel tasks
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }

        // Save data
        saveCooldownData(true);

        // Clear collections
        killRewardCooldowns.clear();
    }
}