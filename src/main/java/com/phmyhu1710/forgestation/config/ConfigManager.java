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
        
        plugin.debug("Loaded configurations:");
        plugin.debug("  - " + recipeConfigs.size() + " recipe files");
        plugin.debug("  - " + smeltingConfigs.size() + " smelting files");
        plugin.debug("  - " + menuConfigs.size() + " menu files");
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
        plugin.debug("Loading folder: " + folder.getAbsolutePath());
        
        if (!folder.exists()) {
            folder.mkdirs();
            plugin.debug("Created folder: " + folderName);
        }
        
        // Auto-extract all files from JAR if they don't exist in data folder
        extractFolderFromJar(folderName, folder);
        
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("No files found in folder: " + folderName);
            return;
        }
        
        plugin.debug("Found " + files.length + " yml files in " + folderName);
        
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            plugin.debug("Loading file: " + file.getName());
            
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
                plugin.debug("Successfully loaded: " + name);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load " + file.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Extract all files from JAR folder to data folder if they don't exist
     */
    private void extractFolderFromJar(String folderName, File targetFolder) {
        try {
            // Try to get all resources from the folder in JAR
            java.util.jar.JarFile jarFile = new java.util.jar.JarFile(
                new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
            
            java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                
                // Check if entry is in our target folder and is a YAML file
                if (entryName.startsWith(folderName + "/") && entryName.endsWith(".yml") && !entry.isDirectory()) {
                    String fileName = entryName.substring(folderName.length() + 1);
                    File targetFile = new File(targetFolder, fileName);
                    
                    // Only extract if file doesn't exist
                    if (!targetFile.exists()) {
                        plugin.getLogger().info("Extracting " + fileName + " from JAR to " + targetFolder.getName() + "/");
                        try (InputStream is = jarFile.getInputStream(entry);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                            
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            }
            jarFile.close();
        } catch (Exception e) {
            // Fallback: If JarFile approach fails (e.g., running from IDE or classpath),
            // files will be loaded from data folder if they exist, or admin can manually copy them
            plugin.debug("Could not extract folder using JarFile (this is normal when running from IDE): " + e.getMessage());
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
