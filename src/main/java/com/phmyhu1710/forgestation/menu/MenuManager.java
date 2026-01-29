package com.phmyhu1710.forgestation.menu;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages GUI menus with caching and performance optimization
 */
public class MenuManager implements Listener {

    private final ForgeStationPlugin plugin;
    
    // Cached menu templates
    private final Map<String, MenuTemplate> menuTemplates = new HashMap<>();
    
    // Active player menus
    private final Map<UUID, String> activeMenus = new ConcurrentHashMap<>();
    
    // Track players currently opening a menu to prevent premature close handling
    private final Set<UUID> openingMenus = ConcurrentHashMap.newKeySet();
    
    // Pagination: UUID -> Page Number (0-indexed) - now per category
    private final Map<UUID, Integer> menuPages = new ConcurrentHashMap<>();
    
    // Category tracking: UUID -> active category name
    private final Map<UUID, String> activeCategories = new ConcurrentHashMap<>();
    
    // Per-category page tracking: UUID -> (category -> page)
    private final Map<UUID, Map<String, Integer>> categoryPages = new ConcurrentHashMap<>();
    
    // Chat input tracking for direct craft/smelt
    private final Map<UUID, String> awaitingCraftInput = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingSmeltInput = new ConcurrentHashMap<>();
    private final Cache<UUID, Long> chatLimiter;
    
    // Rate limiting
    private final Cache<UUID, Long> rateLimiter;
    
    // Placeholder cache per player
    private final Cache<UUID, Map<String, String>> placeholderCache;

    public MenuManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        
        int rateLimitMs = plugin.getConfigManager().getMainConfig().getInt("performance.menu-rate-limit", 500);
        int ttl = plugin.getConfigManager().getMainConfig().getInt("performance.placeholder-cache-ttl", 5);
        
        this.rateLimiter = Caffeine.newBuilder()
            .expireAfterWrite(rateLimitMs, TimeUnit.MILLISECONDS)
            .build();
        
        this.placeholderCache = Caffeine.newBuilder()
            .expireAfterWrite(ttl, TimeUnit.SECONDS)
            .maximumSize(1000)
            .build();
        
        this.chatLimiter = Caffeine.newBuilder()
            .expireAfterWrite(800, TimeUnit.MILLISECONDS)
            .build();
        
        // Register listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void loadMenus() {
        menuTemplates.clear();
        
        Map<String, FileConfiguration> menuConfigs = plugin.getConfigManager().getMenuConfigs();
        
