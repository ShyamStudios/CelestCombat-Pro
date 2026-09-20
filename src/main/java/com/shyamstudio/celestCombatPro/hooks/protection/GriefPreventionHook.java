package com.shyamstudio.celestCombatPro.hooks.protection;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class GriefPreventionHook implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    // Message cooldown optimization
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final long MESSAGE_COOLDOWN = 2000; // 2 seconds cooldown between messages

    // Visual barrier system
    private final Map<UUID, Set<Location>> playerBarriers = new ConcurrentHashMap<>();
    private final Map<Location, Material> originalBlocks = new ConcurrentHashMap<>();
    private final Map<Location, Set<UUID>> barrierViewers = new ConcurrentHashMap<>();

    // Configuration (written on reload, read on region threads)
    private volatile boolean globalEnabled;
    private volatile Map<String, Boolean> worldSettings = new ConcurrentHashMap<>();
    private volatile int barrierDetectionRadius;
    private volatile int barrierHeight;
    private volatile Material barrierMaterial;
    private volatile double pushBackForce;

    // Cache for performance optimization
    private volatile ClaimPermission requiredPermission;
    private final Map<String, Boolean> claimCache = new ConcurrentHashMap<>();
    private long lastCacheClean = System.currentTimeMillis();
    private static final long CACHE_CLEAN_INTERVAL = 30000; // 30 seconds
    private static final int MAX_CACHE_SIZE = 1000;

    public GriefPreventionHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;

        // Load configuration
        reloadConfig();

        // Start cleanup task
        startCleanupTask();
    }

    public void reloadConfig() {
        // Reload configuration
        this.globalEnabled = plugin.getConfig().getBoolean("claim_protection.enabled", true);
        this.barrierDetectionRadius = plugin.getConfig().getInt("claim_protection.barrier_detection_radius", 5);
        this.barrierHeight = plugin.getConfig().getInt("claim_protection.barrier_height", 3);
        this.barrierMaterial = loadBarrierMaterial();
        this.pushBackForce = plugin.getConfig().getDouble("claim_protection.push_back_force", 0.6);
        this.requiredPermission = loadRequiredPermission();

        // Load per-world settings
        loadWorldSettings();

        // Clear cache when config reloads
        claimCache.clear();
    }

    private void loadWorldSettings() {
        Map<String, Boolean> settings = new ConcurrentHashMap<>();

        if (plugin.getConfig().isConfigurationSection("claim_protection.worlds")) {
            var worldSection = plugin.getConfig().getConfigurationSection("claim_protection.worlds");
            if (worldSection != null) {
                for (String worldName : worldSection.getKeys(false)) {
                    boolean enabled = worldSection.getBoolean(worldName, globalEnabled);
                    settings.put(worldName, enabled);
                    plugin.debug("Claim protection for world '" + worldName + "': " + (enabled ? "enabled" : "disabled"));
                }
            }
        }

        this.worldSettings = settings;
        plugin.debug("Loaded " + settings.size() + " world-specific claim protection settings");
    }

    private boolean isEnabledInWorld(World world) {
        if (world == null) return false;

        // Check if there's a specific setting for this world
        Boolean setting = worldSettings.get(world.getName());
        if (setting != null) {
            return setting;
        }

        // Fall back to global setting
        return globalEnabled;
    }

    private boolean isEnabledAtLocation(Location location) {
        return location != null && isEnabledInWorld(location.getWorld());
    }

    private ClaimPermission loadRequiredPermission() {
        String permissionName = plugin.getConfig().getString("claim_protection.required_permission", "BUILD");

        try {
            // Map config values to actual enum values
            ClaimPermission permission;
            switch (permissionName.toUpperCase()) {
                case "MANAGE":
                    permission = ClaimPermission.Manage;
                    break;
                case "ACCESS":
                    permission = ClaimPermission.Access;
                    break;
                case "EDIT":
                    permission = ClaimPermission.Edit;
                    break;
                case "BUILD":
                    permission = ClaimPermission.Build;
                    break;
                case "CONTAINER":
                    permission = ClaimPermission.Inventory;
                    break;
                default:
                    plugin.getLogger().warning("Invalid claim permission '" + permissionName + "' in config. Using BUILD instead.");
                    plugin.getLogger().warning("Valid permissions are: MANAGE, ACCESS, EDIT, BUILD, INVENTORY");
                    return ClaimPermission.Build;
            }

            plugin.debug("Using claim permission: " + permission.name() + " for protection checks.");
            return permission;

        } catch (Exception e) {
            plugin.getLogger().warning("Error loading claim permission '" + permissionName + "': " + e.getMessage());
            plugin.getLogger().warning("Valid permissions are: MANAGE, ACCESS, EDIT, BUILD, INVENTORY");
            return ClaimPermission.Build;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Skip if claim protection is not enabled in this world
        if (!isEnabledAtLocation(event.getTo())) {
            // Remove any existing barriers for this player if protection is disabled
            removePlayerBarriers(player);
            return;
        }

        // Skip event if player is not in combat
        if (!combatManager.isInCombat(player)) {
            // Remove any barriers for this player
            removePlayerBarriers(player);
            return;
        }

        // Only process if the player has moved to a new block (optimization)
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        // Check if player is crossing between unprotected and protected claims
        boolean fromProtected = isInProtectedClaim(from, player);
        boolean toProtected = isInProtectedClaim(to, player);

        // If trying to enter a protected claim while in combat
        if (!fromProtected && toProtected) {
            // Push player back
            pushPlayerBack(player, from, to);

            // Send message
            sendCooldownMessage(player);
        }

        // Update visual barriers regardless of movement result
        updatePlayerBarriers(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // Skip if claim protection is not enabled in this world
        if (!isEnabledAtLocation(player.getLocation())) {
            removePlayerBarriers(player);
            return;
        }

        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        if (event.getClickedBlock() == null) {
            return;
        }

        Location blockLoc = event.getClickedBlock().getLocation();

        // Check if this block is a barrier for this player
        Set<Location> playerBarrierSet = playerBarriers.get(player.getUniqueId());
        if (playerBarrierSet != null && playerBarrierSet.contains(normalizeToBlockLocation(blockLoc))) {
            // Cancel the interaction to prevent visual glitches
            event.setCancelled(true);

            // Refresh the barrier block for the player to fix any visual issues
            Scheduler.runEntityLater(player, () -> refreshBarrierBlock(blockLoc, player), 1L);
        }
    }

    /**
     * Normalizes a location to block coordinates (integer coordinates)
     */
    private Location normalizeToBlockLocation(Location loc) {
        return new Location(
                loc.getWorld(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
        );
    }

    private void refreshBarrierBlock(Location loc, Player player) {
        // Normalize the location to ensure proper lookup
        Location normalizedLoc = normalizeToBlockLocation(loc);

        Set<UUID> viewers = barrierViewers.get(normalizedLoc);
        if (viewers != null && viewers.contains(player.getUniqueId())) {
            // Re-send the barrier block to fix any visual issues
            player.sendBlockChange(normalizedLoc, barrierMaterial.createBlockData());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Skip if claim protection is not enabled in this world
        if (!isEnabledAtLocation(event.getBlock().getLocation())) {
            return;
        }

        Location blockLoc = normalizeToBlockLocation(event.getBlock().getLocation());

        // Check if this block is part of a barrier system
        if (originalBlocks.containsKey(blockLoc)) {
            // Don't allow breaking barrier blocks
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Clean up player's barriers when they disconnect
        removePlayerBarriers(player);

        // Clean up other player-specific data
        lastMessageTime.remove(playerUUID);
    }

    private void pushPlayerBack(Player player, Location from, Location to) {
        // Calculate direction vector from 'to' back to 'from'
        Vector direction = from.toVector().subtract(to.toVector()).normalize();

        // Amplify the push slightly (adjustable force)
        direction.multiply(pushBackForce);

        // Create a new location to teleport the player to
        // This is based on their current location plus a small push back
        Location pushLocation = player.getLocation().clone();
        pushLocation.add(direction);

        // Ensure we're not pushing them into a block
        pushLocation.setY(getSafeY(pushLocation));

        // Maintain the original look direction
        pushLocation.setPitch(player.getLocation().getPitch());
        pushLocation.setYaw(player.getLocation().getYaw());

        // Apply some knockback effect to make it feel more natural
        player.setVelocity(direction);
    }

    private double getSafeY(Location loc) {
        // Get the block at the location
        Block block = loc.getBlock();

        // If the block is not solid, we're good
        if (!block.getType().isSolid()) {
            return loc.getY();
        }

        // Otherwise, look for safe space above
        for (int y = 1; y <= 2; y++) {
            Block above = block.getRelative(0, y, 0);
            if (!above.getType().isSolid()) {
                return loc.getBlockY() + y;
            }
        }

        // Look for safe space below if above wasn't safe
        for (int y = 1; y <= 2; y++) {
            Block below = block.getRelative(0, -y, 0);
            if (!below.getType().isSolid() &&
                    !below.getRelative(0, -1, 0).getType().isSolid()) {
                return loc.getBlockY() - y;
            }
        }

        // If all else fails, return original Y
        return loc.getY();
    }

    /**
     * Updates visual barriers for a combat player based on their current location
     */
    private void updatePlayerBarriers(Player player) {
        // Skip if claim protection is not enabled in this world
        if (!isEnabledAtLocation(player.getLocation())) {
            removePlayerBarriers(player);
            return;
        }

        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }

        Set<Location> newBarriers = findNearbyBarrierLocations(player.getLocation(), player);
        Set<Location> currentBarriers = playerBarriers.getOrDefault(player.getUniqueId(), Collections.emptySet());

        // Barrier locations are stored normalized, so direct set membership is safe
        for (Location loc : currentBarriers) {
            if (!newBarriers.contains(loc)) {
                removeBarrierBlock(loc, player);
            }
        }

        for (Location loc : newBarriers) {
            if (!currentBarriers.contains(loc)) {
                createBarrierBlock(loc, player);
            }
        }

        // Update player's barrier set
        if (newBarriers.isEmpty()) {
            playerBarriers.remove(player.getUniqueId());
        } else {
            playerBarriers.put(player.getUniqueId(), newBarriers);
        }
    }

    /**
     * Finds locations where barriers should be placed near the player.
     * Uses integer block math — no per-block Location allocation for distance checks.
     */
    private Set<Location> findNearbyBarrierLocations(Location playerLoc, Player player) {
        Set<Location> barrierLocations = new HashSet<>();
        World world = playerLoc.getWorld();
        if (world == null) {
            return barrierLocations;
        }

        int radius = barrierDetectionRadius;
        int radiusSq = radius * radius;
        int baseX = playerLoc.getBlockX();
        int baseY = playerLoc.getBlockY();
        int baseZ = playerLoc.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;
                for (int dy = -2; dy <= barrierHeight; dy++) {
                    Location checkLoc = new Location(world, baseX + dx, baseY + dy, baseZ + dz);
                    if (isBorderLocation(checkLoc, player)) {
                        barrierLocations.add(checkLoc);
                    }
                }
            }
        }

        return barrierLocations;
    }

    /**
     * Checks if a location is on the border between unprotected and protected claims
     */
    private boolean isBorderLocation(Location loc, Player player) {
        World world = loc.getWorld();
        if (world == null) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        if (!isInProtectedClaim(world, x, y, z, player)) {
            return false;
        }

        return !isInProtectedClaim(world, x + 1, y, z, player)
                || !isInProtectedClaim(world, x - 1, y, z, player)
                || !isInProtectedClaim(world, x, y, z + 1, player)
                || !isInProtectedClaim(world, x, y, z - 1, player);
    }

    /**
     * Creates a barrier block at the specified location for the player
     */
    private void createBarrierBlock(Location loc, Player player) {
        // Normalize location to block coordinates
        Location normalizedLoc = normalizeToBlockLocation(loc);
        Block block = normalizedLoc.getBlock();

        // Only create barrier if the block is air or replaceable
        if (block.getType() != Material.AIR && block.getType().isSolid()) {
            return;
        }

        // Store original block type
        originalBlocks.put(normalizedLoc, block.getType());

        // Add player to viewers of this barrier
        barrierViewers.computeIfAbsent(normalizedLoc, k -> new HashSet<>()).add(player.getUniqueId());

        // Send block change to player (configurable barrier material)
        player.sendBlockChange(normalizedLoc, barrierMaterial.createBlockData());
    }

    /**
     * Removes a barrier block at the specified location for the player
     */
    private void removeBarrierBlock(Location loc, Player player) {
        // Normalize location to block coordinates
        Location normalizedLoc = normalizeToBlockLocation(loc);

        Set<UUID> viewers = barrierViewers.get(normalizedLoc);
        if (viewers != null) {
            viewers.remove(player.getUniqueId());

            // If no more viewers, clean up completely
            if (viewers.isEmpty()) {
                barrierViewers.remove(normalizedLoc);
                Material originalType = originalBlocks.remove(normalizedLoc);
                if (originalType != null) {
                    // Restore original block for the player
                    player.sendBlockChange(normalizedLoc, originalType.createBlockData());
                }
            } else {
                // Just restore original block for this player
                Material originalType = originalBlocks.get(normalizedLoc);
                if (originalType != null) {
                    player.sendBlockChange(normalizedLoc, originalType.createBlockData());
                }
            }
        }
    }

    /**
     * Removes all barriers for a specific player
     */
    private void removePlayerBarriers(Player player) {
        Set<Location> barriers = playerBarriers.remove(player.getUniqueId());
        if (barriers != null) {
            for (Location loc : barriers) {
                removeBarrierBlock(loc, player);
            }
        }
    }

    /**
     * Enhanced cleanup task with better memory management
     */
    private void startCleanupTask() {
        Scheduler.runAsyncTimer(() -> {
            long currentTime = System.currentTimeMillis();

            // Clean up barriers for players no longer in combat
            cleanupPlayerBarriers();

            // Clean up message cooldowns
            cleanupMessageCooldowns(currentTime);

            // Clean up claim cache periodically
            cleanupClaimCache(currentTime);

        }, 100L, 100L); // Run every 5 seconds
    }

    private void cleanupPlayerBarriers() {
        for (UUID playerUUID : new ArrayList<>(playerBarriers.keySet())) {
            Player player = plugin.getServer().getPlayer(playerUUID);
            if (player != null && player.isOnline() && combatManager.isInCombat(player)) {
                continue;
            }

            // Remove barriers for this player
            Set<Location> barriers = playerBarriers.remove(playerUUID);
            if (barriers == null) continue;
            if (player != null && player.isOnline()) {
                // Block packets must be sent from the owning region thread
                Scheduler.runEntity(player, () -> barriers.forEach(loc -> removeBarrierBlock(loc, player)));
            } else {
                // Player is offline, just clean up data
                for (Location loc : barriers) {
                    cleanupOfflinePlayerBarrier(loc, playerUUID);
                }
            }
        }
    }

    private void cleanupOfflinePlayerBarrier(Location loc, UUID playerUUID) {
        Location normalizedLoc = normalizeToBlockLocation(loc);
        Set<UUID> viewers = barrierViewers.get(normalizedLoc);
        if (viewers != null) {
            viewers.remove(playerUUID);
            if (viewers.isEmpty()) {
                barrierViewers.remove(normalizedLoc);
                originalBlocks.remove(normalizedLoc);
            }
        }
    }

    private void cleanupMessageCooldowns(long currentTime) {
        lastMessageTime.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > MESSAGE_COOLDOWN * 10); // Keep for 10x cooldown time
    }

    private void cleanupClaimCache(long currentTime) {
        if (currentTime - lastCacheClean > CACHE_CLEAN_INTERVAL) {
            // Clean cache if it's too large
            if (claimCache.size() > MAX_CACHE_SIZE) {
                claimCache.clear();
            }
            lastCacheClean = currentTime;
        }
    }

    /**
     * Checks if a location is in a protected claim (claim that the player cannot access)
     */
    private boolean isInProtectedClaim(Location location, Player player) {
        if (location == null) return false;
        return isInProtectedClaim(location.getWorld(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), player);
    }

    private boolean isInProtectedClaim(World world, int x, int y, int z, Player player) {
        if (world == null) return false;

        // Create cache key
        String cacheKey = world.getName() + ":" + x + ":" + y + ":" + z + ":" + player.getUniqueId();

        // Check cache first
        Boolean cached = claimCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(new Location(world, x, y, z), false, null);

            boolean isProtected = false;
            if (claim != null) {
                Supplier<String> permissionResult = claim.checkPermission(player, requiredPermission, null);
                isProtected = permissionResult != null; // null means allowed, any string means denied
            }

            // Cache the result
            claimCache.put(cacheKey, isProtected);
            return isProtected;

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking GriefPrevention claim: " + e.getMessage());
            return false; // Default to not protected if there's an error
        }
    }

    private void sendCooldownMessage(Player player) {
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Check if message is on cooldown
        Long lastTime = lastMessageTime.get(playerUUID);
        if (lastTime != null && currentTime - lastTime < MESSAGE_COOLDOWN) {
            return;
        }

        // Update last message time
        lastMessageTime.put(playerUUID, currentTime);

        // Send message
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(combatManager.getRemainingCombatTime(player)));
        plugin.getMessageService().sendMessage(player, "combat_no_claim_entry", placeholders);
    }

    /**
     * Loads and validates the barrier material from config
     */
    private Material loadBarrierMaterial() {
        String materialName = plugin.getConfig().getString("claim_protection.barrier_material", "BLUE_STAINED_GLASS");

        try {
            Material material = Material.valueOf(materialName.toUpperCase());

            // Validate that the material is a valid block material
            if (!material.isBlock()) {
                plugin.getLogger().warning("Barrier material '" + materialName + "' is not a valid block material. Using BLUE_STAINED_GLASS instead.");
                return Material.BLUE_STAINED_GLASS;
            }

            plugin.debug("Using barrier material: " + material.name() + " for claim protection.");
            return material;

        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid barrier material '" + materialName + "' in config. Using BLUE_STAINED_GLASS instead.");
            plugin.getLogger().warning("Valid materials can be found at: https://jd.papermc.io/paper/1.21.5/org/bukkit/Material.html");
            return Material.BLUE_STAINED_GLASS;
        }
    }

    /**
     * Cleanup method to be called when plugin is disabled
     */
    public void cleanup() {
        playerBarriers.clear();
        originalBlocks.clear();
        barrierViewers.clear();
        lastMessageTime.clear();
        claimCache.clear();
        worldSettings.clear();
    }
}