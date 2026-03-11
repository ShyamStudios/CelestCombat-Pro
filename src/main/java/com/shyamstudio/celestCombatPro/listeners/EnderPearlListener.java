package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
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
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EnderPearlListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    // Track players with active pearl countdown displays to avoid duplicates
    private final Map<UUID, Scheduler.Task> pearlCountdownTasks = new ConcurrentHashMap<>();

    // Track thrown ender pearls to their player owners
    private final Map<Integer, UUID> activePearls = new ConcurrentHashMap<>();
    
    // Ender pearl fix configuration cache
    private double minTeleportDistance;
    
    public EnderPearlListener(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.minTeleportDistance = plugin.getConfig().getDouble("enderpearl_fix.min_teleport_distance", 1.0);
    }
    
    public void reloadConfig() {
        // Config values are now cached in CombatManager for better performance
        // This method can trigger a reload of CombatManager's config cache if needed
        combatManager.reloadConfig();
        this.minTeleportDistance = plugin.getConfig().getDouble("enderpearl_fix.min_teleport_distance", 1.0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

        // Check if player is right-clicking with an ender pearl
        if (item != null && item.getType() == Material.ENDER_PEARL &&
                (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {

            // Check if ender pearl is on cooldown - this now handles all conditions internally
            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                // Send cooldown message
                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            // Check if ender pearl is on cooldown - this now handles all conditions internally
            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                // Send cooldown message
                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            } else {
                // Set cooldown when player successfully launches an ender pearl
                // The setEnderPearlCooldown method now handles all condition checks internally
                combatManager.setEnderPearlCooldown(player);

                // Track the pearl for potential fixes
                EnderPearl pearl = (EnderPearl) event.getEntity();
                activePearls.put(pearl.getEntityId(), player.getUniqueId());
                
                // Start displaying the countdown for pearl cooldown
                startPearlCountdown(player);
            }
        }
    }
    
    // NOTE: This method is now registered dynamically with configurable priority
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (!combatManager.isEnderPearlFixEnabled() || event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        
        if (to == null) {
            return;
        }
        
        // Apply ender pearl fixes
        if (shouldPreventTeleport(player, from, to)) {
            event.setCancelled(true);
            
            // Send message to player
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            plugin.getMessageService().sendMessage(player, "enderpearl_glitch_prevented", placeholders);
            
            plugin.debug("Prevented ender pearl glitch for player: " + player.getName());
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            EnderPearl pearl = (EnderPearl) event.getEntity();
            activePearls.remove(pearl.getEntityId());
        }
    }
    
    private boolean shouldPreventTeleport(Player player, Location from, Location to) {
        // 1. Micro Teleport Glitch Prevention
        // Prevents very short distance teleports used to bypass anti-cheat or clip into blocks
        if (combatManager.shouldPreventMicroTeleport() && from.distance(to) < combatManager.getMinTeleportDistance()) {
            plugin.debug("Blocked ender pearl: micro teleport detected (" + from.distance(to) + " < " + combatManager.getMinTeleportDistance() + ")");
            return true;
        }
        
        // 2. Barrier / Protected Region Glitch Prevention
        // Prevents pearling through barrier blocks, bedrock, or into protected areas
        if (combatManager.shouldPreventBarrierGlitch() && hasProblematicBlocks(to, combatManager.getMaxBlockCheckRadius())) {
            plugin.debug("Blocked ender pearl: problematic blocks detected (barrier/bedrock)");
            return true;
        }
        
        // 3. Block Clip / Wall Phase Glitch Prevention
        // Prevents players from clipping through walls by pearling into solid blocks
        if (combatManager.shouldPreventBlockStuck() && wouldGetStuckInBlocks(to)) {
            plugin.debug("Blocked ender pearl: would clip into solid blocks");
            return true;
        }
        
        // 4. Tight Space / Suffocation Glitch Prevention
        // Prevents pearling into spaces surrounded by too many solid blocks
        if (combatManager.shouldPreventTightSpaces() && isTightSpace(to, combatManager.getMaxSurroundingBlocks())) {
            plugin.debug("Blocked ender pearl: tight space detected (surrounded by solid blocks)");
            return true;
        }
        
        return false;
    }
    
    /**
     * Check for problematic blocks (barrier, bedrock) around the destination
     * This prevents the Barrier / Protected Region Glitch
     */
    private boolean hasProblematicBlocks(Location location, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = location.clone().add(x, y, z).getBlock();
                    Material type = block.getType();
                    
                    // Check for problematic block types
                    if (type == Material.BARRIER || 
                        type == Material.BEDROCK ||
                        type == Material.END_PORTAL_FRAME ||
                        type == Material.END_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Check if player would get stuck in solid blocks
     * This prevents the Block Clip / Wall Phase Glitch
     */
    private boolean wouldGetStuckInBlocks(Location location) {
        // Check player's hitbox (2 blocks tall, 1 block wide)
        Location feet = location.clone();
        Location head = location.clone().add(0, 1, 0);
        Location top = location.clone().add(0, 1.8, 0);
        
        // Check if any part of player hitbox intersects with solid blocks
        if (isSolidAndNotPassable(feet.getBlock())) {
            return true;
        }
        if (isSolidAndNotPassable(head.getBlock())) {
            return true;
        }
        if (isSolidAndNotPassable(top.getBlock())) {
            return true;
        }
        
        // Check blocks immediately around player (0.3 block radius for player width)
        for (double xOffset = -0.3; xOffset <= 0.3; xOffset += 0.3) {
            for (double zOffset = -0.3; zOffset <= 0.3; zOffset += 0.3) {
                if (xOffset == 0 && zOffset == 0) continue;
                
                Location checkLoc = feet.clone().add(xOffset, 0, zOffset);
                if (isSolidAndNotPassable(checkLoc.getBlock())) {
                    return true;
                }
                
                checkLoc = head.clone().add(xOffset, 0, zOffset);
                if (isSolidAndNotPassable(checkLoc.getBlock())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if location is a tight space (surrounded by too many solid blocks)
     * This prevents the Tight Space / Suffocation Glitch
     */
    private boolean isTightSpace(Location location, int maxSurroundingBlocks) {
        int solidBlocksAround = 0;
        
        // Check all blocks around player at feet and head level
        for (int y = 0; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue; // Skip center
                    
                    Block block = location.clone().add(x, y, z).getBlock();
                    if (isSolidAndNotPassable(block)) {
                        solidBlocksAround++;
                    }
                }
            }
        }
        
        // If surrounded by too many solid blocks, it's a tight space exploit
        return solidBlocksAround >= maxSurroundingBlocks;
    }
    
    /**
     * Check if block is solid and not passable
     */
    private boolean isSolidAndNotPassable(Block block) {
        Material type = block.getType();
        return type.isSolid() && !isPassableBlock(type);
    }
    
    private boolean isPassableBlock(Material material) {
        // List of blocks that players can pass through
        switch (material) {
            case AIR:
            case WATER:
            case LAVA:
            case TALL_GRASS:
            case GRASS_BLOCK:
            case DEAD_BUSH:
            case DANDELION:
            case POPPY:
            case BLUE_ORCHID:
            case ALLIUM:
            case AZURE_BLUET:
            case RED_TULIP:
            case ORANGE_TULIP:
            case WHITE_TULIP:
            case PINK_TULIP:
            case OXEYE_DAISY:
            case CORNFLOWER:
            case LILY_OF_THE_VALLEY:
            case BROWN_MUSHROOM:
            case RED_MUSHROOM:
            case TORCH:
            case WALL_TORCH:
            case REDSTONE_TORCH:
            case REDSTONE_WALL_TORCH:
            case SNOW:
            case FIRE:
            case SOUL_FIRE:
                return true;
            default:
                return false;
        }
    }

    private void startPearlCountdown(Player player) {
        // NOTE: Cooldown display is now handled by CombatManager.updatePlayerCountdown()
        // to avoid duplicate messages. This method is kept for future use if needed.
        // The CombatManager already displays pearl cooldowns in its global countdown timer.
    }
    
    public void shutdown() {
        // Cancel all active pearl countdown tasks
        pearlCountdownTasks.values().forEach(task -> {
            if (task != null) {
                task.cancel();
            }
        });
        pearlCountdownTasks.clear();
        activePearls.clear();
    }
}