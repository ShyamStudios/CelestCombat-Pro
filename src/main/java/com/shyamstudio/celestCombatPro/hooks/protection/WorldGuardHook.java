package com.shyamstudio.celestCombatPro.hooks.protection;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
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
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class WorldGuardHook implements Listener {

    private static final long MESSAGE_COOLDOWN = 2_000;
    private static final int SKY_CHECK_LIMIT = 24;
    private final CelestCombatPro plugin;

    // =========================================================================
    // Fields
    // =========================================================================
    private final CombatManager combatManager;
    // One BorderCache per world — created lazily, rebuilt on schedule.
    private final Map<String, BorderCache> borderCaches = new ConcurrentHashMap<>();
    // Barrier state — BlockPos keys eliminate all normalizeToBlockLocation() calls.
    private final Map<UUID, Set<BlockPos>> playerBarriers = new ConcurrentHashMap<>();
    private final Map<BlockPos, Material> originalBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, Set<UUID>> barrierViewers = new ConcurrentHashMap<>();
    // Players whose barriers need refreshing — set by PlayerMoveEvent,
    // drained by the bulk barrier task.
    private final Set<UUID> barrierUpdateQueue = ConcurrentHashMap.newKeySet();
    // Pearl tracking
    private final Map<UUID, UUID> combatPlayerPearls = new ConcurrentHashMap<>();
    private final Map<UUID, PearlLocationData> pearlThrowLocations = new ConcurrentHashMap<>();
    // Message cooldowns
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    // Config
    private volatile boolean globalEnabled;
    private volatile Map<String, Boolean> worldSettings = new HashMap<>();
    private volatile int barrierDetectionRadius;
    private volatile int barrierHeight;
    private volatile Material barrierMaterial;
    private volatile double pushBackForce;
    private volatile long borderCacheRebuildInterval; // ms
    private volatile boolean useChunkCache; // chunk-level optimization
    // =========================================================================
    // Constructor
    // =========================================================================
    public WorldGuardHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        reloadConfig();
        startTasks();
    }

    // =========================================================================
    // Config
    // =========================================================================
    public void reloadConfig() {
        this.globalEnabled = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        this.worldSettings = loadWorldSettings();
        this.barrierDetectionRadius = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        this.barrierHeight = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        this.barrierMaterial = loadBarrierMaterial();
        this.pushBackForce = plugin.getConfig().getDouble("safezone_protection.push_back_force", 0.6);
        this.borderCacheRebuildInterval = plugin.getConfig().getLong("safezone_protection.border_cache_rebuild_interval_ms", 3_000);
        this.useChunkCache = plugin.getConfig().getBoolean("safezone_protection.use_chunk_cache", false);

        borderCaches.clear();
        plugin.debug("WorldGuard safezone protection reloaded. Global: " + globalEnabled 
                + ", Chunk Cache: " + useChunkCache);
    }

    @SuppressWarnings("ConstantConditions")
    private Map<String, Boolean> loadWorldSettings() {
        Map<String, Boolean> settings = new HashMap<>();
        if (plugin.getConfig().isConfigurationSection("safezone_protection.worlds")) {
            for (String w : plugin.getConfig().getConfigurationSection("safezone_protection.worlds").getKeys(false)) {
                settings.put(w, plugin.getConfig().getBoolean("safezone_protection.worlds." + w, globalEnabled));
            }
        }
        return settings;
    }

    private boolean isEnabledInWorld(World world) {
        return world != null && worldSettings.getOrDefault(world.getName(), globalEnabled);
    }

    private boolean isEnabledInWorld(Location loc) {
        return loc != null && isEnabledInWorld(loc.getWorld());
    }

    // =========================================================================
    // BorderCache access — lazy creation + async rebuild scheduling
    // =========================================================================
    private BorderCache getCacheForWorld(World world) {
        return borderCaches.computeIfAbsent(world.getName(), k -> {
            BorderCache cache = new BorderCache(world);
            // Trigger an immediate async rebuild for this world.
            Scheduler.runTaskAsync(cache::rebuild);
            return cache;
        });
    }

    private boolean isSafeZone(Location loc) {
        if (!isEnabledInWorld(loc)) return false;
        return getCacheForWorld(loc.getWorld()).isSafeZone(loc);
    }

    // =========================================================================
    // Background tasks
    // =========================================================================
    private void startTasks() {
        // ── Border cache rebuild task ──────────────────────────────────────
        // Runs async every second; only triggers a rebuild when the TTL has
        // expired for a given world.  With 200 players the rebuild scan is
        // O(region_area) and happens at most once per 3 s per world — not
        // per player, not per move event.
        Scheduler.runTaskTimerAsync(() -> {
            for (BorderCache cache : borderCaches.values()) {
                if (cache.needsRebuild()) cache.rebuild();
            }
        }, 20L, 20L); // every second, async

        // ── Bulk barrier update task ──────────────────────────────────────
        // Drains the barrierUpdateQueue and processes each player once,
        // regardless of how many move-events they fired since last tick.
        // All Bukkit API calls here must be main-thread safe; adjust with
        // Scheduler.runTask() if your Scheduler wrapper requires it.
        Scheduler.runTaskTimer(() -> {
            Set<UUID> queued = new HashSet<>(barrierUpdateQueue);
            barrierUpdateQueue.clear();
            for (UUID uuid : queued) {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player == null || !player.isOnline()) continue;
                if (!combatManager.isInCombat(player)) {
                    removePlayerBarriers(player);
                    continue;
                }
                updatePlayerBarriers(player);
            }
        }, 5L, 5L); // every 5 ticks (~250 ms)

        // ── General cleanup task ──────────────────────────────────────────
        Scheduler.runTaskTimerAsync(() -> {
            long now = System.currentTimeMillis();
            cleanupPlayerBarriers();
            pearlThrowLocations.entrySet().removeIf(e -> e.getValue().isExpired());
            lastMessageTime.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_COOLDOWN * 10);
        }, 100L, 100L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;
        if (!isEnabledInWorld(player.getWorld())) return;

        if (combatManager.isInCombat(player)) {
            combatPlayerPearls.put(pearl.getUniqueId(), player.getUniqueId());
            pearlThrowLocations.put(player.getUniqueId(), new PearlLocationData(player.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!isEnabledInWorld(pearl.getLocation())) return;

        UUID playerUUID = combatPlayerPearls.remove(pearl.getUniqueId());
        if (playerUUID == null) return;

        Location dest = calculateTeleportDestination(event, pearl);
        if (isSafeZone(dest)) {
            event.setCancelled(true);
            Player player = plugin.getServer().getPlayer(playerUUID);
            handlePearlTeleportBack(player, playerUUID);
        }
        pearlThrowLocations.remove(playerUUID);
    }

    /**
     * Hot path — must be as cheap as possible.
     * <p>
     * Work done per firing (after block-level dedup):
     * 1. isEnabledInWorld   — HashMap.get()
     * 2. isInCombat         — whatever CombatManager does (assumed O(1))
     * 3. isSafeZone ×2      — HashSet.contains() on the shared snapshot
     * 4. barrierUpdateQueue — ConcurrentHashMap.newKeySet().add()
     * <p>
     * No WorldGuard calls. No Location allocations beyond event's own objects.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!CelestCombatPro.hasWorldGuard) return;

        // ── 1. Block-level early exit — no map reads ──
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to ==  null) return; // sadly possible
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        World world = player.getWorld();

        // ── 2. World / combat gate ──
        if (!isEnabledInWorld(world)) {
            removePlayerBarriers(player);
            return;
        }
        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }

        // ── 3. Safezone transition — two HashSet.contains() calls ──
        BorderCache cache = getCacheForWorld(world);
        boolean fromSafe = cache.isSafeZone(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        boolean toSafe = cache.isSafeZone(to.getBlockX(), to.getBlockY(), to.getBlockZ());

        if (!fromSafe && toSafe) {
            pushPlayerBack(player, from);
            sendCooldownMessage(player, "combat_no_safezone_entry");
            return; // no barrier update needed if we just pushed them back
        }

        // ── 4. Queue for barrier update (bulk-processed every 5 ticks) ──
        barrierUpdateQueue.add(player.getUniqueId());
    }

    // =========================================================================
    // Events
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isEnabledInWorld(player.getWorld()) || !combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        BlockPos clicked = BlockPos.of(event.getClickedBlock().getLocation());
        Set<BlockPos> barriers = playerBarriers.get(player.getUniqueId());

        if (barriers != null && barriers.contains(clicked)) {
            event.setCancelled(true);
            pushPlayerAwayFromBarrier(player, clicked.toLocation(player.getWorld()));
            Scheduler.runTaskLater(() -> refreshBarrierBlock(clicked, player), 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        if (originalBlocks.containsKey(BlockPos.of(event.getBlock().getLocation())))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        removePlayerBarriers(event.getPlayer());
        barrierUpdateQueue.remove(uuid);
        lastMessageTime.remove(uuid);
        pearlThrowLocations.remove(uuid);
        combatPlayerPearls.entrySet().removeIf(e -> e.getValue().equals(uuid));
    }

    /**
     * Computes which barrier blocks a player should see and diffs against what
     * they currently see — only sending block-change packets for the delta.
     * <p>
     * Uses the shared BorderCache snapshot: finding nearby border columns is
     * O(radius²) HashSet lookups, not O(radius² × 5) WorldGuard queries.
     */
    private void updatePlayerBarriers(Player player) {
        Set<BlockPos> newBarriers = findNearbyBarrierBlocks(player);
        UUID uuid = player.getUniqueId();
        Set<BlockPos> current = playerBarriers.getOrDefault(uuid, Collections.emptySet());

        if (current.equals(newBarriers)) return;

        for (BlockPos pos : current) {
            if (!newBarriers.contains(pos)) removeBarrierBlock(pos, player);
        }
        for (BlockPos pos : newBarriers) {
            if (!current.contains(pos)) createBarrierBlock(pos, player);
        }

        if (newBarriers.isEmpty()) playerBarriers.remove(uuid);
        else playerBarriers.put(uuid, newBarriers);
    }

    /**
     * For each XZ position within radius, checks the shared border snapshot
     * (HashSet.contains) and calculates the vertical extent of the barrier.
     * <p>
     * No WorldGuard calls. No Location allocations inside the loop.
     */
    private Set<BlockPos> findNearbyBarrierBlocks(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();
        int radius = barrierDetectionRadius;
        int radiusSq = radius * radius;
        int skyLimit = Math.min(baseY + SKY_CHECK_LIMIT, world.getMaxHeight() - 1);

        BorderCache cache = getCacheForWorld(world);
        Set<BlockPos> result = new HashSet<>();
        
        plugin.debug("[Barrier] Searching for barriers near " + player.getName() 
                + " at (" + baseX + ", " + baseY + ", " + baseZ + "), radius=" + radius);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;

                int cx = baseX + dx;
                int cz = baseZ + dz;

                // Pure HashSet.contains() — no WG call
                if (!cache.isBorder(cx, baseY, cz)) continue;
                
                plugin.debug("[Barrier] Found border at (" + cx + ", " + baseY + ", " + cz + ")");

                // Sky check — capped at SKY_CHECK_LIMIT
                boolean hasOpenSky = true;
                for (int y = baseY + 1; y <= skyLimit; y++) {
                    if (world.getBlockAt(cx, y, cz).getType().isSolid()) {
                        hasOpenSky = false;
                        break;
                    }
                }

                int startY, endY;
                if (hasOpenSky) {
                    int[] bounds = cache.boundsAt(cx, cz);
                    int minY = bounds != null ? bounds[0] : world.getMinHeight();
                    int maxY = bounds != null ? bounds[1] : world.getMaxHeight();
                    startY = Math.max(minY, baseY - 1);
                    endY = Math.min(maxY, baseY + barrierHeight);
                } else {
                    startY = baseY - 1;
                    endY = baseY + barrierHeight;
                }

                for (int y = startY; y <= endY; y++) {
                    result.add(BlockPos.of(world, cx, y, cz));
                }
            }
        }
        
        plugin.debug("[Barrier] Found " + result.size() + " barrier blocks for " + player.getName());
        return result;
    }

    private void createBarrierBlock(BlockPos pos, Player player) {
        Location loc = pos.toLocation(player.getWorld());
        Block block = loc.getBlock();
        if (block.getType() != Material.AIR && block.getType().isSolid()) return;

        originalBlocks.put(pos, block.getType());
        barrierViewers.computeIfAbsent(pos, k -> new HashSet<>()).add(player.getUniqueId());
        player.sendBlockChange(loc, barrierMaterial.createBlockData());

        if (isPlayerInsideBarrier(player, loc))
            pushPlayerAwayFromBarrier(player, loc);
    }

    // =========================================================================
    // Barrier management
    // =========================================================================

    private void removeBarrierBlock(BlockPos pos, Player player) {
        Set<UUID> viewers = barrierViewers.get(pos);
        if (viewers == null) return;

        viewers.remove(player.getUniqueId());
        Location loc = pos.toLocation(player.getWorld());

        if (viewers.isEmpty()) {
            barrierViewers.remove(pos);
            Material original = originalBlocks.remove(pos);
            if (original != null) player.sendBlockChange(loc, original.createBlockData());
        } else {
            Material original = originalBlocks.get(pos);
            if (original != null) player.sendBlockChange(loc, original.createBlockData());
        }
    }

    private void removePlayerBarriers(Player player) {
        Set<BlockPos> barriers = playerBarriers.remove(player.getUniqueId());
        if (barriers != null) barriers.forEach(pos -> removeBarrierBlock(pos, player));
    }

    private void refreshBarrierBlock(BlockPos pos, Player player) {
        Set<UUID> viewers = barrierViewers.get(pos);
        if (viewers != null && viewers.contains(player.getUniqueId()))
            player.sendBlockChange(pos.toLocation(player.getWorld()), barrierMaterial.createBlockData());
    }

    // =========================================================================
    // Barrier geometry helpers
    // =========================================================================
    private boolean isPlayerInsideBarrier(Player player, Location barrierLoc) {
        return player.getLocation().distance(barrierLoc.clone().add(0.5, 0.5, 0.5)) < 1.5;
    }

    private void pushPlayerAwayFromBarrier(Player player, Location barrierLoc) {
        Vector dir = player.getLocation().toVector()
                .subtract(barrierLoc.clone().add(0.5, 0, 0.5).toVector());
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        dir.setY(0).normalize().multiply(pushBackForce);
        try {
            player.setVelocity(dir);
        } catch (Exception e) {
            plugin.debug("pushPlayerAwayFromBarrier: " + e.getMessage());
        }
    }

    // =========================================================================
    // Pearl helpers
    // =========================================================================
    private Location calculateTeleportDestination(ProjectileHitEvent event, Projectile proj) {
        if (event.getHitBlock() != null) {
            Block hit = event.getHitBlock();
            Location d = new Location(proj.getWorld(), hit.getX(), hit.getY(), hit.getZ());
            if (event.getHitBlockFace() != null)
                d.add(event.getHitBlockFace().getDirection().multiply(0.5));
            return d;
        }
        return proj.getLocation();
    }

    private void handlePearlTeleportBack(Player player, UUID uuid) {
        if (player == null || !player.isOnline()) return;
        PearlLocationData data = pearlThrowLocations.get(uuid);
        if (data != null && !data.isExpired()) {
            player.teleportAsync(data.location()).thenAccept(ok -> {
                if (ok) sendCooldownMessage(player, "combat_no_pearl_safezone");
                else handleFailedTeleport(player, data.location());
            });
        } else {
            sendCooldownMessage(player, "combat_no_pearl_safezone");
        }
    }

    private void handleFailedTeleport(Player player, Location origin) {
        Location safe = findSafeLocation(origin);
        if (safe != null) {
            player.teleportAsync(safe);
            sendCooldownMessage(player, "combat_no_pearl_safezone");
        } else {
            player.setHealth(0);
            plugin.getLogger().warning("Killed " + player.getName() + " — no safe location found.");
            sendCooldownMessage(player, "combat_killed_no_safe_location");
        }
    }

    // =========================================================================
    // Push-back
    // =========================================================================
    private void pushPlayerBack(Player player, Location from) {
        if (player == null || from == null) return;
        Location safe = from.clone();
        safe.setYaw(player.getLocation().getYaw());
        safe.setPitch(player.getLocation().getPitch());
        player.teleportAsync(safe).thenAccept(ok -> {
            try {
                player.setVelocity(new Vector(0, 0, 0));
            } catch (Exception ignored) {
            }
        });
    }

    // =========================================================================
    // Safe-location finder
    // =========================================================================
    private Location findSafeLocation(Location origin) {
        if (origin == null) return null;

        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        int r = 10;
        BorderCache cache = getCacheForWorld(world);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = ox + dx, cz = oz + dz;
                if (cache.isSafeZone(cx, oy, cz)) continue; // skip safe-zone columns

                for (int dy = 0; dy <= r; dy++) {
                    for (int sign : dy == 0 ? new int[]{1} : new int[]{1, -1}) {
                        int cy = oy + dy * sign;
                        if (cy < world.getMinHeight() || cy >= world.getMaxHeight() - 1) continue;
                        if (isColumnSafe(world, cx, cy, cz))
                            return new Location(world, cx, cy, cz,
                                    origin.getYaw(), origin.getPitch());
                    }
                }
            }
        }
        return null;
    }

    private boolean isColumnSafe(World world, int x, int y, int z) {
        return world.getBlockAt(x, y - 1, z).getType().isSolid()
                && !world.getBlockAt(x, y, z).getType().isSolid()
                && !world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    // =========================================================================
    // Cleanup
    // =========================================================================
    private void cleanupPlayerBarriers() {
        playerBarriers.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()
                    || !combatManager.isInCombat(player)
                    || !isEnabledInWorld(player.getWorld())) {
                if (player != null && player.isOnline()) {
                    entry.getValue().forEach(pos -> removeBarrierBlock(pos, player));
                } else {
                    entry.getValue().forEach(pos -> {
                        Set<UUID> viewers = barrierViewers.get(pos);
                        if (viewers != null) {
                            viewers.remove(entry.getKey());
                            if (viewers.isEmpty()) {
                                barrierViewers.remove(pos);
                                originalBlocks.remove(pos);
                            }
                        }
                    });
                }
                return true;
            }
            return false;
        });
    }

    // =========================================================================
    // Public API
    // =========================================================================
    public boolean isLocationInSafeZone(Location location) {
        return isSafeZone(location);
    }

    // =========================================================================
    // Messages
    // =========================================================================
    private void sendCooldownMessage(Player player, String key) {
        long now = System.currentTimeMillis();
        Long last = lastMessageTime.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN) return;
        lastMessageTime.put(player.getUniqueId(), now);
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        ph.put("time", String.valueOf(combatManager.getRemainingCombatTime(player)));
        plugin.getMessageService().sendMessage(player, key, ph);
    }

    // =========================================================================
    // Material loader
    // =========================================================================
    private Material loadBarrierMaterial() {
        String name = plugin.getConfig()
                .getString("safezone_protection.barrier_material", "RED_STAINED_GLASS");
        try {
            Material material = Material.valueOf(name.toUpperCase());
            if (!material.isBlock()) {
                plugin.getLogger().warning("Barrier material '" + name + "' is not a block. Falling back to RED_STAINED_GLASS.");
                return Material.RED_STAINED_GLASS;
            }
            plugin.debug("Using barrier material: " + material.name() + " for safezone protection.");
            return material;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid barrier material '" + name + "'. Falling back to RED_STAINED_GLASS.");
            return Material.RED_STAINED_GLASS;
        }
    }

    // =========================================================================
    // Full cleanup on plugin disable
    // =========================================================================
    public void cleanup() {
        borderCaches.clear();
        playerBarriers.clear();
        originalBlocks.clear();
        barrierViewers.clear();
        barrierUpdateQueue.clear();
        combatPlayerPearls.clear();
        pearlThrowLocations.clear();
        lastMessageTime.clear();
        worldSettings.clear();
    }

    // =========================================================================
    // BlockPos — immutable block coordinate key.
    // record gives equals/hashCode/toString for free; no Location.equals()
    // yaw/pitch bugs, no normalizeToBlockLocation() boilerplate.
    // =========================================================================
    private record BlockPos(String world, int x, int y, int z) {

        static BlockPos of(Location loc) {
            return new BlockPos(
                    loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        static BlockPos of(World world, int x, int y, int z) {
            return new BlockPos(world.getName(), x, y, z);
        }

        Location toLocation(World w) {
            return new Location(w, x, y, z);
        }
    }

    // =========================================================================
    // PearlLocationData
    // =========================================================================
    private record PearlLocationData(Location location, long timestamp) {
        PearlLocationData(Location location) {
            this(location.clone(), System.currentTimeMillis());
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60_000;
        }
    }

    // =========================================================================
    // BorderCache — one instance per world.
    //
    // Rebuilt asynchronously on a schedule (default every 3 s). The rebuild
    // walks every safe-zone region's bounding box at the XZ plane and marks
    // blocks whose cardinal neighbors leave the safe zone as "border" blocks.
    //
    // All hot-path queries (isSafeZone, isBorder, getBarrierColumns) are
    // pure HashSet.contains() — zero WorldGuard calls, zero allocations.
    //
    // The AtomicReference swap makes reads lock-free; a player that races a
    // rebuild mid-frame will see either the old or the new snapshot, both
    // of which are consistent.
    // =========================================================================
    private class BorderCache {

        private final World world;
        private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
        private volatile long lastRebuild = 0;
        BorderCache(World world) {
            this.world = world;
        }

        boolean isSafeZone(int x, int y, int z) {
            return snapshot.get().isSafeZone(BlockPos.of(world, x, 0, z));
        }

        // ----- hot-path queries (called from PlayerMoveEvent) ----------------

        boolean isSafeZone(Location loc) {
            return snapshot.get().isSafeZone(BlockPos.of(world, loc.getBlockX(), 0, loc.getBlockZ()));
        }

        boolean isBorder(int x, int y, int z) {
            return snapshot.get().isBorder(BlockPos.of(world, x, 0, z));
        }

        int[] boundsAt(int x, int z) {
            return snapshot.get().boundsAt(x, z);
        }

        /**
         * Rebuilds the border snapshot asynchronously.  All WorldGuard calls
         * happen here — never on the main/event thread.
         * <p>
         * Steps:
         * 1. Ask WorldGuard for every region in this world.
         * 2. For each region whose PVP flag is DENY, scan its bounding-box
         * columns at the player's Y level and collect safeZone blocks.
         * 3. A second pass marks border blocks (safeZone block with at least
         * one non-safe cardinal neighbor).
         * 4. Atomically swap the snapshot so readers always see a consistent view.
         */
        void rebuild() {
            long startTime = System.nanoTime();
            try {
                RegionManager rm = WorldGuard.getInstance().getPlatform()
                        .getRegionContainer().get(BukkitAdapter.adapt(world));
                if (rm == null) return;

                RegionQuery query = WorldGuard.getInstance()
                        .getPlatform().getRegionContainer().createQuery();

                Set<BlockPos> newSafeZone = new HashSet<>(10000);
                Map<Long, int[]> newBounds = new HashMap<>();
                
                // 🔥 CACHE: Avoid querying same column/chunk multiple times when regions overlap
                Map<Long, Boolean> columnResultCache = new HashMap<>(16384);
                Map<Long, Boolean> chunkResultCache = useChunkCache ? new HashMap<>(1024) : null;

                int worldMinY = world.getMinHeight();
                int worldMaxY = world.getMaxHeight();

                // 🔥 REUSE Location object — zero allocations in loop
                Location bukkitLoc = new Location(world, 0, 0, 0);

                for (ProtectedRegion region : rm.getRegions().values()) {
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();

                    // Skip huge regions with warning (prevents silent protection failures)
                    long regionArea = (long) (max.x() - min.x() + 1) * (max.z() - min.z() + 1);
                    if (regionArea > 500_000) {
                        plugin.getLogger().warning("[BorderCache] Skipping huge region '" 
                                + region.getId() + "' (" + regionArea + " blocks). "
                                + "Protection may not work correctly in this region! "
                                + "Consider splitting this region or increasing the threshold.");
                        continue;
                    }

                    int rMinY = Math.max(min.y(), worldMinY);
                    int rMaxY = Math.min(max.y(), worldMaxY);
                    int cy = (rMinY + rMaxY) / 2;

                    for (int x = min.x(); x <= max.x(); x++) {
                        for (int z = min.z(); z <= max.z(); z++) {
                            long xzKey = Snapshot.xzKey(x, z);
                            
                            // 🔥 CHECK CACHE FIRST — eliminates redundant WG queries for overlapping regions
                            Boolean cached = columnResultCache.get(xzKey);
                            boolean isSafe;
                            
                            if (cached != null) {
                                isSafe = cached;
                            } else {
                                // 🔥 CHUNK-LEVEL CACHE (optional extreme optimization)
                                if (useChunkCache && chunkResultCache != null) {
                                    int chunkX = x >> 4;
                                    int chunkZ = z >> 4;
                                    long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                                    
                                    Boolean chunkCached = chunkResultCache.get(chunkKey);
                                    if (chunkCached != null) {
                                        isSafe = chunkCached;
                                        columnResultCache.put(xzKey, isSafe);
                                    } else {
                                        // Query WG once per chunk
                                        bukkitLoc.setX(x);
                                        bukkitLoc.setY(cy);
                                        bukkitLoc.setZ(z);
                                        isSafe = !query.testState(BukkitAdapter.adapt(bukkitLoc), null, Flags.PVP);
                                        chunkResultCache.put(chunkKey, isSafe);
                                        columnResultCache.put(xzKey, isSafe);
                                    }
                                } else {
                                    // 🔥 REUSE Location (NO allocation per iteration)
                                    bukkitLoc.setX(x);
                                    bukkitLoc.setY(cy);
                                    bukkitLoc.setZ(z);

                                    // 🔥 ONLY 1 WG QUERY PER UNIQUE COLUMN (respects overlaps, priority, inheritance)
                                    boolean pvpAllowed = query.testState(BukkitAdapter.adapt(bukkitLoc), null, Flags.PVP);
                                    isSafe = !pvpAllowed; // Safe zone = PVP NOT allowed
                                    columnResultCache.put(xzKey, isSafe);
                                    
                                    // Debug first few queries
                                    if (columnResultCache.size() <= 5) {
                                        plugin.debug("[BorderCache] Column (" + x + ", " + z + "): PVP=" 
                                                + pvpAllowed + ", isSafe=" + isSafe);
                                    }
                                }
                            }
                            
                            if (!isSafe) continue; // PVP allowed here

                            newSafeZone.add(BlockPos.of(world, x, 0, z));

                            int[] existing = newBounds.get(xzKey);
                            if (existing == null) {
                                newBounds.put(xzKey, new int[]{rMinY, rMaxY});
                            } else {
                                existing[0] = Math.min(existing[0], rMinY);
                                existing[1] = Math.max(existing[1], rMaxY);
                            }
                        }
                    }
                }

                // Border pass — a block is a border block if it is in the safe
                // zone and at least one cardinal XZ neighbor is not.
                Set<BlockPos> newBorder = new HashSet<>();
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

                for (BlockPos pos : newSafeZone) {
                    for (int[] d : dirs) {
                        // Neighbor at same Y (representative row).
                        BlockPos neighbour = BlockPos.of(world, pos.x() + d[0], pos.y(), pos.z() + d[1]);
                        if (!newSafeZone.contains(neighbour)) {
                            newBorder.add(pos);
                            break;
                        }
                    }
                }
                
                plugin.debug("[BorderCache] Border detection: " + newBorder.size() 
                        + " border columns from " + newSafeZone.size() + " safe columns");

                snapshot.set(new Snapshot(
                        Collections.unmodifiableSet(newSafeZone),
                        Collections.unmodifiableSet(newBorder),
                        Collections.unmodifiableMap(newBounds)));

                lastRebuild = System.currentTimeMillis();
                
                // Calculate cache effectiveness and rebuild time
                long rebuildMs = (System.nanoTime() - startTime) / 1_000_000;
                int totalRegionBlocks = 0;
                for (ProtectedRegion region : rm.getRegions().values()) {
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();
                    long area = (long) (max.x() - min.x() + 1) * (max.z() - min.z() + 1);
                    if (area <= 500_000) totalRegionBlocks += area;
                }
                int uniqueQueries = (useChunkCache && chunkResultCache != null) 
                        ? chunkResultCache.size() 
                        : columnResultCache.size();
                int savedQueries = totalRegionBlocks - uniqueQueries;
                double savePercent = totalRegionBlocks > 0 
                        ? (savedQueries * 100.0 / totalRegionBlocks) 
                        : 0;
                
                // Performance warnings
                if (rebuildMs > 100) {
                    plugin.getLogger().warning("[BorderCache] Slow rebuild detected! Took " 
                            + rebuildMs + "ms for world '" + world.getName() 
                            + "'. Consider increasing rebuild interval or splitting large regions.");
                }
                
                if (savePercent < 50 && totalRegionBlocks > 1000) {
                    plugin.getLogger().warning("[BorderCache] Low cache efficiency (" 
                            + String.format("%.1f", savePercent) + "%) in world '" + world.getName() 
                            + "'. This may indicate excessive region overlap or fragmentation.");
                }
                
                plugin.debug("[BorderCache] Rebuilt for world '" + world.getName()
                        + "' in " + rebuildMs + "ms — " 
                        + newSafeZone.size() + " safe columns, "
                        + newBorder.size() + " border columns. "
                        + (useChunkCache ? "Chunk cache" : "Column cache") + " saved " 
                        + savedQueries + " WG queries (" + String.format("%.1f", savePercent) + "%) — "
                        + uniqueQueries + " unique / " + totalRegionBlocks + " total blocks.");

            } catch (Exception e) {
                plugin.getLogger().warning("[BorderCache] Rebuild failed for '"
                        + world.getName() + "': " + e.getMessage());
            }
        }

        // ----- async rebuild -------------------------------------------------

        boolean needsRebuild() {
            return System.currentTimeMillis() - lastRebuild > borderCacheRebuildInterval;
        }

        private record Snapshot(
                Set<BlockPos> safeZone,   // XZ columns in a no-pvp region, stored with sentinel y=0
                Set<BlockPos> border,     // safe XZ columns with at least one non-safe cardinal neighbor
                // XZ column → [minY, maxY] of the region at that column
                Map<Long, int[]> columnBounds
        ) {
            static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), Map.of());

            /**
             * Encode an XZ pair as a single long for the columnBounds map.
             */
            static long xzKey(int x, int z) {
                return ((long) x << 32) | (z & 0xFFFFFFFFL);
            }

            boolean isSafeZone(BlockPos p) {
                return safeZone.contains(p);
            }

            boolean isBorder(BlockPos p) {
                return border.contains(p);
            }

            int[] boundsAt(int x, int z) {
                return columnBounds.get(xzKey(x, z));
            }
        }
    }
}