package com.phmyhu1710.forgestation.menu;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import com.phmyhu1710.forgestation.smelting.SmeltingRecipe;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.InventoryUtil;
import com.phmyhu1710.forgestation.hook.ItemHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages recipe detail GUIs for individual recipe/smelting viewing
 */
public class RecipeDetailGUI {

    private final ForgeStationPlugin plugin;

    public RecipeDetailGUI(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        // NOTE: Event listeners removed - this class is now only used for icon creation
    }

    public ItemStack createRecipeIconForMenu(Player player, Recipe recipe) {
        return createRecipeIcon(player, recipe);
    }
    
    /**
     * PERF-FIX: Recipe icon với inventory snapshot
     */
    public ItemStack createRecipeIconForMenuWithSnapshot(Player player, Recipe recipe, Map<String, Integer> invSnapshot) {
        return createRecipeIconWithSnapshot(player, recipe, invSnapshot);
    }

    private ItemStack createRecipeIcon(Player player, Recipe recipe) {
        return createRecipeIconWithSnapshot(player, recipe, null);
    }
    
    /**
     * PERF-FIX: Recipe icon với inventory snapshot - tránh scan nhiều lần
     * Supports custom model data and item hooks (ItemsAdder, Oraxen, Nexo)
     */
    private ItemStack createRecipeIconWithSnapshot(Player player, Recipe recipe, Map<String, Integer> invSnapshot) {
        ItemStack item;
        String materialString = recipe.getIconMaterialString();
        
        // Check for item hook (itemsadder-, oraxen-, nexo-)
        ItemHook hook = plugin.getHookManager().getItemHookByPrefix(materialString);
        if (hook != null) {
            String itemId = materialString.substring(hook.getPrefix().length());
            item = hook.getItem(itemId);
            if (item == null || item.getType() == Material.STONE) {
                item = new ItemStack(recipe.getIconMaterial());
            }
        } else {
            item = new ItemStack(recipe.getIconMaterial());
        }
        
        ItemMeta meta = item.getItemMeta();
        
        // Apply custom model data if present
        if (recipe.getIconModelData() > 0) {
            meta.setCustomModelData(recipe.getIconModelData());
        }
        
        meta.setDisplayName(MessageUtil.colorize(parseRecipePlaceholdersWithSnapshot(player, recipe, recipe.getIconName(), invSnapshot)));
        
        // Use lore from config if available, otherwise fallback to generated lore
        List<String> configLore = recipe.getIconLore();
        List<String> lore = new ArrayList<>();
        
        if (configLore != null && !configLore.isEmpty()) {
            // Use config lore with placeholder parsing
            for (String line : configLore) {
                if (line.contains("%ingredients_list%")) {
                    lore.addAll(generateIngredientListWithSnapshot(player, recipe, invSnapshot));
                } else {
                    lore.add(MessageUtil.colorize(parseRecipePlaceholdersWithSnapshot(player, recipe, line, invSnapshot)));
                }
            }
        } else {
            // Fallback: Auto-generate basic lore
            lore.add("");
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            lore.add(MessageUtil.colorize("&f&lYÊU CẦU:"));
            
            for (Recipe.Ingredient ing : recipe.getIngredients()) {
                String ingName;
                if (ing.getType().equals("VANILLA")) {
                    ingName = ing.getMaterial();
                } else if (ing.getType().equals("EXTRA_STORAGE")) {
                    ingName = "[ES] " + ing.getMaterial();
                } else if (ing.getType().equals("MMOITEMS")) {
                    ingName = "[MMO] " + ing.getMaterial();
                } else {
                    ingName = ing.getMaterial();
                }
                int displayAmount = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                lore.add(MessageUtil.colorize("&8┃ &f" + displayAmount + "x " + ingName));
            }
            
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            
            if (recipe.usesRankMultiplier()) {
                double mult = plugin.getRecipeManager().getRankMultiplier(player, recipe);
                lore.add(MessageUtil.colorize("&7Hệ số nhân: &ax" + String.format("%.1f", mult)));
            }
        }
        
        meta.setLore(lore);
        
        if (recipe.isIconGlow()) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        
        item.setItemMeta(meta);
        return item;
    }
    
