package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EnderPearlListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    // Track thrown ender pearls to their player owners (entity id → player UUID)
    private final Map<Integer, UUID> activePearls = new ConcurrentHashMap<>();

    // Cached min teleport distance from config
    private double minTeleportDistance;

    // -------------------------------------------------------------------------
    // Passable-block set — built once at class load, O(1) lookup vs switch/enum scan
    // Includes every Material a player's hitbox passes through without collision.
    // -------------------------------------------------------------------------
    private static final Set<Material> PASSABLE_MATERIALS = EnumSet.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.WATER,
            Material.LAVA,
            Material.SEAGRASS,
            Material.TALL_SEAGRASS,
            Material.KELP,
            Material.KELP_PLANT,
            Material.BUBBLE_COLUMN,
            Material.TALL_GRASS,
            Material.SHORT_GRASS,
            Material.DEAD_BUSH,
            Material.FERN,
            Material.LARGE_FERN,
            Material.DANDELION,
            Material.POPPY,
            Material.BLUE_ORCHID,
            Material.ALLIUM,
            Material.AZURE_BLUET,
            Material.RED_TULIP,
            Material.ORANGE_TULIP,
            Material.WHITE_TULIP,
            Material.PINK_TULIP,
            Material.OXEYE_DAISY,
            Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY,
            Material.WITHER_ROSE,
            Material.SUNFLOWER,
            Material.LILAC,
            Material.ROSE_BUSH,
            Material.PEONY,
            Material.BROWN_MUSHROOM,
            Material.RED_MUSHROOM,
            Material.TORCH,
            Material.WALL_TORCH,
            Material.SOUL_TORCH,
            Material.SOUL_WALL_TORCH,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH,
            Material.SNOW,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.VINE,
            Material.GLOW_LICHEN,
            Material.LADDER,
            Material.SUGAR_CANE,
            Material.BAMBOO,
            Material.BAMBOO_SAPLING,
            Material.SWEET_BERRY_BUSH,
            Material.NETHER_SPROUTS,
            Material.WARPED_ROOTS,
            Material.CRIMSON_ROOTS,
            Material.WEEPING_VINES,
            Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES,
            Material.TWISTING_VINES_PLANT,
            Material.SPORE_BLOSSOM,
            Material.HANGING_ROOTS,
            Material.BIG_DRIPLEAF,
            Material.SMALL_DRIPLEAF,
            Material.STRING,
            Material.COBWEB           // cobweb slows but doesn't block movement
    );

    // Problematic blocks that should block pearl teleports
    private static final Set<Material> PROBLEMATIC_MATERIALS = EnumSet.of(
            Material.BARRIER,
            Material.BEDROCK,
            Material.END_PORTAL_FRAME,
            Material.END_PORTAL,
            Material.END_GATEWAY,
            Material.STRUCTURE_VOID
    );

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------
    public EnderPearlListener(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.minTeleportDistance = plugin.getConfig()
                .getDouble("enderpearl_fix.min_teleport_distance", 1.0);
    }

    public void reloadConfig() {
        combatManager.reloadConfig();
        this.minTeleportDistance = plugin.getConfig()
                .getDouble("enderpearl_fix.min_teleport_distance", 1.0);
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    /** Cancel right-click use if pearl is on cooldown. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_PEARL) return;

        Player player = event.getPlayer();
        if (combatManager.isEnderPearlOnCooldown(player)) {
            event.setCancelled(true);
            sendCooldownMessage(player);
        }
    }

    /** Cancel launch if on cooldown, or set cooldown and track pearl. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;

        Player player = (Player) event.getEntity().getShooter();

        if (combatManager.isEnderPearlOnCooldown(player)) {
            event.setCancelled(true);
            sendCooldownMessage(player);
            return;
        }

        combatManager.setEnderPearlCooldown(player);
        activePearls.put(event.getEntity().getEntityId(), player.getUniqueId());
    }

    /**
     * Applied via dynamic registration — handle ender pearl teleport fixes.
     * Not annotated with @EventHandler because priority is set at registration time.
     */
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (!combatManager.isEnderPearlFixEnabled()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;

        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();
        Location from = event.getFrom();

        if (!from.getWorld().equals(to.getWorld())) {
            event.setCancelled(true);
            return;
        }

        if (shouldPreventTeleport(player, from, to)) {
            event.setCancelled(true);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            plugin.getMessageService().sendMessage(player, "enderpearl_glitch_prevented", placeholders);

            plugin.debug("Prevented ender pearl glitch for player: " + player.getName());
        }
    }

    /** Clean up pearl tracking when it lands or despawns. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            activePearls.remove(event.getEntity().getEntityId());
        }
    }

    // -------------------------------------------------------------------------
    // Teleport validation — each check is ordered cheapest → most expensive
    // -------------------------------------------------------------------------
    private boolean shouldPreventTeleport(Player player, Location from, Location to) {
        // 1. Micro-teleport — single distance calculation, no allocations
        if (combatManager.shouldPreventMicroTeleport()) {
            double dist = from.distanceSquared(to); // avoid sqrt
            double min = combatManager.getMinTeleportDistance();
            if (dist < min * min) {
                plugin.debug("Blocked pearl: micro teleport (" + Math.sqrt(dist) + " < " + min + ")");
                return true;
            }
        }

        // 2. Problematic blocks — targeted check around destination, no Location allocs
        if (combatManager.shouldPreventBarrierGlitch()
                && hasProblematicBlocks(to, combatManager.getMaxBlockCheckRadius())) {
            plugin.debug("Blocked pearl: problematic blocks (barrier/bedrock)");
            return true;
        }

        // 3. Block-clip / wall-phase — check player hitbox columns, no Location allocs
        if (combatManager.shouldPreventBlockStuck() && wouldGetStuckInBlocks(to)) {
            plugin.debug("Blocked pearl: would clip into solid blocks");
            return true;
        }

        // 4. Tight space — count surrounding solids, no Location allocs
        if (combatManager.shouldPreventTightSpaces()
                && isTightSpace(to, combatManager.getMaxSurroundingBlocks())) {
            plugin.debug("Blocked pearl: tight space (surrounded by solid blocks)");
            return true;
        }

        return false;
    }

    /**
     * Check for problematic blocks (barrier, bedrock, etc.) in a cube around {@code location}.
     * Uses {@code World.getBlockAt(int,int,int)} directly — no Location allocations.
     */
    private boolean hasProblematicBlocks(Location location, int radius) {
        org.bukkit.World world = location.getWorld();
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (PROBLEMATIC_MATERIALS.contains(
                            world.getBlockAt(bx + x, by + y, bz + z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if the player's 2-block-tall hitbox would intersect solid blocks at {@code location}.
     * All block access via {@code World.getBlockAt(int,int,int)} — zero Location allocations.
     *
     * Player bounding box: ±0.3 on X/Z, 0 to 1.8 on Y.
     */
    private boolean wouldGetStuckInBlocks(Location location) {
        org.bukkit.World world = location.getWorld();

        // Integer block coords for the four corners of the player bounding box
        // X/Z offsets -0.3 and +0.3 map to at most two distinct block coords each
        int x0 = (int) Math.floor(location.getX() - 0.3);
        int x1 = (int) Math.floor(location.getX() + 0.3);
        int z0 = (int) Math.floor(location.getZ() - 0.3);
        int z1 = (int) Math.floor(location.getZ() + 0.3);

        // Y: feet (0), head (1), top of hitbox (1.8 → block y+1)
        int yFeet = location.getBlockY();
        int yHead = yFeet + 1;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (isSolidAndNotPassable(world.getBlockAt(x, yFeet, z))) return true;
                if (isSolidAndNotPassable(world.getBlockAt(x, yHead, z))) return true;
            }
        }
        return false;
    }

    /**
     * Count solid blocks in a 3×2×3 shell around the destination (feet + head level).
     * Uses {@code World.getBlockAt} — zero Location allocations.
     */
    private boolean isTightSpace(Location location, int maxSurroundingBlocks) {
        org.bukkit.World world = location.getWorld();
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();

        int solidCount = 0;
        for (int y = 0; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue; // skip center column
                    if (isSolidAndNotPassable(world.getBlockAt(bx + x, by + y, bz + z))) {
                        solidCount++;
                        // Early exit — no need to count further once threshold exceeded
                        if (solidCount >= maxSurroundingBlocks) return true;
                    }
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Block helpers
    // -------------------------------------------------------------------------
    private boolean isSolidAndNotPassable(Block block) {
        Material type = block.getType();
        return type.isSolid() && !PASSABLE_MATERIALS.contains(type);
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------
    private void sendCooldownMessage(Player player) {
        int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(remainingTime));
        plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------
    public void shutdown() {
        activePearls.clear();
    }
}