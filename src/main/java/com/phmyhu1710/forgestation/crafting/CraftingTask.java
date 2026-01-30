package com.phmyhu1710.forgestation.crafting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.ItemBuilder;
import com.phmyhu1710.forgestation.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an active crafting task with timer and BossBar progress
 * Đồng bộ với SmeltingTask - cùng cơ chế timer và persistence
 */
public class CraftingTask {

    private final ForgeStationPlugin plugin;
    private final UUID playerUuid;
    private final Recipe recipe;
    private long totalTicks; // in ticks
    private long remainingTicks;
    private boolean cancelled = false;
    private int batchCount = 1;

    /** Snapshot để hoàn trả khi hủy: nguyên liệu + tiền */
    private List<ItemStack> refundItems = new ArrayList<>();
    private Map<String, Integer> refundExtraStorage = new java.util.HashMap<>();
    private double refundVault;
    private int refundPP;
    private String refundCoinEngineCurrency = "";
    private double refundCoinEngine;

    /** Chế độ "exchange" (recipe có rewards): dùng batchCount = số lần đổi, multiplier + remainder cho phần thừa. */
    private boolean exchangeMode = false;
    private double exchangeMultiplier = 1.0;
    private int exchangeRemainder = 0;

    // BOSSBAR PROGRESS - Unified format với SmeltingTask
    private BossBar bossBar;
    private static final String BOSSBAR_FORMAT = "&b⚒ &f%s &8| &e%s &8| &a%d%%";

    public CraftingTask(ForgeStationPlugin plugin, Player player, Recipe recipe, long durationTicks) {
        this(plugin, player, recipe, durationTicks, 1);
    }
    
    /**
     * @param totalDurationTicks Total time in ticks = (duration per item) * batchCount (cộng dồn như smelting).
     */
    public CraftingTask(ForgeStationPlugin plugin, Player player, Recipe recipe, long totalDurationTicks, int batchCount) {
        this.plugin = plugin;
        this.playerUuid = player.getUniqueId();
        this.recipe = recipe;
        this.totalTicks = totalDurationTicks;
        this.remainingTicks = totalDurationTicks;
        this.batchCount = Math.max(1, batchCount);
    }

    /**
     * Initialize task - tạo BossBar, gửi message
     * Được gọi bởi CraftingExecutor, global timer sẽ tick
     */
    public void initialize() {
        Player player = getPlayer();
        
        if (player != null) {
            createBossBar(player);
            
            if (batchCount > 1) {
                plugin.getMessageUtil().send(player, "craft.started-batch", 
                    "item", recipe.getDisplayName(),
                    "count", String.valueOf(batchCount));
            } else {
                plugin.getMessageUtil().send(player, "craft.started", 
                    "item", recipe.getDisplayName());
            }
        }
    }
    