    /**
     * Parse recipe-specific placeholders
     * UNIFIED FORMAT: Hiển thị số lượng đang có cho mỗi ingredient
     */
    private List<String> generateIngredientList(Player player, Recipe recipe) {
        return generateIngredientListWithSnapshot(player, recipe, null);
    }
    
    /**
     * PERF-FIX: Generate ingredient list với inventory snapshot
     */
    private List<String> generateIngredientListWithSnapshot(Player player, Recipe recipe, Map<String, Integer> invSnapshot) {
        List<String> list = new ArrayList<>();
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int count;
            if (invSnapshot != null && "VANILLA".equals(ing.getType())) {
                count = InventoryUtil.countFromSnapshot(invSnapshot, ing.getMaterial());
            } else {
                count = InventoryUtil.countItems(plugin, player, ing);
            }
            int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
            boolean has = count >= required;
            
            String name = ing.getName();
            if (name == null) {
                if (ing.getType().equals("VANILLA")) {
                    name = ing.getMaterial();
                } else if (ing.getType().equals("EXTRA_STORAGE")) {
                    name = ing.getMaterial();
                } else if (ing.getType().equals("MMOITEMS")) {
                    name = ing.getMmoitemsId();
                } else {
                    name = ing.getMaterial();
                }
            }
            
            String symbol = has ? "&a✓" : "&c✗";
            // UNIFIED FORMAT: Hiển thị đang có dưới dạng (owned/required)
            String line = String.format("&8┃ &7» &f%s &8× &e%d %s", name, required, symbol);
            list.add(MessageUtil.colorize(line));
            // Add owned line
            String ownedColor = has ? "&a" : "&c";
            String ownedLine = String.format("&8┃   &7Đang có: %s%s", ownedColor, MessageUtil.formatNumber(count));
            list.add(MessageUtil.colorize(ownedLine));
        }
        return list;
    }

    private String parseRecipePlaceholders(Player player, Recipe recipe, String text) {
        return parseRecipePlaceholdersWithSnapshot(player, recipe, text, null);
    }
    
    /**
     * PERF-FIX: Parse recipe placeholders với inventory snapshot
     */
    private String parseRecipePlaceholdersWithSnapshot(Player player, Recipe recipe, String text, Map<String, Integer> invSnapshot) {
        if (text == null) return "";

        // Placeholders tùy chỉnh từ config (placeholders: item_name: "Khối Lưu Ly" → %item_name%)
        for (java.util.Map.Entry<String, String> e : recipe.getPlaceholders().entrySet()) {
            text = text.replace("%" + e.getKey() + "%", e.getValue());
        }

        // Global Currency Placeholders
        if (text.contains("%currency_")) {
             text = text.replace("%currency_vault%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins")));
             text = text.replace("%currency_playerpoints%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.playerpoints", "Points")));
             text = text.replace("%currency_exp%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.exp", "EXP")));
             text = text.replace("%currency_coinengine%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.coinengine", "C")));
        }
        
        // Player info
        text = text.replace("%player_name%", player.getName());
        
        // Rank and multiplier
        double multiplier = plugin.getRecipeManager().getRankMultiplier(player, recipe);
        String rankId = getRankId(player);
        String rankDisplay = getRankDisplay(player, rankId);
        
        text = text.replace("%player_rank%", rankId);
        text = text.replace("%player_rank_display%", rankDisplay);
        text = text.replace("%multiplier%", String.format("%.1f", multiplier));
        
        // Prefix
        // Prefix (Legacy support removed, rely on PAPI)
        text = text.replace("%player_prefix%", "");
        
        // Input materials count (for exchange recipes)
        if (!recipe.getIngredients().isEmpty()) {
            Recipe.Ingredient ing = recipe.getIngredients().get(0);
            int playerHas;
            if (invSnapshot != null && "VANILLA".equals(ing.getType())) {
                playerHas = InventoryUtil.countFromSnapshot(invSnapshot, ing.getMaterial());
            } else {
                playerHas = countItems(player, ing);
            }
            // UNIFIED PLACEHOLDER: %player_input% là chuẩn, giữ backward compat với %player_diamonds%, %player_items%
            String formattedCount = MessageUtil.formatNumber(playerHas);
            text = text.replace("%player_input%", formattedCount);
            text = text.replace("%player_diamonds%", formattedCount); // backward compat
            text = text.replace("%player_items%", formattedCount); // backward compat
            
            // Estimated output for exchange recipes (bulk-exchange)
            if (recipe.isBulkExchange()) {
                int baseAmount = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                if (baseAmount > 0) {
                    int exchangeTimes = playerHas / baseAmount;
                    
                    // Try rewards first (for currency exchange)
                    if (recipe.isExchangeRecipe() && !recipe.getRewards().isEmpty()) {
                        Recipe.Reward reward = recipe.getRewards().get(0);
                        double estimatedPoints = exchangeTimes * reward.getBaseAmount() * multiplier;
                        text = text.replace("%estimated_points%", MessageUtil.formatNumber((int) estimatedPoints));
                    }
                    // Fallback to result (for item exchange)
                    else if (recipe.getResult() != null) {
                        int resultAmount = recipe.getResult().getAmount();
                        double estimatedPoints = exchangeTimes * resultAmount * multiplier;
                        text = text.replace("%estimated_points%", MessageUtil.formatNumber((int) estimatedPoints));
                    }
                    // Default to 0 if neither rewards nor result
                    else {
                        text = text.replace("%estimated_points%", "0");
                    }
                } else {
                    text = text.replace("%estimated_points%", "0");
                }
            }
            // Cleanup placeholder if not bulk-exchange
            else {
                text = text.replace("%estimated_points%", "0");
            }
        } else {
            // No ingredients - replace placeholder with 0
            text = text.replace("%estimated_points%", "0");
        }
        
        // Status - PERF-FIX: Sử dụng snapshot
        boolean hasIngredients = true;
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
             int count;
             if (invSnapshot != null && "VANILLA".equals(ing.getType())) {
                 count = InventoryUtil.countFromSnapshot(invSnapshot, ing.getMaterial());
             } else {
                 count = InventoryUtil.countItems(plugin, player, ing);
             }
             int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
             if (count < required) {
                 hasIngredients = false;
                 break;
             }
        }
        String status = hasIngredients ? "&aCó thể chế tạo" : "&cThiếu nguyên liệu";
        text = text.replace("%status%", status);
        
        // UNIFIED: Cooldown/Duration - hỗ trợ cả hai placeholder cho crafting
        int cooldown = plugin.getRecipeManager().getCooldown(player, recipe);
        String formattedTime = formatTime(cooldown);
        text = text.replace("%cooldown%", formattedTime);
        text = text.replace("%duration%", formattedTime); // Alias cho thống nhất với smelting
        
        // Tỉ lệ thành công (chance)
        double effectiveChance = plugin.getRecipeManager().getEffectiveSuccessChance(player, recipe);
        text = text.replace("%chance%", String.format("%.0f", effectiveChance));
        text = text.replace("%chance_base%", String.format("%.0f", recipe.getSuccessChance()));
        
        // PlaceholderAPI
        if (plugin.getHookManager().isPlaceholderAPIEnabled()) {
            text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
        
        return text;
    }
    
    private String getRankId(Player player) {
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        var priority = config.getStringList("priority-order");
        
        for (String rankId : priority) {
            var rankSection = config.getConfigurationSection("ranks." + rankId);
            if (rankSection == null) continue;
            
            String permission = rankSection.getString("permission");
            if (permission != null && player.hasPermission(permission)) {
                return rankId;
            }
        }
        return "default";
    }
    
    private String getRankDisplay(Player player, String rankId) {
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        return config.getString("ranks." + rankId + ".display-name", rankId);
    }
    
    /**
     * UNIFIED: Sử dụng TimeUtil để format time
     */
    private String formatTime(int seconds) {
        return com.phmyhu1710.forgestation.util.TimeUtil.format(seconds);
    }

    public ItemStack createSmeltingIconForMenu(Player player, SmeltingRecipe recipe) {
        // PERF-FIX: Tạo snapshot 1 lần cho menu icon
        var snapshot = plugin.getSmeltingManager().createSnapshot(player, recipe);
        return createSmeltingIconWithSnapshot(player, recipe, snapshot);
    }
    
    /**
     * PERF-FIX: Smelting icon với snapshot - tránh scan inventory nhiều lần
     * Supports custom model data and item hooks (ItemsAdder, Oraxen, Nexo)
     */
    private ItemStack createSmeltingIconWithSnapshot(Player player, SmeltingRecipe recipe, 
            InventoryUtil.SmeltingSnapshot snapshot) {
        ItemStack item;
        String materialString = recipe.getIconMaterialString();
        
        // Check for item hook (itemsadder-, oraxen-, nexo-)
        ItemHook hook = plugin.getHookManager().getItemHookByPrefix(materialString);
        if (hook != null) {
            String itemId = materialString.substring(hook.getPrefix().length());
            item = hook.getItem(itemId);
            if (item == null || item.getType() == Material.STONE) {
                item = new ItemStack(recipe.getIconMaterial());
            }
        } else {
            Material mat;
            try {
                mat = Material.valueOf(recipe.getInputMaterial().toUpperCase());
            } catch (Exception e) {
                mat = Material.FURNACE;
            }
            item = new ItemStack(mat);
        }
        
        ItemMeta meta = item.getItemMeta();
        
        // Apply custom model data if present
        if (recipe.getIconModelData() > 0) {
            meta.setCustomModelData(recipe.getIconModelData());
        }
        
        meta.setDisplayName(MessageUtil.colorize(parseSmeltingPlaceholdersWithSnapshot(player, recipe, recipe.getIconName(), snapshot)));
        
        // Use lore from config if available, otherwise fallback to generated lore
        List<String> configLore = recipe.getIconLore();
        List<String> lore = new ArrayList<>();
        
        if (configLore != null && !configLore.isEmpty()) {
            // Use config lore with placeholder parsing
            for (String line : configLore) {
                lore.add(MessageUtil.colorize(parseSmeltingPlaceholdersWithSnapshot(player, recipe, line, snapshot)));
            }
        } else {
            // Fallback: Auto-generate basic lore
            lore.add("");
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            lore.add(MessageUtil.colorize("&f&lĐẦU VÀO:"));
            lore.add(MessageUtil.colorize("&8┃ &f" + recipe.getInputAmount() + "x " + recipe.getInputMaterial()));
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            lore.add(MessageUtil.colorize("&f&lĐẦU RA:"));
            lore.add(MessageUtil.colorize("&8┃ &f" + recipe.getOutputAmount() + "x " + recipe.getOutputMaterial()));
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            
            int duration = plugin.getSmeltingManager().getDuration(player, recipe);
            lore.add(MessageUtil.colorize("&7Thời gian: &e" + duration + "s"));
        }
        
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    
    private ItemStack createSmeltingIcon(Player player, SmeltingRecipe recipe) {
        var snapshot = plugin.getSmeltingManager().createSnapshot(player, recipe);
        return createSmeltingIconWithSnapshot(player, recipe, snapshot);
    }
    
    /**
     * PERF-FIX: Parse smelting placeholders với snapshot - không scan inventory
     */
    private String parseSmeltingPlaceholdersWithSnapshot(Player player, SmeltingRecipe recipe, String text,
            InventoryUtil.SmeltingSnapshot snapshot) {
        if (text == null) return "";

        // Placeholders tùy chỉnh từ config (placeholders: item_name: "..." → %item_name%)
        for (java.util.Map.Entry<String, String> e : recipe.getPlaceholders().entrySet()) {
            text = text.replace("%" + e.getKey() + "%", e.getValue());
        }

        // Global Currency Placeholders
        if (text.contains("%currency_")) {
             text = text.replace("%currency_vault%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins")));
             text = text.replace("%currency_playerpoints%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.playerpoints", "Points")));
             text = text.replace("%currency_exp%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.exp", "EXP")));
             text = text.replace("%currency_coinengine%", MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.coinengine", "C")));
        }
        
        // Player info
        text = text.replace("%player_name%", player.getName());
        
        // UNIFIED: Duration/Cooldown - hỗ trợ cả hai placeholder cho smelting
        int duration = plugin.getSmeltingManager().getDuration(player, recipe);
        String formattedDuration = formatTime(duration);
        text = text.replace("%duration%", String.valueOf(duration)); // Giữ nguyên seconds cho các config cũ
        text = text.replace("%duration_formatted%", formattedDuration); // Mới: formatted
        text = text.replace("%cooldown%", formattedDuration); // Alias cho thống nhất với crafting
        
        // Smelting level
        int smeltingLevel = plugin.getUpgradeManager().getEffectiveLevel(player, "smelting_speed");
        text = text.replace("%smelting_level%", String.valueOf(smeltingLevel));
        
        // Time reduction
        int baseDuration = plugin.getSmeltingManager().getBaseDuration(recipe);
        int reduction = baseDuration - duration;
        text = text.replace("%reduction%", String.valueOf(reduction));
        
        // Input/output info
        text = text.replace("%input_material%", recipe.getInputMaterial());
        text = text.replace("%input_amount%", String.valueOf(recipe.getInputAmount()));
        text = text.replace("%output_material%", recipe.getOutputMaterial());
        text = text.replace("%output_amount%", String.valueOf(recipe.getOutputAmount()));
        
        // PERF-FIX: Sử dụng snapshot thay vì gọi countSmeltingMaterial/countFuel
        text = text.replace("%owned_input%", MessageUtil.formatNumber(snapshot.inputCount));
        boolean hasInput = snapshot.inputCount >= recipe.getInputAmount();
        text = text.replace("%input_status%", hasInput ? "&a✓" : "&c✗");
        
        // Fuel info with owned count from snapshot
        if (recipe.isFuelRequired()) {
            text = text.replace("%fuel_material%", recipe.getFuelMaterial());
            text = text.replace("%fuel_amount%", String.valueOf(recipe.getFuelAmount()));
            text = text.replace("%owned_fuel%", MessageUtil.formatNumber(snapshot.fuelCount));
            boolean hasFuel = snapshot.fuelCount >= recipe.getFuelAmount();
            text = text.replace("%fuel_status%", hasFuel ? "&a✓" : "&c✗");
        } else {
            text = text.replace("%fuel_material%", "Không cần");
            text = text.replace("%fuel_amount%", "0");
            text = text.replace("%owned_fuel%", "N/A");
            text = text.replace("%fuel_status%", "&a✓");
        }
        
        // Max batch from snapshot
        text = text.replace("%max_batch%", String.valueOf(snapshot.maxBatch));
        
        // Has materials (combined status) from snapshot
        boolean hasMaterials = snapshot.inputCount >= recipe.getInputAmount();
        boolean hasFuelCheck = !recipe.isFuelRequired() || snapshot.fuelCount >= recipe.getFuelAmount();
        boolean canSmelt = hasMaterials && hasFuelCheck;
        text = text.replace("%has_materials%", hasMaterials ? "&a✓" : "&c✗");
        text = text.replace("%status%", canSmelt ? "&aCó thể nung" : "&cThiếu nguyên liệu");
        
        // PlaceholderAPI
        if (plugin.getHookManager().isPlaceholderAPIEnabled()) {
            text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
        
        return text;
    }
    
    /**
     * Legacy method - creates snapshot internally
     */
    private String parseSmeltingPlaceholders(Player player, SmeltingRecipe recipe, String text) {
        var snapshot = plugin.getSmeltingManager().createSnapshot(player, recipe);
        return parseSmeltingPlaceholdersWithSnapshot(player, recipe, text, snapshot);
    }

    private ItemStack createRequirementsIcon(Player player, Recipe recipe) {
        boolean hasItems = hasIngredients(player, recipe);
        Material mat = hasItems ? Material.LIME_DYE : Material.RED_DYE;
        String status = hasItems ? "&a✓ Đủ nguyên liệu" : "&c✗ Thiếu nguyên liệu";
        
        return createItem(mat, "&f&lTRẠNG THÁI",
            "",
            status);
    }

    private boolean hasIngredients(Player player, Recipe recipe) {
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
            int count = InventoryUtil.countItems(plugin, player, ing);
            if (count < required) {
                return false;
            }
        }
        return true;
    }

    private int countItems(Player player, Recipe.Ingredient ing) {
        return InventoryUtil.countItems(plugin, player, ing);
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(MessageUtil.colorize(name));
        
        List<String> loreList = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                loreList.add(MessageUtil.colorize(line));
            }
        }
        if (!loreList.isEmpty()) {
            meta.setLore(loreList);
        }
        
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

}