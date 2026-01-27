package com.phmyhu1710.forgestation.menu;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import com.phmyhu1710.forgestation.util.NumberParser;
import com.phmyhu1710.forgestation.smelting.SmeltingRecipe;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.InventoryUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Manages recipe detail GUIs for individual recipe/smelting viewing
 */
public class RecipeDetailGUI implements Listener {

    private final ForgeStationPlugin plugin;
    
    // Track active recipe detail menus: UUID -> recipe/smelting id
    private final Map<UUID, String> activeRecipeMenus = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeSmeltingMenus = new ConcurrentHashMap<>();
    
    // Track source menu and category for back navigation
    private final Map<UUID, String> sourceMenus = new ConcurrentHashMap<>();
    private final Map<UUID, String> sourceCategories = new ConcurrentHashMap<>();
    
    // Track players waiting for chat input: UUID -> recipe id
    private final Map<UUID, String> awaitingChatInput = new ConcurrentHashMap<>();
    
    // BATCH SMELTING: Track players waiting for smelting chat input
    private final Map<UUID, String> awaitingSmeltingInput = new ConcurrentHashMap<>();
    
    private final Cache<UUID, Long> chatLimiter = Caffeine.newBuilder()
        .expireAfterWrite(800, TimeUnit.MILLISECONDS)
        .build();

    public RecipeDetailGUI(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Open recipe detail GUI
     */
    public void openRecipeGUI(Player player, Recipe recipe) {
        Inventory inv = Bukkit.createInventory(null, 27, 
            MessageUtil.colorize("&8&l⚒ " + recipe.getDisplayName()));
        
        // Fill background
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, glass);
        }
        
        // Recipe icon in center
        ItemStack icon = createRecipeIcon(player, recipe);
        inv.setItem(13, icon);
        
        // Craft button (left click = 1, right click = all)
        ItemStack craftBtn = createItem(Material.LIME_CONCRETE, "&a&l✓ CHẾ TẠO",
            "",
            "&7Click để thực hiện chế tạo!",
            "",
            "&e▶ Click trái: &fChế 1 lần",
            recipe.isBulkExchange() ? "&e▶ Click phải: &fChế tất cả" : "",
            recipe.isBulkExchange() ? "&e▶ Shift + Click: &fNhập số lượng" : "");
        inv.setItem(15, craftBtn);
        
        // Cancel button
        ItemStack cancelBtn = createItem(Material.RED_CONCRETE, "&c&l✖ HỦY",
            "&7Quay lại menu");
        inv.setItem(11, cancelBtn);
        
        // Requirements info
        ItemStack reqInfo = createRequirementsIcon(player, recipe);
        inv.setItem(4, reqInfo);
        
        activeRecipeMenus.put(player.getUniqueId(), recipe.getId());
        
        // Save source menu and category for back navigation
        sourceMenus.put(player.getUniqueId(), "crafting-menu");
        String category = plugin.getMenuManager().getActiveCategory(player.getUniqueId());
        if (category != null) {
            sourceCategories.put(player.getUniqueId(), category);
        }
        
