package com.shyamstudio.celestCombatPro.hooks.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class WorldGuardHook implements Listener {

    // -----------------------------------------------------------------------
    // Bitset grid — 64×64 chunk sections, 1 bit per chunk
    // 4096 bits = 64 longs per section. Zero GC, perfect cache locality.
    // -----------------------------------------------------------------------
    private static final class ChunkSection {
        // 128 longs: first 64 = safe bits, next 64 = border bits
        // Single array → both planes share one cache line fetch
        private final long[] bits = new long[128];
        // Cache-line padding — prevents false sharing when multiple threads
        // access different ChunkSection instances from the pool concurrently
        @SuppressWarnings("unused")
        private long p0, p1, p2, p3, p4, p5, p6;

        // Safe plane: words 0–63
        void setSafe(int index)   { bits[index >> 6]      |=  (1L << (index & 63)); }
        void clearSafe(int index) { bits[index >> 6]      &= ~(1L << (index & 63)); }

        // Border plane: words 64–127
        void setBorder(int index) { bits[64 + (index >> 6)] |=  (1L << (index & 63)); }

        /**
         * Combined safe+border check in a single method — one array fetch, two bit tests.
         * Returns: 0 = not safe, 1 = safe (interior), 2 = safe (border → needs WG fallback)
         */
        int checkChunk(int index) {
            int  word = index >> 6;
            long mask = 1L << (index & 63);
            // Read safe word first — most chunks are NOT safe, so this exits fast
            if ((bits[word] & mask) == 0) return 0;          // not safe
            if ((bits[64 + word] & mask) == 0) return 1;     // safe interior
            return 2;                                          // safe border
        }

        // Reset for pool reuse — MUST be called before reuse to prevent ghost safe zones
        void reset() { java.util.Arrays.fill(bits, 0L); }
    }

    // Object pool — reuse ChunkSection instances across rebuilds to avoid GC spikes
    private static final java.util.concurrent.ConcurrentLinkedQueue<ChunkSection> SECTION_POOL
            = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static ChunkSection acquireSection() {
        ChunkSection s = SECTION_POOL.poll();
        if (s != null) { s.reset(); return s; }
        return new ChunkSection();
    }

    private static void releaseSection(ChunkSection s) {
        // Cap pool size to avoid unbounded growth
        if (SECTION_POOL.size() < 256) SECTION_POOL.offer(s);
    }

    private static final class WorldGridData {
        // Long2ObjectOpenHashMap: primitive long key → no boxing, faster than HashMap<Long,*>
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ChunkSection> sections;
        volatile boolean gridReady = false;

        WorldGridData(int expectedSections) {
            this.sections = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(
                    Math.max(expectedSections, 4), 0.75f);
        }

        // Default constructor for "rebuilding" placeholder
        WorldGridData() { this(4); }

        static long sectionKey(int chunkX, int chunkZ) {
            return (((long)(chunkX >> 6)) << 32) | ((chunkZ >> 6) & 0xFFFFFFFFL);
        }

        static int sectionIndex(int chunkX, int chunkZ) {
            return ((chunkX & 63) << 6) | (chunkZ & 63);
        }

        void setSafe(int cx, int cz) {
            long key = sectionKey(cx, cz);
            ChunkSection sec = sections.get(key);
            if (sec == null) { sec = acquireSection(); sections.put(key, sec); }
            sec.setSafe(sectionIndex(cx, cz));
        }

        void clearSafe(int cx, int cz) {
            ChunkSection sec = sections.get(sectionKey(cx, cz));
            if (sec != null) sec.clearSafe(sectionIndex(cx, cz));
        }

        /**
         * Single-lookup combined check. Returns:
         *   0 = not safe, 1 = safe interior (no WG needed), 2 = safe border (WG fallback)
         * One map get, one array read, two bit tests — no double lookup, no separate isBorder call.
         */
        int checkChunk(int cx, int cz) {
            ChunkSection sec = sections.get(sectionKey(cx, cz));
            if (sec == null) return 0;
            return sec.checkChunk(sectionIndex(cx, cz));
        }

        // Used during build phase and post-rebuild player position check
        boolean isSafe(int cx, int cz) {
            return checkChunk(cx, cz) != 0;
        }

        void setBorder(int cx, int cz) {
            long key = sectionKey(cx, cz);
            ChunkSection sec = sections.get(key);
            if (sec == null) { sec = acquireSection(); sections.put(key, sec); }
            sec.setBorder(sectionIndex(cx, cz));
        }

        // Return all sections to pool when this grid is discarded
        void release() {
            sections.values().forEach(WorldGuardHook::releaseSection);
            sections.clear();
        }
    }

    // Snapshot passed to async phase — no WG objects cross thread boundary
    private static class RegionSnapshot {
        final int minX, minZ, maxX, maxZ, priority;
        final boolean isSafe;
        RegionSnapshot(int minX, int minZ, int maxX, int maxZ, int priority, boolean isSafe) {
            this.minX = minX; this.minZ = minZ;
            this.maxX = maxX; this.maxZ = maxZ;
            this.priority = priority; this.isSafe = isSafe;
        }
    }

    private static class PearlLocationData {
        final Location location;
        final long timestamp;
        PearlLocationData(Location location) {
            this.location = location.clone();
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() { return System.currentTimeMillis() - timestamp > 60_000; }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    private final RegionQuery regionQuery;

    // Grid: world name → grid data (atomic swap on rebuild)
    private final ConcurrentHashMap<String, AtomicReference<WorldGridData>> worldGrids = new ConcurrentHashMap<>();

    // Debounce: world name → last rebuild request time
    private final ConcurrentHashMap<String, Long> rebuildDebounce = new ConcurrentHashMap<>();

    // Fallback WG cache (used during rebuild or for border-precise checks)
    // TTL is intentionally short (300ms) — border regions must stay accurate in real-time
    // Packed long keys (blockKey) — no Location objects, minimal boxing overhead
    private final ConcurrentHashMap<Long, Boolean> wgFallbackCache = new ConcurrentHashMap<>();
    private static final long FALLBACK_CACHE_TTL = 300;
    private final ConcurrentHashMap<Long, Long> wgFallbackTimestamps = new ConcurrentHashMap<>();

    // Last safe location per player (updated when NOT in safezone)
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();

    // Teleport correction cooldown — prevents loop spam from rapid cancel+teleport cycles
    private final ConcurrentHashMap<UUID, Long> lastCorrection = new ConcurrentHashMap<>();
    private static final long CORRECTION_COOLDOWN_MS = 200;

    // Pearl tracking
    private final ConcurrentHashMap<UUID, UUID> combatPlayerPearls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PearlLocationData> pearlThrowLocations = new ConcurrentHashMap<>();

    // Barrier system (visual only — client-side fake blocks)
    private final ConcurrentHashMap<UUID, Set<Location>> playerBarriers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Location, Material> originalBlocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Location, Set<UUID>> barrierViewers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastBarrierUpdate = new ConcurrentHashMap<>();
    private static final long BARRIER_UPDATE_INTERVAL = 250;

    // Message cooldown
    private final ConcurrentHashMap<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN = 2_000;

    // Config cache
    private boolean globalEnabled;
    private Map<String, Boolean> worldSettings;
    private int barrierDetectionRadius;
    private int barrierHeight;
    private Material barrierMaterial;
    private boolean debugGrid;

    // Periodic fallback rebuild task
    private Scheduler.Task periodicRebuildTask;
    private Scheduler.Task cleanupTask;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public WorldGuardHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        reloadConfig();
        schedulePeriodicRebuild();
        startCleanupTask();
    }

    // -----------------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------------
    public void reloadConfig() {
        this.globalEnabled = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        this.worldSettings = loadWorldSettings();
        this.barrierDetectionRadius = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        this.barrierHeight = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        this.barrierMaterial = loadBarrierMaterial();
        this.debugGrid = plugin.getConfig().getBoolean("safezone_protection.debug_grid", false);

        // Invalidate fallback cache and trigger full rebuild
        wgFallbackCache.clear();
        wgFallbackTimestamps.clear();
        lastSafeLocation.clear(); // stale after region changes
        rebuildAllWorlds();
        plugin.debug("WorldGuard grid system reloaded");
    }

    private Map<String, Boolean> loadWorldSettings() {
        Map<String, Boolean> settings = new HashMap<>();
        if (plugin.getConfig().isConfigurationSection("safezone_protection.worlds")) {
            for (String w : plugin.getConfig().getConfigurationSection("safezone_protection.worlds").getKeys(false)) {
                settings.put(w, plugin.getConfig().getBoolean("safezone_protection.worlds." + w, globalEnabled));
            }
        }
        return settings;
    }

    private Material loadBarrierMaterial() {
        String name = plugin.getConfig().getString("safezone_protection.barrier_material", "RED_STAINED_GLASS");
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid barrier_material '" + name + "', defaulting to RED_STAINED_GLASS");
            return Material.RED_STAINED_GLASS;
        }
    }

    private boolean isEnabledInWorld(World world) {
        if (world == null) return false;
        return worldSettings.getOrDefault(world.getName(), globalEnabled);
    }

    // -----------------------------------------------------------------------
    // Chunk key helpers
    // -----------------------------------------------------------------------
    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // -----------------------------------------------------------------------
    // Grid build — 3-phase pipeline
    // -----------------------------------------------------------------------
    private void rebuildAllWorlds() {
        // Stagger builds by 2 ticks per world to smooth CPU load across multiple worlds
        int delay = 0;
        for (World world : plugin.getServer().getWorlds()) {
            if (isEnabledInWorld(world)) {
                final World w = world;
                if (delay == 0) {
                    scheduleRebuild(w);
                } else {
                    final int d = delay;
                    Scheduler.runTaskLaterAsync(() -> scheduleRebuild(w), d);
                }
                delay += 2;
            }
        }
    }

    /** Debounced rebuild trigger */
    private void scheduleRebuild(World world) {
        long now = System.currentTimeMillis();
        rebuildDebounce.put(world.getName(), now);

        Scheduler.runTaskLaterAsync(() -> {
            Long scheduled = rebuildDebounce.get(world.getName());
            if (scheduled == null || scheduled != now) return; // superseded
            buildGrid(world);
        }, 40L); // 2 ticks delay to batch rapid region events
    }

    private void buildGrid(World world) {
        // Phase 1 (SYNC): snapshot WG region data — must not pass WG objects async
        List<RegionSnapshot> snapshots = new ArrayList<>();
        try {
            RegionManager rm = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            if (rm == null) {
                plugin.debug("No RegionManager for world: " + world.getName());
                return;
            }
            for (ProtectedRegion region : rm.getRegions().values()) {
                if ("__global__".equals(region.getId())) continue;
                com.sk89q.worldguard.protection.flags.StateFlag.State pvp =
                        region.getFlag(Flags.PVP);
                boolean isSafe = (pvp == com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
                snapshots.add(new RegionSnapshot(
                        region.getMinimumPoint().x(), region.getMinimumPoint().z(),
                        region.getMaximumPoint().x(), region.getMaximumPoint().z(),
                        region.getPriority(), isSafe
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("WorldGuard grid build failed (phase 1): " + e.getMessage());
            return;
        }

        // Mark world as "rebuilding" — fallback to WG during this window
        worldGrids.computeIfAbsent(world.getName(), k -> new AtomicReference<>(new WorldGridData()))
                .set(new WorldGridData()); // gridReady = false

        final String worldName = world.getName();

        // Phase 2 (ASYNC): compute bitset grid from snapshots
        final long buildStart = System.currentTimeMillis();
        Scheduler.runTaskAsync(() -> {
            // Priority map still uses fastutil — only needed during build, not at runtime
            int estimatedChunks = snapshots.size() * 64;
            // Estimate sections: each section covers 64×64 = 4096 chunks
            int estimatedSections = Math.max(1, estimatedChunks / 4096);
            Long2IntOpenHashMap priorityMap = new Long2IntOpenHashMap(estimatedChunks, 0.75f);
            priorityMap.defaultReturnValue(Integer.MIN_VALUE);

            // Temporary safe-chunk set for border detection pass
            LongOpenHashSet safeChunkKeys = new LongOpenHashSet(estimatedChunks, 0.75f);

            WorldGridData data = new WorldGridData(estimatedSections);

            for (RegionSnapshot snap : snapshots) {
                int cMinX = snap.minX >> 4;
                int cMinZ = snap.minZ >> 4;
                int cMaxX = snap.maxX >> 4;
                int cMaxZ = snap.maxZ >> 4;

                for (int cx = cMinX; cx <= cMaxX; cx++) {
                    for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                        long key = chunkKey(cx, cz);
                        int existing = priorityMap.get(key);
                        if (snap.priority >= existing) {
                            priorityMap.put(key, snap.priority);
                            if (snap.isSafe) {
                                data.setSafe(cx, cz);
                                safeChunkKeys.add(key);
                            } else {
                                data.clearSafe(cx, cz);
                                safeChunkKeys.remove(key);
                            }
                        }
                    }
                }
            }

            // Border detection: mark chunks adjacent to a safe/unsafe boundary
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
            for (long key : safeChunkKeys) {
                int cx = (int)(key >> 32);
                int cz = (int)(key & 0xFFFFFFFFL);
                for (int[] d : dirs) {
                    if (!data.isSafe(cx + d[0], cz + d[1])) {
                        data.setBorder(cx, cz);
                        data.setBorder(cx + d[0], cz + d[1]);
                        break;
                    }
                }
            }

            // Phase 3 (SYNC): atomic swap — data is fully built, just publish it
            Scheduler.runTask(() -> {
                data.gridReady = true;

                AtomicReference<WorldGridData> ref = worldGrids.computeIfAbsent(
                        worldName, k -> new AtomicReference<>(new WorldGridData()));
                WorldGridData old = ref.getAndSet(data);
                // Return old sections to pool — avoids GC spike from discarded grid
                if (old != null && old != data) old.release();

                // Invalidate fallback cache for this world (keys are global, just clear all)
                wgFallbackCache.clear();
                wgFallbackTimestamps.clear();
                // Stale lastSafeLocations may point into regions that changed — clear them
                lastSafeLocation.clear();

                // Fix #4: if a region was removed/shrunk, combat players may now be inside
                // a safezone that no longer blocks them — push them out immediately
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (combatManager.isInCombat(p)
                            && isEnabledInWorld(p.getWorld())
                            && p.getWorld().getName().equals(worldName)
                            && isSafeZone(p.getLocation())) {
                        Location safe = lastSafeLocation.get(p.getUniqueId());
                        if (safe != null && !isSafeZone(safe)) {
                            pushPlayerBack(p, safe);
                        }
                        sendCooldownMessage(p, "combat_no_safezone_entry");
                    }
                }

                plugin.debug("Grid built for world '" + worldName + "': "
                        + data.sections.size() + " sections, "
                        + "buildMs=" + (System.currentTimeMillis() - buildStart));
                if (debugGrid) {
                    plugin.getLogger().info("[GridDebug] World='" + worldName
                            + "' sections=" + data.sections.size()
                            + " buildMs=" + (System.currentTimeMillis() - buildStart));
                }
            });
        });
    }

    private void schedulePeriodicRebuild() {
        // Fallback: rebuild every 60 seconds in case WG events are missed
        periodicRebuildTask = Scheduler.runTaskTimerAsync(this::rebuildAllWorlds, 1200L, 1200L);
    }

    // -----------------------------------------------------------------------
    // Fast lookup API
    // -----------------------------------------------------------------------

    /** Bitset lookup — one map get, two bit tests, WG fallback only on border chunks */
    public boolean isSafeZone(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        AtomicReference<WorldGridData> ref = worldGrids.get(loc.getWorld().getName());
        if (ref == null) {
            if (isEnabledInWorld(loc.getWorld())) scheduleRebuild(loc.getWorld());
            return wgFallback(loc);
        }

        WorldGridData data = ref.get();
        if (!data.gridReady) return wgFallback(loc);

        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;

        // Single map lookup + two bit tests — no separate isBorder call
        int result = data.checkChunk(cx, cz);
        if (result == 0) return false;   // not safe — common case, exits immediately
        if (result == 1) return true;    // safe interior — no WG needed
        return wgFallback(loc);          // result == 2: border chunk, precise WG check
    }

    // Fallback usage counter for debug reporting
    private volatile long fallbackCallCount = 0;

    // Global rate limiter — soft cap on WG fallback calls per tick to prevent border-storm TPS drops
    // Resets every tick via the cleanup task; threshold = 80 calls/5s ≈ 16/s, well within safe range
    private final java.util.concurrent.atomic.AtomicInteger fallbackRateBucket = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int FALLBACK_RATE_LIMIT = 200; // max WG queries per cleanup cycle (~5s)

    /** WG query with short-lived cache and global rate limiter */
    private boolean wgFallback(Location loc) {
        if (debugGrid) fallbackCallCount++;
        long blockKey = blockKey(loc);
        Long ts = wgFallbackTimestamps.get(blockKey);
        if (ts != null && System.currentTimeMillis() - ts < FALLBACK_CACHE_TTL) {
            Boolean cached = wgFallbackCache.get(blockKey);
            if (cached != null) return cached;
        }

        // Rate limiter: if too many uncached WG calls this cycle, skip and return cached or false
        if (fallbackRateBucket.incrementAndGet() > FALLBACK_RATE_LIMIT) {
            Boolean cached = wgFallbackCache.get(blockKey);
            return cached != null ? cached : false;
        }

        try {
            com.sk89q.worldedit.util.Location weLoc = BukkitAdapter.adapt(loc);
            ApplicableRegionSet regions = regionQuery.getApplicableRegions(weLoc);
            boolean safe = regions.queryState(null, Flags.PVP)
                    == com.sk89q.worldguard.protection.flags.StateFlag.State.DENY;
            wgFallbackCache.put(blockKey, safe);
            wgFallbackTimestamps.put(blockKey, System.currentTimeMillis());
            return safe;
        } catch (Exception e) {
            return false;
        }
    }

    private static long blockKey(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return (((long)(x & 0x3FFFFFF)) << 38) | (((long)(z & 0x3FFFFFF)) << 12) | ((long)(y & 0xFFF));
    }

    /** Public API used by other classes */
    public boolean isLocationInSafeZone(Location location) {
        return isSafeZone(location);
    }

    // -----------------------------------------------------------------------
    // PlayerMoveEvent — O(1) safezone check, no WG spam
    // -----------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!CelestCombatPro.hasWorldGuard) return;

        Player player = event.getPlayer();
        if (!isEnabledInWorld(player.getWorld())) {
            removePlayerBarriers(player);
            return;
        }
        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Skip head-rotation-only moves
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        // Speed/packet bypass: suspicious jump > 4 blocks in one tick — force precise check
        boolean suspiciousMove = from.distanceSquared(to) > 16.0;

        boolean fromSafe = isSafeZone(from);
        boolean toSafe   = suspiciousMove ? wgFallback(to) : isSafeZone(to);

        if (!fromSafe && toSafe) {
            // Block entry
            event.setCancelled(true);
            pushPlayerBack(player, from);
            sendCooldownMessage(player, "combat_no_safezone_entry");
            return;
        }

        if (!toSafe) {
            // Track last safe-outside location for teleport correction
            lastSafeLocation.put(player.getUniqueId(), to.clone());
        }

        // Throttled barrier update
        updatePlayerBarriersThrottled(player);
    }

    // -----------------------------------------------------------------------
    // Teleport protection — covers all bypass methods
    // -----------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!CelestCombatPro.hasWorldGuard) return;

        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || !combatManager.isInCombat(player)) return;
        if (!isEnabledInWorld(to.getWorld())) return;

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        // Allow plugin-internal teleports (e.g. our own pushback)
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN && !isSafeZone(to)) return;

        if (isSafeZone(to)) {
            event.setCancelled(true);
            sendCooldownMessage(player, "combat_no_safezone_entry");

            // Correction cooldown — prevents rapid cancel+teleport loop spam
            long now = System.currentTimeMillis();
            Long lastCorr = lastCorrection.get(player.getUniqueId());
            if (lastCorr != null && now - lastCorr < CORRECTION_COOLDOWN_MS) return;
            lastCorrection.put(player.getUniqueId(), now);

            // Next tick: teleport to last safe location, validated it's not itself safe
            final Location from = event.getFrom();
            Scheduler.runTaskLater(() -> {
                Location safe = lastSafeLocation.get(player.getUniqueId());
                // Validate stored location — if it became safe (region changed), fall back to from
                if (safe == null || isSafeZone(safe)) safe = from;
                player.teleport(safe);
                try {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0);
                } catch (Exception ignored) {}
            }, 1L);
        }
    }

    // -----------------------------------------------------------------------
    // Pearl bypass fix
    // -----------------------------------------------------------------------
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
        Location hitLoc = event.getEntity().getLocation();
        if (!isEnabledInWorld(hitLoc.getWorld())) return;

        UUID projectileId = event.getEntity().getUniqueId();
        UUID playerUUID   = combatPlayerPearls.remove(projectileId);
        if (playerUUID == null) return;

        Player player = plugin.getServer().getPlayer(playerUUID);
        Location dest = calculatePearlDest(event);

        // Always use precise WG fallback for pearls — grid chunk precision is not enough
        // for the subtle border-chunk mismatch case
        if (wgFallback(dest)) {
            event.setCancelled(true);
            handlePearlTeleportBack(player, playerUUID);
        }
        pearlThrowLocations.remove(playerUUID);
    }

    private Location calculatePearlDest(ProjectileHitEvent event) {
        if (event.getHitBlock() != null) {
            Location loc = event.getHitBlock().getLocation().clone();
            if (event.getHitBlockFace() != null) {
                loc.add(event.getHitBlockFace().getDirection().multiply(0.5));
            }
            return loc;
        }
        return event.getEntity().getLocation();
    }

    private void handlePearlTeleportBack(Player player, UUID playerUUID) {
        if (player == null || !player.isOnline()) return;
        PearlLocationData data = pearlThrowLocations.get(playerUUID);
        Location target = (data != null && !data.isExpired()) ? data.location : null;
        // Validate: if stored throw location is itself safe (edge case), fall back to lastSafe
        if (target == null || isSafeZone(target)) {
            target = lastSafeLocation.getOrDefault(playerUUID, player.getLocation());
        }
        final Location finalTarget = target;
        player.teleportAsync(finalTarget).thenAccept(success -> {
            if (!success) player.teleport(finalTarget);
        });
        sendCooldownMessage(player, "combat_no_pearl_safezone");
    }

    // -----------------------------------------------------------------------
    // World events — trigger grid rebuild
    // -----------------------------------------------------------------------
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String name = event.getWorld().getName();
        AtomicReference<WorldGridData> ref = worldGrids.remove(name);
        if (ref != null) {
            WorldGridData old = ref.get();
            if (old != null) old.release();
        }
        rebuildDebounce.remove(name);
        plugin.debug("Grid removed for unloaded world: " + name);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World newWorld = player.getWorld();

        // Ensure grid exists for new world
        if (isEnabledInWorld(newWorld) && !worldGrids.containsKey(newWorld.getName())) {
            scheduleRebuild(newWorld);
        }

        // If player is in combat and lands in a safezone after world change, push them back
        if (combatManager.isInCombat(player) && isEnabledInWorld(newWorld)) {
            Location loc = player.getLocation();
            if (isSafeZone(loc)) {
                sendCooldownMessage(player, "combat_no_safezone_entry");
                // No "from" available cross-world — teleport to spawn of previous world as fallback
                Location safe = lastSafeLocation.get(player.getUniqueId());
                if (safe != null && !isSafeZone(safe)) {
                    player.teleport(safe);
                }
                // If no valid safe location, just warn — we can't force cross-world teleport safely
            } else {
                lastSafeLocation.put(player.getUniqueId(), loc.clone());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pushback
    // -----------------------------------------------------------------------
    private void pushPlayerBack(Player player, Location safeFrom) {
        if (player == null || safeFrom == null) return;
        Location safe = safeFrom.clone();
        safe.setPitch(player.getLocation().getPitch());
        safe.setYaw(player.getLocation().getYaw());
        player.teleportAsync(safe).thenAccept(success -> {
            if (success) {
                try {
                    // Clear momentum so client doesn't rubberband or drift after correction
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0);
                } catch (Exception ignored) {}
            }
        });
    }

    // -----------------------------------------------------------------------
    // Barrier system (visual fake blocks for combat players near borders)
    // -----------------------------------------------------------------------
    private void updatePlayerBarriersThrottled(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastBarrierUpdate.get(uuid);
        if (last == null || now - last > BARRIER_UPDATE_INTERVAL) {
            updatePlayerBarriers(player);
            lastBarrierUpdate.put(uuid, now);
        }
    }

    private void updatePlayerBarriers(Player player) {
        if (!combatManager.isInCombat(player)) {
            removePlayerBarriers(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        Set<Location> newBarriers = findNearbyBarrierLocations(player.getLocation());
        Set<Location> current = playerBarriers.getOrDefault(uuid, Collections.emptySet());

        if (current.equals(newBarriers)) return;

        Set<Location> toRemove = new HashSet<>(current);
        toRemove.removeAll(newBarriers);
        toRemove.forEach(loc -> removeBarrierBlock(loc, player));

        Set<Location> toAdd = new HashSet<>(newBarriers);
        toAdd.removeAll(current);
        toAdd.forEach(loc -> createBarrierBlock(loc, player));

        if (newBarriers.isEmpty()) playerBarriers.remove(uuid);
        else playerBarriers.put(uuid, newBarriers);
    }

    private Set<Location> findNearbyBarrierLocations(Location playerLoc) {
        Set<Location> result = new HashSet<>();
        World world = playerLoc.getWorld();
        int bx = playerLoc.getBlockX();
        int by = playerLoc.getBlockY();
        int bz = playerLoc.getBlockZ();
        int r  = barrierDetectionRadius;
        int r2 = r * r;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r2) continue;
                Location check = new Location(world, bx + dx, by, bz + dz);
                if (isBorderBlock(check)) {
                    for (int dy = -1; dy <= barrierHeight; dy++) {
                        result.add(new Location(world, bx + dx, by + dy, bz + dz));
                    }
                }
            }
        }
        return result;
    }

    /** A block is a "border" if it is in a safe chunk and at least one cardinal neighbour is not */
    private boolean isBorderBlock(Location loc) {
        if (!isSafeZone(loc)) return false;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            if (!isSafeZone(loc.clone().add(d[0], 0, d[1]))) return true;
        }
        return false;
    }

    private void createBarrierBlock(Location loc, Player player) {
        Location norm = norm(loc);
        Block block = norm.getBlock();
        if (block.getType().isSolid()) return;
        originalBlocks.putIfAbsent(norm, block.getType());
        barrierViewers.computeIfAbsent(norm, k -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
        player.sendBlockChange(norm, barrierMaterial.createBlockData());
    }

    private void removeBarrierBlock(Location loc, Player player) {
        Location norm = norm(loc);
        Set<UUID> viewers = barrierViewers.get(norm);
        if (viewers == null) return;
        viewers.remove(player.getUniqueId());
        Material original = originalBlocks.getOrDefault(norm, Material.AIR);
        player.sendBlockChange(norm, original.createBlockData());
        if (viewers.isEmpty()) {
            barrierViewers.remove(norm);
            originalBlocks.remove(norm);
        }
    }

    private void removePlayerBarriers(Player player) {
        Set<Location> barriers = playerBarriers.remove(player.getUniqueId());
        if (barriers != null) barriers.forEach(loc -> removeBarrierBlock(loc, player));
        lastBarrierUpdate.remove(player.getUniqueId());
    }

    private static Location norm(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // -----------------------------------------------------------------------
    // Interact / break protection for barrier blocks
    // -----------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isEnabledInWorld(player.getWorld()) || !combatManager.isInCombat(player)) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Location blockLoc = norm(event.getClickedBlock().getLocation());
        Set<Location> barriers = playerBarriers.get(player.getUniqueId());
        if (barriers != null && barriers.contains(blockLoc)) {
            event.setCancelled(true);
            Scheduler.runTaskLater(() -> {
                Set<UUID> viewers = barrierViewers.get(blockLoc);
                if (viewers != null && viewers.contains(player.getUniqueId())) {
                    player.sendBlockChange(blockLoc, barrierMaterial.createBlockData());
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        if (originalBlocks.containsKey(norm(event.getBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    // -----------------------------------------------------------------------
    // Player quit
    // -----------------------------------------------------------------------
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        removePlayerBarriers(player);
        lastMessageTime.remove(uuid);
        lastSafeLocation.remove(uuid);
        lastCorrection.remove(uuid);
        pearlThrowLocations.remove(uuid);
        combatPlayerPearls.entrySet().removeIf(e -> e.getValue().equals(uuid));
    }

    // -----------------------------------------------------------------------
    // Message helper
    // -----------------------------------------------------------------------
    private void sendCooldownMessage(Player player, String key) {
        long now = System.currentTimeMillis();
        Long last = lastMessageTime.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN) return;
        lastMessageTime.put(player.getUniqueId(), now);
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, key, ph);
    }

    // -----------------------------------------------------------------------
    // Cleanup task
    // -----------------------------------------------------------------------
    private void startCleanupTask() {
        cleanupTask = Scheduler.runTaskTimerAsync(() -> {
            long now = System.currentTimeMillis();

            // Expire fallback cache entries
            wgFallbackTimestamps.entrySet().removeIf(e -> now - e.getValue() > FALLBACK_CACHE_TTL);
            wgFallbackCache.keySet().removeIf(k -> !wgFallbackTimestamps.containsKey(k));

            // Reset rate limiter bucket each cycle
            fallbackRateBucket.set(0);

            // Debug grid stats every ~5s
            if (debugGrid && fallbackCallCount > 0) {
                plugin.getLogger().info("[GridDebug] WG fallback calls (last 5s): " + fallbackCallCount);
                fallbackCallCount = 0;
            }

            // Expire pearl data
            pearlThrowLocations.entrySet().removeIf(e -> e.getValue().isExpired());

            // Expire message cooldowns
            lastMessageTime.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_COOLDOWN * 10);

            // Clean up barriers for offline / out-of-combat players
            playerBarriers.entrySet().removeIf(entry -> {
                Player p = plugin.getServer().getPlayer(entry.getKey());
                if (p == null || !p.isOnline() || !combatManager.isInCombat(p)) {
                    if (p != null && p.isOnline()) {
                        entry.getValue().forEach(loc -> removeBarrierBlock(loc, p));
                    } else {
                        UUID uuid = entry.getKey();
                        entry.getValue().forEach(loc -> {
                            Location norm = norm(loc);
                            Set<UUID> viewers = barrierViewers.get(norm);
                            if (viewers != null) {
                                viewers.remove(uuid);
                                if (viewers.isEmpty()) {
                                    barrierViewers.remove(norm);
                                    originalBlocks.remove(norm);
                                }
                            }
                        });
                    }
                    lastBarrierUpdate.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }, 100L, 100L);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    public void cleanup() {
        if (periodicRebuildTask != null) periodicRebuildTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        worldGrids.values().forEach(ref -> { WorldGridData d = ref.get(); if (d != null) d.release(); });
        worldGrids.clear();
        wgFallbackCache.clear();
        wgFallbackTimestamps.clear();
        playerBarriers.clear();
        originalBlocks.clear();
        barrierViewers.clear();
        lastSafeLocation.clear();
        lastCorrection.clear();
        lastBarrierUpdate.clear();
        lastMessageTime.clear();
        pearlThrowLocations.clear();
        combatPlayerPearls.clear();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
}