    /**
     * Tick method - được gọi mỗi tick bởi global timer
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
            BarColor.BLUE,
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
            return BarColor.BLUE;    // Gần xong
        } else if (progressPercent >= 50) {
            return BarColor.GREEN;   // Đang tiến hành
        } else if (progressPercent >= 25) {
            return BarColor.YELLOW;  // Mới bắt đầu
        } else {
            return BarColor.WHITE;   // Còn lâu
        }
    }
    
    /**
     * UNIFIED: Sử dụng TimeUtil.formatTicksCompact để hiển thị thời gian.
     * BossBar optimization: 1 bar + hiển thị số hàng chờ trong title (tránh nhiều bar).
     */
    private String formatBossBarTitle() {
        String itemName = recipe.getDisplayName();
        if (batchCount > 1) {
            itemName = batchCount + "× " + itemName;
        }
        String timeStr = TimeUtil.formatTicksCompact(remainingTicks);
        String base = String.format(BOSSBAR_FORMAT, itemName, timeStr, getProgress());
        Player p = getPlayer();
        int queueSize = (p != null) ? plugin.getCraftingExecutor().getCraftingQueue(p).size() : 0;
        if (queueSize > 0) {
            base += " &8| &7Hàng chờ: &e" + queueSize;
        }
        return base;
    }
    
    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    /**
     * Complete crafting - give output hoặc rewards (exchange mode) cho player.
     * Một hệ thống: craft và exchange đều là "chế tạo", chỉ khác cách trả thưởng.
     */
    public void complete() {
        cancelled = true;
        removeBossBar();

        try {
            Player player = getPlayer();

            if (player == null || !player.isOnline()) {
                if (exchangeMode) {
                    plugin.getCraftingExecutor().savePendingExchangeOutput(playerUuid, recipe, batchCount, exchangeMultiplier);
                } else {
                    plugin.getCraftingExecutor().savePendingOutput(playerUuid, recipe, batchCount);
                }
                plugin.debug("Player offline, saved pending output for " + playerUuid);
                return;
            }

            if (exchangeMode) {
                giveExchangeRewards(player);
                if (exchangeRemainder > 0) {
                    plugin.getMessageUtil().send(player, "exchange.refund", "amount", String.valueOf(exchangeRemainder));
                }
            } else {
                giveOutputSafe(player);
                for (String cmd : recipe.getCommandsOnSuccess()) {
                    String parsed = cmd.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                }
            }

            plugin.getMessageUtil().send(player, "craft.complete", "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
        } catch (Exception e) {
            plugin.getLogger().warning("Craft complete failed for recipe=" + recipe.getId() + ": " + e.getMessage());
            if (exchangeMode) {
                plugin.getCraftingExecutor().savePendingExchangeOutput(playerUuid, recipe, batchCount, exchangeMultiplier);
            } else {
                plugin.getCraftingExecutor().savePendingOutput(playerUuid, recipe, batchCount);
            }
        } finally {
            plugin.getCraftingExecutor().onCraftingComplete(playerUuid);
        }
    }

    /**
     * Trả thưởng cho recipe dạng exchange (rewards). Dùng chung message craft.complete.
     */
    private void giveExchangeRewards(Player player) {
        for (Recipe.Reward reward : recipe.getRewards()) {
            int baseReward = reward.getBaseAmount() * batchCount;
            int finalReward = (int) Math.floor(baseReward * exchangeMultiplier);
            if (finalReward <= 0) continue;
            switch (reward.getType().toUpperCase()) {
                case "PLAYER_POINTS":
                    plugin.getEconomyManager().givePlayerPoints(player, finalReward);
                    break;
                case "VAULT":
                    plugin.getEconomyManager().depositVault(player, finalReward);
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
                        plugin.getExtraStorageHook().addItems(player, material, finalReward);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Give crafting output to player
     * FIX: MMOITEMS case giờ đã give item đúng cách
     */
    private void giveOutputSafe(Player player) {
        Recipe.RecipeResult result = recipe.getResult();
        if (result == null) {
            plugin.getLogger().warning("Recipe " + recipe.getId() + " has no result configured!");
            return;
        }
        
        int totalAmount = result.getAmount() * batchCount;
        
        switch (result.getType().toUpperCase()) {
            case "MMOITEMS":
                // PERF-FIX: Batch items instead of giving 1 by 1
                ItemStack mmoTemplate = ItemBuilder.buildMMOItem(plugin, result.getMmoitemsType(), result.getMmoitemsId(), 1);
                
                if (mmoTemplate != null && mmoTemplate.getType() != Material.STONE) {
                    int maxStack = mmoTemplate.getMaxStackSize();
                    int remaining = totalAmount;
                    
                    while (remaining > 0) {
                        int batch = Math.min(remaining, maxStack);
                        ItemStack stack = mmoTemplate.clone();
                        stack.setAmount(batch);
                        plugin.getOutputRouter().routeOutput(player, stack, "MMOITEM_" + result.getMmoitemsId());
                        remaining -= batch;
                    }
                    plugin.debug("Gave " + totalAmount + "x MMOItem in batches to " + player.getName());
                } else {
                    plugin.getLogger().warning("Failed to create MMOItem: " + result.getMmoitemsType() + ":" + result.getMmoitemsId());
                }
                break;
                
            case "EXTRA_STORAGE":
                plugin.getExtraStorageHook().addItems(player, result.getStorageId(), totalAmount);
                plugin.debug("Added " + totalAmount + "x " + result.getStorageId() + " to ExtraStorage for " + player.getName());
                break;
                
            default: // VANILLA
                ItemStack vanillaTemplate = ItemBuilder.buildResult(plugin, player, result);
                if (vanillaTemplate != null) {
                    int maxStack = vanillaTemplate.getMaxStackSize();
                    int remaining = totalAmount;
                    
                    while (remaining > 0) {
                        int batch = Math.min(remaining, maxStack);
                        ItemStack stack = vanillaTemplate.clone();
                        stack.setAmount(batch);
                        plugin.getOutputRouter().routeOutput(player, stack, result.getMaterial());
                        remaining -= batch;
                    }
                    plugin.debug("Gave " + totalAmount + "x " + result.getMaterial() + " in batches to " + player.getName());
                }
                break;
        }
    }

    /**
     * Set snapshot để hoàn trả khi hủy (gọi từ CraftingExecutor sau khi đã remove ingredients + withdraw).
     */
    public void setRefundSnapshot(List<ItemStack> items, Map<String, Integer> extraStorage,
                                  double vault, int playerPoints, String coinEngineCurrency, double coinEngine) {
        if (items != null) this.refundItems = new ArrayList<>(items);
        if (extraStorage != null) this.refundExtraStorage = new java.util.HashMap<>(extraStorage);
        this.refundVault = vault;
        this.refundPP = playerPoints;
        this.refundCoinEngineCurrency = coinEngineCurrency != null ? coinEngineCurrency : "";
        this.refundCoinEngine = coinEngine;
    }

    /**
     * Bật chế độ exchange (recipe có rewards). batchCount = số lần đổi; multiplier/remainder cho phần thừa.
     */
    public void setExchangeMode(double multiplier, int remainder) {
        this.exchangeMode = true;
        this.exchangeMultiplier = multiplier;
        this.exchangeRemainder = remainder;
    }

    /**
     * Hoàn trả nguyên liệu + tiền cho player (khi hủy task).
     */
    public void refund(Player player) {
        if (player == null || !player.isOnline()) return;
        for (ItemStack item : refundItems) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                plugin.getOutputRouter().routeOutput(player, item.clone(), item.getType().name());
            }
        }
        refundExtraStorage.forEach((storageId, amount) -> {
            if (amount > 0 && plugin.getExtraStorageHook().isAvailable()) {
                plugin.getExtraStorageHook().addItems(player, storageId, amount);
            }
        });
        if (refundVault > 0) plugin.getEconomyManager().depositVault(player, refundVault);
        if (refundPP > 0) plugin.getEconomyManager().givePlayerPoints(player, refundPP);
        if (refundCoinEngine > 0 && !refundCoinEngineCurrency.isEmpty()) {
            plugin.getEconomyManager().depositCoinEngine(player, refundCoinEngineCurrency, refundCoinEngine);
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
     * COMPATIBILITY: Set total duration in seconds
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

    public int getProgress() {
        if (totalTicks == 0) return 100;
        return (int) (((double) (totalTicks - remainingTicks) / totalTicks) * 100);
    }

    public boolean isCancelled() {
        return cancelled;
    }
    
    public int getBatchCount() {
        return batchCount;
    }
    
    public void setBatchCount(int batchCount) {
        this.batchCount = Math.max(1, batchCount);
    }
}