        player.openInventory(inv);
    }

    /**
     * Open smelting detail GUI
     * BATCH SMELTING: Thêm 3 click modes như Crafting
     * PERF-FIX: Sử dụng snapshot để tránh scan inventory nhiều lần
     */
    public void openSmeltingGUI(Player player, SmeltingRecipe recipe) {
        Inventory inv = Bukkit.createInventory(null, 27, 
            MessageUtil.colorize("&8&l🔥 " + recipe.getDisplayName()));
        
        // Fill background - PERF-FIX: Không clone, dùng chung 1 instance
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, glass);
        }
        
        // PERF-FIX: Tạo snapshot 1 lần, dùng cho tất cả operations
        var snapshot = plugin.getSmeltingManager().createSnapshot(player, recipe);
        
        // Recipe icon in center - pass snapshot
        ItemStack icon = createSmeltingIconWithSnapshot(player, recipe, snapshot);
        inv.setItem(13, icon);
        
        // Start button with 3 click modes
        // BATCH SMELTING FIX: Hiển thị thời gian cho từng mode
        int durationPerItem = plugin.getSmeltingManager().getDuration(player, recipe);
        int totalDurationAll = durationPerItem * snapshot.maxBatch;
        
        ItemStack startBtn = createItem(Material.LIME_CONCRETE, "&a&l✓ BẮT ĐẦU NUNG",
            "",
            "&7Click để bắt đầu nung!",
            "",
            "&e▶ Click trái: &fNung 1 item &7(" + durationPerItem + "s)",
            "&e▶ Click phải: &fNung " + snapshot.maxBatch + " items &7(" + formatTime(totalDurationAll) + ")",
            "&e▶ Shift + Click: &fNhập số lượng",
            "",
            "&7Thời gian mỗi item: &e" + durationPerItem + "s");
        inv.setItem(15, startBtn);
        
        // Cancel button
        ItemStack cancelBtn = createItem(Material.RED_CONCRETE, "&c&l✖ HỦY",
            "&7Quay lại menu");
        inv.setItem(11, cancelBtn);
        
        // Requirements info - pass snapshot
        ItemStack reqInfo = createSmeltingRequirementsIconWithSnapshot(player, recipe, snapshot);
        inv.setItem(4, reqInfo);
        
        activeSmeltingMenus.put(player.getUniqueId(), recipe.getId());
        
        // Save source menu and category for back navigation
        sourceMenus.put(player.getUniqueId(), "smelting-menu");
        String category = plugin.getMenuManager().getActiveCategory(player.getUniqueId());
        if (category != null) {
            sourceCategories.put(player.getUniqueId(), category);
        }
        
        player.openInventory(inv);
    }
    
    /**
     * BATCH SMELTING: Create requirements icon for smelting
     * PERF-FIX: Sử dụng snapshot thay vì gọi nhiều methods
     */
    private ItemStack createSmeltingRequirementsIconWithSnapshot(Player player, SmeltingRecipe recipe, 
            InventoryUtil.SmeltingSnapshot snapshot) {
        int inputNeed = recipe.getInputAmount();
        int fuelNeed = recipe.getFuelAmount();
        
        boolean hasInput = snapshot.inputCount >= inputNeed;
        boolean hasFuel = !recipe.isFuelRequired() || snapshot.fuelCount >= fuelNeed;
        boolean canSmelt = hasInput && hasFuel;
        
        Material mat = canSmelt ? Material.LIME_DYE : Material.RED_DYE;
        String status = canSmelt ? "&a✓ Có thể nung" : "&c✗ Thiếu nguyên liệu";
        
        List<String> loreLines = new ArrayList<>();
        loreLines.add("");
        loreLines.add(MessageUtil.colorize(status));
        loreLines.add("");
        loreLines.add(MessageUtil.colorize("&f&lNGUYÊN LIỆU:"));
        loreLines.add(MessageUtil.colorize("&8┃ &7" + recipe.getInputMaterial() + " &8× &e" + inputNeed + 
            " &7(" + (hasInput ? "&a" : "&c") + snapshot.inputCount + "&7)"));
        
        if (recipe.isFuelRequired()) {
            loreLines.add("");
            loreLines.add(MessageUtil.colorize("&f&lNHIÊN LIỆU:"));
            loreLines.add(MessageUtil.colorize("&8┃ &7" + recipe.getFuelMaterial() + " &8× &e" + fuelNeed +
                " &7(" + (hasFuel ? "&a" : "&c") + snapshot.fuelCount + "&7)"));
        }
        
        return createItem(mat, "&f&lTRẠNG THÁI", loreLines.toArray(new String[0]));
    }
    
    /**
     * Legacy method for backward compatibility
     */
    private ItemStack createSmeltingRequirementsIcon(Player player, SmeltingRecipe recipe) {
        var snapshot = plugin.getSmeltingManager().createSnapshot(player, recipe);
        return createSmeltingRequirementsIconWithSnapshot(player, recipe, snapshot);
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
     */
    private ItemStack createRecipeIconWithSnapshot(Player player, Recipe recipe, Map<String, Integer> invSnapshot) {
        ItemStack item = new ItemStack(recipe.getIconMaterial());
        ItemMeta meta = item.getItemMeta();
        
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
        if (plugin.getHookManager().getVaultChat() != null) {
             String prefix = plugin.getHookManager().getVaultChat().getPlayerPrefix(player);
             text = text.replace("%player_prefix%", prefix != null ? prefix : "");
        } else {
             text = text.replace("%player_prefix%", "");
        }
        
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
            
            // Estimated output for exchange recipes
            if (recipe.isExchangeRecipe() && !recipe.getRewards().isEmpty()) {
                int baseAmount = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                int exchangeTimes = playerHas / baseAmount;
                Recipe.Reward reward = recipe.getRewards().get(0);
                double estimatedPoints = exchangeTimes * reward.getBaseAmount() * multiplier;
                text = text.replace("%estimated_points%", MessageUtil.formatNumber((int) estimatedPoints));
            }
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
     */
    private ItemStack createSmeltingIconWithSnapshot(Player player, SmeltingRecipe recipe, 
            InventoryUtil.SmeltingSnapshot snapshot) {
        Material mat;
        try {
            mat = Material.valueOf(recipe.getInputMaterial().toUpperCase());
        } catch (Exception e) {
            mat = Material.FURNACE;
        }
        
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        
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
        
        // Player info
        text = text.replace("%player_name%", player.getName());
        
        // UNIFIED: Duration/Cooldown - hỗ trợ cả hai placeholder cho smelting
        int duration = plugin.getSmeltingManager().getDuration(player, recipe);
        String formattedDuration = formatTime(duration);
        text = text.replace("%duration%", String.valueOf(duration)); // Giữ nguyên seconds cho các config cũ
        text = text.replace("%duration_formatted%", formattedDuration); // Mới: formatted
        text = text.replace("%cooldown%", formattedDuration); // Alias cho thống nhất với crafting
        
        // Smelting level
        int smeltingLevel = plugin.getPlayerDataManager().getPlayerData(player).getUpgradeLevel("smelting_speed");
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        
        // Check for recipe menu
        if (activeRecipeMenus.containsKey(uuid)) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            
            String recipeId = activeRecipeMenus.get(uuid);
            Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
            if (recipe == null) return;
            
            int slot = event.getRawSlot();
            
            // Craft button (slot 15)
            if (slot == 15) {
                activeRecipeMenus.remove(uuid);
                player.closeInventory();
                
                if (recipe.isExchangeRecipe()) {
                    // Shift + Click = Open chat for custom amount
                    if (event.isShiftClick()) {
                        openAmountInputGUI(player, recipe);
                    }
                    // Right click = Exchange all
                    else if (event.isRightClick()) {
                        // Pass total items, not number of exchanges
                        Recipe.Ingredient input = recipe.getIngredients().get(0);
                        int playerHas = countItems(player, input);
                        plugin.getCraftingExecutor().executeExchange(player, recipe, playerHas);
                    }
                    // Left click = Exchange 1 time
                    else {
                        Recipe.Ingredient input = recipe.getIngredients().get(0);
                        int baseAmount = input.getBaseAmount() > 0 ? input.getBaseAmount() : input.getAmount();
                        plugin.getCraftingExecutor().executeExchange(player, recipe, baseAmount);
                    }
                } else {
                    plugin.getCraftingExecutor().executeCraft(player, recipe);
                }
            }
            // Cancel button (slot 11) - go back to menu with category
            else if (slot == 11) {
                activeRecipeMenus.remove(uuid);
                String sourceMenu = sourceMenus.getOrDefault(uuid, "crafting-menu");
                String sourceCategory = sourceCategories.get(uuid);
                sourceMenus.remove(uuid);
                sourceCategories.remove(uuid);
                player.closeInventory();
                // Use scheduler to open menu on next tick to avoid race condition
                plugin.getScheduler().runLater(() -> {
                    plugin.getMenuManager().openMenuWithCategory(player, sourceMenu, sourceCategory);
                }, 1);
            }
            
            return;
        }
        
        // Check for smelting menu
        if (activeSmeltingMenus.containsKey(uuid)) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            
            String smeltingId = activeSmeltingMenus.get(uuid);
            SmeltingRecipe recipe = plugin.getSmeltingManager().getRecipe(smeltingId);
            if (recipe == null) return;
            
            int slot = event.getRawSlot();
            
            // Start button (slot 15) - BATCH SMELTING: 3 click modes
            if (slot == 15) {
                activeSmeltingMenus.remove(uuid);
                player.closeInventory();
                
                // Shift + Click = Open chat for custom amount
                if (event.isShiftClick()) {
                    openSmeltingAmountInput(player, recipe);
                }
                // Right click = Smelt all
                else if (event.isRightClick()) {
                    int maxBatch = plugin.getSmeltingManager().getMaxBatchCount(player, recipe);
                    if (maxBatch > 0) {
                        plugin.getSmeltingManager().startSmelting(player, recipe, maxBatch);
                    } else {
                        plugin.getMessageUtil().send(player, "smelt.missing-materials",
                            "material", recipe.getInputMaterial(),
                            "amount", String.valueOf(recipe.getInputAmount()));
                    }
                }
                // Left click = Smelt 1 batch
                else {
                    plugin.getSmeltingManager().startSmelting(player, recipe, 1);
                }
            }
            // Cancel button (slot 11) - go back to menu with category
            else if (slot == 11) {
                activeSmeltingMenus.remove(uuid);
                String sourceMenu = sourceMenus.getOrDefault(uuid, "smelting-menu");
                String sourceCategory = sourceCategories.get(uuid);
                sourceMenus.remove(uuid);
                sourceCategories.remove(uuid);
                player.closeInventory();
                plugin.getScheduler().runLater(() -> {
                    plugin.getMenuManager().openMenuWithCategory(player, sourceMenu, sourceCategory);
                }, 1);
            }
        }
    }
    
    /**
     * BATCH SMELTING: Open chat input for custom smelting amount
     */
    private void openSmeltingAmountInput(Player player, SmeltingRecipe recipe) {
        player.closeInventory();
        awaitingSmeltingInput.put(player.getUniqueId(), recipe.getId());
        plugin.getMessageUtil().send(player, "smelt.input-prompt");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        
        if (activeRecipeMenus.containsKey(uuid) || activeSmeltingMenus.containsKey(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            activeRecipeMenus.remove(uuid);
            activeSmeltingMenus.remove(uuid);
            awaitingChatInput.remove(uuid);
            awaitingSmeltingInput.remove(uuid);
            // Don't remove source info here - it's needed for back navigation
        }
    }
    
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activeRecipeMenus.remove(uuid);
        activeSmeltingMenus.remove(uuid);
        awaitingChatInput.remove(uuid);
        awaitingSmeltingInput.remove(uuid);
        sourceMenus.remove(uuid);
        sourceCategories.remove(uuid);
    }

    /**
     * Open chat input for custom amount
     */
    private void openAmountInputGUI(Player player, Recipe recipe) {
        player.closeInventory();
        awaitingChatInput.put(player.getUniqueId(), recipe.getId());
        plugin.getMessageUtil().send(player, "exchange.input-prompt");
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // BATCH SMELTING: Handle smelting chat input
        if (awaitingSmeltingInput.containsKey(uuid)) {
            if (chatLimiter.getIfPresent(uuid) != null) {
                event.setCancelled(true);
                return;
            }
            chatLimiter.put(uuid, System.currentTimeMillis());
            
            event.setCancelled(true);
            String smeltingId = awaitingSmeltingInput.remove(uuid);
            String input = event.getMessage().trim();
            
            SmeltingRecipe recipe = plugin.getSmeltingManager().getRecipe(smeltingId);
            if (recipe == null) return;
            
            // Handle "all" keyword
            if (input.equalsIgnoreCase("all")) {
                plugin.getScheduler().runSync(() -> {
                    int maxBatch = plugin.getSmeltingManager().getMaxBatchCount(player, recipe);
                    if (maxBatch > 0) {
                        plugin.getSmeltingManager().startSmelting(player, recipe, maxBatch);
                    } else {
                        plugin.getMessageUtil().send(player, "smelt.missing-materials",
                            "material", recipe.getInputMaterial(),
                            "amount", String.valueOf(recipe.getInputAmount()));
                    }
                });
                return;
            }
            
            // Parse number with k/m support
            int amount = NumberParser.parse(input);
            
            if (amount <= 0) {
                plugin.getScheduler().runSync(() -> {
                    plugin.getMessageUtil().send(player, "smelt.input-invalid");
                });
                return;
            }
            
            // Execute smelting
            plugin.getScheduler().runSync(() -> {
                plugin.getSmeltingManager().startSmelting(player, recipe, amount);
            });
            return;
        }
        
        // Crafting/Exchange chat input
        if (!awaitingChatInput.containsKey(uuid)) return;
        
        // ISSUE-008 FIX: Rate limit để tránh spam chat input
        if (chatLimiter.getIfPresent(uuid) != null) {
            event.setCancelled(true);
            return;
        }
        chatLimiter.put(uuid, System.currentTimeMillis());
        
        event.setCancelled(true);
        String recipeId = awaitingChatInput.remove(uuid);
        String input = event.getMessage().trim();
        
        Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
        if (recipe == null) return;
        
        // Handle "all" keyword
        if (input.equalsIgnoreCase("all")) {
            // Get total items player has
            Recipe.Ingredient ing = recipe.getIngredients().get(0);
            int playerHas = countItems(player, ing);
            
            plugin.getScheduler().runSync(() -> {
                plugin.getCraftingExecutor().executeExchange(player, recipe, playerHas);
            });
            return;
        }
        
        // Parse number with k/m support
        int amount = NumberParser.parse(input);
        
        if (amount <= 0) {
            plugin.getScheduler().runSync(() -> {
                plugin.getMessageUtil().send(player, "exchange.input-invalid");
            });
            return;
        }
        
        // Execute exchange
        plugin.getScheduler().runSync(() -> {
            plugin.getCraftingExecutor().executeExchange(player, recipe, amount);
        });
    }
}
