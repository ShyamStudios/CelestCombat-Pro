package com.shyamstudio.celestCombatPro.commands.subcommands;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.commands.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class DebugBarrierCommand extends BaseCommand {

    public DebugBarrierCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public String getPermission() {
        return "celestcombat.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }
        
        Player player = (Player) sender;
        
        if (plugin.getWorldGuardHook() == null) {
            player.sendMessage("§c[Debug] WorldGuard hook is NULL! WorldGuard integration is not enabled.");
            player.sendMessage("§cCheck: 1) WorldGuard installed, 2) safezone_protection.enabled: true");
            return true;
        }
        
        player.sendMessage("§a[Debug] WorldGuard Barrier System Status:");
        player.sendMessage("§7- WorldGuard Hook: §aActive");
        player.sendMessage("§7- Your World: §e" + player.getWorld().getName());
        player.sendMessage("§7- Your Location: §e" + player.getLocation().getBlockX() + ", " 
                + player.getLocation().getBlockY() + ", " + player.getLocation().getBlockZ());
        player.sendMessage("§7- In Combat: §e" + plugin.getCombatManager().isInCombat(player));
        player.sendMessage("§7- In Safe Zone: §e" + plugin.getWorldGuardHook().isLocationInSafeZone(player.getLocation()));
        
        player.sendMessage("§7");
        player.sendMessage("§7To see detailed logs, enable debug mode in config.yml");
        player.sendMessage("§7Then check console for [BorderCache] and [Barrier] messages");
        
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
