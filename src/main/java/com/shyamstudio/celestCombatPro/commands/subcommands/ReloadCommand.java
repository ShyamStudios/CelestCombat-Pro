package com.shyamstudio.celestCombatPro.commands.subcommands;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.commands.BaseCommand;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReloadCommand extends BaseCommand {

    public ReloadCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }

        Map<String, String> placeholders = new HashMap<>();

        plugin.reload();

        // Reload config
        plugin.reloadConfig();
        plugin.getMessageService().reload();
        plugin.refreshTimeCache();

        if (plugin.getWorldGuardHook() != null) {
            plugin.getWorldGuardHook().reloadConfig();
        }
        if (plugin.getGriefPreventionHook() != null) {
            plugin.getGriefPreventionHook().reloadConfig();
        }
        if (plugin.getUxmClaimsHook() != null) {
            plugin.getUxmClaimsHook().reloadConfig();
        }

        // Reload combat manager configuration
        plugin.getCombatManager().reloadConfig();
        plugin.getKillRewardManager().loadConfig();
        plugin.getNewbieProtectionManager().reloadConfig();
        plugin.getCombatListeners().reload();

        // Send success message
        messageService.sendMessage(sender, "config_reloaded", placeholders);

        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombat.command.use";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // Allow console to reload the plugin
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return new ArrayList<>(); // No tab completion needed for reload
    }
}