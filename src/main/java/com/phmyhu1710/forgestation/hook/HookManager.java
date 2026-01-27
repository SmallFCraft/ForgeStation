package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

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
    
    // Hook instances
    private Economy vaultEconomy = null;
    private Chat vaultChat = null;
    private PlayerPointsAPI playerPointsAPI = null;

    public HookManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public void setupHooks() {
        // Vault
        if (isPluginEnabled("Vault") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.vault", true)) {
            setupVault();
            setupChat();
        }
        
        // PlayerPoints
        if (isPluginEnabled("PlayerPoints") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.playerpoints", true)) {
            setupPlayerPoints();
        }
        
        // CoinsEngine
        if (isPluginEnabled("CoinsEngine") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.coinengine", true)) {
            coinEngineEnabled = true;
            plugin.getLogger().info("Hooked into CoinsEngine!");
        }
        
        // MMOItems
        if (isPluginEnabled("MMOItems") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.mmoitems", true)) {
            mmoItemsEnabled = true;
            plugin.getLogger().info("Hooked into MMOItems!");
        }
        
        // ExtraStorage
        if (isPluginEnabled("ExtraStorage") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.extrastorage", true)) {
            extraStorageEnabled = true;
            plugin.getLogger().info("Hooked into ExtraStorage!");
        }
        
        // PlaceholderAPI
        if (isPluginEnabled("PlaceholderAPI") && plugin.getConfigManager().getMainConfig().getBoolean("hooks.placeholderapi", true)) {
            placeholderAPIEnabled = true;
            plugin.getLogger().info("Hooked into PlaceholderAPI!");
        }
    }

    private boolean isPluginEnabled(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    private void setupVault() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            vaultEconomy = rsp.getProvider();
            vaultEnabled = true;
            plugin.getLogger().info("Hooked into Vault (Economy: " + vaultEconomy.getName() + ")");
        } else {
            plugin.getLogger().warning("Vault found but no economy provider detected!");
        }
    }

    private void setupChat() {
        RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
        if (rsp != null) {
            vaultChat = rsp.getProvider();
            plugin.getLogger().info("Hooked into Vault Chat!");
        }
    }

    private void setupPlayerPoints() {
        PlayerPoints pp = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp != null) {
            playerPointsAPI = pp.getAPI();
            playerPointsEnabled = true;
            plugin.getLogger().info("Hooked into PlayerPoints!");
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

    public Chat getVaultChat() {
        return vaultChat;
    }

    public PlayerPointsAPI getPlayerPointsAPI() {
        return playerPointsAPI;
    }
}
