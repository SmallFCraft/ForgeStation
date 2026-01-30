package com.phmyhu1710.forgestation.smelting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.util.MessageUtil;
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
 * Represents an active smelting task
 * SMELTING PERSISTENCE FIX: Thêm setTotalDuration để restore tasks
 * BATCH SMELTING: Hỗ trợ nung nhiều batch cùng lúc
 * BOSSBAR PROGRESS: Hiển thị tiến độ trên BossBar
 * PERFORMANCE FIX: Không có internal timer - được tick bởi SmeltingManager global timer
 */
public class SmeltingTask {

    private final ForgeStationPlugin plugin;
    private final UUID playerUuid;
    private final SmeltingRecipe recipe;
    private long totalTicks; // in ticks
    private long remainingTicks;
    private boolean cancelled = false;
    private int batchCount = 1; // BATCH SMELTING: Số lượng batch đang nung

    /** Snapshot để hoàn trả khi hủy: nguyên liệu nung + nhiên liệu */
    private List<ItemStack> refundInputItems = new ArrayList<>();
    private List<ItemStack> refundFuelItems = new ArrayList<>();
    private Map<String, Integer> refundInputExtraStorage = new java.util.HashMap<>();
    private Map<String, Integer> refundFuelExtraStorage = new java.util.HashMap<>();

    // BOSSBAR PROGRESS - Unified format với CraftingTask
    private BossBar bossBar;
    private static final String BOSSBAR_FORMAT = "&6🔥 &f%s &8| &e%s &8| &a%d%%";

    public SmeltingTask(ForgeStationPlugin plugin, Player player, SmeltingRecipe recipe, long durationTicks) {
        this(plugin, player, recipe, durationTicks, 1);
    }
    
    public SmeltingTask(ForgeStationPlugin plugin, Player player, SmeltingRecipe recipe, long durationTicks, int batchCount) {
        this.plugin = plugin;
        this.playerUuid = player.getUniqueId();
        this.recipe = recipe;
        this.totalTicks = durationTicks;
        this.remainingTicks = durationTicks;
        this.batchCount = Math.max(1, batchCount);
    }

    /**
     * PERFORMANCE FIX: Chỉ khởi tạo task (tạo BossBar, gửi message)
     * Không tạo internal timer - SmeltingManager global timer sẽ tick task
     */
    public void initialize() {
        Player player = getPlayer();
        
        // BOSSBAR PROGRESS: Tạo và hiển thị BossBar
        if (player != null) {
            createBossBar(player);
            
            // BATCH: Hiển thị số lượng batch nếu > 1
            if (batchCount > 1) {
                plugin.getMessageUtil().send(player, "smelt.started-batch", 
                    "item", recipe.getDisplayName(),
                    "count", String.valueOf(batchCount));
            } else {
                plugin.getMessageUtil().send(player, "smelt.started", "item", recipe.getDisplayName());
            }
        }
    }
    
