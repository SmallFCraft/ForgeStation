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

import java.util.UUID;

/**
 * Represents an active crafting task with timer and BossBar progress
 * Đồng bộ với SmeltingTask - cùng cơ chế timer và persistence
 */
public class CraftingTask {

    private final ForgeStationPlugin plugin;
    private final UUID playerUuid;
    private final Recipe recipe;
    private int totalDuration; // in seconds
    private int remainingTime;
    private boolean cancelled = false;
    private int batchCount = 1;
    
    // BOSSBAR PROGRESS - Unified format với SmeltingTask
    private BossBar bossBar;
    private static final String BOSSBAR_FORMAT = "&b⚒ &f%s &8| &e%s &8| &a%d%%";

    public CraftingTask(ForgeStationPlugin plugin, Player player, Recipe recipe, int duration) {
        this(plugin, player, recipe, duration, 1);
    }
    
    public CraftingTask(ForgeStationPlugin plugin, Player player, Recipe recipe, int duration, int batchCount) {
        this.plugin = plugin;
        this.playerUuid = player.getUniqueId();
        this.recipe = recipe;
        this.totalDuration = duration;
        this.remainingTime = duration;
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
     * Tick method - được gọi mỗi giây bởi global timer
     */
    public void tick() {
        if (cancelled) return;
        
        remainingTime--;
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
        
        double progress = (double) (totalDuration - remainingTime) / totalDuration;
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
     * UNIFIED: Sử dụng TimeUtil.formatCompact để hiển thị thời gian (hỗ trợ phút/giờ)
     */
    private String formatBossBarTitle() {
        String itemName = recipe.getDisplayName();
        if (batchCount > 1) {
            itemName = batchCount + "× " + itemName;
        }
        String timeStr = TimeUtil.formatCompact(remainingTime);
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
     * Complete crafting - give output to player
     */
    public void complete() {
        cancelled = true;
        removeBossBar();
        
        try {
            Player player = getPlayer();
            
            // Nếu player offline, lưu pending output
            if (player == null || !player.isOnline()) {
                plugin.getCraftingExecutor().savePendingOutput(playerUuid, recipe, batchCount);
                plugin.debug("Player offline, saved pending crafting output for " + playerUuid);
                return;
            }
            
            // Give output
            giveOutputSafe(player);
            
            // Execute commands
            for (String cmd : recipe.getCommandsOnSuccess()) {
                String parsed = cmd.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
            
            plugin.getMessageUtil().send(player, "craft.complete", "item", recipe.getDisplayName());
        } catch (Exception e) {
            plugin.getLogger().warning("Craft complete failed for recipe=" + recipe.getId() + ": " + e.getMessage());
            plugin.getCraftingExecutor().savePendingOutput(playerUuid, recipe, batchCount);
        } finally {
            plugin.getCraftingExecutor().onCraftingComplete(playerUuid);
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
                // FIX: Give MMOItem to player
                ItemStack mmoItem = ItemBuilder.buildMMOItem(plugin, result.getMmoitemsType(), result.getMmoitemsId(), 1);
                if (mmoItem != null && mmoItem.getType() != Material.STONE) {
                    // Give each item individually (MMOItems usually can't stack)
                    for (int i = 0; i < totalAmount; i++) {
                        ItemStack clone = mmoItem.clone();
                        plugin.getOutputRouter().routeOutput(player, clone, "MMOITEM_" + result.getMmoitemsId());
                    }
                    plugin.debug("Gave " + totalAmount + "x MMOItem " + result.getMmoitemsType() + ":" + result.getMmoitemsId() + " to " + player.getName());
                } else {
                    plugin.getLogger().warning("Failed to create MMOItem: " + result.getMmoitemsType() + ":" + result.getMmoitemsId());
                }
                break;
                
            case "EXTRA_STORAGE":
                plugin.getExtraStorageHook().addItems(player, result.getStorageId(), totalAmount);
                plugin.debug("Added " + totalAmount + "x " + result.getStorageId() + " to ExtraStorage for " + player.getName());
                break;
                
            default: // VANILLA
                ItemStack output = ItemBuilder.buildResult(plugin, player, result);
                output.setAmount(totalAmount);
                plugin.getOutputRouter().routeOutput(player, output, result.getMaterial());
                plugin.debug("Gave " + totalAmount + "x " + result.getMaterial() + " to " + player.getName());
                break;
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

    public int getTotalDuration() {
        return totalDuration;
    }
    
    public void setTotalDuration(int totalDuration) {
        this.totalDuration = totalDuration;
    }

    public int getRemainingTime() {
        return remainingTime;
    }

    public int getProgress() {
        if (totalDuration == 0) return 100;
        return (int) (((double) (totalDuration - remainingTime) / totalDuration) * 100);
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
