package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.ForgeStationPlugin;

import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages external plugin hooks
 */
public class HookManager {

    private final ForgeStationPlugin plugin;
    
    // Hook status
    private boolean vaultEnabled = false;
    private boolean playerPointsEnabled = false;
    private boolean coinEngineEnabled = false;
    private boolean mmoItemsEnabled = false;
    private boolean extraStorageEnabled = false;
    private boolean placeholderAPIEnabled = false;
    private boolean itemsAdderEnabled = false;
    private boolean oraxenEnabled = false;
    private boolean nexoEnabled = false;
    private boolean headDatabaseEnabled = false;
    
    // Item hooks map
    private final Map<String, ItemHook> itemHooks = new HashMap<>();
    
    // Hook instances
    private Economy vaultEconomy = null;

    private PlayerPointsAPI playerPointsAPI = null;

    public HookManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public void setupHooks() {
        java.util.List<String> activeHooks = new java.util.ArrayList<>();

        // Vault
        if (isPluginEnabled("Vault") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.vault", true)) {
            setupVault(activeHooks);
        }
        
        // PlayerPoints
        if (isPluginEnabled("PlayerPoints") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.playerpoints", true)) {
            setupPlayerPoints(activeHooks);
        }
        
        // CoinsEngine
        if (isPluginEnabled("CoinsEngine") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.coinengine", true)) {
            coinEngineEnabled = true;
            activeHooks.add("CoinsEngine");
            plugin.debug("Hooked into CoinsEngine!");
        }
        
        // MMOItems
        if (isPluginEnabled("MMOItems") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.mmoitems", true)) {
            mmoItemsEnabled = true;
            activeHooks.add("MMOItems");
            plugin.debug("Hooked into MMOItems!");
        }
        
        // ExtraStorage
        if (isPluginEnabled("ExtraStorage") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.extrastorage", true)) {
            extraStorageEnabled = true;
            activeHooks.add("ExtraStorage");
            plugin.debug("Hooked into ExtraStorage!");
        }
        
        // PlaceholderAPI
        if (isPluginEnabled("PlaceholderAPI") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.placeholderapi", true)) {
            placeholderAPIEnabled = true;
            activeHooks.add("PlaceholderAPI");
            plugin.debug("Hooked into PlaceholderAPI!");
        }
        
        // ItemsAdder
        if (isPluginEnabled("ItemsAdder") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.itemsadder", true)) {
            itemsAdderEnabled = true;
            itemHooks.put("itemsadder", new ItemsAdderHook());
            activeHooks.add("ItemsAdder");
            plugin.debug("Hooked into ItemsAdder!");
        }
        
        // Oraxen
        if (isPluginEnabled("Oraxen") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.oraxen", true)) {
            oraxenEnabled = true;
            itemHooks.put("oraxen", new OraxenHook());
            activeHooks.add("Oraxen");
            plugin.debug("Hooked into Oraxen!");
        }
        
        // Nexo
        if (isPluginEnabled("Nexo") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.nexo", true)) {
            nexoEnabled = true;
            itemHooks.put("nexo", new NexoHook());
            activeHooks.add("Nexo");
            plugin.debug("Hooked into Nexo!");
        }
        
        // HeadDatabase
        if (isPluginEnabled("HeadDatabase") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.headdatabase", true)) {
            headDatabaseEnabled = true;
            itemHooks.put("hdb", new HeadDatabaseHook());
            activeHooks.add("HeadDatabase");
            plugin.debug("Hooked into HeadDatabase!");
        }
        
        // BaseHead (Always enabled)
        itemHooks.put("basehead", new BaseHeadHook());
        
        if (!activeHooks.isEmpty()) {
            plugin.getLogger().info("Hooked into: " + String.join(", ", activeHooks));
        }
    }

    private boolean isPluginEnabled(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    private void setupVault(java.util.List<String> activeHooks) {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            vaultEconomy = rsp.getProvider();
            vaultEnabled = true;
            activeHooks.add("Vault");
            plugin.debug("Hooked into Vault (Economy: " + vaultEconomy.getName() + ")");
        } else {
            plugin.getLogger().warning("Vault found but no economy provider detected!");
        }
    }



    private void setupPlayerPoints(java.util.List<String> activeHooks) {
        PlayerPoints pp = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp != null) {
            playerPointsAPI = pp.getAPI();
            playerPointsEnabled = true;
            activeHooks.add("PlayerPoints");
            plugin.debug("Hooked into PlayerPoints!");
        }
    }

    // Getters
    public boolean isVaultEnabled() {
        return vaultEnabled;
    }

    public boolean isPlayerPointsEnabled() {
        return playerPointsEnabled;
    }

    public boolean isCoinEngineEnabled() {
        return coinEngineEnabled;
    }

    public boolean isMMOItemsEnabled() {
        return mmoItemsEnabled;
    }

    public boolean isExtraStorageEnabled() {
        return extraStorageEnabled;
    }

    public boolean isPlaceholderAPIEnabled() {
        return placeholderAPIEnabled;
    }

    public Economy getVaultEconomy() {
        return vaultEconomy;
    }



    public PlayerPointsAPI getPlayerPointsAPI() {
        return playerPointsAPI;
    }
    
    public boolean isItemsAdderEnabled() {
        return itemsAdderEnabled;
    }
    
    public boolean isOraxenEnabled() {
        return oraxenEnabled;
    }
    
    public boolean isNexoEnabled() {
        return nexoEnabled;
    }
    
    public boolean isHeadDatabaseEnabled() {
        return headDatabaseEnabled;
    }
    
    /**
     * Get all registered item hooks
     */
    public Map<String, ItemHook> getItemHooks() {
        return itemHooks;
    }
    
    /**
     * Get an item hook by its prefix (returns null if not found)
     */
    public ItemHook getItemHookByPrefix(String materialString) {
        for (ItemHook hook : itemHooks.values()) {
            if (materialString.toLowerCase().startsWith(hook.getPrefix())) {
                return hook;
            }
        }
        return null;
    }
}
