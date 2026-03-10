package com.shyamstudio.celestCombatPro.hooks.protection;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UXM Claims integration using runtime detection and reflection
 * Compatible with paid UXM Claims plugin without compile-time dependency
 * 
 * UXM Claims: https://www.spigotmc.org/resources/uxmclaims.108139/
 * API Documentation: https://docs.uxplima.com/minecraft/uxmclaims/developer/overview/
 */
public class UXMClaimsHook implements Listener {
    private final CelestCombatPro plugin;
    private final CombatManager combatManager;
    
    // Runtime detection
    private boolean uxmClaimsEnabled = false;
    private Plugin uxmClaimsPlugin;
    
    // Reflection objects for UXM Claims API
    private Object claimFacade;
    private Method findByLocationMethod;
    private Method toDomainLocationMethod;
    private Method isOwnerMethod;
    private Method findMemberByUidMethod;
    private Method getClaimTypeMethod;
    
    // Configuration cache
    private boolean preventClaimEntry;
    private boolean preventCombatInClaims;
    private boolean allowOwnerCombat;
    private boolean allowTrustedCombat;
    private boolean preventTeleportEntry;
    private boolean blockCombatItems;
    private boolean blockCommandsInClaims;
    private boolean disableFlightInClaims;
    private double pushBackForce;
    private boolean sendEntryBlockedMessage;
    private boolean sendCombatBlockedMessage;
    private boolean sendTeleportBlockedMessage;
    private long messageCooldown;
    
    // Cache settings
    private boolean cacheEnabled;
    private long cacheTTL;
    private int maxCacheSize;
    private long cleanupInterval;
    
    // Message cooldown tracking
    private final Map<UUID, Long> lastEntryMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleportMessageTime = new ConcurrentHashMap<>();
    
    // Claim cache for performance
    private final Map<String, ClaimCacheEntry> claimCache = new ConcurrentHashMap<>();
    private long lastCacheClean = System.currentTimeMillis();
    
    public UXMClaimsHook(CelestCombatPro plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        
        // Runtime detection of UXM Claims
        detectUXMClaims();
        
        loadConfig();
        startCleanupTask();
    }
    
