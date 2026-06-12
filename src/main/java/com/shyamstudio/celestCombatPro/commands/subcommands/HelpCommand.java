package com.shyamstudio.celestCombatPro.commands.subcommands;

import com.shyamstudio.celestCombatPro.CelestCombatPro;
import com.shyamstudio.celestCombatPro.commands.BaseCommand;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public class HelpCommand extends BaseCommand {

    public HelpCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }
        messageService.sendMessage(sender, "help_message");
        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombat.command.use";
    }

    @Override
    public boolean isPlayerOnly() {
        return false; // Help can be shown to console as well
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return new ArrayList<>(); // No tab completion for help command
    }
}