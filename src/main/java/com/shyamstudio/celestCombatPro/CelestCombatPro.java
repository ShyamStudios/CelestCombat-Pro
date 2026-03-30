package com.shyamstudio.celestCombatPro;

import com.sk89q.worldguard.WorldGuard;
import com.shyamstudio.celestCombatPro.bstats.Metrics;
import com.shyamstudio.celestCombatPro.combat.CombatManager;
import com.shyamstudio.celestCombatPro.combat.DeathAnimationManager;
import com.shyamstudio.celestCombatPro.commands.CommandManager;
import com.shyamstudio.celestCombatPro.configs.TimeFormatter;
import com.shyamstudio.celestCombatPro.messages.MessageManager;
import com.shyamstudio.celestCombatPro.listeners.CombatListeners;
import com.shyamstudio.celestCombatPro.listeners.DynamicEventHandler;
import com.shyamstudio.celestCombatPro.listeners.EnderPearlListener;
import com.shyamstudio.celestCombatPro.hooks.protection.WorldGuardHook;
import com.shyamstudio.celestCombatPro.hooks.protection.GriefPreventionHook;
import com.shyamstudio.celestCombatPro.hooks.protection.UXMClaimsHook;
import com.shyamstudio.celestCombatPro.hooks.placeholders.CelestCombatExpansion;
import com.shyamstudio.celestCombatPro.listeners.ItemRestrictionListener;
import com.shyamstudio.celestCombatPro.listeners.TridentListener;
import com.shyamstudio.celestCombatPro.protection.NewbieProtectionManager;
import com.shyamstudio.celestCombatPro.rewards.KillRewardManager;
import com.shyamstudio.celestCombatPro.updates.ConfigUpdater;
import com.shyamstudio.celestCombatPro.updates.UpdateChecker;
import com.shyamstudio.celestCombatPro.api.CelestCombatAPI;
import com.shyamstudio.celestCombatPro.api.CombatAPIImpl;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
@Accessors(chain = false)
public final class CelestCombatPro extends JavaPlugin {
  @Getter
  private static CelestCombatPro instance;
  private final boolean debugMode = getConfig().getBoolean("debug", false);
  private MessageManager messageManager;
  private UpdateChecker updateChecker;
  private ConfigUpdater configUpdater;
  private TimeFormatter timeFormatter;
  private CommandManager commandManager;
  private CombatManager combatManager;
  private KillRewardManager killRewardManager;
  private CombatListeners combatListeners;
  private EnderPearlListener enderPearlListener;
  private TridentListener tridentListener;
  private ItemRestrictionListener itemRestrictionListener;
  private DeathAnimationManager deathAnimationManager;
  private NewbieProtectionManager newbieProtectionManager;
  private WorldGuardHook worldGuardHook;
  private GriefPreventionHook griefPreventionHook;
  private UXMClaimsHook uxmClaimsHook;
  private CombatAPIImpl combatAPI;
  private CelestCombatExpansion placeholderExpansion;
  private DynamicEventHandler dynamicEventHandler;

  public static boolean hasWorldGuard = false;
  public static boolean hasGriefPrevention = false;
  public static boolean hasUXMClaims = false;
  public static boolean hasPlaceholderAPI = false;

