package com.shyamstudio.celestCombatPro.listeners;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ItemRestrictionListener implements Listener {

    private final CelestCombatPro plugin;
    private final CombatManager combatManager;

    private boolean itemRestrictions;
    private List<String> disabledItems = Collections.emptyList();

    public ItemRestrictionListener(CelestCombatPro plugin,  CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;

        this.reloadConfig();
    }

    public void reloadConfig() {
        this.itemRestrictions = plugin.getConfig().getBoolean("combat.item_restrictions.enabled", true);
        this.disabledItems = plugin.getConfig().getStringList("combat.item_restrictions.disabled_items");
    }

    public static String formatItemName(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        // Convert from UPPERCASE_WITH_UNDERSCORES to Title Case
        String[] words = material.name().split("_");
        StringBuilder formattedName = new StringBuilder();

        for (String word : words) {
            // Capitalize first letter, rest lowercase
            formattedName
                    .append(word.substring(0, 1).toUpperCase())
                    .append(word.substring(1).toLowerCase())
                    .append(" ");
        }

        return formattedName.toString().trim();
    }

    // NOTE: This method is now registered dynamically with configurable priority
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        // Check if item restrictions are enabled
        if (!itemRestrictions) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (combatManager.isInCombat(player)) {
            // Check if the consumed item is in the disabled items list
            if (isItemDisabled(item.getType())) {
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", formatItemName(item.getType()));
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMoveEvent(PlayerMoveEvent event) {
        // Check if item restrictions are enabled
        if (!itemRestrictions) {
            return;
        }

        Player player = event.getPlayer();

        if (combatManager.isInCombat(player)) {
            if (disabledItems.contains("ELYTRA") && player.isGliding()) {
                player.setGliding(false);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", "Elytra");
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // Check if item restrictions are enabled
        if (!itemRestrictions) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!combatManager.isInCombat(player)) {
            return;
        }

        // Check if ELYTRA is disabled
        if (!disabledItems.contains("ELYTRA")) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        // Prevent equipping Elytra to chestplate slot
        if (event.getSlot() == 38 && event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
            if (clickedItem != null && clickedItem.getType() == Material.ELYTRA || cursorItem.getType() == Material.ELYTRA) {
                event.setCancelled(true);
                
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", "Elytra");
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
                return;
            }
        }

        // Prevent shift-clicking Elytra to equip it
        if (event.isShiftClick() && clickedItem != null && clickedItem.getType() == Material.ELYTRA) {
            PlayerInventory inv = player.getInventory();
            if (inv.getChestplate() == null || inv.getChestplate().getType() == Material.AIR) {
                event.setCancelled(true);
                
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", "Elytra");
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        // Check if item restrictions are enabled
        if (!itemRestrictions) {
            return;
        }

        Player player = event.getPlayer();

        if (!combatManager.isInCombat(player)) {
            return;
        }

        // Check if ELYTRA is disabled and player is trying to start gliding
        if (disabledItems.contains("ELYTRA") && event.isFlying() && 
            player.getInventory().getChestplate() != null && 
            player.getInventory().getChestplate().getType() == Material.ELYTRA) {
            
            event.setCancelled(true);
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("item", "Elytra");
            plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
        }
    }

    /**
     * Removes Elytra from player when they enter combat
     */
    public void handleCombatStart(Player player) {
        // Check if item restrictions are enabled
        if (!itemRestrictions) {
            return;
        }
        
        if (!disabledItems.contains("ELYTRA")) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack chestplate = inventory.getChestplate();

        // If player has Elytra equipped, remove it
        if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
            // Stop gliding immediately
            if (player.isGliding()) {
                player.setGliding(false);
            }

            // Remove Elytra from chestplate slot
            inventory.setChestplate(null);

            // Try to add it back to inventory, drop if full
            HashMap<Integer, ItemStack> leftover = inventory.addItem(chestplate);
            if (!leftover.isEmpty()) {
                for (ItemStack item : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("item", "Elytra");
            plugin.getMessageService().sendMessage(player, "elytra_removed_combat_start", placeholders);
        }
    }

    private boolean isItemDisabled(Material itemType) {
        return disabledItems.stream()
                .anyMatch(disabledItem ->
                        itemType.name().equalsIgnoreCase(disabledItem) ||
                                itemType.name().contains(disabledItem)
                );
    }
}
