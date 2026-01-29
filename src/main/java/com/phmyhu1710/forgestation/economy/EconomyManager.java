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
     * Get player's total EXP
     */
    public int getExp(Player player) {
        // Simple calculation or use Bukkit's total experience
        // Note: getTotalExperience() sometimes desyncs with levels, but for simple cost checks it's usually the standard way.
        // For better precision, we should calculate from Level + Exp bar.
        return com.phmyhu1710.forgestation.util.ExperienceUtil.getTotalExperience(player);
    }
    
    /**
     * Withdraw EXP from player
     */
    public boolean withdrawExp(Player player, int amount) {
        if (getExp(player) < amount) return false;
        
        // Use ExperienceUtil for safe withdrawal to handle levels correctly
        com.phmyhu1710.forgestation.util.ExperienceUtil.setTotalExperience(player, getExp(player) - amount);
        return true;
    }

    /**
     * Check if player can afford specified costs
     */
    public boolean canAfford(Player player, double vault, int playerPoints, int exp, String coinEngineCurrency, double coinEngineAmount) {
        plugin.debug("=== CHECKING AFFORDABILITY ===");
        plugin.debug("Required - Vault: " + vault + ", PP: " + playerPoints + ", EXP: " + exp + ", CE: " + coinEngineAmount);
        
        if (vault > 0) {
            double balance = getVaultBalance(player);
            if (balance < vault) return false;
        }
        
        if (playerPoints > 0) {
            int balance = getPlayerPoints(player);
            if (balance < playerPoints) return false;
        }
        
        if (exp > 0) {
            int balance = getExp(player);
            if (balance < exp) return false;
        }
        
        if (coinEngineAmount > 0 && !coinEngineCurrency.isEmpty()) {
            double balance = getCoinEngineBalance(player, coinEngineCurrency);
            if (balance < coinEngineAmount) return false;
        }
        
        plugin.debug("Can afford: TRUE");
        return true;
    }
    
    // Legacy overload for backward compatibility if needed, or update callers
    public boolean canAfford(Player player, double vault, int playerPoints, String coinEngineCurrency, double coinEngineAmount) {
        return canAfford(player, vault, playerPoints, 0, coinEngineCurrency, coinEngineAmount);
    }

    /**
     * Withdraw all costs
     */
    public boolean withdrawAll(Player player, double vault, int playerPoints, int exp, String coinEngineCurrency, double coinEngineAmount) {
        plugin.debug("=== WITHDRAWING ===");
        plugin.debug("Amounts - Vault: " + vault + ", PP: " + playerPoints + ", EXP: " + exp + ", CE: " + coinEngineAmount);
        
        if (!canAfford(player, vault, playerPoints, exp, coinEngineCurrency, coinEngineAmount)) {
            plugin.debug("Cannot afford - aborting withdraw");
            return false;
        }
        
        boolean success = true;
        
        if (vault > 0) success &= withdrawVault(player, vault);
        if (playerPoints > 0) success &= withdrawPlayerPoints(player, playerPoints);
        if (exp > 0) success &= withdrawExp(player, exp);
        if (coinEngineAmount > 0 && !coinEngineCurrency.isEmpty()) success &= withdrawCoinEngine(player, coinEngineCurrency, coinEngineAmount);
        
        plugin.debug("Overall withdraw success: " + success);
        return success;
    }
    
    // Legacy overload
    public boolean withdrawAll(Player player, double vault, int playerPoints, String coinEngineCurrency, double coinEngineAmount) {
        return withdrawAll(player, vault, playerPoints, 0, coinEngineCurrency, coinEngineAmount);
    }
}