  @Override
  public void onEnable() {
    long startTime = System.currentTimeMillis();
    instance = this;

    saveDefaultConfig();
    checkProtectionPlugins();

    messageManager = new MessageManager(this);
    updateChecker = new UpdateChecker(this);
    configUpdater = new ConfigUpdater(this);
    configUpdater.checkAndUpdateConfig();
    timeFormatter = new TimeFormatter(this);

    deathAnimationManager = new DeathAnimationManager(this);
    combatManager = new CombatManager(this);
    killRewardManager = new KillRewardManager(this);
    newbieProtectionManager = new NewbieProtectionManager(this);
    
    // Initialize dynamic event handler system
    dynamicEventHandler = new DynamicEventHandler(this);
    dynamicEventHandler.registerHandlers();
    
    // Register static event handlers (ones that don't need configurable priorities)
    combatListeners = new CombatListeners(this);
    // Note: Some methods in combatListeners are now registered dynamically
    getServer().getPluginManager().registerEvents(combatListeners, this);

    enderPearlListener = new EnderPearlListener(this, combatManager);
    // Note: Some methods in enderPearlListener are now registered dynamically
    getServer().getPluginManager().registerEvents(enderPearlListener, this);

    tridentListener = new TridentListener(this, combatManager);
    getServer().getPluginManager().registerEvents(tridentListener, this);

    // Note: Some methods in ItemRestrictionListener are now registered dynamically
    itemRestrictionListener = new ItemRestrictionListener(this, combatManager);
    getServer().getPluginManager().registerEvents(itemRestrictionListener, this);

    // WorldGuard integration
    if (hasWorldGuard && getConfig().getBoolean("safezone_protection.enabled", true)) {
      worldGuardHook = new WorldGuardHook(this, combatManager);
      getServer().getPluginManager().registerEvents(worldGuardHook, this);
      debug("WorldGuard safezone protection enabled");
    } else if(hasWorldGuard) {
      getLogger().info("Found WorldGuard but safe zone barrier is disabled in config.");
    }

    // GriefPrevention integration
    if (hasGriefPrevention && getConfig().getBoolean("claim_protection.enabled", true)) {
      griefPreventionHook = new GriefPreventionHook(this, combatManager);
      getServer().getPluginManager().registerEvents(griefPreventionHook, this);
      debug("GriefPrevention claim protection enabled");
    } else if(hasGriefPrevention) {
      getLogger().info("Found GriefPrevention but claim protection is disabled in config.");
    }

    // UXM Claims integration
    if (hasUXMClaims && getConfig().getBoolean("uxm_claims_protection.enabled", true)) {
      uxmClaimsHook = new UXMClaimsHook(this, combatManager);
      getServer().getPluginManager().registerEvents(uxmClaimsHook, this);
      debug("UXM Claims protection enabled");
    } else if(hasUXMClaims) {
      getLogger().info("Found UXM Claims but claim protection is disabled in config.");
    }

    commandManager = new CommandManager(this);
    commandManager.registerCommands();

    combatAPI = new CombatAPIImpl(this, combatManager);
    CelestCombatAPI.initialize(combatAPI);

    // PlaceholderAPI integration
    if (isPluginEnabled("PlaceholderAPI")) {
      try {
        hasPlaceholderAPI = true;
        placeholderExpansion = new CelestCombatExpansion(this);
        if (placeholderExpansion.register()) {
          getLogger().info("PlaceholderAPI integration enabled successfully!");
        }
      } catch (Exception e) {
        getLogger().warning("Failed to register PlaceholderAPI expansion: " + e.getMessage());
      }
    }

    setupBtatsMetrics();

    long loadTime = System.currentTimeMillis() - startTime;
    getLogger().info("CelestCombat has been enabled! (Loaded in " + loadTime + "ms)");
  }

  @Override
  public void onDisable() {
    // Unregister dynamic event handlers first
    if (dynamicEventHandler != null) {
      dynamicEventHandler.unregisterHandlers();
    }
    
    if (combatManager != null) {
      combatManager.shutdown();
    }

    if(combatListeners != null) {
      combatListeners.shutdown();
    }

    if (enderPearlListener != null) {
      enderPearlListener.shutdown();
    }

    if (tridentListener != null) {
      tridentListener.shutdown();
    }

    if (worldGuardHook != null) {
      worldGuardHook.cleanup();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.cleanup();
    }

    if (uxmClaimsHook != null) {
      uxmClaimsHook.cleanup();
    }

    if (killRewardManager != null) {
      killRewardManager.shutdown();
    }

    if (newbieProtectionManager != null) {
      newbieProtectionManager.shutdown();
    }

    if (placeholderExpansion != null && hasPlaceholderAPI) {
      try {
        placeholderExpansion.unregister();
      } catch (Exception e) {
        getLogger().warning("Failed to unregister PlaceholderAPI expansion: " + e.getMessage());
      }
    }

    CelestCombatAPI.shutdown();

    getLogger().info("CelestCombat has been disabled!");
  }

