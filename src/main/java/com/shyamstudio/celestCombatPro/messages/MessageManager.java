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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {
    private final JavaPlugin plugin;
    private volatile FileConfiguration messages;
    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // Colorized templates are computed once per reload; placeholders are applied afterwards
    private final Map<String, String> chatTemplates = new ConcurrentHashMap<>();
    private final Map<String, String> titleTemplates = new ConcurrentHashMap<>();
    private final Map<String, String> subtitleTemplates = new ConcurrentHashMap<>();
    private final Map<String, String> actionBarTemplates = new ConcurrentHashMap<>();
    private final Map<String, String> sounds = new ConcurrentHashMap<>();

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

        chatTemplates.clear();
        titleTemplates.clear();
        subtitleTemplates.clear();
        actionBarTemplates.clear();
        sounds.clear();
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
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }

        FileConfiguration config = messages;
        if (!config.contains(key)) {
            // Fallback: send hardcoded message with key name
            String fallbackMessage = "&c[CelestCombat] &7Message not configured: &e" + key;
            sender.sendMessage(translateColors(fallbackMessage));
            plugin.getLogger().warning("Message key not found: " + key + " - Sent fallback message to player");
            return;
        }

        // Check if message is enabled
        if (config.contains(key + ".enabled") && !config.getBoolean(key + ".enabled")) {
            return;
        }

        // Send chat message
        String message = config.getString(key + ".message");
        if (message != null) {
            String template = chatTemplates.get(key);
            if (template == null) {
                template = translateColors(config.getString("prefix", "") + message);
                chatTemplates.put(key, template);
            }
            sender.sendMessage(applyPlaceholders(template, placeholders));
        }

        // Player-specific features
        if (sender instanceof Player player) {
            // Title and subtitle
            String title = getTemplate(config, titleTemplates, key, ".title");
            String subtitle = getTemplate(config, subtitleTemplates, key, ".subtitle");
            if (title != null || subtitle != null) {
                String finalTitle = title != null ? applyPlaceholders(title, placeholders) : "";
                String finalSubtitle = subtitle != null ? applyPlaceholders(subtitle, placeholders) : "";
                player.sendTitle(finalTitle, finalSubtitle, 10, 70, 20);
            }

            // Action bar
            String actionBar = getTemplate(config, actionBarTemplates, key, ".action_bar");
            if (actionBar != null) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        TextComponent.fromLegacyText(applyPlaceholders(actionBar, placeholders)));
            }

            // Sound
            String sound = sounds.get(key);
            if (sound == null) {
                String configured = config.getString(key + ".sound");
                if (configured != null) {
                    sounds.put(key, configured);
                    sound = configured;
                }
            }
            if (sound != null) {
                try {
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid sound for key " + key + ": " + sound);
                }
            }
        }
    }

    private String getTemplate(FileConfiguration config, Map<String, String> cache, String key, String suffix) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String raw = config.getString(key + suffix);
        if (raw == null) {
            return null;
        }
        String colorized = translateColors(raw);
        cache.put(key, colorized);
        return colorized;
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
