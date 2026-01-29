package com.phmyhu1710.forgestation;

import com.phmyhu1710.forgestation.command.ForgeStationCommand;
import com.phmyhu1710.forgestation.config.ConfigManager;
import com.phmyhu1710.forgestation.crafting.CraftingExecutor;
import com.phmyhu1710.forgestation.crafting.RecipeManager;
import com.phmyhu1710.forgestation.economy.EconomyManager;
import com.phmyhu1710.forgestation.hook.ExtraStorageHook;
import com.phmyhu1710.forgestation.hook.HookManager;
import com.phmyhu1710.forgestation.menu.MenuManager;
import com.phmyhu1710.forgestation.menu.RecipeDetailGUI;
import com.phmyhu1710.forgestation.placeholder.ForgeStationExpansion;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import com.phmyhu1710.forgestation.scheduler.SchedulerAdapter;
import com.phmyhu1710.forgestation.smelting.SmeltingManager;
import com.phmyhu1710.forgestation.upgrade.UpgradeManager;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.OutputRouter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ForgeStation - Advanced crafting and smelting system
 * @author phmyhu_1710
 */
public class ForgeStationPlugin extends JavaPlugin {

    private static ForgeStationPlugin instance;
    
    // Managers
    private ConfigManager configManager;
    private MessageUtil messageUtil;
    private HookManager hookManager;
    private ExtraStorageHook extraStorageHook;
    private EconomyManager economyManager;
    private RecipeManager recipeManager;
    private CraftingExecutor craftingExecutor;
    private SmeltingManager smeltingManager;
    private UpgradeManager upgradeManager;
    private PlayerDataManager playerDataManager;
    private MenuManager menuManager;
    private RecipeDetailGUI recipeDetailGUI;
    private SchedulerAdapter scheduler;
    // ISSUE-014 FIX: Output router for configurable item delivery
    private OutputRouter outputRouter;
    private com.phmyhu1710.forgestation.command.CustomCommandManager customCommandManager;
    
    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        
        // Print startup banner
        printBanner();
        
        // Load configurations
        configManager = new ConfigManager(this);
        configManager.loadAll();
        
        // Initialize scheduler (Folia/Spigot)
        initScheduler();
        
        // Initialize message utility
        messageUtil = new MessageUtil(this);
        
        // Initialize hooks (Vault, PlayerPoints, etc.)
        hookManager = new HookManager(this);
        hookManager.setupHooks();
        
        // Initialize ExtraStorage hook
        extraStorageHook = new ExtraStorageHook(this);
        
        // Initialize economy manager
        economyManager = new EconomyManager(this);
        
        // Initialize player data manager
        playerDataManager = new PlayerDataManager(this);
        
        // Initialize upgrade manager
        upgradeManager = new UpgradeManager(this);
        
        // Initialize custom command manager
        customCommandManager = new com.phmyhu1710.forgestation.command.CustomCommandManager(this);
        
        // Initialize recipe manager
        recipeManager = new RecipeManager(this);
        recipeManager.loadRecipes();
        
        // ISSUE-014 FIX: Initialize output router
        outputRouter = new OutputRouter(this);
        
        // Initialize crafting executor
        craftingExecutor = new CraftingExecutor(this);
        
        // Initialize smelting manager
        smeltingManager = new SmeltingManager(this);
        smeltingManager.loadSmeltingRecipes();
        
        // Initialize menu manager
        menuManager = new MenuManager(this);
        menuManager.loadMenus();
        
        // Initialize recipe detail GUI
        recipeDetailGUI = new RecipeDetailGUI(this);
        
        // Register commands
        registerCommands();
        
        // Register PlaceholderAPI expansion
        if (hookManager.isPlaceholderAPIEnabled()) {
            new ForgeStationExpansion(this).register();
            log("&a✓ &7PlaceholderAPI expansion registered");
        }
        
        // EXTERNAL RELOAD FIX: Restore tasks cho online players sau khi khởi tạo xong
        // Điều này cần thiết khi plugin được reload bởi PlugMan/PlugManX
        scheduler.runLater(() -> restoreTasksForOnlinePlayers(), 20L);
        