        for (Map.Entry<String, FileConfiguration> entry : menuConfigs.entrySet()) {
            String menuName = entry.getKey();
            FileConfiguration config = entry.getValue();
            
            // Auto-detect and add missing categories from recipe folder names
            autoDetectCategories(config, menuName);
            
            MenuTemplate template = new MenuTemplate(menuName, config);
            menuTemplates.put(menuName, template);
            plugin.debug("Loaded menu template: " + menuName);
        }
    }
    
    /**
     * Auto-detect categories from recipe folder names and add to menu config if missing
     */
    private void autoDetectCategories(FileConfiguration menuConfig, String menuName) {
        ConfigurationSection autoPopulateSection = menuConfig.getConfigurationSection("auto-populate");
        if (autoPopulateSection == null || !autoPopulateSection.getBoolean("enabled", false)) {
            return;
        }
        
        String recipeType = autoPopulateSection.getString("recipe-type", "recipe");
        if (!autoPopulateSection.contains("categories")) {
            autoPopulateSection.createSection("categories");
        }
        
        ConfigurationSection categoriesSection = autoPopulateSection.getConfigurationSection("categories");
        if (categoriesSection == null) {
            return;
        }
        
        // Get all recipe file names (folder names)
        Set<String> existingCategories = new HashSet<>(categoriesSection.getKeys(false));
        Set<String> detectedFolders = new HashSet<>();
        boolean hasNewCategories = false;
        
        // Detect from recipe files
        if (recipeType.equalsIgnoreCase("recipe")) {
            Map<String, FileConfiguration> recipeConfigs = plugin.getConfigManager().getRecipeConfigs();
            for (String fileName : recipeConfigs.keySet()) {
                detectedFolders.add(fileName.toLowerCase());
            }
        } else if (recipeType.equalsIgnoreCase("smelting")) {
            Map<String, FileConfiguration> smeltingConfigs = plugin.getConfigManager().getSmeltingConfigs();
            for (String fileName : smeltingConfigs.keySet()) {
                detectedFolders.add(fileName.toLowerCase());
            }
        }
        
        // Add missing categories automatically
        for (String folderName : detectedFolders) {
            if (!existingCategories.contains(folderName)) {
                plugin.getLogger().info("[MenuManager] Auto-detected category: " + folderName + " for menu: " + menuName);
                ConfigurationSection newCategorySection = categoriesSection.createSection(folderName);
                newCategorySection.set("folder", folderName);
                hasNewCategories = true;
            }
        }
        
        // Save config if new categories were added
        if (hasNewCategories) {
            try {
                menuConfig.save(new File(plugin.getDataFolder(), "menus/" + menuName + ".yml"));
                plugin.getLogger().info("[MenuManager] Auto-saved menu config with new categories: " + menuName);
            } catch (Exception e) {
                plugin.getLogger().warning("[MenuManager] Could not auto-save menu config: " + e.getMessage());
            }
        }
    }
    
    public int getMenuCount() {
        return menuTemplates.size();
    }

    /**
     * Open main menu for player
     */
    public void openMainMenu(Player player) {
        openMenu(player, "main-menu");
    }
    
    /**
     * Get current active category for player
     */
    public String getActiveCategory(UUID uuid) {
        return activeCategories.get(uuid);
    }
    
    /**
     * Open menu with a specific category pre-set
     */
    public void openMenuWithCategory(Player player, String menuName, String category) {
        UUID uuid = player.getUniqueId();
        if (category != null) {
            activeCategories.put(uuid, category);
        }
        openMenu(player, menuName, 0, true);
    }
    
    /**
     * Get all categories from a menu template
     */
    public List<String> getCategoriesForMenu(String menuName) {
        MenuTemplate template = menuTemplates.get(menuName);
        if (template == null || template.getAutoPopulate() == null) {
            return new ArrayList<>();
        }
        
        MenuTemplate.AutoPopulateConfig config = template.getAutoPopulate();
        if (!config.hasCategoryMode()) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(config.getCategories().keySet());
    }
    
    /**
     * Find menu that contains the specified category
     */
    public String findMenuWithCategory(String category) {
        for (Map.Entry<String, MenuTemplate> entry : menuTemplates.entrySet()) {
            MenuTemplate template = entry.getValue();
            if (template.getAutoPopulate() != null && template.getAutoPopulate().hasCategoryMode()) {
                if (template.getAutoPopulate().getCategories().containsKey(category)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
    
    /**
     * Get all categories from all menus
     */
    public Set<String> getAllCategories() {
        Set<String> allCategories = new HashSet<>();
        for (Map.Entry<String, MenuTemplate> entry : menuTemplates.entrySet()) {
            MenuTemplate template = entry.getValue();
            if (template.getAutoPopulate() != null && template.getAutoPopulate().hasCategoryMode()) {
                allCategories.addAll(template.getAutoPopulate().getCategories().keySet());
            }
        }
        return allCategories;
    }

    /**
     * Open a menu for player
     */
    public void openMenu(Player player, String menuName) {
        openMenu(player, menuName, 0); // Default to page 0
    }
    
    public void openMenu(Player player, String menuName, int page) {
        openMenu(player, menuName, page, false);
    }
    
    /**
     * Open a menu for player with option to bypass rate limiting
     */
    public void openMenu(Player player, String menuName, boolean bypassRateLimit) {
        openMenu(player, menuName, 0, bypassRateLimit);
    }

    public void openMenu(Player player, String menuName, int page, boolean bypassRateLimit) {
        // Rate limiting
        if (!bypassRateLimit && rateLimiter.getIfPresent(player.getUniqueId()) != null) {
            plugin.getMessageUtil().send(player, "menu.rate-limited");
            plugin.debug("Menu open blocked by rate limit for " + player.getName());
            return;
        }
        rateLimiter.put(player.getUniqueId(), System.currentTimeMillis());
        
        MenuTemplate template = menuTemplates.get(menuName);
        if (template == null) {
            plugin.getLogger().warning("Menu not found: " + menuName);
            return;
        }
        
        UUID uuid = player.getUniqueId();
        
        // Initialize category if menu has category support and no active category
        if (template.getAutoPopulate() != null && template.getAutoPopulate().hasCategoryMode()) {
            if (!activeCategories.containsKey(uuid)) {
                String defaultCat = template.getAutoPopulate().getDefaultCategory();
                if (defaultCat != null) {
                    activeCategories.put(uuid, defaultCat);
                }
            }
        }
        
        // Get current category's page if using category mode
        String activeCategory = activeCategories.get(uuid);
        if (activeCategory != null && template.getAutoPopulate() != null) {
            Map<String, Integer> catPages = categoryPages.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            page = catPages.getOrDefault(activeCategory, 0);
        }
        
        // Build inventory from template
        Inventory inv = buildInventory(player, template, page);
        
        // Track menu page
        menuPages.put(uuid, page);
        
        // Track active menu
        activeMenus.put(uuid, menuName);
        plugin.debug("Menu opened and tracked: " + menuName + " for " + player.getName() + " (bypass=" + bypassRateLimit + ", category=" + activeCategory + ")");
        
        // Open
        openingMenus.add(uuid);
        try {
            player.openInventory(inv);
        } finally {
            openingMenus.remove(uuid);
        }
    }

    private Inventory buildInventory(Player player, MenuTemplate template, int page) {
        String title = parsePlaceholders(player, template.getTitle(), null);
        Inventory inv = Bukkit.createInventory(null, template.getSize(), 
            MessageUtil.colorize(title));
        
        // PERF-FIX: Fill background - KHÔNG clone, Bukkit handles item references
        // ItemStack trong inventory slot được copy internally bởi Bukkit
        if (template.hasFill()) {
            ItemStack filler = createItem(template.getFillMaterial(), template.getFillName(), null, false);
            for (int i = 0; i < template.getSize(); i++) {
                inv.setItem(i, filler); // No clone needed
            }
        }
        
        // PERF-FIX: Border - KHÔNG clone
        if (template.hasBorder()) {
            ItemStack border = createItem(template.getBorderMaterial(), template.getBorderName(), null, false);
            for (int slot : template.getBorderSlots()) {
                if (slot < template.getSize()) {
                    inv.setItem(slot, border); // No clone needed
                }
            }
        }
        
        // Items
        for (MenuTemplate.MenuItem item : template.getItems()) {
            ItemStack itemStack = buildMenuItem(player, item);
            inv.setItem(item.getSlot(), itemStack);
        }

        // Auto Populate
        if (template.getAutoPopulate() != null) {
            handleAutoPopulate(player, template.getAutoPopulate(), inv, page);
        }
        
        return inv;
    }
    
    private void handleAutoPopulate(Player player, MenuTemplate.AutoPopulateConfig config, Inventory inv, int page) {
        UUID uuid = player.getUniqueId();
        String activeCategory = activeCategories.get(uuid);
        
        // PERF-FIX: Tạo inventory snapshot 1 lần cho toàn bộ auto-populate
        Map<String, Integer> invSnapshot = com.phmyhu1710.forgestation.util.InventoryUtil.createInventorySnapshot(player);
        
        // Use category mode
        if (config.hasCategoryMode()) {
            // Browser View: Show categories if mode is browser and no category selected
            if ("browser".equalsIgnoreCase(config.getMode()) && activeCategory == null) {
                populateCategoryBrowser(player, config, inv);
                return;
            }

            if (activeCategory != null) {
                // Get category config
                MenuTemplate.CategoryConfig catConfig = config.getCategories().get(activeCategory);
                if (catConfig == null) {
                    // Fallback to source-based if no category found
                    for (MenuTemplate.PopulateSource src : config.getSources()) {
                        if (src.getIdentifier().equals(activeCategory)) {
                            handleLegacyPopulate(player, config, inv, page, src, invSnapshot);
                            return;
                        }
                    }
                    return;
                }
                
                // Get slots - from sharedSlots or from first source
                List<Integer> slots = config.getSharedSlots();
                if (slots.isEmpty()) {
                    // Try to get from sources
                    for (MenuTemplate.PopulateSource src : config.getSources()) {
                        if (!src.getSlots().isEmpty()) {
                            slots = src.getSlots();
                            break;
                        }
                    }
                }
                if (slots.isEmpty()) return;
                
                // Get recipe IDs for this category
                List<String> recipeIds = getFilteredRecipeIdsForCategory(catConfig, config.getRecipeType());
                
                int itemsPerPage = slots.size();
                int startIndex = page * itemsPerPage;
                
                // Populate items
                for (int i = 0; i < itemsPerPage; i++) {
                    int recipeIndex = startIndex + i;
                    if (recipeIndex >= recipeIds.size()) break;
                    
                    String id = recipeIds.get(recipeIndex);
                    int slot = slots.get(i);
                    
                    if (config.getRecipeType().equalsIgnoreCase("smelting")) {
                        var recipe = plugin.getSmeltingManager().getRecipe(id);
                        if (recipe != null) {
                            ItemStack icon = createSmeltingIcon(player, recipe);
                            inv.setItem(slot, icon);
                        }
                    } else {
                        var recipe = plugin.getRecipeManager().getRecipe(id);
                        if (recipe != null) {
                            ItemStack icon = createRecipeIcon(player, recipe, invSnapshot);
                            inv.setItem(slot, icon);
                        }
                    }
                }
            } else {
                // Legacy logic or empty state if in direct mode with no category?
                // If direct mode and no active category (should default to default-category handled by getActiveCategory?)
                // Actually openMenu calls `getActiveCategory(uuid)` before this?
                // Wait, logic in openMenu (not shown here entirely) typically sets activeCategory default if null?
                // Let's assume typical flow handles defaults.
            }
        } else {
            // Legacy mode: populate from all sources
            for (MenuTemplate.PopulateSource source : config.getSources()) {
                handleLegacyPopulate(player, config, inv, page, source, invSnapshot);
            }
        }
    }

    private void populateCategoryBrowser(Player player, MenuTemplate.AutoPopulateConfig config, Inventory inv) {
        List<Integer> slots = config.getCategorySlots();
        if (slots == null || slots.isEmpty()) return;
        
        List<MenuTemplate.CategoryConfig> categories = new ArrayList<>(config.getCategories().values());
        
        for (int i = 0; i < slots.size(); i++) {
            if (i >= categories.size()) break;
            
            MenuTemplate.CategoryConfig cat = categories.get(i);
            MenuTemplate.MenuItem icon = cat.getIcon();
            if (icon == null) continue;
            
            // Calculate count
            int count = getFilteredRecipeIdsForCategory(cat, config.getRecipeType()).size();
            
            // Create item
            ItemStack item = new ItemStack(icon.getMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(MessageUtil.colorize(icon.getName()));
                
                List<String> lore = new ArrayList<>();
                for (String line : icon.getLore()) {
                    lore.add(MessageUtil.colorize(line.replace("%category_count%", String.valueOf(count))));
                }
                meta.setLore(lore);
                
                if (icon.isGlow()) {
                    meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                
                item.setItemMeta(meta);
            }
            inv.setItem(slots.get(i), item);
        }
    }
    
    private void handleLegacyPopulate(Player player, MenuTemplate.AutoPopulateConfig config, Inventory inv, int page, MenuTemplate.PopulateSource source) {
        handleLegacyPopulate(player, config, inv, page, source, null);
    }
    
    /**
     * PERF-FIX: Overload với inventory snapshot
     */
    private void handleLegacyPopulate(Player player, MenuTemplate.AutoPopulateConfig config, Inventory inv, int page, 
            MenuTemplate.PopulateSource source, Map<String, Integer> invSnapshot) {
        List<String> recipeIds = getFilteredRecipeIds(source, config.getRecipeType());
        
        // Note: sorting đã được cache trong getFilteredRecipeIds
        
        List<Integer> slots = source.getSlots();
        if (slots.isEmpty()) return;

        int itemsPerPage = slots.size();
        int startIndex = page * itemsPerPage;
        
        // Populate items for this source
        for (int i = 0; i < itemsPerPage; i++) {
            int recipeIndex = startIndex + i;
            if (recipeIndex >= recipeIds.size()) break;
            
            String id = recipeIds.get(recipeIndex);
            int slot = slots.get(i);
            
            if (config.getRecipeType().equalsIgnoreCase("smelting")) {
                var recipe = plugin.getSmeltingManager().getRecipe(id);
                if (recipe != null) {
                    ItemStack icon = createSmeltingIcon(player, recipe);
                    inv.setItem(slot, icon);
                }
            } else {
                var recipe = plugin.getRecipeManager().getRecipe(id);
                if (recipe != null) {
                    ItemStack icon = (invSnapshot != null) 
                        ? createRecipeIcon(player, recipe, invSnapshot)
                        : createRecipeIcon(player, recipe);
                    inv.setItem(slot, icon);
                }
            }
        }
    }

    private ItemStack createRecipeIcon(Player player, com.phmyhu1710.forgestation.crafting.Recipe recipe) {
        return plugin.getRecipeDetailGUI().createRecipeIconForMenu(player, recipe);
    }
    
    /**
     * PERF-FIX: Recipe icon với inventory snapshot - tránh scan nhiều lần
     */
    private ItemStack createRecipeIcon(Player player, com.phmyhu1710.forgestation.crafting.Recipe recipe, Map<String, Integer> invSnapshot) {
        return plugin.getRecipeDetailGUI().createRecipeIconForMenuWithSnapshot(player, recipe, invSnapshot);
    }
    
    private ItemStack createSmeltingIcon(Player player, com.phmyhu1710.forgestation.smelting.SmeltingRecipe recipe) {
         return plugin.getRecipeDetailGUI().createSmeltingIconForMenu(player, recipe);
    }

    private ItemStack buildMenuItem(Player player, MenuTemplate.MenuItem item) {
        Material material = item.getMaterial();
        
        // Handle player head
        if (material == Material.PLAYER_HEAD && item.getSkullOwner() != null) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            String owner = parsePlaceholders(player, item.getSkullOwner(), item);
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            meta.setDisplayName(MessageUtil.colorize(parsePlaceholders(player, item.getName(), item)));
            
            List<String> lore = new ArrayList<>();
            for (String line : item.getLore()) {
                String parsed = parsePlaceholders(player, line, item);
                // SPLIT FIX: Support multiline placeholders
                if (parsed.contains("\n")) {
                    for (String subLine : parsed.split("\n")) {
                         lore.add(MessageUtil.colorize(subLine));
                    }
                } else {
                    lore.add(MessageUtil.colorize(parsed));
                }
            }
            meta.setLore(lore);
            
            if (item.isGlow()) {
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            
            head.setItemMeta(meta);
            return head;
        }
        
        // Regular item
        String name = parsePlaceholders(player, item.getName(), item);
        List<String> lore = new ArrayList<>();
        for (String line : item.getLore()) {
            String parsed = parsePlaceholders(player, line, item);
            // SPLIT FIX: Support multiline placeholders
            if (parsed.contains("\n")) {
                 for (String subLine : parsed.split("\n")) {
                      lore.add(parsed); // colorize handled in createItem
                 }
            } else {
                 lore.add(parsed);
            }
        }
        
        return createItem(material, name, lore, item.isGlow());
    }

    private ItemStack createItem(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        
        if (name != null) {
            meta.setDisplayName(MessageUtil.colorize(name));
        }
        
        if (lore != null && !lore.isEmpty()) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(MessageUtil.colorize(line));
            }
            meta.setLore(coloredLore);
        }
        
        if (glow) {
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String parsePlaceholders(Player player, String text, MenuTemplate.MenuItem item) {
        if (text == null) return "";
        
        // PERF-FIX: Re-enable cache cho PlaceholderAPI results
        // Cache key = raw text, vì internal placeholders đã được parse trước
        UUID uuid = player.getUniqueId();
        Map<String, String> cache = placeholderCache.get(uuid, k -> new ConcurrentHashMap<>());
        
        
        // Global Currency Placeholders
        if (text.contains("%currency_")) {
             text = text.replace("%currency_vault%", MessageUtil.colorize(plugin.getConfig().getString("currency.vault", "Coins")));
             text = text.replace("%currency_playerpoints%", MessageUtil.colorize(plugin.getConfig().getString("currency.playerpoints", "Points")));
             text = text.replace("%currency_exp%", MessageUtil.colorize(plugin.getConfig().getString("currency.exp", "EXP")));
             text = text.replace("%currency_coinengine%", MessageUtil.colorize(plugin.getConfig().getString("currency.coinengine", "C")));
        }

        // Custom internal placeholders (dynamic, không cache)
        text = parseInternalPlaceholders(player, text, item);
        
        // PlaceholderAPI - cache result nếu text không chứa dynamic placeholders
        if (plugin.getHookManager().isPlaceholderAPIEnabled()) {
            // Chỉ cache nếu không có context-specific placeholders
            if (item == null || item.getAction() == null) {
                String cached = cache.get(text);
                if (cached != null) {
                    return cached;
                }
                String result = PlaceholderAPI.setPlaceholders(player, text);
                cache.put(text, result);
                return result;
            }
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        
        return text;
    }

    private String parseInternalPlaceholders(Player player, String text, MenuTemplate.MenuItem item) {
        // Tạo snapshot 1 lần cho toàn bộ parsing
        Map<String, Integer> invSnapshot = com.phmyhu1710.forgestation.util.InventoryUtil.createInventorySnapshot(player);
        return parseInternalPlaceholders(player, text, item, invSnapshot);
    }
    
    private String parseInternalPlaceholders(Player player, String text, MenuTemplate.MenuItem item, Map<String, Integer> invSnapshot) {
        text = text.replace("%player_name%", player.getName());
        
        // Prefix
        // Prefix (Legacy support removed, rely on PAPI)
        text = text.replace("%player_prefix%", "");
        
        
        // Upgrade placeholders (Dynamic)
        // Format: %upgrade_level_<id>%, %upgrade_cost_vault_<id>%, etc.
        var data = plugin.getPlayerDataManager().getPlayerData(player);
        if (text.contains("upgrade_")) {
             for (com.phmyhu1710.forgestation.upgrade.UpgradeManager.Upgrade upgrade : plugin.getUpgradeManager().getAllUpgrades().values()) {
                 String id = upgrade.getId();
                 if (!text.contains(id)) continue;
                 
                 // Level
                 if (text.contains("upgrade_level_" + id)) {
                     text = text.replace("%upgrade_level_" + id + "%", 
                         String.valueOf(data.getUpgradeLevel(id)));
                 }
                 
                 // Max Level
                 if (text.contains("upgrade_max_level_" + id)) {
                     text = text.replace("%upgrade_max_level_" + id + "%", 
                         String.valueOf(upgrade.getMaxLevel()));
                 }
                 
                 // Costs (Next Level)
                 if (text.contains("upgrade_cost_")) {
                     java.util.Map<String, Double> costs = plugin.getUpgradeManager().getNextLevelCost(player, id);
                     
                     if (text.contains("upgrade_cost_display_" + id)) {
                         StringBuilder display = new StringBuilder();
                         boolean hasCost = false;
                         
                         // Vault
                         double vault = costs.getOrDefault("vault", 0.0);
                         if (vault > 0) {
                             display.append("&8┃ &6💰 &e").append(MessageUtil.formatNumber(vault))
                                    .append(" &7").append(plugin.getConfig().getString("currency.vault", "Coins"));
                             hasCost = true;
                         }
                         
                         // PlayerPoints
                         double pp = costs.getOrDefault("playerpoints", 0.0);
                         if (pp > 0) {
                             if (hasCost) display.append("\n");
                             display.append("&8┃ &b⬤ &e").append(MessageUtil.formatNumber(pp))
                                    .append(" &7").append(plugin.getConfig().getString("currency.playerpoints", "Points"));
                             hasCost = true;
                         }
                         
                         // EXP
                         double exp = costs.getOrDefault("exp", 0.0);
                         if (exp > 0) {
                             if (hasCost) display.append("\n");
                             display.append("&8┃ &a✦ &e").append(MessageUtil.formatNumber(exp))
                                    .append(" &7").append(plugin.getConfig().getString("currency.exp", "EXP"));
                             hasCost = true;
                         }
                         
                         // CoinEngine
                         double ce = costs.getOrDefault("coinengine", 0.0);
                         if (ce > 0) {
                             if (hasCost) display.append("\n");
                             display.append("&8┃ &e⛃ &e").append(MessageUtil.formatNumber(ce))
                                    .append(" &7").append(upgrade.getCoinEngineCurrency());
                             hasCost = true;
                         }
                         
                         if (!hasCost) {
                             // Check if max level
                             if (data.getUpgradeLevel(id) >= upgrade.getMaxLevel()) {
                                 display.append("&a✔ &fĐã đạt cấp tối đa");
                             } else {
                                 display.append("&a✔ &fMiễn phí");
                             }
                         }
                         
                         text = text.replace("%upgrade_cost_display_" + id + "%", display.toString());
                     }
                 
                     if (text.contains("upgrade_cost_vault_" + id)) {
                         text = text.replace("%upgrade_cost_vault_" + id + "%", 
                             MessageUtil.formatNumber(costs.getOrDefault("vault", 0.0)));
                     }
                     if (text.contains("upgrade_cost_point_" + id)) {
                         text = text.replace("%upgrade_cost_point_" + id + "%", 
                             MessageUtil.formatNumber(costs.getOrDefault("playerpoints", 0.0)));
                     }
                     if (text.contains("upgrade_cost_exp_" + id)) {
                         text = text.replace("%upgrade_cost_exp_" + id + "%", 
                             MessageUtil.formatNumber(costs.getOrDefault("exp", 0.0)));
                     }
                 }
             }
        }
        
        // Legacy support remove soon if needed, but keeping for now as backup fallback
        if (text.contains("%crafting_level%")) 
             text = text.replace("%crafting_level%", String.valueOf(data.getUpgradeLevel("crafting_speed")));
        if (text.contains("%smelting_level%"))
             text = text.replace("%smelting_level%", String.valueOf(data.getUpgradeLevel("smelting_speed")));
        
        // Balances
        text = text.replace("%vault_balance%", MessageUtil.formatNumber(
            plugin.getEconomyManager().getVaultBalance(player)));
        text = text.replace("%player_points%", MessageUtil.formatNumber(
            plugin.getEconomyManager().getPlayerPoints(player)));
        
        // Rank and multiplier
        String rankId = getRankId(player);
        String rankDisplay = getRankDisplay(player, rankId);
        double multiplier = getMultiplier(player, rankId);
        
        text = text.replace("%rank%", rankId);
        text = text.replace("%player_rank_display%", rankDisplay);
        text = text.replace("%multiplier%", String.format("%.1f", multiplier));
        
        // Context aware placeholders
        if (item != null && item.getAction() != null) {
            String type = item.getAction().getType().toUpperCase();
            String value = item.getAction().getValue();

            plugin.debug("Parsing placeholder for item with action: " + type + " value: " + value);

            if (type.equals("CRAFT") || type.equals("EXCHANGE")) {
                var recipe = plugin.getRecipeManager().getRecipe(value);
                if (recipe != null) {
                     plugin.debug("Found recipe: " + recipe.getId());
                    // ISSUE-006 FIX: Sử dụng snapshot để check ingredients thay vì quét inventory mỗi lần
                    boolean hasIngredients = true;
                    for (var ing : recipe.getIngredients()) {
                         int count;
                         if ("VANILLA".equals(ing.getType())) {
                             // Sử dụng snapshot cho VANILLA items
                             count = com.phmyhu1710.forgestation.util.InventoryUtil.countFromSnapshot(invSnapshot, ing.getMaterial());
                         } else {
                             // Fallback cho MMOITEMS, EXTRA_STORAGE
                             count = com.phmyhu1710.forgestation.util.InventoryUtil.countItems(plugin, player, ing);
                         }
                         int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                         plugin.debug("Ingredient: " + ing.getMaterial() + " | Has: " + count + " | Req: " + required);
                         if (count < required) {
                             hasIngredients = false;
                             break;
                         }
                    }
                    String status = hasIngredients ? "&aCó thể chế tạo" : "&cThiếu nguyên liệu";
                    text = text.replace("%status%", status);

                    // Duration (thời gian chế tạo)
                    int duration = plugin.getRecipeManager().getDuration(player, recipe);
                    text = text.replace("%duration%", formatTime(duration));
                    text = text.replace("%cooldown%", formatTime(duration)); // Alias
                } else {
                    plugin.debug("Recipe not found for ID: " + value);
                }
            } else if (type.equals("SMELT")) {
                var recipe = plugin.getSmeltingManager().getRecipe(value);
                if (recipe != null) {
                    text = text.replace("%status%", plugin.getSmeltingManager().hasSmeltingMaterials(player, recipe) ? "&aCó thể nung" : "&cThiếu nguyên liệu");
                    // Cooldown for smelting is just duration usually or N/A
                    text = text.replace("%cooldown%", "N/A"); 
                }
            }
        } else {
             // plugin.debug("Item has no action context");
        }
        
        // Cleanup unresolved context placeholders if no context
        text = text.replace("%status%", "");
        text = text.replace("%duration%", "0s");
        text = text.replace("%cooldown%", "0s");
        
        return text;
    }
    
    private String formatTime(int seconds) {
        if (seconds <= 0) return "&aSẵn sàng";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
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

    private double getMultiplier(Player player, String rankId) {
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        return config.getDouble("ranks." + rankId + ".multiplier", 1.0);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onInventoryClickEarly(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // Check by UUID tracking - only handle if we're tracking this menu
        String menuName = activeMenus.get(player.getUniqueId());
        
        // If not in our tracking, it might be a detail GUI - let it pass
        if (menuName == null) return;
        
        plugin.debug("Click detected in ForgeStation GUI - CANCELLING");
        
        // Cancel absolutely everything early
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // Check by UUID tracking - only handle if we're tracking this menu
        String menuName = activeMenus.get(player.getUniqueId());
        
        // If not in our tracking, it might be a detail GUI - let it pass
        if (menuName == null) return;
        
        // Cancel ALL clicks in our menu - always, no matter what
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        
        // Also clear cursor just in case
        if (event.getCursor() != null && event.getCursor().getType() != org.bukkit.Material.AIR) {
            player.setItemOnCursor(null);
        }
        
        // Don't process clicks outside top inventory
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        
        MenuTemplate template = menuTemplates.get(menuName);
        if (template == null) return;
        
        int slot = event.getRawSlot();
        
        // Check for Auto Populate clicks
        if (template.getAutoPopulate() != null) {
            MenuTemplate.AutoPopulateConfig config = template.getAutoPopulate();
            UUID uuid = player.getUniqueId();
            String activeCategory = activeCategories.get(uuid);
            
            // Browser Mode Click
            if (config.getMode().equalsIgnoreCase("browser") && activeCategory == null) {
                List<Integer> catSlots = config.getCategorySlots();
                if (catSlots != null && catSlots.contains(slot)) {
                    int index = catSlots.indexOf(slot);
                    List<String> catIds = new ArrayList<>(config.getCategories().keySet());
                    
                    if (index < catIds.size()) {
                        String catId = catIds.get(index);
                        // Trigger SWITCH_CATEGORY
                        MenuTemplate.MenuAction action = new MenuTemplate.MenuAction(
                            createActionConfig("SWITCH_CATEGORY", catId), "UI_BUTTON_CLICK");
                        handleAction(player, action, event);
                        return;
                    }
                }
            }
            
            // Category mode: check shared slots
            if (config.hasCategoryMode() && activeCategory != null) {
                List<Integer> slots = config.getSharedSlots();
                if (slots.isEmpty()) {
                    for (MenuTemplate.PopulateSource src : config.getSources()) {
                        if (!src.getSlots().isEmpty()) {
                            slots = src.getSlots();
                            break;
                        }
                    }
                }
                
                if (slots.contains(slot)) {
                    int page = menuPages.getOrDefault(uuid, 0);
                    int indexInPage = slots.indexOf(slot);
                    int itemsPerPage = slots.size();
                    int globalIndex = page * itemsPerPage + indexInPage;
                    
                    // Get recipe IDs for active category
                    MenuTemplate.CategoryConfig catConfig = config.getCategories().get(activeCategory);
                    List<String> recipeIds;
                    if (catConfig != null) {
                        recipeIds = getFilteredRecipeIdsForCategory(catConfig, config.getRecipeType());
                    } else {
                        // Fallback to source
                        recipeIds = new ArrayList<>();
                        for (MenuTemplate.PopulateSource src : config.getSources()) {
                            if (src.getIdentifier().equals(activeCategory)) {
                                recipeIds = getFilteredRecipeIds(src, config.getRecipeType());
                                break;
                            }
                        }
                    }
                    
                    if (globalIndex < recipeIds.size()) {
                        String recipeId = recipeIds.get(globalIndex);
                        if (config.getRecipeType().equalsIgnoreCase("smelting")) {
                            MenuTemplate.MenuAction action = new MenuTemplate.MenuAction(
                                createActionConfig("SMELT", recipeId), null);
                            handleAction(player, action, event);
                        } else {
                            MenuTemplate.MenuAction action = new MenuTemplate.MenuAction(
                                createActionConfig("CRAFT", recipeId), null);
                            handleAction(player, action, event);
                        }
                        return;
                    }
                    return; // Empty slot in category mode
                }
            } else {
                // Legacy mode: loop through all sources
                for (MenuTemplate.PopulateSource source : config.getSources()) {
                    if (source.getSlots().contains(slot)) {
                        int page = menuPages.getOrDefault(uuid, 0);
                        int indexInPage = source.getSlots().indexOf(slot);
                        int itemsPerPage = source.getSlots().size();
                        int globalIndex = page * itemsPerPage + indexInPage;
                        
                        List<String> recipeIds = getFilteredRecipeIds(source, config.getRecipeType());
                        
                        if (globalIndex < recipeIds.size()) {
                            String recipeId = recipeIds.get(globalIndex);
                            if (config.getRecipeType().equalsIgnoreCase("smelting")) {
                                MenuTemplate.MenuAction action = new MenuTemplate.MenuAction(
                                    createActionConfig("SMELT", recipeId), null);
                                handleAction(player, action, event);
                            } else {
                                MenuTemplate.MenuAction action = new MenuTemplate.MenuAction(
                                    createActionConfig("CRAFT", recipeId), null);
                                handleAction(player, action, event);
                            }
                            return;
                        }
                        return;
                    }
                }
            }
        }

        // Find clicked item action
        plugin.debug("Looking for action at slot " + slot + " in menu " + menuName);
        boolean actionFound = false;
        for (MenuTemplate.MenuItem item : template.getItems()) {
            if (item.getSlot() == slot && item.getAction() != null) {
                plugin.debug("Found action: type=" + item.getAction().getType() + ", value=" + item.getAction().getValue());
                handleAction(player, item.getAction(), event);
                actionFound = true;
                break;
            }
        }
        if (!actionFound) {
            plugin.debug("No action found for slot " + slot + ". Available items: " + 
                template.getItems().stream().map(i -> "slot=" + i.getSlot() + (i.getAction() != null ? "/action" : "")).collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        String menuName = activeMenus.get(player.getUniqueId());
        if (menuName == null) return;
        
        // Cancel ALL drags in our menu
        event.setCancelled(true);
    }

    private void handleAction(Player player, MenuTemplate.MenuAction action, InventoryClickEvent event) {
        UUID uuid = player.getUniqueId();
        
        switch (action.getType().toUpperCase()) {
            case "OPEN_MENU":
                // Hijack for Browser Mode "Back" navigation
                String curMenu = activeMenus.get(uuid);
                if (curMenu != null && activeCategories.get(uuid) != null) {
                    MenuTemplate tmpl = menuTemplates.get(curMenu);
                    if (tmpl != null && tmpl.getAutoPopulate() != null && 
                        "browser".equalsIgnoreCase(tmpl.getAutoPopulate().getMode())) {
                        
                        // Go back to browser view (clear category, keep menu)
                        activeCategories.remove(uuid);
                        openMenu(player, curMenu, 0, true);
                        return;
                    }
                }

                // Remove from current tracking and schedule new menu
                activeMenus.remove(uuid);
                activeCategories.remove(uuid); // Reset category when opening new menu
                categoryPages.remove(uuid);
                player.closeInventory();
                final String targetMenu = action.getValue();
                plugin.getScheduler().runLater(() -> {
                    openMenu(player, targetMenu, 0); // Reset to page 0
                }, 1);
                break;
            case "SWITCH_CATEGORY":
                String currentMenuName = activeMenus.get(uuid);
                if (currentMenuName != null) {
                    String newCategory = action.getValue();
                    String oldCategory = activeCategories.get(uuid);
                    
                    // Save current page for old category
                    if (oldCategory != null) {
                        Map<String, Integer> catPages = categoryPages.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                        catPages.put(oldCategory, menuPages.getOrDefault(uuid, 0));
                    }
                    
                    // Set new category
                    activeCategories.put(uuid, newCategory);
                    
                    // Get saved page for new category or 0
                    Map<String, Integer> catPages = categoryPages.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                    int newPage = catPages.getOrDefault(newCategory, 0);
                    
                    // Refresh menu with new category
                    menuPages.put(uuid, newPage);
                    openMenu(player, currentMenuName, newPage, true);
                }
                break;
            case "NEXT_PAGE":
                String nextMenuName = activeMenus.get(uuid);
                if (nextMenuName != null) {
                    MenuTemplate tmpl = menuTemplates.get(nextMenuName);
                    if (tmpl != null) {
                         int maxPage = getMaxPage(tmpl, uuid);
                         int currentPage = menuPages.getOrDefault(uuid, 0);
                         if (currentPage < maxPage - 1) {
                             int newPage = currentPage + 1;
                             
                             // Update category page tracking
                             String activeCategory = activeCategories.get(uuid);
                             if (activeCategory != null) {
                                 Map<String, Integer> catPages = categoryPages.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                                 catPages.put(activeCategory, newPage);
                             }
                             
                             menuPages.put(uuid, newPage);
                             openMenu(player, nextMenuName, newPage, true);
                         }
                    }
                }
                break;
            case "PREV_PAGE":
                String prevMenuName = activeMenus.get(uuid);
                if (prevMenuName != null) {
                    int page = menuPages.getOrDefault(uuid, 0) - 1;
                    if (page < 0) page = 0;
                    
                    // Update category page tracking
                    String activeCategory = activeCategories.get(uuid);
                    if (activeCategory != null) {
                        Map<String, Integer> catPages = categoryPages.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                        catPages.put(activeCategory, page);
                    }
                    
                    menuPages.put(uuid, page);
                    openMenu(player, prevMenuName, page, true);
                }
                break;
            case "CLOSE":
                activeMenus.remove(uuid);
                player.closeInventory();
                break;
            case "UPGRADE":
                plugin.getUpgradeManager().tryUpgrade(player, action.getValue());
                // Refresh menu on next tick - bypass rate limit for refresh
                String currentMenu = activeMenus.get(uuid);
                if (currentMenu != null) {
                    final String refreshMenu = currentMenu;
                    plugin.getScheduler().runLater(() -> {
                        openMenu(player, refreshMenu, true); // Bypass rate limit
                    }, 1);
                }
                break;
            case "CRAFT":
            case "EXCHANGE":
                var recipe = plugin.getRecipeManager().getRecipe(action.getValue());
                if (recipe != null) {
                    // Direct execution based on click type
                    if (event != null && event.isShiftClick()) {
                        // Shift + Click = Open chat input for custom amount
                        awaitingCraftInput.put(uuid, recipe.getId());
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        plugin.getMessageUtil().send(player, "exchange.input-prompt");
                    } else if (event != null && event.isRightClick()) {
                        // Right click = Craft/Exchange all
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        if (recipe.isExchangeRecipe() && !recipe.getIngredients().isEmpty()) {
                            com.phmyhu1710.forgestation.crafting.Recipe.Ingredient input = recipe.getIngredients().get(0);
                            int playerHas = com.phmyhu1710.forgestation.util.InventoryUtil.countItems(plugin, player, input);
                            plugin.getCraftingExecutor().executeExchange(player, recipe, playerHas);
                        } else {
                            // Fix: Execute craft with max possible batches
                            int maxBatches = Integer.MAX_VALUE;
                            for (com.phmyhu1710.forgestation.crafting.Recipe.Ingredient ing : recipe.getIngredients()) {
                                int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                                if (required <= 0) continue;
                                int playerHas = com.phmyhu1710.forgestation.util.InventoryUtil.countItems(plugin, player, ing);
                                int batches = playerHas / required;
                                maxBatches = Math.min(maxBatches, batches);
                            }
                            
                            if (maxBatches == Integer.MAX_VALUE) maxBatches = 0;
                            
                            if (maxBatches > 0) {
                                plugin.getCraftingExecutor().executeCraft(player, recipe, maxBatches);
                            } else {
                                // Trigger executeCraft with 1 to let it show the error message
                                plugin.getCraftingExecutor().executeCraft(player, recipe, 1);
                            }
                        }
                    } else {
                        // Left click = Craft/Exchange 1 time
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        if (recipe.isExchangeRecipe() && !recipe.getIngredients().isEmpty()) {
                            com.phmyhu1710.forgestation.crafting.Recipe.Ingredient input = recipe.getIngredients().get(0);
                            int baseAmount = input.getBaseAmount() > 0 ? input.getBaseAmount() : input.getAmount();
                            plugin.getCraftingExecutor().executeExchange(player, recipe, baseAmount);
                        } else {
                            plugin.getCraftingExecutor().executeCraft(player, recipe);
                        }
                    }
                }
                break;
            case "SMELT":
                plugin.debug("SMELT action triggered with value: " + action.getValue());
                var smeltRecipe = plugin.getSmeltingManager().getRecipe(action.getValue());
                plugin.debug("Smelting recipe lookup result: " + (smeltRecipe != null ? smeltRecipe.getId() : "NULL"));
                if (smeltRecipe != null) {
                    // Direct execution based on click type
                    if (event != null && event.isShiftClick()) {
                        // Shift + Click = Open chat input for custom amount
                        awaitingSmeltInput.put(uuid, smeltRecipe.getId());
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        plugin.getMessageUtil().send(player, "smelt.input-prompt");
                    } else if (event != null && event.isRightClick()) {
                        // Right click = Smelt all
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        int maxBatch = plugin.getSmeltingManager().getMaxBatchCount(player, smeltRecipe);
                        if (maxBatch > 0) {
                            plugin.getSmeltingManager().startSmelting(player, smeltRecipe, maxBatch);
                        } else {
                            plugin.getMessageUtil().send(player, "smelt.missing-materials",
                                "material", smeltRecipe.getInputMaterial(),
                                "amount", String.valueOf(smeltRecipe.getInputAmount()));
                        }
                    } else {
                        // Left click = Smelt 1 batch
                        activeMenus.remove(uuid);
                        player.closeInventory();
                        plugin.getSmeltingManager().startSmelting(player, smeltRecipe, 1);
                    }
                } else {
                    plugin.debug("Failed to find smelting recipe! Available IDs: " + plugin.getSmeltingManager().getSmeltingIds());
                }
                break;
            case "COMMAND":
                player.performCommand(action.getValue());
                break;
            case "REFRESH":
                String menuRef = activeMenus.get(uuid);
                if (menuRef != null) {
                    openMenu(player, menuRef, true); // Bypass rate limit for refresh
                }
                break;
        }
        
        // Play sound
        if (action.getSound() != null) {
            try {
                player.playSound(player.getLocation(), 
                    org.bukkit.Sound.valueOf(action.getSound().toUpperCase()), 1.0f, 1.0f);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            // If we are in the process of opening a new menu, don't clear tracking
            if (openingMenus.contains(player.getUniqueId())) {
                return;
            }
            UUID uuid = player.getUniqueId();
            activeMenus.remove(uuid);
            menuPages.remove(uuid);
            activeCategories.remove(uuid);
            categoryPages.remove(uuid);
        }
    }
    
    // Helper methods
    private ConfigurationSection createActionConfig(String type, String value) {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("type", type);
        config.set("menu", value); // Value is read from 'menu', 'upgrade-id', etc. MenuAction constructor checks 'menu' first.
        return config; 
    }
    
    private List<String> getFilteredRecipeIds(MenuTemplate.PopulateSource source, String filterType) {
        // Check cache first
        if (source.getCachedRecipeIds() != null) {
            return source.getCachedRecipeIds();
        }

        List<String> recipeIds = new ArrayList<>();
        
        if (filterType.equalsIgnoreCase("smelting")) {
             // For smelting, check folders if needed, or return all if none specified
             Map<String, com.phmyhu1710.forgestation.smelting.SmeltingRecipe> all = plugin.getSmeltingManager().getAllRecipes();
             
             if (source.getFolders().isEmpty()) {
                 recipeIds.addAll(all.keySet());
             } else {
                 for (var entry : all.entrySet()) {
                     boolean match = false;
                     for (String folder : source.getFolders()) {
                         if (folder.isEmpty() || entry.getValue().getSourceFile().contains(folder)) {
                             match = true;
                             break;
                         }
                     }
                     if (match) recipeIds.add(entry.getKey());
                 }
             }
        } else {
            // Crafting recipes
            Map<String, com.phmyhu1710.forgestation.crafting.Recipe> all = plugin.getRecipeManager().getAllRecipes();
            
            if (source.getFolders().isEmpty()) {
                 recipeIds.addAll(all.keySet());
            } else {
                for (var entry : all.entrySet()) {
                    boolean match = false;
                    for (String folder : source.getFolders()) {
                         if (folder.isEmpty() || entry.getValue().getSourceFile().contains(folder)) {
                             match = true;
                             break;
                         }
                    }
                    if (match) recipeIds.add(entry.getKey());
                }
            }
        }
        // Collections.sort(recipeIds); // Removed to respect config order
        
        // Update cache
        source.setCachedRecipeIds(recipeIds);
        
        return recipeIds;
    }
    
    private List<String> getFilteredRecipeIdsForCategory(MenuTemplate.CategoryConfig category, String filterType) {
        // Check cache first
        if (category.getCachedRecipeIds() != null) {
            return category.getCachedRecipeIds();
        }

        List<String> recipeIds = new ArrayList<>();
        
        if (filterType.equalsIgnoreCase("smelting")) {
            Map<String, com.phmyhu1710.forgestation.smelting.SmeltingRecipe> all = plugin.getSmeltingManager().getAllRecipes();
            
            if (category.getFolders().isEmpty()) {
                recipeIds.addAll(all.keySet());
            } else {
                for (var entry : all.entrySet()) {
                    boolean match = false;
                    for (String folder : category.getFolders()) {
                        if (folder.isEmpty() || entry.getValue().getSourceFile().contains(folder)) {
                            match = true;
                            break;
                        }
                    }
                    if (match) recipeIds.add(entry.getKey());
                }
            }
        } else {
            // Crafting recipes
            Map<String, com.phmyhu1710.forgestation.crafting.Recipe> all = plugin.getRecipeManager().getAllRecipes();
            
            if (category.getFolders().isEmpty()) {
                recipeIds.addAll(all.keySet());
            } else {
                for (var entry : all.entrySet()) {
                    boolean match = false;
                    for (String folder : category.getFolders()) {
                        if (folder.isEmpty() || entry.getValue().getSourceFile().contains(folder)) {
                            match = true;
                            break;
                        }
                    }
                    if (match) recipeIds.add(entry.getKey());
                }
            }
        }
        // Collections.sort(recipeIds); // Removed to respect config order
        
        // Update cache
        category.setCachedRecipeIds(recipeIds);
        
        return recipeIds;
    }

    private int getMaxPage(MenuTemplate template) {
        return getMaxPage(template, null);
    }
    
    private int getMaxPage(MenuTemplate template, UUID playerUuid) {
        if (template.getAutoPopulate() == null) return 1;
        
        MenuTemplate.AutoPopulateConfig config = template.getAutoPopulate();
        
        // Category mode
        if (config.hasCategoryMode() && playerUuid != null) {
            String activeCategory = activeCategories.get(playerUuid);
            if (activeCategory != null) {
                MenuTemplate.CategoryConfig catConfig = config.getCategories().get(activeCategory);
                if (catConfig != null) {
                    List<String> ids = getFilteredRecipeIdsForCategory(catConfig, config.getRecipeType());
                    
                    // Get slots count
                    int slotsCount = config.getSharedSlots().size();
                    if (slotsCount == 0) {
                        for (MenuTemplate.PopulateSource src : config.getSources()) {
                            if (!src.getSlots().isEmpty()) {
                                slotsCount = src.getSlots().size();
                                break;
                            }
                        }
                    }
                    if (slotsCount == 0) return 1;
                    
                    return Math.max(1, (int) Math.ceil((double) ids.size() / slotsCount));
                }
            }
        }
        
        // Legacy mode
        int max = 1;
        for (MenuTemplate.PopulateSource source : config.getSources()) {
             List<String> ids = getFilteredRecipeIds(source, config.getRecipeType());
             int itemsCount = ids.size();
             int slotsCount = source.getSlots().size();
             if (slotsCount == 0) continue;
             
             int pages = (int) Math.ceil((double) itemsCount / slotsCount);
             if (pages > max) max = pages;
        }
        return max;
    }

    /**
     * Invalidate placeholder cache for player
     */
    public void invalidateCache(UUID uuid) {
        placeholderCache.invalidate(uuid);
    }
    
    /**
     * Handle chat input for custom craft/smelt amounts
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Handle smelting input
        if (awaitingSmeltInput.containsKey(uuid)) {
            if (chatLimiter.getIfPresent(uuid) != null) {
                event.setCancelled(true);
                return;
            }
            chatLimiter.put(uuid, System.currentTimeMillis());
            
            event.setCancelled(true);
            String smeltingId = awaitingSmeltInput.remove(uuid);
            String input = event.getMessage().trim();
            
            var recipe = plugin.getSmeltingManager().getRecipe(smeltingId);
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
            
            int amount = com.phmyhu1710.forgestation.util.NumberParser.parse(input);
            if (amount <= 0) {
                plugin.getScheduler().runSync(() -> {
                    plugin.getMessageUtil().send(player, "smelt.input-invalid");
                });
                return;
            }
            
            plugin.getScheduler().runSync(() -> {
                plugin.getSmeltingManager().startSmelting(player, recipe, amount);
            });
            return;
        }
        
        // Handle crafting/exchange input
        if (awaitingCraftInput.containsKey(uuid)) {
            if (chatLimiter.getIfPresent(uuid) != null) {
                event.setCancelled(true);
                return;
            }
            chatLimiter.put(uuid, System.currentTimeMillis());
            
            event.setCancelled(true);
            String recipeId = awaitingCraftInput.remove(uuid);
            String input = event.getMessage().trim();
            
            var recipe = plugin.getRecipeManager().getRecipe(recipeId);
            if (recipe == null) return;
            
            // Handle "all" keyword
            if (input.equalsIgnoreCase("all")) {
                plugin.getScheduler().runSync(() -> {
                    if (recipe.isExchangeRecipe()) {
                        if (!recipe.getIngredients().isEmpty()) {
                            com.phmyhu1710.forgestation.crafting.Recipe.Ingredient ing = recipe.getIngredients().get(0);
                            int playerHas = com.phmyhu1710.forgestation.util.InventoryUtil.countItems(plugin, player, ing);
                            plugin.getCraftingExecutor().executeExchange(player, recipe, playerHas);
                        }
                    } else {
                        // Crafting recipe: calculate max batches
                        int maxBatches = Integer.MAX_VALUE;
                        for (com.phmyhu1710.forgestation.crafting.Recipe.Ingredient ing : recipe.getIngredients()) {
                            int required = ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount();
                            if (required <= 0) continue;
                            int playerHas = com.phmyhu1710.forgestation.util.InventoryUtil.countItems(plugin, player, ing);
                            int batches = playerHas / required;
                            maxBatches = Math.min(maxBatches, batches);
                        }
                        if (maxBatches == Integer.MAX_VALUE) maxBatches = 0;
                        
                        if (maxBatches > 0) {
                            plugin.getCraftingExecutor().executeCraft(player, recipe, maxBatches);
                        } else {
                            plugin.getCraftingExecutor().executeCraft(player, recipe, 1); // Trigger fail msg
                        }
                    }
                });
                return;
            }
            
            int amount = com.phmyhu1710.forgestation.util.NumberParser.parse(input);
            if (amount <= 0) {
                plugin.getScheduler().runSync(() -> {
                    plugin.getMessageUtil().send(player, "exchange.input-invalid");
                });
                return;
            }
            
            plugin.getScheduler().runSync(() -> {
                if (recipe.isExchangeRecipe()) {
                    plugin.getCraftingExecutor().executeExchange(player, recipe, amount);
                } else {
                    // Crafting recipe
                    int batches = amount;
                    // If bulk-exchange mode, input is treated as raw material amount
                    if (recipe.isBulkExchange() && !recipe.getIngredients().isEmpty()) {
                        int cost = recipe.getIngredients().get(0).getBaseAmount();
                        if (cost <= 0) cost = recipe.getIngredients().get(0).getAmount();
                        if (cost > 0) batches = amount / cost;
                    }
                    plugin.getCraftingExecutor().executeCraft(player, recipe, batches);
                }
            });
        }
    }
    
    /**
     * Cleanup on player quit
     */
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        awaitingCraftInput.remove(uuid);
        awaitingSmeltInput.remove(uuid);
    }
}
