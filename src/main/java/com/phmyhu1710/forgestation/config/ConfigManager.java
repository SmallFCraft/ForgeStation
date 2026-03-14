package com.phmyhu1710.forgestation.config;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages all configuration files for ForgeStation.
 * - Single loadFolderConfigs() for menus/recipes/smelting (DRY, merge defaults).
 * - saveConfig() for persisting config changes.
 * - getMenuConfig(name) for direct menu lookup.
 */
public class ConfigManager {

    private static final String[] DEFAULT_MENUS = {
        "main-menu.yml", "crafting-menu.yml", "smelting-menu.yml", "upgrade-menu.yml"
    };
    private static final String[] DEFAULT_RECIPES = {
        "blocks.yml", "dungeon.yml", "event_tet.yml", "exchange.yml", "luyendan.yml"
    };
    private static final String[] DEFAULT_SMELTING = { "metals.yml", "ores.yml" };

    private final ForgeStationPlugin plugin;

    private FileConfiguration mainConfig;
    private final Map<String, FileConfiguration> menuConfigs = new HashMap<>();
    private final Map<String, FileConfiguration> recipeConfigs = new HashMap<>();
    private final Map<String, FileConfiguration> smeltingConfigs = new HashMap<>();
    private FileConfiguration upgradesConfig;
    private FileConfiguration rankMultipliersConfig;
    private FileConfiguration messagesConfig;

    public ConfigManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load all configuration files
     */
    public void loadAll() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        mainConfig = plugin.getConfig();

        loadFolderConfigs("menus", DEFAULT_MENUS, menuConfigs);
        loadFolderConfigs("recipes", DEFAULT_RECIPES, recipeConfigs);
        loadFolderConfigs("smelting", DEFAULT_SMELTING, smeltingConfigs);

        loadUpgradesConfig();
        loadRankMultipliersConfig();
        loadMessagesConfig();
    }

    /**
     * Load all YAML configs from a folder: save defaults if missing, then load each file
     * with defaults merged from JAR. Used for menus, recipes, smelting.
     */
    private void loadFolderConfigs(String folderName, String[] defaultFiles,
                                   Map<String, FileConfiguration> target) {
        target.clear();
        File folder = new File(plugin.getDataFolder(), folderName);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        // Ensure default files exist (saveResource from JAR)
        for (String fileName : defaultFiles) {
            File file = new File(folder, fileName);
            if (!file.exists()) {
                plugin.saveResource(folderName + "/" + fileName, false);
            }
        }

        // Load all .yml in folder (defaults + any extra added by user)
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            plugin.debug("ConfigManager: no yml files in " + folderName);
            return;
        }

        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            // Merge defaults from JAR so missing keys are filled from default
            String resourcePath = folderName + "/" + file.getName();
            try (InputStream defStream = plugin.getResource(resourcePath)) {
                if (defStream != null) {
                    YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                    config.setDefaults(defConfig);
                    config.options().copyDefaults(true);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not load defaults for " + resourcePath, e);
            }
            target.put(name, config);
        }
        plugin.debug("ConfigManager: loaded " + target.size() + " configs from " + folderName);
    }

    private void loadUpgradesConfig() {
        saveDefaultResource("upgrades.yml");
        File file = new File(plugin.getDataFolder(), "upgrades.yml");
        upgradesConfig = YamlConfiguration.loadConfiguration(file);
    }

    private void loadRankMultipliersConfig() {
        saveDefaultResource("rank-multipliers.yml");
        File file = new File(plugin.getDataFolder(), "rank-multipliers.yml");
        rankMultipliersConfig = YamlConfiguration.loadConfiguration(file);
        mergeDefaultsFromResource("rank-multipliers.yml", rankMultipliersConfig);
    }

    private void loadMessagesConfig() {
        saveDefaultResource("messages.yml");
        File file = new File(plugin.getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(file);
        mergeDefaultsFromResource("messages.yml", messagesConfig);
    }

    private void saveDefaultResource(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(path, false);
        }
    }

    private void mergeDefaultsFromResource(String resourcePath, FileConfiguration config) {
        try (InputStream defStream = plugin.getResource(resourcePath)) {
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
                config.setDefaults(defConfig);
                config.options().copyDefaults(true);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not merge defaults for " + resourcePath, e);
        }
    }

    /**
     * Save a config to a file in the plugin data folder (e.g. "messages.yml", "upgrades.yml").
     */
    public void saveConfig(String fileName, FileConfiguration config) {
        try {
            config.save(new File(plugin.getDataFolder(), fileName));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + fileName + ": " + e.getMessage());
        }
    }

    // --- Getters ---

    public FileConfiguration getMainConfig() {
        return mainConfig;
    }

    public Map<String, FileConfiguration> getMenuConfigs() {
        return menuConfigs;
    }

    /**
     * Get a single menu config by name (with or without .yml).
     */
    public FileConfiguration getMenuConfig(String menuName) {
        return menuConfigs.get(menuName == null ? null : menuName.replace(".yml", ""));
    }

    public Map<String, FileConfiguration> getRecipeConfigs() {
        return recipeConfigs;
    }

    public Map<String, FileConfiguration> getSmeltingConfigs() {
        return smeltingConfigs;
    }

    public FileConfiguration getUpgradesConfig() {
        return upgradesConfig;
    }

    public FileConfiguration getRankMultipliersConfig() {
        return rankMultipliersConfig;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }
}
