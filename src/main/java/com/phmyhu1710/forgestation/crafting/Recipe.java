package com.phmyhu1710.forgestation.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a crafting recipe
 */
public class Recipe {

    private final String id;
    private final String sourceFile;
    private final String displayName;
    private final String permission;
    private final String cooldownExpression;
    private final boolean useRankMultiplier;
    /** Tỉ lệ thành công 0-100. Mặc định 100 = chắc chắn. Dùng cho luyendan v.v. */
    private final double successChance;
    private final boolean bulkExchange;
    
    // Icon
    private final Material iconMaterial;
    private final String iconMaterialString;  // Raw material string for hook support
    private final int iconModelData;  // -1 = not set
    private final int iconSlot;
    private final boolean iconGlow;
    private final String iconName;
    private final List<String> iconLore;
    
    // Ingredients
    private final List<Ingredient> ingredients;
    
    // Cost
    private final String vaultCostExpr;
    private final String playerPointsCostExpr;
    private final String coinEngineCurrency;
    private final String coinEngineCostExpr;
    
    // Result
    private final RecipeResult result;
    
    // Rewards (for exchange recipes)
    private final List<Reward> rewards;
    
    // Commands
    private final List<String> commandsOnSuccess;

    public Recipe(String id, String sourceFile, ConfigurationSection config) {
        this.id = id;
        this.sourceFile = sourceFile;
        this.displayName = config.getString("display-name", id);
        this.permission = config.getString("permission", "");
        // Support both "duration" and "cooldown" keys, and both integer/string values
        // Priority: duration > cooldown (duration is the new preferred key)
        Object durationObj = config.get("duration");
        if (durationObj == null) {
            durationObj = config.get("cooldown", "0");
        }
        this.cooldownExpression = durationObj != null ? String.valueOf(durationObj) : "0";
        this.useRankMultiplier = config.getBoolean("use-rank-multiplier", false);
        this.bulkExchange = config.getBoolean("bulk-exchange", false);
        Object chanceObj = config.get("chance");
        if (chanceObj != null) {
            this.successChance = chanceObj instanceof Number ? ((Number) chanceObj).doubleValue() : 100.0;
        } else {
            this.successChance = 100.0;
        }
        
        // Icon
        ConfigurationSection iconSection = config.getConfigurationSection("icon");
        if (iconSection != null) {
            this.iconMaterialString = iconSection.getString("material", "STONE");
            // Try to parse as Material, fallback to STONE if it's a hook prefix
            Material parsedMat = Material.STONE;
            try {
                if (!iconMaterialString.contains("-")) {
                    parsedMat = Material.valueOf(iconMaterialString.toUpperCase());
                }
            } catch (Exception e) {
                // Keep STONE for hook items
            }
            this.iconMaterial = parsedMat;
            this.iconModelData = iconSection.getInt("model-data", -1);
            this.iconSlot = iconSection.getInt("slot", 0);
            this.iconGlow = iconSection.getBoolean("glow", false);
            this.iconName = iconSection.getString("name", displayName);
            this.iconLore = iconSection.getStringList("lore");
        } else {
            this.iconMaterial = Material.STONE;
            this.iconMaterialString = "STONE";
            this.iconModelData = -1;
            this.iconSlot = 0;
            this.iconGlow = false;
            this.iconName = displayName;
            this.iconLore = new ArrayList<>();
        }
        
        // Ingredients
        this.ingredients = new ArrayList<>();
        ConfigurationSection ingredientsSection = config.getConfigurationSection("ingredients");
        if (ingredientsSection != null) {
            for (String key : ingredientsSection.getKeys(false)) {
                ConfigurationSection ingConfig = ingredientsSection.getConfigurationSection(key);
                if (ingConfig != null) {
                    ingredients.add(new Ingredient(ingConfig));
                }
            }
        }
        
        // Input (for exchange recipes)
        ConfigurationSection inputSection = config.getConfigurationSection("input");
        if (inputSection != null) {
            ingredients.add(new Ingredient(inputSection));
        }
        
        // Cost
        ConfigurationSection costSection = config.getConfigurationSection("cost");
        if (costSection != null) {
            this.vaultCostExpr = costSection.getString("vault", "0");
            this.playerPointsCostExpr = String.valueOf(costSection.getInt("playerpoints", 0));
            
            ConfigurationSection coinEngineSection = costSection.getConfigurationSection("coinengine");
            if (coinEngineSection != null) {
                this.coinEngineCurrency = coinEngineSection.getString("currency", "");
                this.coinEngineCostExpr = coinEngineSection.getString("amount", "0");
            } else {
                this.coinEngineCurrency = "";
                this.coinEngineCostExpr = "0";
            }
        } else {
            this.vaultCostExpr = "0";
            this.playerPointsCostExpr = "0";
            this.coinEngineCurrency = "";
            this.coinEngineCostExpr = "0";
        }
        
        // Result
        ConfigurationSection resultSection = config.getConfigurationSection("result");
        if (resultSection != null) {
            this.result = new RecipeResult(resultSection);
        } else {
            this.result = null;
        }
        
        // Rewards (for exchange recipes)
        this.rewards = new ArrayList<>();
        if (config.contains("rewards")) {
            for (Map<?, ?> rewardMap : config.getMapList("rewards")) {
                rewards.add(new Reward(rewardMap));
            }
        }
        
        // Commands
        this.commandsOnSuccess = config.getStringList("commands-on-success");
    }

