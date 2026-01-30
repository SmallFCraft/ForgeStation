package com.phmyhu1710.forgestation.upgrade;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.expression.ExpressionParser;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import com.phmyhu1710.forgestation.util.MessageUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages upgrade system
 */
public class UpgradeManager {

    private final ForgeStationPlugin plugin;
    private final Map<String, Upgrade> upgrades = new HashMap<>();

    public UpgradeManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        upgrades.clear();
        
        FileConfiguration config = plugin.getConfigManager().getUpgradesConfig();
        ConfigurationSection upgradesSection = config.getConfigurationSection("upgrades");
        
        if (upgradesSection == null) return;
        
        for (String upgradeId : upgradesSection.getKeys(false)) {
            ConfigurationSection upgradeConfig = upgradesSection.getConfigurationSection(upgradeId);
            if (upgradeConfig == null) continue;
            
            upgrades.put(upgradeId, new Upgrade(upgradeId, upgradeConfig));
        }

        // Fallback: thêm queue_slots nếu upgrades.yml không có (chi phí lấy từ upgrades.yml khi có file mặc định)
        if (!upgrades.containsKey("queue_slots")) {
            YamlConfiguration def = new YamlConfiguration();
            def.set("display-name", "Slot hàng chờ");
            def.set("max-level", 10);
            def.set("cost-per-level.playerpoints", "5000");
            upgrades.put("queue_slots", new Upgrade("queue_slots", def));
            plugin.debug("Loaded default upgrade: queue_slots (fallback)");
        }

