package com.shyamstudio.celestCombatPro.commands.subcommands;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.commands.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SafeDisconnectCommand extends BaseCommand {

    public SafeDisconnectCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("§cUsage: /celestcombat safeDisconnect <player>");
            return true;
        }

        Map<String, String> placeholders = new HashMap<>();

        // Find the player
        Player target = Bukkit.getPlayer(args[0]);

        // Validate player
        if (target == null) {
            placeholders.put("player", args[0]);
            messageService.sendMessage(sender, "player_not_found", placeholders);
            return true;
        }

        // Check if player is online
        if (!target.isOnline()) {
            placeholders.put("player", target.getName());
            messageService.sendMessage(sender, "player_not_found", placeholders);
            return true;
        }

        // Use the API to safely disconnect the player
        plugin.getCombatAPI().disconnectPlayerSafely(target);

        // Send success message to command sender
        placeholders.put("player", target.getName());
        messageService.sendMessage(sender, "safe_disconnect_success", placeholders);

        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombat.command.safedisconnect";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // Console can also safely disconnect players
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // Suggest online player names
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return super.tabComplete(sender, args);
    }
}