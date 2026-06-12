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
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


public class WorldGuardHook implements Listener {

    private static final long MESSAGE_COOLDOWN = 2_000;

    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    private final Map<String, BorderCache> borderCaches = new ConcurrentHashMap<>();

    private final Map<UUID, Set<BlockPos>> playerBarriers = new ConcurrentHashMap<>();
    private final Map<BlockPos, org.bukkit.block.data.BlockData>  originalBlocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, Set<UUID>> barrierViewers = new ConcurrentHashMap<>();

    private final Set<UUID> barrierUpdateNeeded = ConcurrentHashMap.newKeySet();


    private final Map<UUID, BlockPos> lastKnownPos = new ConcurrentHashMap<>();


    private final Map<UUID, UUID> combatPlayerPearls = new ConcurrentHashMap<>();

    private final Map<UUID, PearlLocationData> pearlThrowLocations = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();

    private volatile boolean             globalEnabled;
    private volatile Map<String, Boolean> worldSettings    = new HashMap<>();
    private volatile int                 barrierDetectionRadius;
    private volatile int                 barrierHeight;           // now actually used — replaces sky-check loop
    private volatile Material            barrierMaterial;
    private volatile double              pushBackForce;
    private volatile long                borderCacheRebuildInterval;
    private volatile boolean             useChunkCache;
    private volatile int                 liquidProtectionRadius;