        plugin.debug("Loaded " + upgrades.size() + " upgrades");
    }

    /**
     * Số slot hàng chờ đã mở (0-10). Dùng cho crafting/smelting queue.
     */
    public int getQueueSlotsUnlocked(Player player) {
        return Math.min(10, Math.max(0, getPlayerLevel(player, "queue_slots")));
    }

    public Upgrade getUpgrade(String id) {
        return upgrades.get(id);
    }

    /**
     * ISSUE-008 FIX: Returns unmodifiable view instead of copy to reduce allocations
     */
    public Map<String, Upgrade> getAllUpgrades() {
        return java.util.Collections.unmodifiableMap(upgrades);
    }

    /**
     * Get player's upgrade level
     */
    public int getPlayerLevel(Player player, String upgradeId) {
        return plugin.getPlayerDataManager().getPlayerData(player).getUpgradeLevel(upgradeId);
    }

    /**
     * Get cost for next level
     */
    public Map<String, Double> getNextLevelCost(Player player, String upgradeId) {
        Upgrade upgrade = upgrades.get(upgradeId);
        if (upgrade == null) {
            plugin.debug("Upgrade not found: " + upgradeId);
            return new HashMap<>();
        }
        
        int currentLevel = getPlayerLevel(player, upgradeId);
        int nextLevel = currentLevel + 1;
        
        if (nextLevel > upgrade.getMaxLevel()) {
            plugin.debug("Max level reached for " + upgradeId);
            return new HashMap<>();
        }
        
        Map<String, Double> vars = new HashMap<>();
        vars.put("level", (double) nextLevel);
        
        Map<String, Double> costs = new HashMap<>();
        
        if (!upgrade.getVaultCostExpr().isEmpty()) {
            double vaultCost = ExpressionParser.evaluate(upgrade.getVaultCostExpr(), vars);
            costs.put("vault", vaultCost);
            plugin.debug("Vault cost for " + upgradeId + " level " + nextLevel + ": " + vaultCost);
        }
        if (!upgrade.getPlayerPointsCostExpr().isEmpty()) {
            double ppCost = ExpressionParser.evaluate(upgrade.getPlayerPointsCostExpr(), vars);
            costs.put("playerpoints", ppCost);
            plugin.debug("PlayerPoints cost for " + upgradeId + " level " + nextLevel + ": " + ppCost);
        }
        if (!upgrade.getCoinEngineCostExpr().isEmpty()) {
            double ceCost = ExpressionParser.evaluate(upgrade.getCoinEngineCostExpr(), vars);
            costs.put("coinengine", ceCost);
            plugin.debug("CoinEngine cost for " + upgradeId + " level " + nextLevel + ": " + ceCost);
        }
        if (!upgrade.getExpCostExpr().isEmpty()) {
            int expCost = ExpressionParser.evaluateInt(upgrade.getExpCostExpr(), vars);
            costs.put("exp", (double) expCost);
            plugin.debug("Exp cost for " + upgradeId + " level " + nextLevel + ": " + expCost);
        }
        
        return costs;
    }

    /**
     * Try to upgrade
     */
    public boolean tryUpgrade(Player player, String upgradeId) {
        plugin.debug("=== UPGRADE ATTEMPT ===");
        plugin.debug("Player: " + player.getName());
        plugin.debug("Upgrade ID: " + upgradeId);
        
        Upgrade upgrade = upgrades.get(upgradeId);
        if (upgrade == null) {
            plugin.debug("Upgrade not found!");
            return false;
        }
        
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        int currentLevel = data.getUpgradeLevel(upgradeId);
        int nextLevel = currentLevel + 1;
        
        plugin.debug("Current level: " + currentLevel);
        plugin.debug("Next level: " + nextLevel);
        plugin.debug("Max level: " + upgrade.getMaxLevel());
        
        // Check max level
        if (nextLevel > upgrade.getMaxLevel()) {
            plugin.getMessageUtil().send(player, "upgrade.max-level", "max", String.valueOf(upgrade.getMaxLevel()));
            return false;
        }
        
        // Get costs
        Map<String, Double> costs = getNextLevelCost(player, upgradeId);
        double vaultCost = costs.getOrDefault("vault", 0.0);
        int ppCost = costs.getOrDefault("playerpoints", 0.0).intValue();
        int expCost = costs.getOrDefault("exp", 0.0).intValue();
        double coinEngineCost = costs.getOrDefault("coinengine", 0.0);
        
        plugin.debug("Calculated costs:");
        plugin.debug("  Vault: " + vaultCost);
        plugin.debug("  PlayerPoints: " + ppCost);
        plugin.debug("  Exp: " + expCost);
        plugin.debug("  CoinEngine: " + coinEngineCost + " (" + upgrade.getCoinEngineCurrency() + ")");
        
        // Get player balances
        double playerVault = plugin.getEconomyManager().getVaultBalance(player);
        int playerPP = plugin.getEconomyManager().getPlayerPoints(player);
        int playerExp = plugin.getEconomyManager().getExp(player);
        double playerCE = upgrade.getCoinEngineCurrency().isEmpty() ? 0 : 
            plugin.getEconomyManager().getCoinEngineBalance(player, upgrade.getCoinEngineCurrency());
        
        plugin.debug("Player balances:");
        plugin.debug("  Vault: " + playerVault);
        plugin.debug("  PlayerPoints: " + playerPP);
        plugin.debug("  Exp: " + playerExp);
        plugin.debug("  CoinEngine: " + playerCE);
        
        // Check each currency individually and build detailed message
        List<String> insufficientList = new ArrayList<>();
        
        if (vaultCost > 0 && playerVault < vaultCost) {
            double needed = vaultCost - playerVault;
            String currencyName = MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins"));
            insufficientList.add(String.format("&8┃ &6💰 &7Thiếu &e%s %s &8(&7Có: &e%s&8)",
                MessageUtil.formatNumber(needed), currencyName, MessageUtil.formatNumber(playerVault)));
        }
        
        if (ppCost > 0 && playerPP < ppCost) {
            int needed = ppCost - playerPP;
            String currencyName = MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.playerpoints", "Points"));
            insufficientList.add(String.format("&8┃ &b⬤ &7Thiếu &e%s %s &8(&7Có: &e%s&8)",
                MessageUtil.formatNumber(needed), currencyName, MessageUtil.formatNumber(playerPP)));
        }
        
        if (expCost > 0 && playerExp < expCost) {
            int needed = expCost - playerExp;
            String currencyName = MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.exp", "EXP"));
            insufficientList.add(String.format("&8┃ &a✦ &7Thiếu &e%s %s &8(&7Có: &e%s&8)",
                MessageUtil.formatNumber(needed), currencyName, MessageUtil.formatNumber(playerExp)));
        }
        
        if (coinEngineCost > 0 && !upgrade.getCoinEngineCurrency().isEmpty() && playerCE < coinEngineCost) {
            double needed = coinEngineCost - playerCE;
            insufficientList.add(String.format("&8┃ &e⛃ &7Thiếu &e%s %s &8(&7Có: &e%s&8)",
                MessageUtil.formatNumber(needed), upgrade.getCoinEngineCurrency(), MessageUtil.formatNumber(playerCE)));
        }
        
        // If insufficient, send detailed message
        if (!insufficientList.isEmpty()) {
            plugin.getMessageUtil().send(player, "upgrade.insufficient",
                "details", String.join("\n", insufficientList));
            return false;
        }
        
        // Double check with canAfford (should always pass if above checks passed)
        boolean canAfford = plugin.getEconomyManager().canAfford(player, vaultCost, ppCost, expCost,
                upgrade.getCoinEngineCurrency(), coinEngineCost);
        
        plugin.debug("Can afford: " + canAfford);
        
        if (!canAfford) {
            plugin.getMessageUtil().send(player, "upgrade.no-money", 
                "cost", formatCost(vaultCost, ppCost, expCost, coinEngineCost, upgrade.getCoinEngineCurrency()));
            return false;
        }
        
        // Withdraw
        plugin.debug("Attempting to withdraw...");
        boolean withdrawn = plugin.getEconomyManager().withdrawAll(player, vaultCost, ppCost, expCost,
            upgrade.getCoinEngineCurrency(), coinEngineCost);
        
        plugin.debug("Withdraw result: " + withdrawn);
        
        if (!withdrawn) {
            plugin.debug("Withdraw failed!");
            plugin.getMessageUtil().send(player, "upgrade.no-money", 
                "cost", formatCost(vaultCost, ppCost, expCost, coinEngineCost, upgrade.getCoinEngineCurrency()));
            return false;
        }
        
        // Apply upgrade
        data.setUpgradeLevel(upgradeId, nextLevel);
        plugin.debug("Upgrade applied! New level: " + nextLevel);
        
        plugin.getMessageUtil().send(player, "upgrade.success",
            "upgrade", upgrade.getDisplayName(),
            "level", String.valueOf(nextLevel));
        
        plugin.debug("=== UPGRADE SUCCESS ===");
        return true;
    }

    private String formatCost(double vault, int pp, int exp, double coinEngine, String currency) {
        StringBuilder sb = new StringBuilder();
        if (vault > 0) {
            sb.append("§6").append(MessageUtil.formatNumber(vault)).append(" ").append(MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins")));
        }
        if (pp > 0) {
            if (!sb.isEmpty()) sb.append(" §7+ ");
            sb.append("§b").append(MessageUtil.formatNumber(pp)).append(" ").append(MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.playerpoints", "Points")));
        }
        if (exp > 0) {
            if (!sb.isEmpty()) sb.append(" §7+ ");
            sb.append("§a").append(MessageUtil.formatNumber(exp)).append(" ").append(MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.exp", "EXP")));
        }
        if (coinEngine > 0 && !currency.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" §7+ ");
            sb.append("§e").append(MessageUtil.formatNumber(coinEngine)).append(" ").append(currency);
        }
        return sb.toString();
    }

    /**
     * Upgrade data class
     * IMPROVED: Hỗ trợ PERCENTAGE mode để giảm theo phần trăm
     */
    public static class Upgrade {
        private final String id;
        private final String displayName;
        private final int maxLevel;
        private final String vaultCostExpr;
        private final String playerPointsCostExpr;
        private final String coinEngineCurrency;
        private final String coinEngineCostExpr;
        private final String expCostExpr;
        private final String effectType;
        private final double valuePerLevel;
        
        // IMPROVED: Percentage-based reduction
        private final String effectMode; // "PERCENTAGE" hoặc "FIXED" (default)
        private final double percentPerLevel; // % giảm mỗi level
        private final long minDurationTicks; // Thời gian tối thiểu (ticks)

        public Upgrade(String id, ConfigurationSection config) {
            this.id = id;
            this.displayName = config.getString("display-name", id);
            this.maxLevel = config.getInt("max-level", 10);
            
            ConfigurationSection costSection = config.getConfigurationSection("cost-per-level");
            if (costSection != null) {
                this.vaultCostExpr = costSection.getString("vault", "");
                this.playerPointsCostExpr = costSection.getString("playerpoints", "");
                this.expCostExpr = costSection.getString("exp", "");
                
                ConfigurationSection coinEngineSection = costSection.getConfigurationSection("coinengine");
                if (coinEngineSection != null) {
                    this.coinEngineCurrency = coinEngineSection.getString("currency", "");
                    this.coinEngineCostExpr = coinEngineSection.getString("amount", "");
                } else {
                    this.coinEngineCurrency = "";
                    this.coinEngineCostExpr = "";
                }
            } else {
                this.vaultCostExpr = "";
                this.playerPointsCostExpr = "";
                this.expCostExpr = "";
                this.coinEngineCurrency = "";
                this.coinEngineCostExpr = "";
            }
            
            ConfigurationSection effectSection = config.getConfigurationSection("effect");
            if (effectSection != null) {
                this.effectType = effectSection.getString("type", "");
                this.valuePerLevel = effectSection.getDouble("value-per-level", 0);
                
                // IMPROVED: Read new percentage config
                this.effectMode = effectSection.getString("mode", "FIXED").toUpperCase();
                this.percentPerLevel = effectSection.getDouble("percent-per-level", 0);
                // TICK-SUPPORT: Read min-duration as double seconds and convert to ticks
                double minDurationSec = effectSection.getDouble("min-duration", 1.0);
                this.minDurationTicks = (long) (minDurationSec * 20);
            } else {
                this.effectType = "";
                this.valuePerLevel = 0;
                this.effectMode = "FIXED";
                this.percentPerLevel = 0;
                this.minDurationTicks = 20; // 1 second default
            }
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public int getMaxLevel() { return maxLevel; }
        public String getVaultCostExpr() { return vaultCostExpr; }
        public String getPlayerPointsCostExpr() { return playerPointsCostExpr; }
        public String getExpCostExpr() { return expCostExpr; }
        public String getCoinEngineCurrency() { return coinEngineCurrency; }
        public String getCoinEngineCostExpr() { return coinEngineCostExpr; }
        public String getEffectType() { return effectType; }
        public double getValuePerLevel() { return valuePerLevel; }
        
        // IMPROVED: New getters for percentage mode
        public String getEffectMode() { return effectMode; }
        public double getPercentPerLevel() { return percentPerLevel; }
        public long getMinDurationTicks() { return minDurationTicks; }
        
        /**
         * TICK-SUPPORT: Tính duration sau khi áp dụng upgrade (theo ticks)
         * @param baseTicks Thời gian gốc (ticks)
         * @param level Level upgrade hiện tại của player
         * @return Thời gian sau khi giảm (ticks)
         */
        public long applyDurationReduction(long baseTicks, int level) {
            if (level <= 0) return baseTicks;
            
            long result;
            if ("PERCENTAGE".equals(effectMode)) {
                // PERCENTAGE mode: giảm X% mỗi level
                double reductionPercent = Math.min(level * percentPerLevel, 100) / 100.0;
                result = (long) Math.ceil(baseTicks * (1 - reductionPercent));
            } else {
                // FIXED mode: giảm X giây mỗi level (cũ) -> convert to ticks
                // valuePerLevel is in seconds
                long reductionTicks = (long) (level * valuePerLevel * 20);
                result = baseTicks - reductionTicks;
            }
            
            return Math.max(result, minDurationTicks);
        }
    }
}
