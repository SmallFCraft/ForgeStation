package com.phmyhu1710.forgestation.menu;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu template loaded from YAML configuration
 */
public class MenuTemplate {

    private final String name;
    private final String title;
    private final int size;
    
    // Fill
    private final boolean hasFill;
    private final Material fillMaterial;
    private final String fillName;
    
    // Border
    private final boolean hasBorder;
    private final Material borderMaterial;
    private final String borderName;
    private final List<Integer> borderSlots;
    
    // Items
    private final List<MenuItem> items;

    // Auto Populate
    private final AutoPopulateConfig autoPopulate;

    public MenuTemplate(String name, FileConfiguration config) {
        this.name = name;
        this.title = config.getString("title", name);
        this.size = config.getInt("size", 27);
        
        // Fill
        ConfigurationSection fillSection = config.getConfigurationSection("fill");
        if (fillSection != null && fillSection.getBoolean("enabled", false)) {
            this.hasFill = true;
            this.fillMaterial = parseMaterial(fillSection.getString("material", "GRAY_STAINED_GLASS_PANE"));
            this.fillName = fillSection.getString("name", " ");
        } else {
            this.hasFill = false;
            this.fillMaterial = Material.AIR;
            this.fillName = "";
        }
        
        // Border
        ConfigurationSection borderSection = config.getConfigurationSection("border");
        if (borderSection != null && borderSection.getBoolean("enabled", false)) {
            this.hasBorder = true;
            this.borderMaterial = parseMaterial(borderSection.getString("material", "BLACK_STAINED_GLASS_PANE"));
            this.borderName = borderSection.getString("name", " ");
            this.borderSlots = parseSlotList(borderSection.getList("slots"));
        } else {
            this.hasBorder = false;
            this.borderMaterial = Material.AIR;
            this.borderName = "";
            this.borderSlots = new ArrayList<>();
        }
        
        // Auto Populate
        ConfigurationSection autoSection = config.getConfigurationSection("auto-populate");
        if (autoSection != null && autoSection.getBoolean("enabled", false)) {
            this.autoPopulate = new AutoPopulateConfig(autoSection);
        } else {
            this.autoPopulate = null;
        }
        
        // Items
        this.items = new ArrayList<>();
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemConfig = itemsSection.getConfigurationSection(key);
                if (itemConfig != null) {
                    items.add(new MenuItem(key, itemConfig));
                }
            }
        }
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return Material.STONE;
        }
    }

    private static List<Integer> parseSlotList(List<?> rawSlots) {
        List<Integer> slots = new ArrayList<>();
        if (rawSlots != null) {
            for (Object obj : rawSlots) {
                if (obj instanceof Integer) {
                    slots.add((Integer) obj);
                } else if (obj instanceof String) {
                    String s = (String) obj;
                    if (s.contains("-")) {
                        try {
                            String[] parts = s.split("-");
                            int start = Integer.parseInt(parts[0].trim());
                            int end = Integer.parseInt(parts[1].trim());
                            for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                                slots.add(i);
                            }
                        } catch (NumberFormatException e) {
                            // Ignore invalid format
                        }
                    } else {
                         try {
                             slots.add(Integer.parseInt(s.trim()));
                         } catch (NumberFormatException e) {
                             // Ignore
                         }
                    }
                }
            }
        }
        return slots;
    }

    // Getters
    public String getName() { return name; }
    public String getTitle() { return title; }
    public int getSize() { return size; }
    
    public boolean hasFill() { return hasFill; }
    public Material getFillMaterial() { return fillMaterial; }
    public String getFillName() { return fillName; }
    
    public boolean hasBorder() { return hasBorder; }
    public Material getBorderMaterial() { return borderMaterial; }
    public String getBorderName() { return borderName; }
    public List<Integer> getBorderSlots() { return borderSlots; }
    
    public AutoPopulateConfig getAutoPopulate() { return autoPopulate; }
    
    public List<MenuItem> getItems() { return items; }

    /**
     * Menu item
     */
    public static class MenuItem {
        private final String id;
        private final int slot;
        private final Material material;
        private final boolean glow;
        private final String name;
        private final List<String> lore;
        private final String skullOwner;
        private final MenuAction action;

        public MenuItem(String id, ConfigurationSection config) {
            this.id = id;
            this.slot = config.getInt("slot", 0);
            this.material = parseMaterial(config.getString("material", "STONE"));
            this.glow = config.getBoolean("glow", false);
            this.name = config.getString("name", id);
            this.lore = config.getStringList("lore");
            this.skullOwner = config.getString("skull-owner", null);
            
            ConfigurationSection actionSection = config.getConfigurationSection("action");
            if (actionSection != null) {
                this.action = new MenuAction(actionSection, config.getString("sound", null));
            } else {
                this.action = null;
            }
        }

        private Material parseMaterial(String name) {
            try {
                return Material.valueOf(name.toUpperCase());
            } catch (Exception e) {
                return Material.STONE;
            }
        }

        public String getId() { return id; }
        public int getSlot() { return slot; }
        public Material getMaterial() { return material; }
        public boolean isGlow() { return glow; }
        public String getName() { return name; }
        public List<String> getLore() { return lore; }
        public String getSkullOwner() { return skullOwner; }
        public MenuAction getAction() { return action; }
    }

    /**
     * Menu action
     */
    public static class MenuAction {
        private final String type;
        private final String value;
        private final String sound;

        public MenuAction(ConfigurationSection config, String sound) {
            this.type = config.getString("type", "");
            // Support multiple value keys for different action types
            String val = config.getString("menu", null);
            if (val == null) val = config.getString("upgrade-id", null);
            if (val == null) val = config.getString("recipe-id", null);
            if (val == null) val = config.getString("smelting-id", null);
            if (val == null) val = config.getString("category", null); // For SWITCH_CATEGORY action
            if (val == null) val = config.getString("command", "");
            this.value = val;
            this.sound = sound;
        }

        public String getType() { return type; }
        public String getValue() { return value; }
        public String getSound() { return sound; }
    }
    
    /**
     * Auto Populate configuration with category support
     */
    public static class AutoPopulateConfig {
        private final List<PopulateSource> sources; // Legacy support
        private final String recipeType; // recipe or smelting
        
        // Category-based pagination
        private final List<Integer> sharedSlots;
        private final String defaultCategory;
        private final Map<String, CategoryConfig> categories;
        
        // Browser Mode Support
        private final String mode; // "direct" or "browser"
        private final List<Integer> categorySlots;

        public AutoPopulateConfig(ConfigurationSection config) {
            this.sources = new ArrayList<>();
            this.categories = new java.util.LinkedHashMap<>();
            
            // Mode
            this.mode = config.getString("mode", "direct");
            this.categorySlots = parseSlotList(config.getList("category-slots"));
            
            // Recipe type
            if (config.contains("type")) {
                this.recipeType = config.getString("type", "recipe");
            } else if (config.contains("recipe-type")) {
                this.recipeType = config.getString("recipe-type", "recipe");
            } else {
                this.recipeType = config.getString("filter", "recipe");
            }
            
            // Shared slots for category mode
            this.sharedSlots = parseSlotList(config.getList("slots"));
            this.defaultCategory = config.getString("default-category", null);
            
            // Parse categories section
            ConfigurationSection categoriesSection = config.getConfigurationSection("categories");
            if (categoriesSection != null) {
                for (String key : categoriesSection.getKeys(false)) {
                    ConfigurationSection catConfig = categoriesSection.getConfigurationSection(key);
                    if (catConfig != null) {
                        categories.put(key, new CategoryConfig(key, catConfig));
                    }
                }
            }
            
            // ... (legacy sources parsing omitted for brevity if unchanged, but I need to include context or keep it)
            // Legacy sources support (backward compatibility)
            ConfigurationSection sourcesSection = config.getConfigurationSection("sources");
            if (sourcesSection != null && categories.isEmpty()) {
                // If no categories defined, use sources as categories
                for (String key : sourcesSection.getKeys(false)) {
                    ConfigurationSection sourceConfig = sourcesSection.getConfigurationSection(key);
                    if (sourceConfig != null) {
                        sources.add(new PopulateSource(key, sourceConfig));
                        // Also create category from source
                        categories.put(key, new CategoryConfig(key, sourceConfig));
                    }
                }
            } else if (sourcesSection != null) {
                // Keep sources for backward compatibility
                for (String key : sourcesSection.getKeys(false)) {
                    ConfigurationSection sourceConfig = sourcesSection.getConfigurationSection(key);
                    if (sourceConfig != null) {
                        sources.add(new PopulateSource(key, sourceConfig));
                    }
                }
            } else {
                // Backward compatibility / Single source
                if (config.contains("folder") || (config.contains("slots") && sharedSlots.isEmpty())) {
                    sources.add(new PopulateSource("default", config));
                }
            }
        }

        public List<PopulateSource> getSources() { return sources; }
        public String getRecipeType() { return recipeType; }
        public List<Integer> getSharedSlots() { return sharedSlots; }
        public String getMode() { return mode; }
        public List<Integer> getCategorySlots() { return categorySlots; }
        
        public String getDefaultCategory() { 
            if (mode.equalsIgnoreCase("browser")) return null; // Browser mode starts with no category selected
            
            if (defaultCategory != null) return defaultCategory;
            // Return first category if no default specified
            if (!categories.isEmpty()) return categories.keySet().iterator().next();
            if (!sources.isEmpty()) return sources.get(0).getIdentifier();
            return null;
        }
        public Map<String, CategoryConfig> getCategories() { return categories; }
        
        public boolean hasCategoryMode() {
            return !categories.isEmpty() || !sources.isEmpty();
        }
    }

    public static class PopulateSource {
        private final String identifier;
        private final List<String> folders;
        private final List<Integer> slots;

        public PopulateSource(String id, ConfigurationSection config) {
            this.identifier = id;
            this.folders = new ArrayList<>();
            this.slots = parseSlotList(config.getList("slots"));
            
            if (config.contains("folder")) {
                if (config.isList("folder")) {
                    folders.addAll(config.getStringList("folder"));
                } else {
                    String f = config.getString("folder");
                    if (f != null) folders.add(f);
                }
            } else {
                if (!id.equals("default")) {
                    folders.add(id);
                }
            }
        }

        public String getIdentifier() { return identifier; }
        public List<String> getFolders() { return folders; }
        public List<Integer> getSlots() { return slots; }
        
        // Caching for performance
        private List<String> cachedRecipeIds = null;
        public List<String> getCachedRecipeIds() { return cachedRecipeIds; }
        public void setCachedRecipeIds(List<String> ids) { this.cachedRecipeIds = ids; }
    }
    
    /**
     * Category configuration for category-based pagination
     */
    public static class CategoryConfig {
        private final String id;
        private final List<String> folders;
        private List<String> cachedRecipeIds = null;
        
        // Browser Mode Support
        private final MenuItem icon;

        public CategoryConfig(String id, ConfigurationSection config) {
            this.id = id;
            this.folders = new ArrayList<>();
            
            if (config.contains("folder")) {
                if (config.isList("folder")) {
                    folders.addAll(config.getStringList("folder"));
                } else {
                    String f = config.getString("folder");
                    if (f != null) folders.add(f);
                }
            } else {
                // Use category id as folder name by default
                folders.add(id);
            }
            
            // Icon for browser mode
            if (config.contains("icon")) {
                this.icon = new MenuItem(id + "-icon", config.getConfigurationSection("icon"));
            } else {
                this.icon = null;
            }
        }

        public String getId() { return id; }
        public List<String> getFolders() { return folders; }
        public List<String> getCachedRecipeIds() { return cachedRecipeIds; }
        public void setCachedRecipeIds(List<String> ids) { this.cachedRecipeIds = ids; }
        public MenuItem getIcon() { return icon; }
    }
}
