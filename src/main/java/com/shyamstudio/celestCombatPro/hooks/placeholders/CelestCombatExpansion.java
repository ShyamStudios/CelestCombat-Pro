package com.shyamstudio.celestCombatPro.hooks.placeholders;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.api.CelestCombatAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class CelestCombatExpansion extends PlaceholderExpansion {
    private final CelestCombatPro plugin;

    public CelestCombatExpansion(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "celestcombat";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        switch (identifier.toLowerCase()) {
            case "in_combat":
                return CelestCombatAPI.getCombatAPI().isInCombat(player) ? "true" : "false";
            
            case "combat_time":
                return String.valueOf(CelestCombatAPI.getCombatAPI().getRemainingCombatTime(player));
            
            case "combat_time_formatted":
                int seconds = CelestCombatAPI.getCombatAPI().getRemainingCombatTime(player);
                return formatTime(seconds);
            
            case "pearl_cooldown":
                return String.valueOf(plugin.getCombatManager().getRemainingEnderPearlCooldown(player));
            
            case "pearl_cooldown_formatted":
                int pearlSeconds = plugin.getCombatManager().getRemainingEnderPearlCooldown(player);
                return formatTime(pearlSeconds);
            
            case "trident_cooldown":
                return String.valueOf(plugin.getCombatManager().getRemainingTridentCooldown(player));
            
            case "trident_cooldown_formatted":
                int tridentSeconds = plugin.getCombatManager().getRemainingTridentCooldown(player);
                return formatTime(tridentSeconds);
            
            case "opponent":
                Player opponent = CelestCombatAPI.getCombatAPI().getCombatOpponent(player);
                return opponent != null ? opponent.getName() : "None";
            
            case "has_pearl_cooldown":
                return plugin.getCombatManager().isEnderPearlOnCooldown(player) ? "true" : "false";
            
            case "has_trident_cooldown":
                return plugin.getCombatManager().isTridentOnCooldown(player) ? "true" : "false";
            
            default:
                return null;
        }
    }

    private String formatTime(int seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }
}
