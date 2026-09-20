package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.Scheduler;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TridentListener implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    public TridentListener(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
    }

    // Track thrown tridents to their player owners
    private final Map<Integer, UUID> activeTridents = new ConcurrentHashMap<>();

    // Store original locations for riptide rollback
    private final Map<UUID, Location> riptideOriginalLocations = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        riptideOriginalLocations.remove(playerUUID);
        activeTridents.entrySet().removeIf(entry -> entry.getValue().equals(playerUUID));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

        // Check if player is right-clicking with a trident
        if (item != null && item.getType() == Material.TRIDENT &&
                (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {

            // Check if trident usage is banned in this world
            if (combatManager.isTridentBanned(player)) {
                event.setCancelled(true);
                sendBannedMessage(player);
                return;
            }

            // Handle riptide tridents differently - we need to prevent the interaction entirely
            if (item.containsEnchantment(Enchantment.RIPTIDE)) {
                if (combatManager.isTridentOnCooldown(player)) {
                    event.setCancelled(true);
                    sendCooldownMessage(player);
                    return;
                } else {
                    // Store the player's location before riptide for potential rollback
                    riptideOriginalLocations.put(player.getUniqueId(), player.getLocation().clone());
                }
            } else {
                // Handle non-riptide tridents
                if (combatManager.isTridentOnCooldown(player)) {
                    event.setCancelled(true);
                    sendCooldownMessage(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptideUse(PlayerRiptideEvent event) {
        Player player = event.getPlayer();

        // Check if trident usage is banned in this world
        if (combatManager.isTridentBanned(player)) {
            sendBannedMessage(player);
            rollbackRiptide(player);
            return;
        }

        // Check if trident is on cooldown
        if (combatManager.isTridentOnCooldown(player)) {
            sendCooldownMessage(player);
            rollbackRiptide(player);
            return;
        }

        // Set cooldown for riptide usage
        combatManager.setTridentCooldown(player);

        // Refresh combat on riptide usage if enabled
        combatManager.refreshCombatOnTridentLand(player);

        // Clean up the stored location
        riptideOriginalLocations.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Trident && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            // Check if trident usage is banned in this world
            if (combatManager.isTridentBanned(player)) {
                event.setCancelled(true);
                sendBannedMessage(player);
                return;
            }

            // Check if trident is on cooldown
            if (combatManager.isTridentOnCooldown(player)) {
                event.setCancelled(true);
                sendCooldownMessage(player);
            } else {
                // Set cooldown when player successfully launches a trident (non-riptide)
                combatManager.setTridentCooldown(player);

                // Track this trident to the player for the hit event
                activeTridents.put(event.getEntity().getEntityId(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Trident) {
            // Get the trident's entity ID
            int tridentId = event.getEntity().getEntityId();

            // Check if we're tracking this trident
            if (activeTridents.containsKey(tridentId)) {
                UUID playerUUID = activeTridents.remove(tridentId);
                Player player = plugin.getServer().getPlayer(playerUUID);

                if (player != null && player.isOnline()) {
                    // Trident landed, refresh combat if enabled
                    combatManager.refreshCombatOnTridentLand(player);
                }
            }
        }
    }

    private void rollbackRiptide(Player player) {
        Location originalLocation = riptideOriginalLocations.remove(player.getUniqueId());

        if (originalLocation != null) {
            Scheduler.runEntityLater(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.setVelocity(player.getVelocity().multiply(0));

                Location current = player.getLocation();
                if (current.getWorld() != null && current.getWorld().equals(originalLocation.getWorld())
                        && current.distanceSquared(originalLocation) > 25) {
                    player.teleportAsync(originalLocation);
                }
            }, 2L);
        } else {
            Scheduler.runEntity(player, () -> {
                if (player.isOnline()) {
                    player.setVelocity(player.getVelocity().multiply(0));
                }
            });
        }
    }

    /**
     * Helper method to send banned message
     */
    private void sendBannedMessage(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, "trident_banned", placeholders);
    }

    /**
     * Helper method to send cooldown message
     */
    private void sendCooldownMessage(Player player) {
        int remainingTime = combatManager.getRemainingTridentCooldown(player);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(remainingTime));
        plugin.getMessageService().sendMessage(player, "trident_cooldown", placeholders);
    }

    /**
     * Cleanup method to cancel all tasks when the plugin is disabled.
     * Call this from your main plugin's onDisable method.
     */
    public void shutdown() {
        activeTridents.clear();
        riptideOriginalLocations.clear();
    }
}