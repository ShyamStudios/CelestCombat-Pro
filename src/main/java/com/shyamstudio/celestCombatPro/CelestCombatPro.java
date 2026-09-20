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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class CelestCombatPro extends JavaPlugin {
  private static CelestCombatPro instance;

  public static CelestCombatPro getInstance() {
    return instance;
  }

  private volatile boolean debugMode = false;
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
  private Metrics metrics;

  public static boolean hasWorldGuard = false;
  public static boolean hasGriefPrevention = false;
  public static boolean hasUXMClaims = false;
  public static boolean hasPlaceholderAPI = false;

  @Override
  public void onEnable() {
    long startTime = System.currentTimeMillis();
    instance = this;

    // The scheduler must be bound before any manager schedules its own tasks
    Scheduler.init(this);

    saveDefaultConfig();
    debugMode = getConfig().getBoolean("debug", false);
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

    // Create all listeners before the dynamic handler so it can register them
    combatListeners = new CombatListeners(this);
    enderPearlListener = new EnderPearlListener(this, combatManager);
    tridentListener = new TridentListener(this, combatManager);
    itemRestrictionListener = new ItemRestrictionListener(this, combatManager);

    // TridentListener uses static annotations; everything else is registered by
    // DynamicEventHandler so priorities stay configurable and instances are shared.
    getServer().getPluginManager().registerEvents(tridentListener, this);

    // WorldGuard integration
    if (hasWorldGuard && getConfig().getBoolean("safezone_protection.enabled", true)) {
      worldGuardHook = new WorldGuardHook(this, combatManager);
      getServer().getPluginManager().registerEvents(worldGuardHook, this);
      debug("WorldGuard safezone protection enabled");
    } else if (hasWorldGuard) {
      getLogger().info("Found WorldGuard but safe zone barrier is disabled in config.");
    }

    // GriefPrevention integration
    if (hasGriefPrevention && getConfig().getBoolean("claim_protection.enabled", true)) {
      griefPreventionHook = new GriefPreventionHook(this, combatManager);
      getServer().getPluginManager().registerEvents(griefPreventionHook, this);
      debug("GriefPrevention claim protection enabled");
    } else if (hasGriefPrevention) {
      getLogger().info("Found GriefPrevention but claim protection is disabled in config.");
    }

    // UXM Claims integration
    if (hasUXMClaims && getConfig().getBoolean("uxm_claims_protection.enabled", true)) {
      uxmClaimsHook = new UXMClaimsHook(this, combatManager);
      getServer().getPluginManager().registerEvents(uxmClaimsHook, this);
      debug("UXM Claims protection enabled");
    } else if (hasUXMClaims) {
      getLogger().info("Found UXM Claims but claim protection is disabled in config.");
    }

    // Register the configurable event handler set
    dynamicEventHandler = new DynamicEventHandler(this);
    dynamicEventHandler.registerHandlers();

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

    setupBstatsMetrics();

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

    if (combatListeners != null) {
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

    if (metrics != null) {
      metrics.shutdown();
      metrics = null;
    }

    // Final safety net: no plugin task may survive a disable/reload cycle
    Scheduler.cancelAll(this);

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
      } else {
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
      getLogger().log(java.util.logging.Level.SEVERE, "[WorldGuard API] Unexpected error", e);
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

  private void setupBstatsMetrics() {
    this.metrics = new Metrics(this, 27299);
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

  /**
   * Full plugin reload: refreshes configuration, all caches and re-registers
   * dynamic handlers. All cached values are refreshed before consumers re-read them.
   */
  public void reload() {
    reloadConfig();
    debugMode = getConfig().getBoolean("debug", false);

    // Time values feed the managers below, so drop the cache before they reload
    if (timeFormatter != null) {
      timeFormatter.clearCache();
    }

    if (combatManager != null) {
      combatManager.reloadConfig();
    }

    if (killRewardManager != null) {
      killRewardManager.loadConfig();
    }

    if (newbieProtectionManager != null) {
      newbieProtectionManager.reloadConfig();
    }

    if (combatListeners != null) {
      combatListeners.reload();
    }

    if (itemRestrictionListener != null) {
      itemRestrictionListener.reloadConfig();
    }

    if (deathAnimationManager != null) {
      deathAnimationManager.reloadConfig();
    }

    if (messageManager != null) {
      messageManager.reload();
    }

    if (worldGuardHook != null) {
      worldGuardHook.reloadConfig();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.reloadConfig();
    }

    if (uxmClaimsHook != null) {
      uxmClaimsHook.reloadConfig();
    }

    // Re-register dynamic event handlers with new priorities
    if (dynamicEventHandler != null) {
      dynamicEventHandler.registerHandlers();
    }

    debug("Plugin reloaded successfully");
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