    /**
     * Detect UXM Claims plugin at runtime
     */
    private void detectUXMClaims() {
        try {
            uxmClaimsPlugin = Bukkit.getPluginManager().getPlugin("UXMClaims");
            
            if (uxmClaimsPlugin != null && uxmClaimsPlugin.isEnabled()) {
                plugin.getLogger().info("UXM Claims detected! Initializing integration...");
                initializeUXMClaimsAPI();
            } else {
                plugin.getLogger().info("UXM Claims not found - claim protection disabled");
                uxmClaimsEnabled = false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to detect UXM Claims: " + e.getMessage());
            uxmClaimsEnabled = false;
        }
    }
    
    /**
     * Initialize UXM Claims API using reflection for paid plugin compatibility
     */
    private void initializeUXMClaimsAPI() {
        try {
            // Load UXM Claims classes using reflection
            Class<?> uxmClaimBukkitAPIClass = Class.forName("com.uxplima.claim.bukkit.api.UxmClaimBukkitAPI");
            Class<?> bukkitConverterClass = Class.forName("com.uxplima.claim.bukkit.api.BukkitConverter");
            Class<?> claimClass = Class.forName("com.uxplima.claim.domain.model.Claim");
            Class<?> locationClass = Class.forName("com.uxplima.claim.domain.model.vo.Location");
            
            // Get API instance
            Method getInstanceMethod = uxmClaimBukkitAPIClass.getMethod("getInstance");
            Object apiInstance = getInstanceMethod.invoke(null);
            
            // Get ClaimFacade
            Method claimFacadeMethod = uxmClaimBukkitAPIClass.getMethod("claimFacade");
            claimFacade = claimFacadeMethod.invoke(apiInstance);
            
            // Get methods we'll need
            Class<?> claimFacadeClass = Class.forName("com.uxplima.claim.app.facade.ClaimFacade");
            
            findByLocationMethod = claimFacadeClass.getMethod("findByLocation", locationClass);
            toDomainLocationMethod = bukkitConverterClass.getMethod("toDomainLocation", org.bukkit.Location.class);
            isOwnerMethod = claimClass.getMethod("isOwner", UUID.class);
            findMemberByUidMethod = claimClass.getMethod("findMemberByUid", UUID.class);
            
            // Try to get claim type method (may not exist in all versions)
            try {
                getClaimTypeMethod = claimClass.getMethod("getClaimType");
            } catch (NoSuchMethodException e) {
                plugin.debug("ClaimType method not found - using basic claim detection");
            }
            
            uxmClaimsEnabled = true;
            plugin.getLogger().info("UXM Claims API initialized successfully using reflection!");
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize UXM Claims API: " + e.getMessage());
            plugin.debug("UXM Claims reflection error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            uxmClaimsEnabled = false;
            claimFacade = null;
        }
    }
    
    public void reloadConfig() {
        loadConfig();
        claimCache.clear();
    }
    
    private void loadConfig() {
        // Basic settings
        this.preventClaimEntry = plugin.getConfig().getBoolean("uxm_claims_protection.prevent_claim_entry", true);
        this.preventCombatInClaims = plugin.getConfig().getBoolean("uxm_claims_protection.prevent_combat_in_claims", true);
        this.allowOwnerCombat = plugin.getConfig().getBoolean("uxm_claims_protection.allow_owner_combat", false);
        this.allowTrustedCombat = plugin.getConfig().getBoolean("uxm_claims_protection.allow_trusted_combat", false);
        this.preventTeleportEntry = plugin.getConfig().getBoolean("uxm_claims_protection.prevent_teleport_entry", true);
        this.blockCombatItems = plugin.getConfig().getBoolean("uxm_claims_protection.block_combat_items.enabled", true);
        this.blockCommandsInClaims = plugin.getConfig().getBoolean("uxm_claims_protection.block_commands_in_claims", true);
        this.disableFlightInClaims = plugin.getConfig().getBoolean("uxm_claims_protection.disable_flight_in_claims", true);
        this.pushBackForce = plugin.getConfig().getDouble("uxm_claims_protection.push_back_force", 0.6);
        
        // Message settings
        this.sendEntryBlockedMessage = plugin.getConfig().getBoolean("uxm_claims_protection.send_entry_blocked_message", true);
        this.sendCombatBlockedMessage = plugin.getConfig().getBoolean("uxm_claims_protection.send_combat_blocked_message", true);
        this.sendTeleportBlockedMessage = plugin.getConfig().getBoolean("uxm_claims_protection.send_teleport_blocked_message", true);
        this.messageCooldown = plugin.getConfig().getLong("uxm_claims_protection.message_cooldown", 2) * 1000L;
        
        // Cache settings
        this.cacheEnabled = plugin.getConfig().getBoolean("uxm_claims_protection.cache.enabled", true);
        this.cacheTTL = plugin.getConfig().getLong("uxm_claims_protection.cache.ttl", 10000);
        this.maxCacheSize = plugin.getConfig().getInt("uxm_claims_protection.cache.max_size", 1000);
        this.cleanupInterval = plugin.getConfig().getLong("uxm_claims_protection.cache.cleanup_interval", 30000);
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!uxmClaimsEnabled || !preventClaimEntry || claimFacade == null) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Only process if player is in combat
        if (!combatManager.isInCombat(player)) {
            return;
        }
        
        // Only process if enabled in this world
        if (!combatManager.isUXMClaimsEnabledInWorld(player.getWorld().getName())) {
            return;
        }
        
        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        
        // Only process if player actually moved to a different block
        if (to == null || (from.getBlockX() == to.getBlockX() && 
                          from.getBlockY() == to.getBlockY() && 
                          from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        
        // Check if player is trying to enter a claimed area
        if (isEnteringClaim(from, to, player)) {
            event.setCancelled(true);
            
            // Push player back
            Vector pushBack = from.toVector().subtract(to.toVector()).normalize().multiply(pushBackForce);
            pushBack.setY(Math.max(0.1, pushBack.getY())); // Ensure upward movement
            player.setVelocity(pushBack);
            
            // Disable flight if configured
            if (disableFlightInClaims && player.isFlying()) {
                player.setFlying(false);
            }
            
            // Send message with cooldown
            if (sendEntryBlockedMessage) {
                sendMessageWithCooldown(player, "uxm_claims_combat_entry_blocked", lastEntryMessageTime);
            }
            
            plugin.debug("Blocked combat player from entering UXM Claim: " + player.getName());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!uxmClaimsEnabled || !preventCombatInClaims || claimFacade == null) {
            return;
        }
        
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        
        Player victim = (Player) event.getEntity();
        Player attacker = null;
        
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        }
        
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        
        // Check if enabled in this world
        if (!combatManager.isUXMClaimsEnabledInWorld(victim.getWorld().getName())) {
            return;
        }
        
        // Check if either player is in a claim
        Object victimClaim = getClaimAt(victim.getLocation());
        Object attackerClaim = getClaimAt(attacker.getLocation());
        
        if (victimClaim == null && attackerClaim == null) {
            return; // Neither player in claim, allow combat
        }
        
        // Check if combat should be allowed based on ownership/trust
        if (shouldAllowCombatInClaim(victimClaim, attackerClaim, attacker, victim)) {
            return; // Combat allowed
        }
        
        // Block the combat
        event.setCancelled(true);
        
        // Send messages
        if (sendCombatBlockedMessage) {
            sendMessageWithCooldown(attacker, "uxm_claims_combat_blocked", lastCombatMessageTime);
            sendMessageWithCooldown(victim, "uxm_claims_combat_blocked", lastCombatMessageTime);
        }
        
        plugin.debug("Blocked combat in UXM Claim: " + attacker.getName() + " vs " + victim.getName());
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!uxmClaimsEnabled || !preventTeleportEntry || claimFacade == null) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Only process if player is in combat
        if (!combatManager.isInCombat(player)) {
            return;
        }
        
        // Only process if enabled in this world
        if (!combatManager.isUXMClaimsEnabledInWorld(event.getTo().getWorld().getName())) {
            return;
        }
        
        // Check if teleporting into a claim
        Object fromClaim = getClaimAt(event.getFrom());
        Object toClaim = getClaimAt(event.getTo());
        
        if (fromClaim == null && toClaim != null) {
            // Teleporting from unclaimed to claimed area
            event.setCancelled(true);
            
            if (sendTeleportBlockedMessage) {
                sendMessageWithCooldown(player, "uxm_claims_teleport_blocked", lastTeleportMessageTime);
            }
            
            plugin.debug("Blocked teleport into UXM Claim during combat: " + player.getName());
        }
    }
    
    private boolean isEnteringClaim(org.bukkit.Location from, org.bukkit.Location to, Player player) {
        // Check if moving from unclaimed to claimed area
        Object fromClaim = getClaimAt(from);
        Object toClaim = getClaimAt(to);
        
        // Player is entering a claim if they're moving from unclaimed to claimed
        return fromClaim == null && toClaim != null;
    }
    
    private boolean shouldAllowCombatInClaim(Object victimClaim, Object attackerClaim, Player attacker, Player victim) {
        try {
            // If allow_owner_combat is true, check ownership
            if (allowOwnerCombat) {
                if (victimClaim != null && isOwnerOrTrusted(victimClaim, attacker)) {
                    return true; // Attacker is owner/trusted in victim's claim
                }
                if (attackerClaim != null && isOwnerOrTrusted(attackerClaim, victim)) {
                    return true; // Victim is owner/trusted in attacker's claim
                }
            }
            
            // If allow_trusted_combat is true, check if both are trusted
            if (allowTrustedCombat) {
                boolean attackerTrusted = (victimClaim != null && isOwnerOrTrusted(victimClaim, attacker)) ||
                                        (attackerClaim != null && isOwnerOrTrusted(attackerClaim, attacker));
                boolean victimTrusted = (victimClaim != null && isOwnerOrTrusted(victimClaim, victim)) ||
                                      (attackerClaim != null && isOwnerOrTrusted(attackerClaim, victim));
                
                if (attackerTrusted && victimTrusted) {
                    return true; // Both players are trusted
                }
            }
            
            return false; // Block combat by default
            
        } catch (Exception e) {
            plugin.debug("Error checking combat permissions: " + e.getMessage());
            return false; // Block combat on error
        }
    }
    
    private Object getClaimAt(org.bukkit.Location bukkitLocation) {
        if (claimFacade == null) {
            return null;
        }
        
        String cacheKey = locationToCacheKey(bukkitLocation);
        
        // Check cache first if enabled
        if (cacheEnabled) {
            ClaimCacheEntry cached = claimCache.get(cacheKey);
            if (cached != null && !cached.isExpired(cacheTTL)) {
                return cached.claim;
            }
        }
        
        try {
            // Convert Bukkit location to UXM Claims domain location using reflection
            Object domainLocation = toDomainLocationMethod.invoke(null, bukkitLocation);
            
            // Query UXM Claims API using reflection
            Optional<?> claimOptional = (Optional<?>) findByLocationMethod.invoke(claimFacade, domainLocation);
            Object claim = claimOptional.orElse(null);
            
            // Cache the result
            if (cacheEnabled) {
                if (claimCache.size() >= maxCacheSize) {
                    cleanCacheIfNeeded();
                }
                claimCache.put(cacheKey, new ClaimCacheEntry(claim));
            }
            
            return claim;
            
        } catch (Exception e) {
            plugin.debug("Error checking UXM Claims at location " + bukkitLocation + ": " + e.getMessage());
            return null;
        }
    }
    
    private boolean isOwnerOrTrusted(Object claim, Player player) {
        try {
            UUID playerUid = player.getUniqueId();
            
            // Check if player is owner using reflection
            Boolean isOwner = (Boolean) isOwnerMethod.invoke(claim, playerUid);
            if (isOwner != null && isOwner) {
                return true;
            }
            
            // Check if player is a member with permissions using reflection
            Optional<?> memberOptional = (Optional<?>) findMemberByUidMethod.invoke(claim, playerUid);
            return memberOptional.isPresent(); // If they're a member, they're trusted
            
        } catch (Exception e) {
            plugin.debug("Error checking UXM Claims ownership: " + e.getMessage());
            return false;
        }
    }
    
    private String locationToCacheKey(org.bukkit.Location location) {
        return location.getWorld().getName() + ":" + 
               location.getBlockX() + ":" + 
               location.getBlockY() + ":" + 
               location.getBlockZ();
    }
    
    private void cleanCacheIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheClean > cleanupInterval) {
            claimCache.entrySet().removeIf(entry -> entry.getValue().isExpired(cacheTTL));
            lastCacheClean = currentTime;
        }
    }
    
    private void sendMessageWithCooldown(Player player, String messageKey, Map<UUID, Long> cooldownMap) {
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        Long lastMessage = cooldownMap.get(playerUUID);
        if (lastMessage == null || currentTime - lastMessage > messageCooldown) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            plugin.getMessageService().sendMessage(player, messageKey, placeholders);
            cooldownMap.put(playerUUID, currentTime);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        lastEntryMessageTime.remove(playerUUID);
        lastCombatMessageTime.remove(playerUUID);
        lastTeleportMessageTime.remove(playerUUID);
    }
    
    private void startCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            cleanCacheIfNeeded();
            
            // Clean up message cooldowns for offline players
            long currentTime = System.currentTimeMillis();
            lastEntryMessageTime.entrySet().removeIf(entry -> 
                currentTime - entry.getValue() > messageCooldown * 5);
            lastCombatMessageTime.entrySet().removeIf(entry -> 
                currentTime - entry.getValue() > messageCooldown * 5);
            lastTeleportMessageTime.entrySet().removeIf(entry -> 
                currentTime - entry.getValue() > messageCooldown * 5);
        }, 20L * 30L, 20L * 30L); // Run every 30 seconds
    }
    
    public void cleanup() {
        claimCache.clear();
        lastEntryMessageTime.clear();
        lastCombatMessageTime.clear();
        lastTeleportMessageTime.clear();
    }
    
    /**
     * Check if UXM Claims integration is enabled and working
     */
    public boolean isEnabled() {
        return uxmClaimsEnabled && claimFacade != null;
    }
    
    /**
     * Get UXM Claims plugin instance
     */
    public Plugin getUXMClaimsPlugin() {
        return uxmClaimsPlugin;
    }
    
    /**
     * Cache entry for claim lookups
     */
    private static class ClaimCacheEntry {
        final Object claim; // Can be null for unclaimed areas
        final long timestamp;
        
        ClaimCacheEntry(Object claim) {
            this.claim = claim;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - timestamp > ttl;
        }
    }
}