    public WorldGuardHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        reloadConfig();
        startTasks();
    }


    public void reloadConfig() {
        this.globalEnabled             = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        this.worldSettings             = loadWorldSettings();
        this.barrierDetectionRadius    = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        this.barrierHeight             = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        this.barrierMaterial           = loadBarrierMaterial();
        this.pushBackForce             = plugin.getConfig().getDouble("safezone_protection.push_back_force", 0.6);
        this.borderCacheRebuildInterval= plugin.getConfig().getLong("safezone_protection.border_cache_rebuild_interval_ms", 3_000);
        this.useChunkCache             = plugin.getConfig().getBoolean("safezone_protection.use_chunk_cache", false);
        this.liquidProtectionRadius    = plugin.getConfig().getInt("safezone_protection.liquid_protection_radius", 2);

        borderCaches.clear();
        plugin.debug("WorldGuard safezone protection reloaded."
                + " Global=" + globalEnabled
                + " ChunkCache=" + useChunkCache
                + " PushForce=" + pushBackForce
                + " BarrierHeight=" + barrierHeight);
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

    // =========================================================================
    // World / cache helpers
    // =========================================================================

    private boolean isEnabledInWorld(World world) {
        if (world == null) return false;
        return worldSettings.getOrDefault(world.getName(), false);
    }

    private boolean isEnabledInWorld(Location loc) {
        return loc != null && isEnabledInWorld(loc.getWorld());
    }

    private BorderCache getCacheForWorld(World world) {
        if (!isEnabledInWorld(world)) return null;
        return borderCaches.computeIfAbsent(world.getName(), k -> {
            BorderCache cache = new BorderCache(world);
            Scheduler.runTaskAsync(cache::rebuild);
            return cache;
        });
    }

    private boolean isSafeZone(Location loc) {
        if (!isEnabledInWorld(loc)) return false;
        BorderCache cache = getCacheForWorld(loc.getWorld());
        return cache != null && cache.isSafeZone(loc);
    }

    private boolean isNearSafeZone(Location loc, int radius) {
        if (radius <= 0) return isSafeZone(loc);
        if (!isEnabledInWorld(loc)) return false;
        BorderCache cache = getCacheForWorld(loc.getWorld());
        if (cache == null) return false;
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++)
                    if (cache.isSafeZone(bx + dx, by + dy, bz + dz)) return true;
        return false;
    }


    private void startTasks() {

        Scheduler.runTaskTimerAsync(() -> {
            if (!CelestCombatPro.hasWorldGuard) return;

            List<UUID>          needPushBack = new ArrayList<>();
            Map<UUID, Location> pushBackLocs = new HashMap<>();

            for (Player player : plugin.getServer().getOnlinePlayers()) {
                UUID uuid  = player.getUniqueId();
                World world = player.getWorld();

                if (!combatManager.isInCombat(player) || !isEnabledInWorld(world)) {
                    lastKnownPos.remove(uuid);

                    if (playerBarriers.containsKey(uuid)) {
                        barrierUpdateNeeded.add(uuid);
                    }
                    continue;
                }

                BorderCache cache = getCacheForWorld(world);
                if (cache == null) continue;

                Location loc = player.getLocation();
                int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
                BlockPos current = BlockPos.of(world, bx, by, bz);
                BlockPos last    = lastKnownPos.put(uuid, current);

                boolean moved = !current.equals(last);
                boolean currentlySafe = cache.isSafeZone(bx, by, bz);

                if (moved && last != null) {
                    boolean wasSafe = cache.isSafeZone(last.x(), last.y(), last.z());
                    if (!wasSafe && currentlySafe) {

                        Location pushTo = new Location(world,
                                last.x() + 0.5, last.y(), last.z() + 0.5,
                                loc.getYaw(), loc.getPitch());
                        needPushBack.add(uuid);
                        pushBackLocs.put(uuid, pushTo);
                    }

                    barrierUpdateNeeded.add(uuid);

                } else if (currentlySafe && !moved) {

                    if (!needPushBack.contains(uuid)) {
                        needPushBack.add(uuid);
                        pushBackLocs.put(uuid, loc.clone());
                    }
                }
            }


            for (UUID uuid : needPushBack) {
                final Location pushTo = pushBackLocs.get(uuid);
                if (pushTo == null) continue;
                Player p = plugin.getServer().getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                Scheduler.runEntityTask(p, () -> {
                    if (!p.isOnline() || !combatManager.isInCombat(p)) return;
                    pushPlayerBack(p, pushTo);
                    sendCooldownMessage(p, "combat_no_safezone_entry");
                });
            }

        }, 1L, 2L);  // initial delay 1 tick, period 2 ticks



        Scheduler.runTaskTimerAsync(() -> {
            Set<UUID> queued = new HashSet<>(barrierUpdateNeeded);
            barrierUpdateNeeded.clear();

            for (UUID uuid : queued) {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;

                Scheduler.runEntityTask(p, () -> {
                    if (!p.isOnline()) return;
                    if (!combatManager.isInCombat(p) || !isEnabledInWorld(p.getWorld())) {
                        removePlayerBarriers(p);
                    } else {
                        updatePlayerBarriers(p);
                    }
                });
            }
        }, 5L, 5L);



        Scheduler.runTaskTimerAsync(() -> {
            for (BorderCache cache : borderCaches.values()) {
                if (!isEnabledInWorld(cache.world)) continue;
                if (cache.needsRebuild()) cache.rebuild();
            }
        }, 20L, 20L);


        Scheduler.runTaskTimerAsync(() -> {
            long now = System.currentTimeMillis();
            cleanupPlayerBarriers();
            pearlThrowLocations.entrySet().removeIf(e -> e.getValue().isExpired());
            lastMessageTime.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_COOLDOWN * 10);
        }, 100L, 100L);
    }


    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!CelestCombatPro.hasWorldGuard) return;

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Location to = event.getTo();
        if (to == null) return;
        if (!isEnabledInWorld(to.getWorld())) return;

        Player player = event.getPlayer();
        if (!combatManager.isInCombat(player)) return;

        BorderCache cache = getCacheForWorld(to.getWorld());
        if (cache == null) return;

        if (cache.isSafeZone(to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            event.setCancelled(true);
            sendCooldownMessage(player, "combat_no_safezone_entry");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player))   return;
        if (!isEnabledInWorld(player.getWorld()))              return;
        if (!combatManager.isInCombat(player))                 return;

        combatPlayerPearls.put(pearl.getUniqueId(), player.getUniqueId());
        pearlThrowLocations.put(player.getUniqueId(), new PearlLocationData(player.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!isEnabledInWorld(pearl.getLocation()))            return;

        UUID playerUUID = combatPlayerPearls.remove(pearl.getUniqueId());
        if (playerUUID == null) return;

        Location dest = calculateTeleportDestination(event, pearl);
        if (isSafeZone(dest)) {
            event.setCancelled(true);
            handlePearlTeleportBack(plugin.getServer().getPlayer(playerUUID), playerUUID);
        }
        pearlThrowLocations.remove(playerUUID);
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!isEnabledInWorld(player.getWorld()) || !combatManager.isInCombat(player)) {
            if (playerBarriers.containsKey(player.getUniqueId())) {
                removePlayerBarriers(player);
            }
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
            Scheduler.runEntityTaskLater(player, () -> refreshBarrierBlock(clicked, player), 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        if (originalBlocks.containsKey(BlockPos.of(event.getBlock().getLocation())))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        Material type = event.getBlock().getType();
        if (type != Material.WATER && type != Material.LAVA) return;

        boolean fromSafe = isNearSafeZone(event.getBlock().getLocation(), liquidProtectionRadius);
        boolean toSafe   = isNearSafeZone(event.getToBlock().getLocation(), liquidProtectionRadius);
        if (fromSafe || toSafe) event.setCancelled(true);
        if (fromSafe) event.getBlock().setType(Material.AIR);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        Material type = event.getNewState().getType();
        if (type != Material.COBBLESTONE && type != Material.OBSIDIAN && type != Material.STONE) return;
        if (isNearSafeZone(event.getBlock().getLocation(), liquidProtectionRadius))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!isEnabledInWorld(event.getBlock().getWorld())) return;
        Material type = event.getBucket();
        if (type != Material.WATER_BUCKET && type != Material.LAVA_BUCKET) return;
        if (isNearSafeZone(event.getBlock().getLocation(), liquidProtectionRadius)) {
            event.setCancelled(true);
            sendCooldownMessage(event.getPlayer(), "liquid_place_deny");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        removePlayerBarriers(event.getPlayer());
        barrierUpdateNeeded.remove(uuid);
        lastKnownPos.remove(uuid);
        lastMessageTime.remove(uuid);
        pearlThrowLocations.remove(uuid);
        combatPlayerPearls.entrySet().removeIf(e -> e.getValue().equals(uuid));
    }


    private void updatePlayerBarriers(Player player) {
        Set<BlockPos> desired = findNearbyBarrierBlocks(player);
        UUID uuid = player.getUniqueId();
        Set<BlockPos> current = playerBarriers.getOrDefault(uuid, Collections.emptySet());

        if (current.equals(desired)) return;

        for (BlockPos pos : current) if (!desired.contains(pos)) removeBarrierBlock(pos, player);
        for (BlockPos pos : desired) if (!current.contains(pos)) createBarrierBlock(pos, player);

        if (desired.isEmpty()) playerBarriers.remove(uuid);
        else                   playerBarriers.put(uuid, desired);
    }


    private Set<BlockPos> findNearbyBarrierBlocks(Player player) {
        Location loc   = player.getLocation();
        World    world = loc.getWorld();
        int baseX = loc.getBlockX(), baseY = loc.getBlockY(), baseZ = loc.getBlockZ();
        int radius   = barrierDetectionRadius;
        int radiusSq = radius * radius;

        BorderCache cache = getCacheForWorld(world);
        if (cache == null) return Collections.emptySet();


        int startY = baseY - 1;
        int endY   = baseY + barrierHeight;
        int maxY   = world.getMaxHeight() - 1;
        int minY   = world.getMinHeight();

        Set<BlockPos> result = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) continue;

                int cx = baseX + dx, cz = baseZ + dz;

                if (!cache.isBorder(cx, baseY, cz)) continue;

                int[] bounds = cache.boundsAt(cx, cz);
                if (bounds == null) continue;

                for (int y = Math.max(startY, minY); y <= Math.min(endY, maxY); y++) {
                    if (y >= bounds[0] && y <= bounds[1]) {
                        result.add(BlockPos.of(world, cx, y, cz));
                    }
                }
            }
        }

        plugin.debug("[Barrier] " + result.size() + " barrier blocks for "
                + player.getName() + " at (" + baseX + "," + baseY + "," + baseZ + ")");
        return result;
    }

    private void createBarrierBlock(BlockPos pos, Player player) {
        Location loc = pos.toLocation(player.getWorld());
        Block block = loc.getBlock();
        if (block.getType().isSolid()) return;

        originalBlocks.putIfAbsent(pos, block.getBlockData());
        barrierViewers.computeIfAbsent(pos, k -> new HashSet<>()).add(player.getUniqueId());
        player.sendBlockChange(loc, barrierMaterial.createBlockData());

        if (isPlayerInsideBarrier(player, loc)) pushPlayerAwayFromBarrier(player, loc);
    }



    private void removeBarrierBlock(BlockPos pos, Player player) {
        Set<UUID> viewers = barrierViewers.get(pos);
        if (viewers == null) return;
        viewers.remove(player.getUniqueId());
        Location loc = pos.toLocation(player.getWorld());
        if (viewers.isEmpty()) {
            barrierViewers.remove(pos);
            org.bukkit.block.data.BlockData original = originalBlocks.remove(pos);
            if (original != null) player.sendBlockChange(loc, original);
        } else {
            org.bukkit.block.data.BlockData original = originalBlocks.get(pos);
            if (original != null) player.sendBlockChange(loc, original);
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


    private void pushPlayerBack(Player player, Location from) {
        if (player == null || from == null) return;
        Location safe = from.clone();
        safe.setYaw(player.getLocation().getYaw());
        safe.setPitch(player.getLocation().getPitch());
        player.teleportAsync(safe).thenAccept(ok -> {
            if (ok) {
                Scheduler.runEntityTask(player, () -> {
                    try { player.setVelocity(new Vector(0, 0, 0)); }
                    catch (Exception ignored) {}
                });
            }
        });
    }

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
                else    handleFailedTeleport(player, data.location());
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


    private Location findSafeLocation(Location origin) {
        if (origin == null) return null;
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        int r = 10;
        BorderCache cache = getCacheForWorld(world);
        if (cache == null) return null;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = ox + dx, cz = oz + dz;
                if (cache.isSafeZone(cx, oy, cz)) continue;
                for (int dy = 0; dy <= r; dy++) {
                    for (int sign : dy == 0 ? new int[]{1} : new int[]{1, -1}) {
                        int cy = oy + dy * sign;
                        if (cy < world.getMinHeight() || cy >= world.getMaxHeight() - 1) continue;
                        if (isColumnSafe(world, cx, cy, cz))
                            return new Location(world, cx, cy, cz, origin.getYaw(), origin.getPitch());
                    }
                }
            }
        }
        return null;
    }

    private boolean isColumnSafe(World world, int x, int y, int z) {
        return  world.getBlockAt(x, y - 1, z).getType().isSolid()
                && !world.getBlockAt(x, y,     z).getType().isSolid()
                && !world.getBlockAt(x, y + 1, z).getType().isSolid();
    }


    private void cleanupPlayerBarriers() {
        playerBarriers.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            boolean shouldRemove = player == null || !player.isOnline()
                    || !combatManager.isInCombat(player)
                    || !isEnabledInWorld(player.getWorld());
            if (!shouldRemove) return false;

            if (player != null && player.isOnline()) {
                entry.getValue().forEach(pos -> removeBarrierBlock(pos, player));
            } else {
                entry.getValue().forEach(pos -> {
                    Set<UUID> viewers = barrierViewers.get(pos);
                    if (viewers == null) return;
                    viewers.remove(entry.getKey());
                    if (viewers.isEmpty()) {
                        barrierViewers.remove(pos);
                        originalBlocks.remove(pos);
                    }
                });
            }
            return true;
        });
    }


    public boolean isLocationInSafeZone(Location location) {
        return isSafeZone(location);
    }


    private void sendCooldownMessage(Player player, String key) {
        long now  = System.currentTimeMillis();
        Long last = lastMessageTime.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN) return;
        lastMessageTime.put(player.getUniqueId(), now);
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player.getName());
        ph.put("time", String.valueOf(combatManager.getRemainingCombatTime(player)));
        plugin.getMessageService().sendMessage(player, key, ph);
    }


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


    public void cleanup() {
        borderCaches.clear();
        playerBarriers.clear();
        originalBlocks.clear();
        barrierViewers.clear();
        barrierUpdateNeeded.clear();
        lastKnownPos.clear();
        combatPlayerPearls.clear();
        pearlThrowLocations.clear();
        lastMessageTime.clear();
        worldSettings.clear();
    }


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


    private record PearlLocationData(Location location, long timestamp) {
        PearlLocationData(Location location) {
            this(location.clone(), System.currentTimeMillis());
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60_000;
        }
    }


    private class BorderCache {

        final World world;
        private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
        private volatile long lastRebuild = 0;

        BorderCache(World world) { this.world = world; }

        boolean isSafeZone(int x, int y, int z) {
            Snapshot snap = snapshot.get();
            if (!snap.isSafeZone(BlockPos.of(world, x, 0, z))) return false;
            int[] bounds = snap.boundsAt(x, z);
            return bounds != null && y >= bounds[0] && y <= bounds[1];
        }

        boolean isSafeZone(Location loc) {
            return isSafeZone(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        boolean isBorder(int x, int y, int z) {
            Snapshot snap = snapshot.get();
            if (!snap.isBorder(BlockPos.of(world, x, 0, z))) return false;
            int[] bounds = snap.boundsAt(x, z);
            return bounds != null && y >= bounds[0] && y <= bounds[1];
        }

        int[] boundsAt(int x, int z) {
            return snapshot.get().boundsAt(x, z);
        }


        void rebuild() {
            long startNs = System.nanoTime();
            try {
                RegionManager rm = WorldGuard.getInstance()
                        .getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
                if (rm == null) return;

                RegionQuery query = WorldGuard.getInstance()
                        .getPlatform().getRegionContainer().createQuery();

                Set<BlockPos>    newSafeZone = new HashSet<>(10_000);
                Map<Long, int[]> newBounds   = new HashMap<>();

                Map<Long, Boolean> columnCache = new HashMap<>(16_384);
                Map<Long, Boolean> chunkCache  = useChunkCache ? new HashMap<>(1_024) : null;

                int worldMinY = world.getMinHeight();
                int worldMaxY = world.getMaxHeight();

                Location probe = new Location(world, 0, 0, 0);

                for (ProtectedRegion region : rm.getRegions().values()) {
                    if (region.getFlag(Flags.PVP) != com.sk89q.worldguard.protection.flags.StateFlag.State.DENY) {
                        continue;
                    }
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();
                    long area = (long)(max.x() - min.x() + 1) * (max.z() - min.z() + 1);

                    if (area > 500_000) {
                        plugin.getLogger().warning("[BorderCache] Skipping huge region '"
                                + region.getId() + "' (" + area + " blocks). "
                                + "Split this region or the protection will be incomplete.");
                        continue;
                    }

                    int rMinY = Math.max(min.y(), worldMinY);
                    int rMaxY = Math.min(max.y(), worldMaxY);
                    int cy    = (rMinY + rMaxY) / 2;   // representative Y for PVP query

                    for (int x = min.x(); x <= max.x(); x++) {
                        for (int z = min.z(); z <= max.z(); z++) {
                            long xzKey = Snapshot.xzKey(x, z);

                            Boolean cached = columnCache.get(xzKey);
                            boolean isSafe;

                            if (cached != null) {
                                isSafe = cached;
                            } else if (useChunkCache && chunkCache != null) {
                                long chunkKey = ((long)(x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                                Boolean chunkCached = chunkCache.get(chunkKey);
                                if (chunkCached != null) {
                                    isSafe = chunkCached;
                                } else {
                                    probe.setX(x); probe.setY(cy); probe.setZ(z);
                                    isSafe = !query.testState(BukkitAdapter.adapt(probe), null, Flags.PVP);
                                    chunkCache.put(chunkKey, isSafe);
                                }
                                columnCache.put(xzKey, isSafe);
                            } else {
                                probe.setX(x); probe.setY(cy); probe.setZ(z);
                                isSafe = !query.testState(BukkitAdapter.adapt(probe), null, Flags.PVP);
                                columnCache.put(xzKey, isSafe);
                            }

                            if (!isSafe) continue;

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

                Set<BlockPos> newBorder = new HashSet<>();
                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                for (BlockPos pos : newSafeZone) {
                    for (int[] d : dirs) {
                        if (!newSafeZone.contains(BlockPos.of(world, pos.x()+d[0], 0, pos.z()+d[1]))) {
                            newBorder.add(pos);
                            break;
                        }
                    }
                }

                snapshot.set(new Snapshot(
                        Collections.unmodifiableSet(newSafeZone),
                        Collections.unmodifiableSet(newBorder),
                        Collections.unmodifiableMap(newBounds)));
                lastRebuild = System.currentTimeMillis();

                long ms = (System.nanoTime() - startNs) / 1_000_000;
                plugin.debug("[BorderCache] Rebuilt '" + world.getName() + "' in " + ms + "ms — "
                        + newSafeZone.size() + " safe cols, " + newBorder.size() + " border cols, "
                        + columnCache.size() + " unique WG queries.");

                if (ms > 100 && plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().warning("[BorderCache] Slow rebuild (" + ms + "ms) for '"
                            + world.getName() + "'. Consider increasing rebuild interval or splitting large regions.");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("[BorderCache] Rebuild failed for '"
                        + world.getName() + "': " + e.getMessage());
            }
        }

        boolean needsRebuild() {
            return System.currentTimeMillis() - lastRebuild > borderCacheRebuildInterval;
        }

        private record Snapshot(
                Set<BlockPos>    safeZone,
                Set<BlockPos>    border,
                Map<Long, int[]> columnBounds
        ) {
            static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), Map.of());

            static long xzKey(int x, int z) {
                return ((long) x << 32) | (z & 0xFFFFFFFFL);
            }

            boolean isSafeZone(BlockPos p) { return safeZone.contains(p); }
            boolean isBorder(BlockPos p)   { return border.contains(p);   }
            int[]   boundsAt(int x, int z) { return columnBounds.get(xzKey(x, z)); }
        }
    }
}