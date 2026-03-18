package com.shyamstudio.celestCombatPro.hooks.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldGuardHook implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final long MESSAGE_COOLDOWN = 2000;

    private final Map<UUID, UUID> combatPlayerPearls = new ConcurrentHashMap<>();
    private final Map<UUID, PearlLocationData> pearlThrowLocations = new ConcurrentHashMap<>();

    private final Map<UUID, Set<Location>> playerBarriers = new ConcurrentHashMap<>();
    private final Map<Location, Material> originalBlocks = new ConcurrentHashMap<>();
    private final Map<Location, Set<UUID>> barrierViewers = new ConcurrentHashMap<>();

    private boolean globalEnabled;
    private Map<String, Boolean> worldSettings;
    private int barrierDetectionRadius;
    private int barrierHeight;
    private Material barrierMaterial;
    private double pushBackForce;

    private final Map<Long, SafeZoneInfo> safeZoneCache = new ConcurrentHashMap<>();
    private final Map<String, Long> regionCheckCache = new ConcurrentHashMap<>();
    private long lastCacheClean = System.currentTimeMillis();
    private static final long CACHE_CLEAN_INTERVAL = 30000;
    private static final long CACHE_TTL = 10000;
    private static final int MAX_CACHE_SIZE = 2000;

    private final Map<UUID, Long> lastBarrierUpdate = new ConcurrentHashMap<>();
    private static final long BARRIER_UPDATE_INTERVAL = 250;

    private final Map<String, RegionManager> regionManagerCache = new ConcurrentHashMap<>();
    private final RegionQuery regionQuery;

    private static long locationToCacheKey(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return (((long)x & 0x3FFFFFF) << 38) | (((long)z & 0x3FFFFFF) << 12) | ((long)y & 0xFFF);
    }

    private static class SafeZoneInfo {
        final boolean isSafeZone;
        final boolean hasRegions;
        final long timestamp;

        SafeZoneInfo(boolean isSafeZone, boolean hasRegions) {
            this.isSafeZone = isSafeZone;
            this.hasRegions = hasRegions;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }

    private static class PearlLocationData {
        final Location location;
        final long timestamp;

        PearlLocationData(Location location) {
            this.location = location.clone();
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60000;
        }
    }

    public WorldGuardHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;

        this.regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();

        reloadConfig();
        startCleanupTask();
    }

    public void reloadConfig() {
        this.globalEnabled = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        this.worldSettings = loadWorldSettings();
        this.barrierDetectionRadius = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        this.barrierHeight = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        this.barrierMaterial = loadBarrierMaterial();
        this.pushBackForce = plugin.getConfig().getDouble("safezone_protection.push_back_force", 0.6);

        safeZoneCache.clear();
        regionCheckCache.clear();
        regionManagerCache.clear();

        plugin.debug("WorldGuard safezone protection - Global enabled: " + globalEnabled);
        plugin.debug("WorldGuard safezone protection - World settings: " + worldSettings);
    }

    private Map<String, Boolean> loadWorldSettings() {
        Map<String, Boolean> settings = new HashMap<>();

        if (plugin.getConfig().isConfigurationSection("safezone_protection.worlds")) {
            Set<String> worldKeys = plugin.getConfig().getConfigurationSection("safezone_protection.worlds").getKeys(false);
            for (String worldName : worldKeys) {
                boolean enabled = plugin.getConfig().getBoolean("safezone_protection.worlds." + worldName, globalEnabled);
                settings.put(worldName, enabled);
                plugin.debug("World '" + worldName + "' safezone protection: " + enabled);
            }
        }

        return settings;
    }

    private boolean isEnabledInWorld(World world) {
        if (world == null) return false;

        String worldName = world.getName();

        if (worldSettings.containsKey(worldName)) {
            return worldSettings.get(worldName);
        }

        return globalEnabled;
    }

    /**
     * Check if safezone protection is enabled for a specific location
     */
    private boolean isEnabledInWorld(Location location) {
        return location != null && isEnabledInWorld(location.getWorld());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;

        ProjectileSource source = event.getEntity().getShooter();
        if (!(source instanceof Player)) return;

        Player player = (Player) source;

        if (!isEnabledInWorld(player.getWorld())) return;

        if (combatManager.isInCombat(player)) {
            combatPlayerPearls.put(event.getEntity().getUniqueId(), player.getUniqueId());
            pearlThrowLocations.put(player.getUniqueId(), new PearlLocationData(player.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;

        Location hitLocation = event.getEntity().getLocation();
        if (!isEnabledInWorld(hitLocation)) return;

        UUID projectileId = event.getEntity().getUniqueId();
        UUID playerUUID = combatPlayerPearls.remove(projectileId);
        if (playerUUID == null) return;

        Player player = plugin.getServer().getPlayer(playerUUID);
        Location teleportDestination = calculateTeleportDestination(event, event.getEntity());

        if (isSafeZone(teleportDestination)) {
            event.setCancelled(true);
            handlePearlTeleportBack(player, playerUUID);
        }

        pearlThrowLocations.remove(playerUUID);
    }

    private Location calculateTeleportDestination(ProjectileHitEvent event, Projectile projectile) {
        if (event.getHitBlock() != null) {
            Block hitPosition = event.getHitBlock();
            Location teleportDestination = new Location(
                    projectile.getWorld(),
                    hitPosition.getX(),
                    hitPosition.getY(),
                    hitPosition.getZ()
            );

            if (event.getHitBlockFace() != null) {
                teleportDestination.add(event.getHitBlockFace().getDirection().multiply(0.5));
            }
            return teleportDestination;
        }
        return projectile.getLocation();
    }

    private void handlePearlTeleportBack(Player player, UUID playerUUID) {
        if (player != null && player.isOnline()) {
            PearlLocationData pearlData = pearlThrowLocations.get(playerUUID);
            if (pearlData != null && !pearlData.isExpired()) {
                Location originalLocation = pearlData.location;
                player.teleportAsync(originalLocation).thenAccept(success -> {
                    if (success) {
                        sendCooldownMessage(player, "combat_no_pearl_safezone");
                    } else {
                        handleFailedTeleport(player, originalLocation);
                    }
                });
            } else {
                sendCooldownMessage(player, "combat_no_pearl_safezone");
            }
        }
    }

    private void handleFailedTeleport(Player player, Location originalLocation) {
        Location safeLocation = findSafeLocation(originalLocation);
        if (safeLocation != null) {
            player.teleportAsync(safeLocation);
            sendCooldownMessage(player, "combat_no_pearl_safezone");
        } else {
            player.setHealth(0);
            plugin.getLogger().warning("Killed player " + player.getName() + " as no safe location could be found");
            sendCooldownMessage(player, "combat_killed_no_safe_location");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPlayerMove(PlayerMoveEvent event) {
            if (!CelestCombatPro.hasWorldGuard) return;

            Player player = event.getPlayer();

            // Check if enabled in this world
            if (!isEnabledInWorld(player.getWorld())) {
                removePlayerBarriers(player);
                return;
            }

            if (!combatManager.isInCombat(player)) {
                removePlayerBarriers(player);
                return;
            }

            Location from = event.getFrom();
            Location to = event.getTo();

            // Early exit if player hasn't moved to a new block
            if (to == null || (from.getBlockX() == to.getBlockX() &&
                    from.getBlockY() == to.getBlockY() &&
                    from.getBlockZ() == to.getBlockZ())) {
                return;
            }

            // Only check safezone transitions, not every movement
            SafeZoneInfo fromInfo = getSafeZoneInfo(from);
            SafeZoneInfo toInfo = getSafeZoneInfo(to);

            if (!fromInfo.isSafeZone && toInfo.isSafeZone) {
                pushPlayerBack(player, from, to);
                sendCooldownMessage(player, "combat_no_safezone_entry");
            }

            // Throttle barrier updates per player
            updatePlayerBarriersThrottled(player);
        }

    private void updatePlayerBarriersThrottled(Player player) {
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastUpdate = lastBarrierUpdate.get(playerUUID);

        if (lastUpdate == null || currentTime - lastUpdate > BARRIER_UPDATE_INTERVAL) {
            updatePlayerBarriers(player);
            lastBarrierUpdate.put(playerUUID, currentTime);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!isEnabledInWorld(player.getWorld())) {
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

        if (event.getClickedBlock() == null) return;

        Location blockLoc = event.getClickedBlock().getLocation();
        Set<Location> playerBarrierSet = playerBarriers.get(player.getUniqueId());

        if (playerBarrierSet != null && containsBlockLocation(playerBarrierSet, blockLoc)) {
            event.setCancelled(true);
            
            // Push player back if they're trying to interact with barrier
            pushPlayerAwayFromBarrier(player, blockLoc);
            
            Scheduler.runTaskLater(() -> refreshBarrierBlock(blockLoc, player), 1L);
        }
    }

    private boolean containsBlockLocation(Set<Location> locations, Location blockLoc) {
        Location normalizedBlockLoc = normalizeToBlockLocation(blockLoc);
        for (Location loc : locations) {
            if (normalizeToBlockLocation(loc).equals(normalizedBlockLoc)) {
                return true;
            }
        }
        return false;
    }

    private Location normalizeToBlockLocation(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private void refreshBarrierBlock(Location loc, Player player) {
        Location normalizedLoc = normalizeToBlockLocation(loc);
        Set<UUID> viewers = barrierViewers.get(normalizedLoc);
        if (viewers != null && viewers.contains(player.getUniqueId())) {
            player.sendBlockChange(normalizedLoc, barrierMaterial.createBlockData());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;

        Location blockLoc = normalizeToBlockLocation(event.getBlock().getLocation());
        if (originalBlocks.containsKey(blockLoc)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        removePlayerBarriers(player);
        lastMessageTime.remove(playerUUID);
        pearlThrowLocations.remove(playerUUID);
        lastBarrierUpdate.remove(playerUUID);
        combatPlayerPearls.entrySet().removeIf(entry -> entry.getValue().equals(playerUUID));
    }

    private void pushPlayerBack(Player player, Location from, Location to) {
        if (player == null || from == null) return;

        Location safeLocation = from.clone();
        safeLocation.setPitch(player.getLocation().getPitch());
        safeLocation.setYaw(player.getLocation().getYaw());

        player.teleportAsync(safeLocation).thenAccept(success -> {
            try {
                player.setVelocity(new Vector(0, 0, 0));
            } catch (Exception ignored) {
            }
        });
    }

    public boolean isLocationInSafeZone(Location location) {
        return isSafeZone(location);
    }

    private double getSafeY(Location loc) {
        Block block = loc.getBlock();
        if (!block.getType().isSolid()) return loc.getY();

        for (int y = 1; y <= 2; y++) {
            Block above = block.getRelative(0, y, 0);
            if (!above.getType().isSolid()) {
                return loc.getBlockY() + y;
            }
        }

        for (int y = 1; y <= 2; y++) {
            Block below = block.getRelative(0, -y, 0);
            if (!below.getType().isSolid() && !below.getRelative(0, -1, 0).getType().isSolid()) {
                return loc.getBlockY() - y;
            }
        }

        return loc.getY();
    }

    private void updatePlayerBarriers(Player player) {
        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }

        UUID playerUUID = player.getUniqueId();
        Set<Location> newBarriers = findNearbyBarrierLocations(player.getLocation());
        Set<Location> currentBarriers = playerBarriers.get(playerUUID);

        if (currentBarriers != null && currentBarriers.equals(newBarriers)) {
            return;
        }

        if (currentBarriers == null) {
            currentBarriers = new HashSet<>();
        }

        Set<Location> toRemove = new HashSet<>(currentBarriers);
        toRemove.removeAll(newBarriers);
        for (Location loc : toRemove) {
            removeBarrierBlock(loc, player);
        }

        Set<Location> toAdd = new HashSet<>(newBarriers);
        toAdd.removeAll(currentBarriers);
        for (Location loc : toAdd) {
            createBarrierBlock(loc, player);
        }

        if (newBarriers.isEmpty()) {
            playerBarriers.remove(playerUUID);
        } else {
            playerBarriers.put(playerUUID, newBarriers);
        }
    }

    private Set<Location> findNearbyBarrierLocations(Location playerLoc) {
        Set<Location> barrierLocations = new HashSet<>();
        int radius = barrierDetectionRadius;

        double radiusSquared = radius * radius;

        World world = playerLoc.getWorld();
        int baseX = playerLoc.getBlockX();
        int baseY = playerLoc.getBlockY();
        int baseZ = playerLoc.getBlockZ();

        Location checkLoc = new Location(world, 0, 0, 0);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radiusSquared) continue;

                // Check if this X,Z position is a border at player's Y level
                checkLoc.setX(baseX + x);
                checkLoc.setY(baseY);
                checkLoc.setZ(baseZ + z);
                
                if (isBorderLocation(checkLoc)) {
                    // This is a border location - now extend barriers vertically
                    // Check if there's open sky above
                    boolean hasOpenSky = true;
                    for (int y = baseY + 1; y <= world.getMaxHeight(); y++) {
                        Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                        if (block.getType().isSolid()) {
                            hasOpenSky = false;
                            break;
                        }
                    }
                    
                    if (hasOpenSky) {
                        // Extend barrier through entire region height (Y-min to Y-max)
                        // Get region bounds for this location
                        int minY = getRegionMinY(checkLoc);
                        int maxY = getRegionMaxY(checkLoc);
                        
                        // Create barriers from region min to max, but limit to configured height around player
                        int startY = Math.max(minY, baseY - 1);
                        int endY = Math.min(maxY, baseY + barrierHeight);
                        
                        for (int y = startY; y <= endY; y++) {
                            Location barrierLoc = new Location(world, baseX + x, y, baseZ + z);
                            barrierLocations.add(normalizeToBlockLocation(barrierLoc));
                        }
                    } else {
                        // No open sky - use standard height
                        for (int y = -1; y <= barrierHeight; y++) {
                            checkLoc.setY(baseY + y);
                            barrierLocations.add(normalizeToBlockLocation(checkLoc.clone()));
                        }
                    }
                }
            }
        }

        return barrierLocations;
    }
    
    private int getRegionMinY(Location loc) {
        try {
            String worldName = loc.getWorld().getName();
            RegionManager regionManager = regionManagerCache.get(worldName);
            if (regionManager == null) {
                regionManager = WorldGuard.getInstance().getPlatform()
                        .getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
                if (regionManager != null) {
                    regionManagerCache.put(worldName, regionManager);
                }
            }
            
            if (regionManager != null) {
                BlockVector3 pos = BlockVector3.at(loc.getX(), loc.getY(), loc.getZ());
                ApplicableRegionSet regions = regionManager.getApplicableRegions(pos);
                
                if (!regions.getRegions().isEmpty()) {
                    return regions.getRegions().stream()
                            .mapToInt(region -> region.getMinimumPoint().y())
                            .min()
                            .orElse(loc.getWorld().getMinHeight());
                }
            }
        } catch (Exception e) {
            plugin.debug("Error getting region min Y: " + e.getMessage());
        }
        return loc.getWorld().getMinHeight();
    }
    
    private int getRegionMaxY(Location loc) {
        try {
            String worldName = loc.getWorld().getName();
            RegionManager regionManager = regionManagerCache.get(worldName);
            if (regionManager == null) {
                regionManager = WorldGuard.getInstance().getPlatform()
                        .getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
                if (regionManager != null) {
                    regionManagerCache.put(worldName, regionManager);
                }
            }
            
            if (regionManager != null) {
                BlockVector3 pos = BlockVector3.at(loc.getX(), loc.getY(), loc.getZ());
                ApplicableRegionSet regions = regionManager.getApplicableRegions(pos);
                
                if (!regions.getRegions().isEmpty()) {
                    return regions.getRegions().stream()
                            .mapToInt(region -> region.getMaximumPoint().y())
                            .max()
                            .orElse(loc.getWorld().getMaxHeight());
                }
            }
        } catch (Exception e) {
            plugin.debug("Error getting region max Y: " + e.getMessage());
        }
        return loc.getWorld().getMaxHeight();
    }

    private boolean isBorderLocation(Location loc) {
        SafeZoneInfo info = getSafeZoneInfo(loc);
        if (!info.isSafeZone) return false;

        int[][] directions = {{1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}};

        for (int[] dir : directions) {
            Location adjacent = loc.clone().add(dir[0], dir[1], dir[2]);
            if (!getSafeZoneInfo(adjacent).isSafeZone) {
                return true;
            }
        }

        return false;
    }

    private void createBarrierBlock(Location loc, Player player) {
        Location normalizedLoc = normalizeToBlockLocation(loc);
        Block block = normalizedLoc.getBlock();

        if (block.getType() != Material.AIR && block.getType().isSolid()) return;

        originalBlocks.put(normalizedLoc, block.getType());
        barrierViewers.computeIfAbsent(normalizedLoc, k -> new HashSet<>()).add(player.getUniqueId());
        player.sendBlockChange(normalizedLoc, barrierMaterial.createBlockData());
        
        // Check if player is inside or very close to this barrier block
        if (isPlayerInsideBarrier(player, normalizedLoc)) {
            pushPlayerAwayFromBarrier(player, normalizedLoc);
        }
    }
    
    private boolean isPlayerInsideBarrier(Player player, Location barrierLoc) {
        Location playerLoc = player.getLocation();
        
        // Check if player's hitbox intersects with the barrier block
        double distance = playerLoc.distance(barrierLoc.clone().add(0.5, 0.5, 0.5));
        return distance < 1.5; // Player is inside or very close to barrier
    }
    
    private void pushPlayerAwayFromBarrier(Player player, Location barrierLoc) {
        Location playerLoc = player.getLocation();
        Location barrierCenter = barrierLoc.clone().add(0.5, 0, 0.5);
        
        // Calculate direction away from barrier
        Vector direction = playerLoc.toVector().subtract(barrierCenter.toVector()).normalize();
        
        // If player is exactly on the barrier, push them in a default direction
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(1, 0, 0);
        }
        
        // Apply horizontal push force (don't push vertically to avoid launching players)
        direction.setY(0);
        direction.normalize().multiply(pushBackForce);
        
        try {
            player.setVelocity(direction);
        } catch (Exception e) {
            plugin.debug("Failed to push player away from barrier: " + e.getMessage());
        }
    }

    private void removeBarrierBlock(Location loc, Player player) {
        Location normalizedLoc = normalizeToBlockLocation(loc);
        Set<UUID> viewers = barrierViewers.get(normalizedLoc);

        if (viewers != null) {
            viewers.remove(player.getUniqueId());

            if (viewers.isEmpty()) {
                barrierViewers.remove(normalizedLoc);
                Material originalType = originalBlocks.remove(normalizedLoc);
                if (originalType != null) {
                    player.sendBlockChange(normalizedLoc, originalType.createBlockData());
                }
            } else {
                Material originalType = originalBlocks.get(normalizedLoc);
                if (originalType != null) {
                    player.sendBlockChange(normalizedLoc, originalType.createBlockData());
                }
            }
        }
    }

    private void removePlayerBarriers(Player player) {
        Set<Location> barriers = playerBarriers.remove(player.getUniqueId());
        if (barriers != null) {
            for (Location loc : barriers) {
                removeBarrierBlock(loc, player);
            }
        }
    }

    private void startCleanupTask() {
        Scheduler.runTaskTimerAsync(() -> {
            long currentTime = System.currentTimeMillis();

            cleanupPlayerBarriers();
            cleanupExpiredPearlLocations();
            cleanupMessageCooldowns(currentTime);
            cleanupCaches(currentTime);

        }, 100L, 100L);
    }

    private void cleanupPlayerBarriers() {
        playerBarriers.entrySet().removeIf(entry -> {
            UUID playerUUID = entry.getKey();
            Player player = plugin.getServer().getPlayer(playerUUID);

            if (player == null || !player.isOnline() || !combatManager.isInCombat(player) || !isEnabledInWorld(player.getWorld())) {
                Set<Location> barriers = entry.getValue();
                if (player != null && player.isOnline()) {
                    for (Location loc : barriers) {
                        removeBarrierBlock(loc, player);
                    }
                } else {
                    for (Location loc : barriers) {
                        cleanupOfflinePlayerBarrier(loc, playerUUID);
                    }
                }
                return true;
            }
            return false;
        });
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

    private void cleanupExpiredPearlLocations() {
        pearlThrowLocations.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void cleanupMessageCooldowns(long currentTime) {
        lastMessageTime.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > MESSAGE_COOLDOWN * 10);
    }

    private void cleanupCaches(long currentTime) {
        if (currentTime - lastCacheClean > CACHE_CLEAN_INTERVAL) {
            safeZoneCache.entrySet().removeIf(entry -> entry.getValue().isExpired());

            regionCheckCache.entrySet().removeIf(entry ->
                    currentTime - entry.getValue() > CACHE_TTL);

            if (safeZoneCache.size() > MAX_CACHE_SIZE) {
                safeZoneCache.clear();
            }
            if (regionCheckCache.size() > MAX_CACHE_SIZE) {
                regionCheckCache.clear();
            }

            lastCacheClean = currentTime;
        }
    }

    private SafeZoneInfo getSafeZoneInfo(Location location) {
        if (location == null) return new SafeZoneInfo(false, false);

        long cacheKey = locationToCacheKey(location);

        SafeZoneInfo cached = safeZoneCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        try {
            String worldName = location.getWorld().getName();
            RegionManager regionManager = regionManagerCache.get(worldName);
            if (regionManager == null) {
                regionManager = WorldGuard.getInstance().getPlatform()
                        .getRegionContainer().get(BukkitAdapter.adapt(location.getWorld()));
                if (regionManager != null) {
                    regionManagerCache.put(worldName, regionManager);
                }
            }

            boolean hasRegions = false;
            boolean isSafeZone = false;

            if (regionManager != null) {
                BlockVector3 pos = BlockVector3.at(location.getX(), location.getY(), location.getZ());
                ApplicableRegionSet regions = regionManager.getApplicableRegions(pos);
                hasRegions = !regions.getRegions().isEmpty();

                if (hasRegions) {
                    com.sk89q.worldedit.util.Location worldGuardLoc = BukkitAdapter.adapt(location);
                    boolean pvpAllowed = regionQuery.testState(worldGuardLoc, null, Flags.PVP);
                    isSafeZone = !pvpAllowed;
                }
            }

            SafeZoneInfo info = new SafeZoneInfo(isSafeZone, hasRegions);
            safeZoneCache.put(cacheKey, info);
            return info;

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking WorldGuard: " + e.getMessage());
            return new SafeZoneInfo(false, false);
        }
    }

    private boolean isSafeZone(Location location) {
        return getSafeZoneInfo(location).isSafeZone;
    }

    private Location findSafeLocation(Location location) {
        if (location == null) return null;

        int searchRadius = 10;

        if (isLocationSafe(location)) return location;

        for (int y = 1; y <= searchRadius; y++) {
            Location above = location.clone().add(0, y, 0);
            if (isLocationSafe(above)) return above;

            Location below = location.clone().add(0, -y, 0);
            if (isLocationSafe(below)) return below;
        }

        for (int distance = 1; distance <= searchRadius; distance++) {
            for (int x = -distance; x <= distance; x++) {
                for (int z = -distance; z <= distance; z++) {
                    if (Math.abs(x) < distance && Math.abs(z) < distance) continue;

                    for (int y = -distance; y <= distance; y++) {
                        Location checkLoc = location.clone().add(x, y, z);
                        if (isLocationSafe(checkLoc)) return checkLoc;
                    }
                }
            }
        }

        return null;
    }

    private boolean isLocationSafe(Location location) {
        if (location == null) return false;

        if (isSafeZone(location)) return false;

        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();
        Block ground = location.clone().add(0, -1, 0).getBlock();

        return (feet.getType() == Material.AIR || !feet.getType().isSolid())
                && (head.getType() == Material.AIR || !head.getType().isSolid())
                && ground.getType().isSolid();
    }

    private void sendCooldownMessage(Player player, String messageKey) {
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        Long lastTime = lastMessageTime.get(playerUUID);
        if (lastTime != null && currentTime - lastTime < MESSAGE_COOLDOWN) return;
        lastMessageTime.put(playerUUID, currentTime);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(combatManager.getRemainingCombatTime(player)));
        plugin.getMessageService().sendMessage(player, messageKey, placeholders);
    }

    private Material loadBarrierMaterial() {
        String materialName = plugin.getConfig().getString("safezone_protection.barrier_material", "RED_STAINED_GLASS");

        try {
            Material material = Material.valueOf(materialName.toUpperCase());

            if (!material.isBlock()) {
                plugin.getLogger().warning("Barrier material '" + materialName + "' is not a valid block material. Using RED_STAINED_GLASS instead.");
                return Material.RED_STAINED_GLASS;
            }

            plugin.debug("Using barrier material: " + material.name() + " for safezone protection.");
            return material;

        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid barrier material '" + materialName + "' in config. Using RED_STAINED_GLASS instead.");
            return Material.RED_STAINED_GLASS;
        }
    }

    public void cleanup() {
        combatPlayerPearls.clear();
        pearlThrowLocations.clear();
        playerBarriers.clear();
        originalBlocks.clear();
        barrierViewers.clear();
        lastMessageTime.clear();
        safeZoneCache.clear();
        regionCheckCache.clear();
        regionManagerCache.clear();
        lastBarrierUpdate.clear();
        worldSettings.clear();
    }
}