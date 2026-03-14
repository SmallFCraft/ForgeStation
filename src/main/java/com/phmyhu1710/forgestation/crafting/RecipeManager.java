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
    private final Map<String, Recipe> recipes = new java.util.LinkedHashMap<>();

    public RecipeManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        recipes.clear();
        
        Map<String, FileConfiguration> recipeConfigs = plugin.getConfigManager().getRecipeConfigs();
        
        plugin.debug("[RecipeManager] Loading from " + recipeConfigs.size() + " recipe files: " + recipeConfigs.keySet());
        
        for (Map.Entry<String, FileConfiguration> entry : recipeConfigs.entrySet()) {
            String fileName = entry.getKey();
            FileConfiguration config = entry.getValue();
            
            plugin.debug("[RecipeManager] Processing file: " + fileName);
            
            // Register custom command(s) if set
            if (config.isList("open_command")) {
                for (String cmd : config.getStringList("open_command")) {
                     plugin.getCustomCommandManager().registerCommand(cmd, fileName.toLowerCase());
                }
            } else {
                String openCommand = config.getString("open_command");
                if (openCommand != null && !openCommand.isEmpty()) {
                    plugin.getCustomCommandManager().registerCommand(openCommand, fileName.toLowerCase());
                }
            }
            
            ConfigurationSection recipesSection = config.getConfigurationSection("recipes");
            if (recipesSection == null) {
                plugin.getLogger().warning("[RecipeManager] No 'recipes' section found in: " + fileName);
                continue;
            }
            
            plugin.debug("[RecipeManager] Found recipes section with keys: " + recipesSection.getKeys(false));
            
            for (String recipeId : recipesSection.getKeys(false)) {
                ConfigurationSection recipeConfig = recipesSection.getConfigurationSection(recipeId);
                if (recipeConfig == null) {
                    plugin.getLogger().warning("[RecipeManager] Recipe config is null for: " + recipeId);
                    continue;
                }
                
                if (!recipeConfig.getBoolean("enabled", true)) {
                    plugin.debug("[RecipeManager] Recipe disabled: " + recipeId);
                    continue;
                }
                
                try {
                    Recipe recipe = new Recipe(recipeId, fileName, recipeConfig);
                    recipes.put(recipeId, recipe);
                    plugin.debug("[RecipeManager] Loaded recipe: " + recipeId + " from " + fileName);
                } catch (Exception e) {
                    plugin.getLogger().warning("[RecipeManager] Failed to load recipe " + recipeId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        plugin.debug("Loaded " + recipes.size() + " crafting recipes");
    }

    public Recipe getRecipe(String id) {
        return recipes.get(id);
    }

    public Map<String, Recipe> getAllRecipes() {
        return java.util.Collections.unmodifiableMap(recipes);
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
     * Get duration for recipe (in seconds) - with upgrade reduction.
     * Per-item duration: total = getDuration(...) * batchCount (crafting) or * actualExchanges (exchange).
     */
    public int getDuration(Player player, Recipe recipe) {
        return (int) (getDurationTicks(player, recipe) / 20);
    }

    /**
     * Per-item duration in ticks. Caller must multiply by batchCount/actualExchanges for total.
     */
    public long getDurationTicks(Player player, Recipe recipe) {
        // 1. Get base duration
        long baseTicks = getBaseDurationTicks(recipe);
        
        // 2. Effective level (cap theo config max-level khi admin đổi config)
        int craftingLevel = plugin.getUpgradeManager().getEffectiveLevel(player, "crafting_speed");
        if (craftingLevel <= 0) return baseTicks;

        // 3. Apply reduction from upgrade config
        var upgrade = plugin.getUpgradeManager().getUpgrade("crafting_speed");
        if (upgrade != null) {
            return upgrade.applyDurationReduction(baseTicks, craftingLevel);
        }
        
        // Fallback
        Map<String, Double> vars = ExpressionParser.createVariables(craftingLevel, 0, craftingLevel);
        return ExpressionParser.parseTimeTicks(recipe.getCooldownExpression(), vars);
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
        return (int) (getBaseDurationTicks(recipe) / 20);
    }
    
    /**
     * TICK-SUPPORT: Get base duration in ticks
     */
    public long getBaseDurationTicks(Recipe recipe) {
        Map<String, Double> vars = ExpressionParser.createVariables(0, 0, 0);
        return ExpressionParser.parseTimeTicks(recipe.getCooldownExpression(), vars);
    }
    
    /**
     * Alias for getBaseDuration (backward compatibility)
     */
    public int getBaseCooldown(Recipe recipe) {
        return getBaseDuration(recipe);
    }
    
    /**
     * Tỉ lệ thành công hiệu dụng (0-100) = base chance + upgrade success_rate bonus
     */
    public double getEffectiveSuccessChance(Player player, Recipe recipe) {
        double base = recipe.getSuccessChance();
        if (base >= 100.0) return 100.0;
        var upgrade = plugin.getUpgradeManager().getUpgrade("success_rate");
        if (upgrade == null || !"CRAFT_SUCCESS_RATE".equals(upgrade.getEffectType())) {
            return Math.min(100, base);
        }
        int level = plugin.getUpgradeManager().getEffectiveLevel(player, "success_rate");
        double bonus = level * upgrade.getPercentPerLevel();
        return Math.min(100.0, base + bonus);
    }
}