        long took = System.currentTimeMillis() - start;
        printStartupSummary(took);
    }
    
    @Override
    public void onDisable() {
        try {
            // Unregister custom commands
            if (customCommandManager != null) {
                customCommandManager.unregisterAll();
            }

            // ⚠️ CRITICAL ORDER: Phải lưu tasks TRƯỚC KHI đóng database!
            
            // 1. Cancel và lưu tất cả smelting tasks vào database
            if (smeltingManager != null) {
                smeltingManager.cancelAllTasks();
            }
            
            // 2. Cancel và lưu tất cả crafting tasks vào database
            if (craftingExecutor != null) {
                craftingExecutor.cancelAllTasks();
            }
            
            // 3. SAU KHI đã lưu tasks xong, mới đóng database
            if (playerDataManager != null) {
                playerDataManager.shutdown();
            }
            
            log("");
            log("&c&l✦ ForgeStation &7v" + getDescription().getVersion() + " &cdisabled!");
            log("&7Thank you for using &6ForgeStation&7!");
            log("");
        } finally {
            // ISSUE-001 FIX: Cắt reference tĩnh để GC có thể thu dọn khi reload
            instance = null;
        }
    }
    
    private void printBanner() {
        log("");
        log("&6&l╔═══════════════════════════════════════╗");
        log("&6&l║          &e&lFORGE&6&lSTATION                 &6&l║");
        log("&6&l║   &7Advanced Crafting & Smelting        &6&l║");
        log("&6&l╚═══════════════════════════════════════╝");
        log("&7  Author: &ephmyhu_1710");
        log("&7  Version: &e" + getDescription().getVersion());
        log("");
    }
    
    private void printStartupSummary(long took) {
        log("");
        log("&8┌────────────────────────────────────────┐");
        log("&8│ &a&l✓ &fForgeStation loaded successfully!  &8  │");
        log("&8├────────────────────────────────────────┤");
        log("&8│ &7Recipes:     &e" + padRight(String.valueOf(recipeManager.getRecipeCount()), 24) + "&8  │");
        log("&8│ &7Smelting:    &e" + padRight(String.valueOf(smeltingManager.getSmeltingCount()), 24) + "&8  │");
        log("&8│ &7Upgrades:    &e" + padRight(String.valueOf(upgradeManager.getAllUpgrades().size()), 24) + "&8  │");
        log("&8│ &7Menus:       &e" + padRight(String.valueOf(menuManager.getMenuCount()), 24) + "&8  │");
        log("&8├────────────────────────────────────────┤");
        log("&8│ &7Load time:   &a" + padRight(took + "ms", 24) + "&8  │");
        log("&8└────────────────────────────────────────┘");
        log("");
    }
    
    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
    
    private void log(String message) {
        Bukkit.getConsoleSender().sendMessage(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message));
    }
    
    private void initScheduler() {
        try {
            // Check if Folia
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            scheduler = new com.phmyhu1710.forgestation.scheduler.FoliaScheduler(this);
            debug("Detected Folia - using RegionScheduler");
        } catch (ClassNotFoundException e) {
            scheduler = new com.phmyhu1710.forgestation.scheduler.BukkitScheduler(this);
            debug("Using Bukkit scheduler");
        }
    }
    
    private void registerCommands() {
        ForgeStationCommand command = new ForgeStationCommand(this);
        getCommand("forgestation").setExecutor(command);
        getCommand("forgestation").setTabCompleter(command);
    }
    
    /**
     * EXTERNAL RELOAD FIX: Restore tasks cho tất cả online players
     * Được gọi khi plugin enable (sau khi reload bởi PlugMan)
     */
    private void restoreTasksForOnlinePlayers() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        debug("Attempting to restore tasks for " + onlinePlayers + " online players...");
        
        int smeltingRestored = 0;
        int craftingRestored = 0;
        
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            debug("Checking saved tasks for player: " + player.getName());
            
            // Restore smelting tasks
            if (smeltingManager.restoreTaskForPlayer(player)) {
                smeltingRestored++;
                debug("Restored smelting task for " + player.getName());
            }
            
            // Restore crafting tasks
            if (craftingExecutor.restoreTaskForPlayer(player)) {
                craftingRestored++;
                debug("Restored crafting task for " + player.getName());
            }
        }
        
        if (smeltingRestored > 0 || craftingRestored > 0) {
            getLogger().info("Restored " + smeltingRestored + " smelting and " + craftingRestored + " crafting tasks for online players");
        } else if (onlinePlayers > 0) {
            debug("No saved tasks found to restore");
        }
    }
    
    /**
     * Reload plugin configurations
     * PERSISTENCE FIX: Lưu active tasks trước khi reload, restore sau khi reload
     */
    public void reload() {
        // PERSISTENCE FIX: Lưu active tasks trước khi reload recipes
        var savedSmeltingTasks = smeltingManager.saveAllTasksForReload();
        var savedCraftingTasks = craftingExecutor.saveAllTasksForReload();
        
        // Unregister old commands
        if (customCommandManager != null) {
            customCommandManager.unregisterAll();
        }
        
        // Reload configs
        configManager.loadAll();
        messageUtil.reload();
        recipeManager.loadRecipes();
        smeltingManager.loadSmeltingRecipes();
        menuManager.loadMenus();
        upgradeManager.reload();
        
        // PERSISTENCE FIX: Restore tasks với recipe references mới
        smeltingManager.restoreTasksAfterReload(savedSmeltingTasks);
        craftingExecutor.restoreTasksAfterReload(savedCraftingTasks);
    }
    
    // Getters
    public static ForgeStationPlugin getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public MessageUtil getMessageUtil() {
        return messageUtil;
    }
    
    public HookManager getHookManager() {
        return hookManager;
    }
    
    public ExtraStorageHook getExtraStorageHook() {
        return extraStorageHook;
    }
    
    public EconomyManager getEconomyManager() {
        return economyManager;
    }
    
    public RecipeManager getRecipeManager() {
        return recipeManager;
    }
    
    public CraftingExecutor getCraftingExecutor() {
        return craftingExecutor;
    }
    
    public SmeltingManager getSmeltingManager() {
        return smeltingManager;
    }
    
    public UpgradeManager getUpgradeManager() {
        return upgradeManager;
    }
    
    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }
    
    public MenuManager getMenuManager() {
        return menuManager;
    }
    
    public RecipeDetailGUI getRecipeDetailGUI() {
        return recipeDetailGUI;
    }
    
    public SchedulerAdapter getScheduler() {
        return scheduler;
    }
    
    // ISSUE-014 FIX: Getter for output router
    public OutputRouter getOutputRouter() {
        return outputRouter;
    }
    
    public com.phmyhu1710.forgestation.command.CustomCommandManager getCustomCommandManager() {
        return customCommandManager;
    }
    
    public boolean isDebug() {
        if (configManager == null || configManager.getMainConfig() == null) return false;
        return configManager.getMainConfig().getBoolean("debug", false);
    }
    
    public void debug(String message) {
        if (isDebug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
