package com.phmyhu1710.forgestation.crafting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Exchange Task with duration and BossBar progress
 * Similar to CraftingTask but for exchange recipes
 */
public class ExchangeTask {

    private final ForgeStationPlugin plugin;
    private final UUID playerUuid;
    private final Recipe recipe;
    private long totalTicks; // in ticks
    private long remainingTicks;
    private boolean cancelled = false;
    
    // Exchange data - stored for when complete
    private final int actualExchanges;
    private final double multiplier;
    private final int remainder;
    
    // BOSSBAR PROGRESS
    private BossBar bossBar;
    private static final String BOSSBAR_FORMAT = "&e⚡ &f%s &8| &e%s &8| &a%d%%";

    public ExchangeTask(ForgeStationPlugin plugin, Player player, Recipe recipe, long durationTicks,
                        int actualExchanges, double multiplier, int remainder) {
        this.plugin = plugin;
        this.playerUuid = player.getUniqueId();
        this.recipe = recipe;
        this.totalTicks = durationTicks;
        this.remainingTicks = durationTicks;
        this.actualExchanges = actualExchanges;
        this.multiplier = multiplier;
        this.remainder = remainder;
    }

    /**
     * Initialize task - create BossBar, send message
     */
    public void initialize() {
        Player player = getPlayer();
        
        if (player != null) {
            createBossBar(player);
            plugin.getMessageUtil().send(player, "exchange.started", 
                "item", recipe.getDisplayName(),
                "count", String.valueOf(actualExchanges));
        }
    }
    
    /**
     * Tick method - called every tick by global timer
     */
    public void tick() {
        if (cancelled) return;
        
        remainingTicks--;
        updateBossBar();
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // BOSSBAR PROGRESS METHODS
    // ═══════════════════════════════════════════════════════════════════════
    
    private void createBossBar(Player player) {
        String title = formatBossBarTitle();
        bossBar = Bukkit.createBossBar(
            MessageUtil.colorize(title),
            BarColor.YELLOW,
            BarStyle.SEGMENTED_10
        );
        bossBar.setProgress(0.0);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
    }
    
    private void updateBossBar() {
        if (bossBar == null) return;
        
        Player player = getPlayer();
        if (player == null || !player.isOnline()) {
            removeBossBar();
            return;
        }
        
        String title = formatBossBarTitle();
        bossBar.setTitle(MessageUtil.colorize(title));
        
        double progress = (double) (totalTicks - remainingTicks) / totalTicks;
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        
        bossBar.setColor(getBossBarColor());
    }
    
    private BarColor getBossBarColor() {
        double progressPercent = getProgress();
        if (progressPercent >= 80) {
            return BarColor.GREEN;   // Almost done
        } else if (progressPercent >= 50) {
            return BarColor.YELLOW;  // In progress
        } else if (progressPercent >= 25) {
            return BarColor.WHITE;   // Started
        } else {
            return BarColor.WHITE;   // Just started
        }
    }
    
    private String formatBossBarTitle() {
        String itemName = recipe.getDisplayName();
        if (actualExchanges > 1) {
            itemName = actualExchanges + "× " + itemName;
        }
        String timeStr = TimeUtil.formatTicksCompact(remainingTicks);
        return String.format(BOSSBAR_FORMAT, itemName, timeStr, getProgress());
    }
    
    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    /**
     * Complete exchange - give rewards to player
     */
    public void complete() {
        cancelled = true;
        removeBossBar();
        
        try {
            Player player = getPlayer();
            
            // If player offline, save pending output
            if (player == null || !player.isOnline()) {
                plugin.getCraftingExecutor().savePendingExchangeOutput(playerUuid, recipe, actualExchanges, multiplier);
                plugin.debug("Player offline, saved pending exchange output for " + playerUuid);
                return;
            }
            
            // Give rewards
            giveRewards(player);
            
            // Notify remainder
            if (remainder > 0) {
                plugin.getMessageUtil().send(player, "exchange.refund",
                    "amount", String.valueOf(remainder));
            }
            
            plugin.getMessageUtil().send(player, "exchange.complete", "item", recipe.getDisplayName());
        } catch (Exception e) {
            plugin.getLogger().warning("Exchange complete failed for recipe=" + recipe.getId() + ": " + e.getMessage());
            plugin.getCraftingExecutor().savePendingExchangeOutput(playerUuid, recipe, actualExchanges, multiplier);
        } finally {
            plugin.getCraftingExecutor().onExchangeComplete(playerUuid);
        }
    }

    /**
     * Give exchange rewards to player
     */
    private void giveRewards(Player player) {
        for (Recipe.Reward reward : recipe.getRewards()) {
            int baseReward = reward.getBaseAmount() * actualExchanges;
            int finalReward = (int) Math.floor(baseReward * multiplier);
            
            plugin.debug("Exchange reward: " + reward.getType() + " x" + finalReward);

            switch (reward.getType().toUpperCase()) {
                case "PLAYER_POINTS":
                    plugin.getEconomyManager().givePlayerPoints(player, finalReward);
                    plugin.getMessageUtil().send(player, "exchange.success",
                        "points", String.valueOf(finalReward),
                        "currency", "Points");
                    break;
                case "VAULT":
                    plugin.getEconomyManager().depositVault(player, finalReward);
                    plugin.getMessageUtil().send(player, "exchange.success",
                        "points", String.valueOf(finalReward),
                        "currency", "Coins");
                    break;
                case "COMMAND":
                    String cmd = reward.getCommand()
                        .replace("%player%", player.getName())
                        .replace("%amount%", String.valueOf(finalReward));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    break;
                case "EXTRA_STORAGE":
                    String material = reward.getMaterial();
                    if (material != null && !material.isEmpty()) {
                        boolean added = plugin.getExtraStorageHook().addItems(player, material, finalReward);
                        if (added) {
                            plugin.getMessageUtil().send(player, "exchange.success",
                                "points", String.valueOf(finalReward),
                                "currency", material.replace("_", " "));
                        } else {
                            plugin.getLogger().warning("Failed to add " + finalReward + " " + material + " to ExtraStorage");
                        }
                    }
                    break;
            }
        }
    }

    public void cancel() {
        cancelled = true;
        removeBossBar();
    }

    // Getters & Setters
    public Player getPlayer() {
        return Bukkit.getPlayer(playerUuid);
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public long getTotalTicks() {
        return totalTicks;
    }
    
    /**
     * COMPATIBILITY: Get total duration in seconds
     */
    public int getTotalDuration() {
        return (int) (totalTicks / 20);
    }
    
    public void setTotalTicks(long totalTicks) {
        this.totalTicks = totalTicks;
    }
    
    /**
     * COMPATIBILITY
     */
    public void setTotalDuration(int seconds) {
        this.totalTicks = seconds * 20L;
    }

    public long getRemainingTicks() {
        return remainingTicks;
    }
    
    /**
     * COMPATIBILITY: Get remaining time in seconds
     */
    public int getRemainingTime() {
        return (int) (remainingTicks / 20);
    }
    
    public void setRemainingTicks(long remainingTicks) {
        this.remainingTicks = remainingTicks;
    }

    public int getProgress() {
        if (totalTicks == 0) return 100;
        return (int) (((double) (totalTicks - remainingTicks) / totalTicks) * 100);
    }

    public boolean isCancelled() {
        return cancelled;
    }
    
    public int getActualExchanges() {
        return actualExchanges;
    }
    
    public double getMultiplier() {
        return multiplier;
    }
    
    public int getRemainder() {
        return remainder;
    }
}
