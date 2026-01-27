package com.phmyhu1710.forgestation.crafting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.expression.ExpressionParser;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages crafting recipes
 */
public class RecipeManager {

    private final ForgeStationPlugin plugin;
    private final Map<String, Recipe> recipes = new HashMap<>();

    public RecipeManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        recipes.clear();
        
        Map<String, FileConfiguration> recipeConfigs = plugin.getConfigManager().getRecipeConfigs();
        
        plugin.getLogger().info("[RecipeManager] Loading from " + recipeConfigs.size() + " recipe files: " + recipeConfigs.keySet());
        
        for (Map.Entry<String, FileConfiguration> entry : recipeConfigs.entrySet()) {
            String fileName = entry.getKey();
            FileConfiguration config = entry.getValue();
            
            plugin.getLogger().info("[RecipeManager] Processing file: " + fileName);
            
            ConfigurationSection recipesSection = config.getConfigurationSection("recipes");
            if (recipesSection == null) {
                plugin.getLogger().warning("[RecipeManager] No 'recipes' section found in: " + fileName);
                continue;
            }
            
            plugin.getLogger().info("[RecipeManager] Found recipes section with keys: " + recipesSection.getKeys(false));
            
            for (String recipeId : recipesSection.getKeys(false)) {
                ConfigurationSection recipeConfig = recipesSection.getConfigurationSection(recipeId);
                if (recipeConfig == null) {
                    plugin.getLogger().warning("[RecipeManager] Recipe config is null for: " + recipeId);
                    continue;
                }
                
                if (!recipeConfig.getBoolean("enabled", true)) {
                    plugin.getLogger().info("[RecipeManager] Recipe disabled: " + recipeId);
                    continue;
                }
                
                try {
                    Recipe recipe = new Recipe(recipeId, fileName, recipeConfig);
                    recipes.put(recipeId, recipe);
                    plugin.getLogger().info("[RecipeManager] Loaded recipe: " + recipeId + " from " + fileName);
                } catch (Exception e) {
                    plugin.getLogger().warning("[RecipeManager] Failed to load recipe " + recipeId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        plugin.getLogger().info("Loaded " + recipes.size() + " crafting recipes");
    }

    public Recipe getRecipe(String id) {
        return recipes.get(id);
    }

    public Map<String, Recipe> getAllRecipes() {
        return new HashMap<>(recipes);
    }

    public Map<String, Recipe> getRecipesByFolder(String folder) {
        Map<String, Recipe> result = new HashMap<>();
        for (Map.Entry<String, Recipe> entry : recipes.entrySet()) {
            if (entry.getValue().getSourceFile().equals(folder)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public int getRecipeCount() {
        return recipes.size();
    }

    public java.util.Set<String> getRecipeIds() {
        return recipes.keySet();
    }

    /**
     * Get the rank multiplier for a player and recipe
     */
    public double getRankMultiplier(Player player, Recipe recipe) {
        if (!recipe.usesRankMultiplier()) {
            return 1.0;
        }
        
        FileConfiguration rankConfig = plugin.getConfigManager().getRankMultipliersConfig();
        
        // Get priority order
        java.util.List<String> priority = rankConfig.getStringList("priority-order");
        
        for (String rankId : priority) {
            ConfigurationSection rankSection = rankConfig.getConfigurationSection("ranks." + rankId);
            if (rankSection == null) continue;
            
            String permission = rankSection.getString("permission");
            if (permission != null && player.hasPermission(permission)) {
                // Check for recipe-specific override
                double multiplier = rankSection.getDouble("multiplier", 1.0);
                
                ConfigurationSection overrides = rankSection.getConfigurationSection("recipe-overrides." + recipe.getId());
                if (overrides != null) {
                    multiplier = overrides.getDouble("multiplier", multiplier);
                }
                
                return multiplier;
            }
        }
        
        return 1.0;
    }

    /**
     * Get duration for recipe (in seconds) - with upgrade reduction
     * Used for both crafting and exchange recipes
     */
    public int getDuration(Player player, Recipe recipe) {
        // 1. Get base duration
        int baseDuration = getBaseDuration(recipe);
        
        // 2. Get crafting level của player
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        int craftingLevel = data.getUpgradeLevel("crafting_speed");
        
        if (craftingLevel <= 0) return baseDuration;
        
        // 3. Apply reduction from upgrade config
        var upgrade = plugin.getUpgradeManager().getUpgrade("crafting_speed");
        if (upgrade != null) {
            return upgrade.applyDurationReduction(baseDuration, craftingLevel);
        }
        
        // Fallback: use expression if no upgrade config
        Map<String, Double> vars = ExpressionParser.createVariables(craftingLevel, 0, craftingLevel);
        return ExpressionParser.parseTimeSeconds(recipe.getCooldownExpression(), vars);
    }
    
    /**
     * Alias for getDuration (backward compatibility)
     */
    public int getCooldown(Player player, Recipe recipe) {
        return getDuration(player, recipe);
    }
    
    /**
     * Get base duration (without player upgrades)
     */
    public int getBaseDuration(Recipe recipe) {
        Map<String, Double> vars = ExpressionParser.createVariables(0, 0, 0);
        int duration = ExpressionParser.parseTimeSeconds(recipe.getCooldownExpression(), vars);
        plugin.debug("Recipe " + recipe.getId() + " duration: " + duration + "s");
        return duration;
    }
    
    /**
     * Alias for getBaseDuration (backward compatibility)
     */
    public int getBaseCooldown(Recipe recipe) {
        return getBaseDuration(recipe);
    }
}