  private void checkProtectionPlugins() {
    boolean wgPluginFound = isPluginEnabled("WorldGuard");
    boolean wgAPIAvailable = isWorldGuardAPIAvailable();
    
    getLogger().info("[Protection Check] WorldGuard plugin found: " + wgPluginFound);
    getLogger().info("[Protection Check] WorldGuard API available: " + wgAPIAvailable);
    
    hasWorldGuard = wgPluginFound && wgAPIAvailable;
    if (hasWorldGuard) {
      getLogger().info("WorldGuard integration enabled successfully!");
    } else {
      if (!wgPluginFound) {
        getLogger().warning("WorldGuard plugin not found or not enabled!");
      } else if (!wgAPIAvailable) {
        getLogger().warning("WorldGuard plugin found but API is not available!");
      }
    }

    hasGriefPrevention = isPluginEnabled("GriefPrevention") && isGriefPreventionAPIAvailable();
    if (hasGriefPrevention) {
      getLogger().info("GriefPrevention integration enabled successfully!");
    }

    hasUXMClaims = isPluginEnabled("UXMClaims");
    if (hasUXMClaims) {
      getLogger().info("UXM Claims integration enabled successfully!");
    }
  }

  private boolean isPluginEnabled(String pluginName) {
    Plugin plugin = getServer().getPluginManager().getPlugin(pluginName);
    return plugin != null && plugin.isEnabled();
  }

  private boolean isWorldGuardAPIAvailable() {
    try {
      Class.forName("com.sk89q.worldguard.WorldGuard");
      WorldGuard wg = WorldGuard.getInstance();
      getLogger().info("[WorldGuard API] WorldGuard.getInstance() = " + (wg != null ? "SUCCESS" : "NULL"));
      return wg != null;
    } catch (ClassNotFoundException e) {
      getLogger().warning("[WorldGuard API] ClassNotFoundException: " + e.getMessage());
      return false;
    } catch (NoClassDefFoundError e) {
      getLogger().warning("[WorldGuard API] NoClassDefFoundError: " + e.getMessage());
      return false;
    } catch (Exception e) {
      getLogger().warning("[WorldGuard API] Unexpected error: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  private boolean isGriefPreventionAPIAvailable() {
    try {
      Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
      return true;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  private void setupBtatsMetrics() {
    Scheduler.runTask(() -> new Metrics(this, 27299));
  }

  public long getTimeFromConfig(String path, String defaultValue) {
    return timeFormatter.getTimeFromConfig(path, defaultValue);
  }

  public long getTimeFromConfigInMilliseconds(String path, String defaultValue) {
    long ticks = timeFormatter.getTimeFromConfig(path, defaultValue);
    return ticks * 50L; // Convert ticks to milliseconds
  }

  public void refreshTimeCache() {
    if (timeFormatter != null) {
      timeFormatter.clearCache();
    }
  }

  public void debug(String message) {
    if (debugMode) {
      getLogger().info("[DEBUG] " + message);
    }
  }

  public void reload() {
    // Reload configuration first
    reloadConfig();
    
    // Reload combat manager (which includes event priority manager)
    if (combatManager != null) {
      combatManager.reloadConfig();
    }
    
    // Re-register dynamic event handlers with new priorities
    if (dynamicEventHandler != null) {
      dynamicEventHandler.registerHandlers();
    }
    
    if (messageManager != null) {
      messageManager.reload();
    }

    if (worldGuardHook != null) {
      worldGuardHook.cleanup();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.cleanup();
    }
    
    debug("Plugin reloaded with new event priorities");
  }

  public MessageManager getMessageService() {
    return messageManager;
  }
  
  public DynamicEventHandler getDynamicEventHandler() {
    return dynamicEventHandler;
  }

  public ItemRestrictionListener getItemRestrictionListener() {
    return itemRestrictionListener;
  }
}