    // Getters
    public String getId() { return id; }
    public String getSourceFile() { return sourceFile; }
    public String getDisplayName() { return displayName; }
    public String getPermission() { return permission; }
    public String getCooldownExpression() { return cooldownExpression; }
    public boolean usesRankMultiplier() { return useRankMultiplier; }
    public boolean isBulkExchange() { return bulkExchange; }
    /** Tỉ lệ thành công base (0-100). 100 = chắc chắn. */
    public double getSuccessChance() { return successChance; }
    
    public Material getIconMaterial() { return iconMaterial; }
    public String getIconMaterialString() { return iconMaterialString; }
    public int getIconModelData() { return iconModelData; }
    public int getIconSlot() { return iconSlot; }
    public boolean isIconGlow() { return iconGlow; }
    public String getIconName() { return iconName; }
    public List<String> getIconLore() { return iconLore; }
    
    public List<Ingredient> getIngredients() { return ingredients; }
    
    public String getVaultCostExpr() { return vaultCostExpr; }
    public String getPlayerPointsCostExpr() { return playerPointsCostExpr; }
    public String getCoinEngineCurrency() { return coinEngineCurrency; }
    public String getCoinEngineCostExpr() { return coinEngineCostExpr; }
    
    public RecipeResult getResult() { return result; }
    public List<Reward> getRewards() { return rewards; }
    public List<String> getCommandsOnSuccess() { return commandsOnSuccess; }
    
    public boolean isExchangeRecipe() {
        return !rewards.isEmpty();
    }

    /**
     * Ingredient class
     */
    public static class Ingredient {
        private final String type; // VANILLA, MMOITEMS, EXTRA_STORAGE
        private final String material;
        private final String mmoitemsType;
        private final String mmoitemsId;
        private final String storageId;
        private final int amount;
        private final int baseAmount;
        private final String name;

        public Ingredient(ConfigurationSection config) {
            this.type = config.getString("type", "VANILLA").toUpperCase();
            this.material = config.getString("material", "STONE");
            this.mmoitemsType = config.getString("mmoitems-type", "");
            this.mmoitemsId = config.getString("mmoitems-id", "");
            this.storageId = config.getString("storage-id", "");
            this.amount = config.getInt("amount", 1);
            this.baseAmount = config.getInt("base-amount", amount);
            this.name = config.getString("name", null);
        }

        public String getType() { return type; }
        public String getMaterial() { return material; }
        public String getMmoitemsType() { return mmoitemsType; }
        public String getMmoitemsId() { return mmoitemsId; }
        public String getStorageId() { return storageId; }
        public int getAmount() { return amount; }
        public int getBaseAmount() { return baseAmount; }
        public String getName() { return name; }
    }

    /**
     * Recipe result class
     */
    public static class RecipeResult {
        private final String type;
        private final String material;
        private final String mmoitemsType;
        private final String mmoitemsId;
        private final String storageId;
        private final int amount;
        private final String name;
        private final List<String> lore;
        private final List<String> enchantments;

        public RecipeResult(ConfigurationSection config) {
            this.type = config.getString("type", "VANILLA").toUpperCase();
            this.material = config.getString("material", "STONE");
            this.mmoitemsType = config.getString("mmoitems-type", "");
            this.mmoitemsId = config.getString("mmoitems-id", "");
            this.storageId = config.getString("storage-id", "");
            this.amount = config.getInt("amount", 1);
            this.name = config.getString("name", null);
            this.lore = config.getStringList("lore");
            this.enchantments = config.getStringList("enchantments");
        }

        public String getType() { return type; }
        public String getMaterial() { return material; }
        public String getMmoitemsType() { return mmoitemsType; }
        public String getMmoitemsId() { return mmoitemsId; }
        public String getStorageId() { return storageId; }
        public int getAmount() { return amount; }
        public String getName() { return name; }
        public List<String> getLore() { return lore; }
        public List<String> getEnchantments() { return enchantments; }
    }

    /**
     * Reward class (for exchange recipes)
     * BUGFIX: Added material field for EXTRA_STORAGE support
     */
    public static class Reward {
        private final String type; // PLAYER_POINTS, VAULT, COINENGINE, COMMAND, EXTRA_STORAGE
        private final int baseAmount;
        private final String currency;
        private final String command;
        private final String material; // For EXTRA_STORAGE type

        public Reward(Map<?, ?> map) {
            Object typeObj = map.get("type");
            this.type = typeObj != null ? (String) typeObj : "VAULT";
            
            Object amountObj = map.get("base-amount");
            this.baseAmount = amountObj != null ? ((Number) amountObj).intValue() : 0;
            
            Object currencyObj = map.get("currency");
            this.currency = currencyObj != null ? (String) currencyObj : "";
            
            Object commandObj = map.get("command");
            this.command = commandObj != null ? (String) commandObj : "";
            
            // BUGFIX: Parse material for EXTRA_STORAGE rewards
            Object materialObj = map.get("material");
            this.material = materialObj != null ? (String) materialObj : "";
        }

        public String getType() { return type; }
        public int getBaseAmount() { return baseAmount; }
        public String getCurrency() { return currency; }
        public String getCommand() { return command; }
        public String getMaterial() { return material; }
    }
}
