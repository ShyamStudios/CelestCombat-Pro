package com.shyamstudio.celestCombatPro.messages;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {
    private final JavaPlugin plugin;
    private FileConfiguration messages;
    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reload() {
        loadMessages();
    }

    public void sendMessage(CommandSender sender, String key) {
        sendMessage(sender, key, EMPTY_PLACEHOLDERS);
    }

    public void sendMessage(Player player, String key) {
        sendMessage(player, key, EMPTY_PLACEHOLDERS);
    }

    public void sendMessage(Player player, String key, Map<String, String> placeholders) {
        sendMessage((CommandSender) player, key, placeholders);
    }

    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        if (!messages.contains(key)) {
            // Fallback: send hardcoded message with key name
            String fallbackMessage = "&c[CelestCombat] &7Message not configured: &e" + key;
            sender.sendMessage(translateColors(fallbackMessage));
            plugin.getLogger().warning("Message key not found: " + key + " - Sent fallback message to player");
            return;
        }

        // Check if message is enabled
        if (messages.contains(key + ".enabled") && !messages.getBoolean(key + ".enabled")) {
            return;
        }

        // Send chat message
        String message = messages.getString(key + ".message");
        if (message != null) {
            String prefix = messages.getString("prefix", "");
            message = prefix + message;
            message = applyPlaceholders(message, placeholders);
            message = translateColors(message);
            sender.sendMessage(message);
        }

        // Player-specific features
        if (sender instanceof Player player) {
            // Title and subtitle
            String title = messages.getString(key + ".title");
            String subtitle = messages.getString(key + ".subtitle");
            if (title != null || subtitle != null) {
                title = title != null ? translateColors(applyPlaceholders(title, placeholders)) : "";
                subtitle = subtitle != null ? translateColors(applyPlaceholders(subtitle, placeholders)) : "";
                player.sendTitle(title, subtitle, 10, 70, 20);
            }

            // Action bar
            String actionBar = messages.getString(key + ".action_bar");
            if (actionBar != null) {
                actionBar = translateColors(applyPlaceholders(actionBar, placeholders));
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionBar));
            }

            // Sound
            String sound = messages.getString(key + ".sound");
            if (sound != null) {
                try {
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid sound for key " + key + ": " + sound);
                }
            }
        }
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) {
            return text;
        }

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return text;
    }

    private String translateColors(String text) {
        if (text == null) {
            return null;
        }

        // Translate hex colors (&#RRGGBB)
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hexCode = matcher.group(1);
            matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + hexCode).toString());
        }
        matcher.appendTail(buffer);
        text = buffer.toString();

        // Translate legacy color codes (&)
        text = org.bukkit.ChatColor.translateAlternateColorCodes('&', text);

        return text;
    }

    public boolean keyExists(String key) {
        return messages.contains(key);
    }

    /**
     * Colorize text with hex and legacy color codes
     * @param text The text to colorize
     * @return The colorized text
     */
    public String colorize(String text) {
        return translateColors(text);
    }
}
