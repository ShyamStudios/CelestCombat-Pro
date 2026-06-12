package com.shyamstudio.celestCombatPro.language;

import org.bukkit.configuration.file.YamlConfiguration;

public record LocaleData(YamlConfiguration messages, YamlConfiguration gui, YamlConfiguration formatting,
                         YamlConfiguration items) {
}