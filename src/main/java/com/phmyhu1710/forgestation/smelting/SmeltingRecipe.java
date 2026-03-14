package com.phmyhu1710.forgestation.smelting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a smelting recipe
 */
public class SmeltingRecipe {

    private final String id;
    private final String sourceFile;
    private final String displayName;
    private final String permission;
    private final String durationExpression;
    
    // Icon
    private final Material iconMaterial;
    private final String iconMaterialString;  // Raw material string for hook support
    private final int iconModelData;  // -1 = not set
    private final int iconSlot;
    private final String iconName;
    private final List<String> iconLore;
    
    // Input
    private final String inputType;
    private final String inputMaterial;
    private final String inputMmoitemsType;
    private final String inputMmoitemsId;
    private final int inputAmount;
    
    // Output
    private final String outputType;
    private final String outputMaterial;
    private final String outputMmoitemsType;
    private final String outputMmoitemsId;
    private final int outputAmount;
    
    // Fuel
    private final boolean fuelRequired;
    private final String fuelType;
    private final String fuelMaterial;
    private final int fuelAmount;

    /** Placeholders tùy chỉnh cho icon name/lore (vd. %item_name%). */
    private final Map<String, String> placeholders;

    public SmeltingRecipe(String id, String sourceFile, ConfigurationSection config) {
        this.id = id;
        this.sourceFile = sourceFile;
        this.displayName = config.getString("display-name", id);
        this.permission = config.getString("permission", "");
        this.durationExpression = config.getString("duration", "30");
        
        // Icon
        ConfigurationSection iconSection = config.getConfigurationSection("icon");
        if (iconSection != null) {
            this.iconMaterialString = iconSection.getString("material", "FURNACE");
            // Try to parse as Material, fallback to FURNACE if it's a hook prefix
            Material parsedMat = Material.FURNACE;
            try {
                if (!iconMaterialString.contains("-")) {
                    parsedMat = Material.valueOf(iconMaterialString.toUpperCase());
                }
            } catch (Exception e) {
                // Keep FURNACE for hook items
            }
            this.iconMaterial = parsedMat;
            this.iconModelData = iconSection.getInt("model-data", -1);
            this.iconSlot = iconSection.getInt("slot", 0);
            this.iconName = iconSection.getString("name", displayName);
            this.iconLore = iconSection.getStringList("lore");
        } else {
            this.iconMaterial = Material.FURNACE;
            this.iconMaterialString = "FURNACE";
            this.iconModelData = -1;
            this.iconSlot = 0;
            this.iconName = displayName;
            this.iconLore = new ArrayList<>();
        }
        
        // Input
        ConfigurationSection inputSection = config.getConfigurationSection("input");
        if (inputSection != null) {
            this.inputType = inputSection.getString("type", "VANILLA").toUpperCase();
            this.inputMaterial = inputSection.getString("material", "STONE");
            this.inputMmoitemsType = inputSection.getString("mmoitems-type", "");
            this.inputMmoitemsId = inputSection.getString("mmoitems-id", "");
            this.inputAmount = inputSection.getInt("amount", 1);
        } else {
            this.inputType = "VANILLA";
            this.inputMaterial = "STONE";
            this.inputMmoitemsType = "";
            this.inputMmoitemsId = "";
            this.inputAmount = 1;
        }
        
        // Output
        ConfigurationSection outputSection = config.getConfigurationSection("output");
        if (outputSection != null) {
            this.outputType = outputSection.getString("type", "VANILLA").toUpperCase();
            this.outputMaterial = outputSection.getString("material", "STONE");
            this.outputMmoitemsType = outputSection.getString("mmoitems-type", "");
            this.outputMmoitemsId = outputSection.getString("mmoitems-id", "");
            this.outputAmount = outputSection.getInt("amount", 1);
        } else {
            this.outputType = "VANILLA";
            this.outputMaterial = "STONE";
            this.outputMmoitemsType = "";
            this.outputMmoitemsId = "";
            this.outputAmount = 1;
        }
        
        // Fuel
        ConfigurationSection fuelSection = config.getConfigurationSection("fuel");
        if (fuelSection != null && fuelSection.getBoolean("enabled", false)) {
            this.fuelRequired = true;
            this.fuelType = fuelSection.getString("type", "VANILLA").toUpperCase();
            this.fuelMaterial = fuelSection.getString("material", "COAL");
            this.fuelAmount = fuelSection.getInt("amount", 1);
        } else {
            this.fuelRequired = false;
            this.fuelType = "VANILLA";
            this.fuelMaterial = "COAL";
            this.fuelAmount = 0;
        }

        // Placeholders: key-value dùng trong icon name/lore
        Map<String, String> ph = new HashMap<>();
        ConfigurationSection phSection = config.getConfigurationSection("placeholders");
        if (phSection != null) {
            for (String key : phSection.getKeys(false)) {
                if (phSection.isString(key)) {
                    ph.put(key, phSection.getString(key, ""));
                }
            }
        }
        this.placeholders = Collections.unmodifiableMap(ph);
    }

    // Getters
    public String getId() { return id; }
    public String getSourceFile() { return sourceFile; }
    public String getDisplayName() { return displayName; }
    public String getPermission() { return permission; }
    public String getDurationExpression() { return durationExpression; }
    
    public Material getIconMaterial() { return iconMaterial; }
    public String getIconMaterialString() { return iconMaterialString; }
    public int getIconModelData() { return iconModelData; }
    public int getIconSlot() { return iconSlot; }
    public String getIconName() { return iconName; }
    public List<String> getIconLore() { return iconLore; }
    /** Placeholders tùy chỉnh (key → value). Trong icon name/lore dùng %key%. */
    public Map<String, String> getPlaceholders() { return placeholders; }

    public String getInputType() { return inputType; }
    public String getInputMaterial() { return inputMaterial; }
    public String getInputMmoitemsType() { return inputMmoitemsType; }
    public String getInputMmoitemsId() { return inputMmoitemsId; }
    public int getInputAmount() { return inputAmount; }
    
    public String getOutputType() { return outputType; }
    public String getOutputMaterial() { return outputMaterial; }
    public String getOutputMmoitemsType() { return outputMmoitemsType; }
    public String getOutputMmoitemsId() { return outputMmoitemsId; }
    public int getOutputAmount() { return outputAmount; }
    
    public boolean isFuelRequired() { return fuelRequired; }
    public String getFuelType() { return fuelType; }
    public String getFuelMaterial() { return fuelMaterial; }
    public int getFuelAmount() { return fuelAmount; }
}
