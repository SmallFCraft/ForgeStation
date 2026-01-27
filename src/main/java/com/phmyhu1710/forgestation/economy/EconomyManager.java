package com.phmyhu1710.forgestation.economy;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.entity.Player;

/**
 * Manages multiple economy systems
 */
public class EconomyManager {

    private final ForgeStationPlugin plugin;

    public EconomyManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get player's Vault balance
     */
    public double getVaultBalance(Player player) {
        if (!plugin.getHookManager().isVaultEnabled()) return 0;
        return plugin.getHookManager().getVaultEconomy().getBalance(player);
    }

    /**
     * Withdraw from Vault
     */
    public boolean withdrawVault(Player player, double amount) {
        if (!plugin.getHookManager().isVaultEnabled()) return false;
        if (getVaultBalance(player) < amount) return false;
        return plugin.getHookManager().getVaultEconomy().withdrawPlayer(player, amount).transactionSuccess();
    }

    /**
     * Deposit to Vault
     */
    public boolean depositVault(Player player, double amount) {
        if (!plugin.getHookManager().isVaultEnabled()) return false;
        return plugin.getHookManager().getVaultEconomy().depositPlayer(player, amount).transactionSuccess();
    }

    /**
     * Get player's PlayerPoints balance
     */
    public int getPlayerPoints(Player player) {
        if (!plugin.getHookManager().isPlayerPointsEnabled()) return 0;
        return plugin.getHookManager().getPlayerPointsAPI().look(player.getUniqueId());
    }

    /**
     * Withdraw PlayerPoints
     */
    public boolean withdrawPlayerPoints(Player player, int amount) {
        if (!plugin.getHookManager().isPlayerPointsEnabled()) return false;
        if (getPlayerPoints(player) < amount) return false;
        return plugin.getHookManager().getPlayerPointsAPI().take(player.getUniqueId(), amount);
    }

    /**
     * Give PlayerPoints
     */
    public boolean givePlayerPoints(Player player, int amount) {
        if (!plugin.getHookManager().isPlayerPointsEnabled()) return false;
        return plugin.getHookManager().getPlayerPointsAPI().give(player.getUniqueId(), amount);
    }

    /**
     * Get CoinsEngine balance
     */
    public double getCoinEngineBalance(Player player, String currency) {
        if (!plugin.getHookManager().isCoinEngineEnabled()) return 0;
        
        try {
            // Use reflection to avoid hard dependency
            Class<?> coinsEngineAPI = Class.forName("su.nightexpress.coinsengine.api.CoinsEngineAPI");
            Object currencyObj = coinsEngineAPI.getMethod("getCurrency", String.class).invoke(null, currency);
            if (currencyObj == null) return 0;
            
            return (double) coinsEngineAPI.getMethod("getBalance", Player.class, currencyObj.getClass().getInterfaces()[0])
                .invoke(null, player, currencyObj);
        } catch (Exception e) {
            plugin.debug("CoinsEngine balance check failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Withdraw from CoinsEngine
     */
    public boolean withdrawCoinEngine(Player player, String currency, double amount) {
        if (!plugin.getHookManager().isCoinEngineEnabled()) return false;
        
        try {
            Class<?> coinsEngineAPI = Class.forName("su.nightexpress.coinsengine.api.CoinsEngineAPI");
            Object currencyObj = coinsEngineAPI.getMethod("getCurrency", String.class).invoke(null, currency);
            if (currencyObj == null) return false;
            
            coinsEngineAPI.getMethod("removeBalance", Player.class, currencyObj.getClass().getInterfaces()[0], double.class)
                .invoke(null, player, currencyObj, amount);
            return true;
        } catch (Exception e) {
            plugin.debug("CoinsEngine withdraw failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if player can afford specified costs
     */
    public boolean canAfford(Player player, double vault, int playerPoints, String coinEngineCurrency, double coinEngineAmount) {
        plugin.debug("=== CHECKING AFFORDABILITY ===");
        plugin.debug("Required - Vault: " + vault + ", PP: " + playerPoints + ", CE: " + coinEngineAmount + " (" + coinEngineCurrency + ")");
        
        if (vault > 0) {
            double balance = getVaultBalance(player);
            plugin.debug("Vault check: " + balance + " >= " + vault + " ? " + (balance >= vault));
            if (balance < vault) return false;
        }
        
        if (playerPoints > 0) {
            int balance = getPlayerPoints(player);
            plugin.debug("PP check: " + balance + " >= " + playerPoints + " ? " + (balance >= playerPoints));
            if (balance < playerPoints) return false;
        }
        
        if (coinEngineAmount > 0 && !coinEngineCurrency.isEmpty()) {
            double balance = getCoinEngineBalance(player, coinEngineCurrency);
            plugin.debug("CE check: " + balance + " >= " + coinEngineAmount + " ? " + (balance >= coinEngineAmount));
            if (balance < coinEngineAmount) return false;
        }
        
        plugin.debug("Can afford: TRUE");
        return true;
    }

    /**
     * Withdraw all costs
     */
    public boolean withdrawAll(Player player, double vault, int playerPoints, String coinEngineCurrency, double coinEngineAmount) {
        plugin.debug("=== WITHDRAWING ===");
        plugin.debug("Amounts - Vault: " + vault + ", PP: " + playerPoints + ", CE: " + coinEngineAmount);
        
        if (!canAfford(player, vault, playerPoints, coinEngineCurrency, coinEngineAmount)) {
            plugin.debug("Cannot afford - aborting withdraw");
            return false;
        }
        
        boolean success = true;
        
        if (vault > 0) {
            plugin.debug("Withdrawing Vault: " + vault);
            boolean vaultSuccess = withdrawVault(player, vault);
            plugin.debug("Vault withdraw result: " + vaultSuccess);
            success &= vaultSuccess;
        } else {
            plugin.debug("Skipping Vault withdraw (amount = 0)");
        }
        
        if (playerPoints > 0) {
            plugin.debug("Withdrawing PP: " + playerPoints);
            boolean ppSuccess = withdrawPlayerPoints(player, playerPoints);
            plugin.debug("PP withdraw result: " + ppSuccess);
            success &= ppSuccess;
        } else {
            plugin.debug("Skipping PP withdraw (amount = 0)");
        }
        
        if (coinEngineAmount > 0 && !coinEngineCurrency.isEmpty()) {
            plugin.debug("Withdrawing CE: " + coinEngineAmount + " " + coinEngineCurrency);
            boolean ceSuccess = withdrawCoinEngine(player, coinEngineCurrency, coinEngineAmount);
            plugin.debug("CE withdraw result: " + ceSuccess);
            success &= ceSuccess;
        } else {
            plugin.debug("Skipping CE withdraw (amount = 0 or no currency)");
        }
        
        plugin.debug("Overall withdraw success: " + success);
        return success;
    }
}