    /**
     * PERFORMANCE FIX: Tick method - được gọi mỗi tick bởi SmeltingManager global timer
     */
    public void tick() {
        if (cancelled) return;
        
        remainingTicks--;
        
        // BOSSBAR PROGRESS: Cập nhật BossBar mỗi tick cho mượt
        updateBossBar();
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // BOSSBAR PROGRESS METHODS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * BOSSBAR PROGRESS: Tạo BossBar cho player
     */
    private void createBossBar(Player player) {
        String title = formatBossBarTitle();
        bossBar = Bukkit.createBossBar(
            MessageUtil.colorize(title),
            BarColor.GREEN,
            BarStyle.SEGMENTED_10
        );
        bossBar.setProgress(0.0);
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
    }
    
    /**
     * BOSSBAR PROGRESS: Cập nhật BossBar progress và màu sắc
     */
    private void updateBossBar() {
        if (bossBar == null) return;
        
        Player player = getPlayer();
        if (player == null || !player.isOnline()) {
            removeBossBar();
            return;
        }
        
        // Cập nhật title
        String title = formatBossBarTitle();
        bossBar.setTitle(MessageUtil.colorize(title));
        
        // Cập nhật progress (0.0 -> 1.0)
        double progress = (double) (totalTicks - remainingTicks) / totalTicks;
        bossBar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
        
        // Cập nhật màu dựa trên thời gian còn lại
        bossBar.setColor(getBossBarColor());
    }
    
    /**
     * BOSSBAR PROGRESS: Lấy màu BossBar dựa trên tiến độ
     */
    private BarColor getBossBarColor() {
        double progressPercent = getProgress();
        if (progressPercent >= 80) {
            return BarColor.GREEN;  // Gần xong - xanh lá
        } else if (progressPercent >= 50) {
            return BarColor.YELLOW; // Đang tiến hành - vàng
        } else if (progressPercent >= 25) {
            return BarColor.PINK;   // Mới bắt đầu - hồng/cam
        } else {
            return BarColor.RED;    // Còn lâu - đỏ
        }
    }
    
    /**
     * BOSSBAR PROGRESS: Format title cho BossBar
     * UNIFIED: Sử dụng TimeUtil.formatCompact để hiển thị thời gian (hỗ trợ phút/giờ).
     * BossBar optimization: 1 bar + hiển thị số hàng chờ trong title (tránh nhiều bar).
     */
    private String formatBossBarTitle() {
        String itemName = recipe.getDisplayName();
        // Nếu batch > 1, hiển thị số lượng
        if (batchCount > 1) {
            itemName = batchCount + "× " + itemName;
        }
        String timeStr = TimeUtil.formatTicksCompact(remainingTicks);
        String base = String.format(BOSSBAR_FORMAT, itemName, timeStr, getProgress());
        Player p = getPlayer();
        int queueSize = (p != null) ? plugin.getSmeltingManager().getSmeltingQueue(p).size() : 0;
        if (queueSize > 0) {
            base += " &8| &7Hàng chờ: &e" + queueSize;
        }
        return base;
    }
    
    /**
     * BOSSBAR PROGRESS: Xóa BossBar
     */
    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    public void complete() {
        cancelled = true;
        
        // BOSSBAR PROGRESS: Xóa BossBar khi hoàn tất
        removeBossBar();
        
        // ISSUE-007 FIX: Wrap trong try/finally để đảm bảo onSmeltingComplete luôn được gọi
        try {
            Player player = getPlayer();
            
            // SMELTING PERSISTENCE FIX: Nếu player offline, lưu pending output thay vì bỏ qua
            // BATCH FIX: Truyền batchCount để lưu đúng số lượng
            if (player == null || !player.isOnline()) {
                plugin.getSmeltingManager().savePendingOutput(playerUuid, recipe, batchCount);
                plugin.debug("Player offline, saved pending smelting output for " + playerUuid + " (batch=" + batchCount + ")");
                return;
            }
            
            // Give output item (wrapped trong try/catch)
            giveOutputSafe(player);
            
            plugin.getMessageUtil().send(player, "smelt.complete", "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
        } catch (Exception e) {
            plugin.getLogger().warning("Smelt complete failed for recipe=" + recipe.getId() + ": " + e.getMessage());
            // SMELTING PERSISTENCE FIX: Vẫn save pending output nếu có lỗi
            // BATCH FIX: Truyền batchCount để lưu đúng số lượng
            plugin.getSmeltingManager().savePendingOutput(playerUuid, recipe, batchCount);
        } finally {
            // ISSUE-007 FIX: Luôn gọi onSmeltingComplete để giải phóng state
            plugin.getSmeltingManager().onSmeltingComplete(playerUuid);
        }
    }

    /**
     * ISSUE-007 FIX: Safe version của giveOutput với exception handling
     * ISSUE-014 FIX: Sử dụng OutputRouter để route output theo config
     * BATCH SMELTING: Nhân output với batchCount
     */
    private void giveOutputSafe(Player player) {
        ItemStack output;
        String storageId = null;
        int totalOutputAmount = recipe.getOutputAmount() * batchCount;
        
        switch (recipe.getOutputType()) {
            case "MMOITEMS":
                // TODO: Create MMOItem with batch support
                output = new ItemStack(Material.STONE);
                break;
            case "EXTRA_STORAGE":
                // BATCH: Thêm totalOutputAmount vào ExtraStorage
                storageId = recipe.getOutputMaterial();
                plugin.getExtraStorageHook().addItems(player, storageId, totalOutputAmount);
                return;
            default: // VANILLA
                Material mat;
                try {
                    mat = Material.valueOf(recipe.getOutputMaterial().toUpperCase());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid output material '" + recipe.getOutputMaterial() + "' in recipe " + recipe.getId() + ", using STONE as fallback");
                    mat = Material.STONE;
                }
                output = new ItemStack(mat);
                output.setAmount(Math.max(1, totalOutputAmount));
                storageId = mat.name();
                break;
        }
        
        // ISSUE-014 FIX: Sử dụng OutputRouter thay vì logic hardcoded
        plugin.getOutputRouter().routeOutput(player, output, storageId);
    }

    /**
     * PERFORMANCE FIX: Cancel task - chỉ set flag và cleanup BossBar
     * Không cần cancel scheduler vì không có internal timer
     */
    public void cancel() {
        cancelled = true;
        // BOSSBAR PROGRESS: Xóa BossBar khi hủy
        removeBossBar();
    }

    /**
     * Set snapshot để hoàn trả khi hủy (gọi từ SmeltingManager sau khi đã consume input + fuel).
     */
    public void setRefundSnapshot(List<ItemStack> inputItems, List<ItemStack> fuelItems,
                                  Map<String, Integer> inputES, Map<String, Integer> fuelES) {
        if (inputItems != null) this.refundInputItems = new ArrayList<>(inputItems);
        if (fuelItems != null) this.refundFuelItems = new ArrayList<>(fuelItems);
        if (inputES != null) this.refundInputExtraStorage = new java.util.HashMap<>(inputES);
        if (fuelES != null) this.refundFuelExtraStorage = new java.util.HashMap<>(fuelES);
    }

    /**
     * Hoàn trả nguyên liệu nung + nhiên liệu cho player (khi hủy task).
     */
    public void refund(Player player) {
        if (player == null || !player.isOnline()) return;
        for (ItemStack item : refundInputItems) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                plugin.getOutputRouter().routeOutput(player, item.clone(), item.getType().name());
            }
        }
        for (ItemStack item : refundFuelItems) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                plugin.getOutputRouter().routeOutput(player, item.clone(), item.getType().name());
            }
        }
        refundInputExtraStorage.forEach((id, amount) -> {
            if (amount > 0 && plugin.getExtraStorageHook().isAvailable()) {
                plugin.getExtraStorageHook().addItems(player, id, amount);
            }
        });
        refundFuelExtraStorage.forEach((id, amount) -> {
            if (amount > 0 && plugin.getExtraStorageHook().isAvailable()) {
                plugin.getExtraStorageHook().addItems(player, id, amount);
            }
        });
    }

    public Player getPlayer() {
        return Bukkit.getPlayer(playerUuid);
    }

    public SmeltingRecipe getRecipe() {
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
    
    /**
     * SMELTING PERSISTENCE FIX: Set total duration (dùng khi restore task)
     */
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
