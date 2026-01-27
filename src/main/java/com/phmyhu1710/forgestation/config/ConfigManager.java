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

/**
 * Manages all configuration files
 */
public class ConfigManager {

    private final ForgeStationPlugin plugin;
    
    // Cached configurations
    private FileConfiguration mainConfig;
    private FileConfiguration messagesConfig;
    private FileConfiguration rankMultipliersConfig;
    private FileConfiguration upgradesConfig;
    
    // Recipe configs (from recipes/ folder)
    private final Map<String, FileConfiguration> recipeConfigs = new HashMap<>();
    
    // Smelting configs (from smelting/ folder)
    private final Map<String, FileConfiguration> smeltingConfigs = new HashMap<>();
    
    // Menu configs (from menus/ folder)
    private final Map<String, FileConfiguration> menuConfigs = new HashMap<>();

    public ConfigManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        // Save defaults
        plugin.saveDefaultConfig();
        saveDefaultResource("messages.yml");
        saveDefaultResource("rank-multipliers.yml");
        saveDefaultResource("upgrades.yml");
        
        // Save recipe defaults
        saveDefaultResource("recipes/exchange.yml");
        saveDefaultResource("recipes/weapons.yml");
        saveDefaultResource("recipes/blocks.yml");
        
        // Save smelting defaults
        saveDefaultResource("smelting/ores.yml");
        
        // Save menu defaults
        saveDefaultResource("menus/main-menu.yml");
        saveDefaultResource("menus/crafting-menu.yml");
        saveDefaultResource("menus/smelting-menu.yml");
        saveDefaultResource("menus/upgrade-menu.yml");
        
        // Load main configs
        plugin.reloadConfig();
        mainConfig = plugin.getConfig();
        messagesConfig = loadConfig("messages.yml");
        rankMultipliersConfig = loadConfig("rank-multipliers.yml");
        upgradesConfig = loadConfig("upgrades.yml");
        
        // Load recipe folder
        loadFolder("recipes", recipeConfigs);
        
        // Load smelting folder
        loadFolder("smelting", smeltingConfigs);
        
        // Load menus folder
        loadFolder("menus", menuConfigs);
        
        plugin.getLogger().info("Loaded configurations:");
        plugin.getLogger().info("  - " + recipeConfigs.size() + " recipe files");
        plugin.getLogger().info("  - " + smeltingConfigs.size() + " smelting files");
        plugin.getLogger().info("  - " + menuConfigs.size() + " menu files");
    }

    private void saveDefaultResource(String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(resourcePath, false);
        }
    }

    private FileConfiguration loadConfig(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            saveDefaultResource(fileName);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void loadFolder(String folderName, Map<String, FileConfiguration> target) {
        target.clear();
        File folder = new File(plugin.getDataFolder(), folderName);
        plugin.getLogger().info("Loading folder: " + folder.getAbsolutePath());
        
        if (!folder.exists()) {
            folder.mkdirs();
            plugin.getLogger().info("Created folder: " + folderName);
        }
        
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("No files found in folder: " + folderName);
            return;
        }
        
        plugin.getLogger().info("Found " + files.length + " yml files in " + folderName);
        
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            plugin.getLogger().info("Loading file: " + file.getName());
            
            try {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                
                // Apply defaults from jar if exists
                InputStream defStream = plugin.getResource(folderName + "/" + file.getName());
                if (defStream != null) {
                    YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                    config.setDefaults(defConfig);
                }
                
                target.put(name, config);
                plugin.getLogger().info("Successfully loaded: " + name);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load " + file.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void saveConfig(String fileName, FileConfiguration config) {
        try {
            config.save(new File(plugin.getDataFolder(), fileName));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + fileName + ": " + e.getMessage());
        }
    }

    // Getters
    public FileConfiguration getMainConfig() {
        return mainConfig;
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public FileConfiguration getRankMultipliersConfig() {
        return rankMultipliersConfig;
    }

    public FileConfiguration getUpgradesConfig() {
        return upgradesConfig;
    }

    public Map<String, FileConfiguration> getRecipeConfigs() {
        return recipeConfigs;
    }

    public Map<String, FileConfiguration> getSmeltingConfigs() {
        return smeltingConfigs;
    }

    public Map<String, FileConfiguration> getMenuConfigs() {
        return menuConfigs;
    }
    
    public FileConfiguration getMenuConfig(String menuName) {
        return menuConfigs.get(menuName.replace(".yml", ""));
    }
}
