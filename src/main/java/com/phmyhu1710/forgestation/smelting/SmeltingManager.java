package com.phmyhu1710.forgestation.smelting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.expression.ExpressionParser;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages smelting recipes and active smelting tasks
 * SMELTING PERSISTENCE FIX: Implements Listener để xử lý player quit/join
 * PERFORMANCE FIX: Single global timer thay vì mỗi task có timer riêng
 */
public class SmeltingManager implements Listener {

    private final ForgeStationPlugin plugin;
    private final Map<String, SmeltingRecipe> smeltingRecipes = new java.util.LinkedHashMap<>();
    /** Mỗi player có List tasks chạy song song. Size <= getMaxParallelTasks(player). */
    private final Map<UUID, java.util.List<SmeltingTask>> activeTasks = new ConcurrentHashMap<>();

    /** Hàng chờ thống nhất — dùng TaskQueueManager */

    // PERFORMANCE FIX: Single global timer cho tất cả smelting tasks
    private int globalTimerTaskId = -1;

    public static final class PendingSmeltingTask {
        private final String recipeId;
        private final int batchCount;
        public PendingSmeltingTask(String recipeId, int batchCount) {
            this.recipeId = recipeId;
            this.batchCount = Math.max(1, batchCount);
        }
        public String getRecipeId() { return recipeId; }
        public int getBatchCount() { return batchCount; }
    }

    public SmeltingManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Start global timer
        startGlobalTimer();
    }
    
    /**
     * PERFORMANCE FIX: Start single global timer để tick tất cả active tasks
     * Thay vì 300 timers riêng biệt, chỉ có 1 timer loop qua tất cả tasks
     */
    private void startGlobalTimer() {
        globalTimerTaskId = plugin.getScheduler().runTimer(() -> {
            if (activeTasks.isEmpty()) return;
            java.util.List<SmeltingTask> toComplete = new java.util.ArrayList<>();
            for (java.util.Map.Entry<UUID, java.util.List<SmeltingTask>> entry : new java.util.ArrayList<>(activeTasks.entrySet())) {
                java.util.List<SmeltingTask> list = entry.getValue();
                if (list == null) continue;
                java.util.Iterator<SmeltingTask> it = list.iterator();
                while (it.hasNext()) {
                    SmeltingTask task = it.next();
                    if (task == null || task.isCancelled()) {
                        it.remove();
                        continue;
                    }
                    task.tick();
                    if (task.getRemainingTicks() <= 0) {
                        it.remove();
                        toComplete.add(task);
                    }
                }
                if (list.isEmpty()) activeTasks.remove(entry.getKey());
            }
            for (SmeltingTask task : toComplete) task.complete();
        }, 1L, 1L);
    }
    
    public void stopGlobalTimer() {
        if (globalTimerTaskId != -1) {
            plugin.getScheduler().cancelTask(globalTimerTaskId);
            globalTimerTaskId = -1;
        }
    }

    public void loadSmeltingRecipes() {
        smeltingRecipes.clear();
        
        Map<String, FileConfiguration> smeltingConfigs = plugin.getConfigManager().getSmeltingConfigs();
        
        for (Map.Entry<String, FileConfiguration> entry : smeltingConfigs.entrySet()) {
            String fileName = entry.getKey();
            FileConfiguration config = entry.getValue();
            
            // Register custom command(s) if set
            if (config.isList("open_command")) {
                for (String cmd : config.getStringList("open_command")) {
                     plugin.getCustomCommandManager().registerCommand(cmd, fileName.toLowerCase());
                }
            } else {
                String openCommand = config.getString("open_command");
                if (openCommand != null && !openCommand.isEmpty()) {
                    plugin.getCustomCommandManager().registerCommand(openCommand, fileName.toLowerCase());
                }
            }
            
            ConfigurationSection recipesSection = config.getConfigurationSection("smelting");
            if (recipesSection == null) continue;
            
            for (String recipeId : recipesSection.getKeys(false)) {
                ConfigurationSection recipeConfig = recipesSection.getConfigurationSection(recipeId);
                if (recipeConfig == null) continue;
                
                if (!recipeConfig.getBoolean("enabled", true)) continue;
                
                try {
                    SmeltingRecipe recipe = new SmeltingRecipe(recipeId, fileName, recipeConfig);
                    smeltingRecipes.put(recipeId, recipe);
                    plugin.debug("Loaded smelting recipe: " + recipeId + " from " + fileName);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load smelting recipe " + recipeId + ": " + e.getMessage());
                }
            }
        }
        
        plugin.debug("Loaded " + smeltingRecipes.size() + " smelting recipes");
    }

    public SmeltingRecipe getRecipe(String id) {
        return smeltingRecipes.get(id);
    }

    /**
     * ISSUE-004 FIX: Returns unmodifiable view instead of copy
     */
    public Map<String, SmeltingRecipe> getAllRecipes() {
        return java.util.Collections.unmodifiableMap(smeltingRecipes);
    }

    public int getSmeltingCount() {
        return smeltingRecipes.size();
    }

    public java.util.Set<String> getSmeltingIds() {
        return smeltingRecipes.keySet();
    }

    /**
     * Get duration for smelting recipe (in seconds)
     * IMPROVED: Sử dụng percentage-based reduction từ upgrade system
     */
    public int getDuration(Player player, SmeltingRecipe recipe) {
        return (int) (getDurationTicks(player, recipe) / 20);
    }
    
    /**
     * TICK-SUPPORT: Get duration in ticks
     */
    public long getDurationTicks(Player player, SmeltingRecipe recipe) {
        // 1. Lấy base duration (ticks)
        long baseTicks = getBaseDurationTicks(recipe);
        
        // 2. Effective level (cap theo config max-level khi admin đổi config)
        int smeltingLevel = plugin.getUpgradeManager().getEffectiveLevel(player, "smelting_speed");
        if (smeltingLevel <= 0) return baseTicks;

        // 3. Áp dụng reduction từ upgrade config
        var upgrade = plugin.getUpgradeManager().getUpgrade("smelting_speed");
        if (upgrade != null) {
            return upgrade.applyDurationReduction(baseTicks, smeltingLevel);
        }
        
        // Fallback
        Map<String, Double> vars = ExpressionParser.createVariables(0, smeltingLevel, smeltingLevel);
        return ExpressionParser.parseTimeTicks(recipe.getDurationExpression(), vars);
    }
    
    /**
     * Get base duration (without player upgrades)
     * Parse expression với level = 0
     */
    public int getBaseDuration(SmeltingRecipe recipe) {
        return (int) (getBaseDurationTicks(recipe) / 20);
    }
    
    /**
     * TICK-SUPPORT: Get base duration in ticks
     */
    public long getBaseDurationTicks(SmeltingRecipe recipe) {
        Map<String, Double> vars = ExpressionParser.createVariables(0, 0, 0);
        return ExpressionParser.parseTimeTicks(recipe.getDurationExpression(), vars);
    }

    /**
     * Start a smelting task for player (single batch)
     */
    public boolean startSmelting(Player player, SmeltingRecipe recipe) {
        return startSmelting(player, recipe, 1);
    }
    
    /**
     * BATCH SMELTING: Start smelting with specified batch count
     */
    public boolean startSmelting(Player player, SmeltingRecipe recipe, int batchCount) {
        UUID uuid = player.getUniqueId();
        batchCount = Math.max(1, batchCount);
        int maxParallel = plugin.getUpgradeManager().getMaxParallelTasks(player);
        java.util.List<SmeltingTask> list = activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());

        if (list.size() >= maxParallel) {
            if (!plugin.getTaskQueueManager().addToQueue(player, com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.SMELT, recipe.getId(), batchCount)) {
                plugin.getMessageUtil().send(player, "smelt.queue-full");
                return false;
            }
            plugin.getMessageUtil().send(player, "smelt.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(batchCount),
                "queue", String.valueOf(plugin.getTaskQueueManager().getQueueSize(player)));
            return true;
        }

        // Check permission (OP bypass)
        if (!player.isOp() && !recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
            plugin.getMessageUtil().send(player, "smelt.no-permission");
            return false;
        }
        
        // Calculate required amounts for batch
        int requiredInput = recipe.getInputAmount() * batchCount;
        int requiredFuel = recipe.getFuelAmount() * batchCount;
        
        // Feature: Inventory Space Check
        String outputType = recipe.getOutputType();
        // Calculate output items
        java.util.List<ItemStack> potentialOutputs = new java.util.ArrayList<>();
        if ("VANILLA".equalsIgnoreCase(outputType)) {
             try {
                 Material mat = Material.valueOf(recipe.getOutputMaterial().toUpperCase());
                 int totalOutput = recipe.getOutputAmount() * batchCount;
                 potentialOutputs.add(new ItemStack(mat, totalOutput));
                 
                 if (!com.phmyhu1710.forgestation.util.InventoryUtil.hasSpace(player, potentialOutputs)) {
                     plugin.getMessageUtil().send(player, "smelt.no-space");
                     return false;
                 }
             } catch (Exception e) {
                 // Ignore invalid material here, let it fail later or handle gracefully
             }
        } else if ("MMOITEMS".equalsIgnoreCase(outputType)) {
             // For MMOItems, similar logic if possible
             com.phmyhu1710.forgestation.util.ItemBuilder.buildMMOItem(plugin, recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId(), 1);
              // Simplified check: assume 1 slot per stack if we knew stack size, or just check empty slots
             // For now, let's skip rigorous check for MMOItems or do a basic slot check? 
             // Better to try building 1 item to get stack size.
             ItemStack mmoItem = com.phmyhu1710.forgestation.util.ItemBuilder.buildMMOItem(plugin, recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId(), 1);
             if (mmoItem != null && mmoItem.getType() != Material.STONE) {
                  int totalOutput = recipe.getOutputAmount() * batchCount;
                  mmoItem.setAmount(totalOutput);
                  if (!com.phmyhu1710.forgestation.util.InventoryUtil.hasSpace(player, java.util.Collections.singletonList(mmoItem))) {
                       plugin.getMessageUtil().send(player, "smelt.no-space");
                       return false;
                  }
             }
        }

        
        // Check input materials for batch
        int availableInput = countSmeltingMaterial(player, recipe);
        if (availableInput < requiredInput) {
            plugin.getMessageUtil().send(player, "smelt.missing-materials",
                "material", recipe.getInputMaterial(),
                "amount", String.valueOf(requiredInput));
            return false;
        }
        
        // Check fuel if required for batch
        if (recipe.isFuelRequired()) {
            int availableFuel = countFuel(player, recipe);
            if (availableFuel < requiredFuel) {
                plugin.getMessageUtil().send(player, "smelt.missing-fuel",
                    "fuel", recipe.getFuelMaterial(),
                    "amount", String.valueOf(requiredFuel));
                return false;
            }
        }
        
        // Thu thập snapshot hoàn trả trước khi consume (để hủy thì hoàn tác)
        java.util.Map<String, Integer> refundInputES = new java.util.HashMap<>();
        java.util.List<ItemStack> refundInput = consumeSmeltingMaterialsAndCollect(player, recipe, batchCount, refundInputES);
        java.util.Map<String, Integer> refundFuelES = new java.util.HashMap<>();
        java.util.List<ItemStack> refundFuel = recipe.isFuelRequired()
            ? consumeFuelAndCollect(player, recipe, batchCount, refundFuelES) : new java.util.ArrayList<>();

        long durationPerItemTicks = getDurationTicks(player, recipe);
        long totalDurationTicks = durationPerItemTicks * batchCount;

        SmeltingTask task = new SmeltingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundInput, refundFuel, refundInputES, refundFuelES);
        list.add(task);

        task.initialize();

        // CRASH PROTECTION: Save task ngay vào DB
        immediatelySaveTask(player.getUniqueId());

        return true;
    }
    
    /**
     * BATCH SMELTING: Calculate max batch count player can smelt
     */
    public int getMaxBatchCount(Player player, SmeltingRecipe recipe) {
        int inputHas = countSmeltingMaterial(player, recipe);
        int inputNeed = recipe.getInputAmount();
        int maxByInput = inputNeed > 0 ? inputHas / inputNeed : 0;
        
        if (!recipe.isFuelRequired()) {
            return maxByInput;
        }
        
        int fuelHas = countFuel(player, recipe);
        int fuelNeed = recipe.getFuelAmount();
        int maxByFuel = fuelNeed > 0 ? fuelHas / fuelNeed : Integer.MAX_VALUE;
        
        return Math.min(maxByInput, maxByFuel);
    }
    
    /**
     * PERF-FIX: Tạo snapshot chứa tất cả thông tin inventory cần thiết
     * Gọi 1 lần thay vì gọi countSmeltingMaterial, countFuel, getMaxBatchCount riêng lẻ
     */
    public com.phmyhu1710.forgestation.util.InventoryUtil.SmeltingSnapshot createSnapshot(Player player, SmeltingRecipe recipe) {
        return com.phmyhu1710.forgestation.util.InventoryUtil.createSmeltingSnapshot(plugin, player, recipe);
    }
    
    /**
     * Check if player has required smelting input materials
     */
    public boolean hasSmeltingMaterials(Player player, SmeltingRecipe recipe) {
        return countSmeltingMaterial(player, recipe) >= recipe.getInputAmount();
    }
    
    /**
     * Count smelting input materials in player inventory
     */
    public int countSmeltingMaterial(Player player, SmeltingRecipe recipe) {
        int count = 0;
        
        switch (recipe.getInputType()) {
            case "VANILLA":
                try {
                    org.bukkit.Material mat = org.bukkit.Material.valueOf(recipe.getInputMaterial().toUpperCase());
                    for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                        if (item != null && item.getType() == mat) {
                            count += item.getAmount();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid smelting input material: " + recipe.getInputMaterial());
                }
                break;
            case "MMOITEMS":
                for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                    if (item != null && com.phmyhu1710.forgestation.util.ItemBuilder.matchesMMOItem(plugin, item, 
                            recipe.getInputMmoitemsType(), recipe.getInputMmoitemsId())) {
                        count += item.getAmount();
                    }
                }
                break;
            case "EXTRA_STORAGE":
                count = (int) plugin.getExtraStorageHook().getItemCount(player, recipe.getInputMaterial());
                break;
        }
        
        return count;
    }
    
    /**
     * Consume smelting input materials from player (single batch)
     */
    public void consumeSmeltingMaterials(Player player, SmeltingRecipe recipe) {
        consumeSmeltingMaterials(player, recipe, 1);
    }
    
    /**
     * BATCH SMELTING: Consume input materials and collect clones for refund.
     * Returns list of cloned items taken; for EXTRA_STORAGE, add (material, amount) to outRefundES.
     */
    public java.util.List<ItemStack> consumeSmeltingMaterialsAndCollect(Player player, SmeltingRecipe recipe, int batchCount,
                                                                        java.util.Map<String, Integer> outRefundES) {
        java.util.List<ItemStack> collected = new java.util.ArrayList<>();
        int totalNeed = recipe.getInputAmount() * batchCount;

        if ("EXTRA_STORAGE".equals(recipe.getInputType())) {
            if (outRefundES != null) outRefundES.put(recipe.getInputMaterial(), totalNeed);
            plugin.getExtraStorageHook().removeItems(player, recipe.getInputMaterial(), totalNeed);
            return collected;
        }

        int remaining = totalNeed;
        switch (recipe.getInputType()) {
            case "VANILLA":
                try {
                    org.bukkit.Material mat = org.bukkit.Material.valueOf(recipe.getInputMaterial().toUpperCase());
                    for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                        if (item != null && item.getType() == mat && remaining > 0) {
                            int take = Math.min(item.getAmount(), remaining);
                            ItemStack clone = item.clone();
                            clone.setAmount(take);
                            collected.add(clone);
                            item.setAmount(item.getAmount() - take);
                            remaining -= take;
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to consume smelting material: " + e.getMessage());
                }
                break;
            case "MMOITEMS":
                for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                    if (item != null && remaining > 0 && com.phmyhu1710.forgestation.util.ItemBuilder.matchesMMOItem(plugin, item,
                            recipe.getInputMmoitemsType(), recipe.getInputMmoitemsId())) {
                        int take = Math.min(item.getAmount(), remaining);
                        ItemStack clone = item.clone();
                        clone.setAmount(take);
                        collected.add(clone);
                        item.setAmount(item.getAmount() - take);
                        remaining -= take;
                    }
                }
                break;
        }
        return collected;
    }

    /**
     * BATCH SMELTING: Consume smelting input materials with batch multiplier
     */
    public void consumeSmeltingMaterials(Player player, SmeltingRecipe recipe, int batchCount) {
        consumeSmeltingMaterialsAndCollect(player, recipe, batchCount, null);
    }
    
    /**
     * Check if player has required fuel
     */
    public boolean hasFuel(Player player, SmeltingRecipe recipe) {
        return countFuel(player, recipe) >= recipe.getFuelAmount();
    }
    
    /**
     * Count fuel materials in player inventory
     */
    public int countFuel(Player player, SmeltingRecipe recipe) {
        int count = 0;
        
        switch (recipe.getFuelType()) {
            case "VANILLA":
                try {
                    org.bukkit.Material mat = org.bukkit.Material.valueOf(recipe.getFuelMaterial().toUpperCase());
                    for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                        if (item != null && item.getType() == mat) {
                            count += item.getAmount();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Invalid fuel material: " + recipe.getFuelMaterial());
                }
                break;
            case "EXTRA_STORAGE":
                count = (int) plugin.getExtraStorageHook().getItemCount(player, recipe.getFuelMaterial());
                break;
        }
        
        return count;
    }
    
    /**
     * Consume fuel from player (single batch)
     */
    public void consumeFuel(Player player, SmeltingRecipe recipe) {
        consumeFuel(player, recipe, 1);
    }
    
    /**
     * BATCH SMELTING: Consume fuel and collect clones for refund.
     */
    public java.util.List<ItemStack> consumeFuelAndCollect(Player player, SmeltingRecipe recipe, int batchCount,
                                                           java.util.Map<String, Integer> outRefundES) {
        java.util.List<ItemStack> collected = new java.util.ArrayList<>();
        int totalNeed = recipe.getFuelAmount() * batchCount;

        if ("EXTRA_STORAGE".equals(recipe.getFuelType())) {
            if (outRefundES != null) outRefundES.put(recipe.getFuelMaterial(), totalNeed);
            plugin.getExtraStorageHook().removeItems(player, recipe.getFuelMaterial(), totalNeed);
            return collected;
        }

        int remaining = totalNeed;
        if ("VANILLA".equals(recipe.getFuelType())) {
            try {
                org.bukkit.Material mat = org.bukkit.Material.valueOf(recipe.getFuelMaterial().toUpperCase());
                for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == mat && remaining > 0) {
                        int take = Math.min(item.getAmount(), remaining);
                        ItemStack clone = item.clone();
                        clone.setAmount(take);
                        collected.add(clone);
                        item.setAmount(item.getAmount() - take);
                        remaining -= take;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to consume fuel: " + e.getMessage());
            }
        }
        return collected;
    }

    /**
     * BATCH SMELTING: Consume fuel with batch multiplier
     */
    public void consumeFuel(Player player, SmeltingRecipe recipe, int batchCount) {
        consumeFuelAndCollect(player, recipe, batchCount, null);
    }

    /**
     * Cancel smelting task for player và hoàn trả nguyên liệu + nhiên liệu
     */
    public void cancelSmelting(Player player) {
        UUID uuid = player.getUniqueId();
        java.util.List<SmeltingTask> list = activeTasks.remove(uuid);
        if (list != null && !list.isEmpty()) {
            for (SmeltingTask task : list) {
                task.refund(player);
                task.cancel();
            }
            plugin.getMessageUtil().send(player, "smelt.cancelled-refund");
        }
        // ANTI-DUPE: Clear DB entry để tránh restore lại task đã cancel
        var db = getDatabase();
        if (db != null) db.clearAllActiveSmeltingTasks(uuid);
    }

    /**
     * Called when smelting completes. Promote từ queue chung (chỉ item type SMELT)
     */
    public void onSmeltingComplete(UUID uuid) {
        // ANTI-DUPE: Sync DB với memory — xóa task đã complete, giữ lại task còn active
        var db = getDatabase();
        if (db != null) {
            java.util.List<SmeltingTask> remaining = activeTasks.get(uuid);
            if (remaining == null || remaining.isEmpty() || remaining.stream().allMatch(SmeltingTask::isCancelled)) {
                db.clearAllActiveSmeltingTasks(uuid);
            } else {
                immediatelySaveTask(uuid);
            }
        }
        Player player = org.bukkit.Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        plugin.getTaskQueueManager().promoteNext(player, com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.SMELT);
    }

    /**
     * Bắt đầu nung từ hàng chờ. Gọi từ TaskQueueManager khi promote.
     */
    public boolean startFromQueue(Player player, String recipeId, int batchCount) {
        SmeltingRecipe recipe = smeltingRecipes.get(recipeId);
        if (recipe == null) return false;
        int requiredInput = recipe.getInputAmount() * batchCount;
        int requiredFuel = recipe.getFuelAmount() * batchCount;
        if (countSmeltingMaterial(player, recipe) < requiredInput) {
            plugin.getMessageUtil().send(player, "smelt.missing-materials", "material", recipe.getInputMaterial(), "amount", String.valueOf(requiredInput));
            return false;
        }
        if (recipe.isFuelRequired() && countFuel(player, recipe) < requiredFuel) {
            plugin.getMessageUtil().send(player, "smelt.missing-fuel", "fuel", recipe.getFuelMaterial(), "amount", String.valueOf(requiredFuel));
            return false;
        }
        java.util.Map<String, Integer> refundInputES = new java.util.HashMap<>();
        java.util.List<ItemStack> refundInput = consumeSmeltingMaterialsAndCollect(player, recipe, batchCount, refundInputES);
        java.util.Map<String, Integer> refundFuelES = new java.util.HashMap<>();
        java.util.List<ItemStack> refundFuel = recipe.isFuelRequired() ? consumeFuelAndCollect(player, recipe, batchCount, refundFuelES) : new java.util.ArrayList<>();
        long durationPerItemTicks = getDurationTicks(player, recipe);
        long totalDurationTicks = durationPerItemTicks * batchCount;
        SmeltingTask task = new SmeltingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundInput, refundFuel, refundInputES, refundFuelES);
        activeTasks.computeIfAbsent(player.getUniqueId(), k -> new java.util.ArrayList<>()).add(task);
        task.initialize();
        // CRASH PROTECTION: Save task ngay vào DB
        immediatelySaveTask(player.getUniqueId());
        return true;
    }

    /** @deprecated Dùng getTaskQueueManager().getQueue(player) */
    @Deprecated
    public java.util.List<PendingSmeltingTask> getSmeltingQueue(Player player) {
        java.util.List<com.phmyhu1710.forgestation.queue.TaskQueueManager.PendingTask> all = plugin.getTaskQueueManager().getQueue(player);
        java.util.List<PendingSmeltingTask> out = new java.util.ArrayList<>();
        for (var pt : all) {
            if (pt.getType() == com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.SMELT) {
                out.add(new PendingSmeltingTask(pt.getRecipeId(), pt.getBatchCount()));
            }
        }
        return out;
    }

    public void cancelQueueSlot(Player player, int index) {
        plugin.getTaskQueueManager().removeSlot(player, index);
        plugin.getMessageUtil().send(player, "smelt.queue-slot-cancelled");
    }

    /**
     * Get active smelting task for player
     */
    public SmeltingTask getActiveTask(Player player) {
        java.util.List<SmeltingTask> list = activeTasks.get(player.getUniqueId());
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * Check if player is currently smelting
     */
    public boolean isSmelting(Player player) {
        java.util.List<SmeltingTask> list = activeTasks.get(player.getUniqueId());
        return list != null && !list.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELOAD PERSISTENCE: Save/restore tasks khi reload plugin
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * RELOAD PERSISTENCE: Lưu tất cả active tasks để restore sau reload
     * Không cancel tasks, chỉ lưu data và trả về list
     * @return List of [uuid, recipeId, remainingTime, totalDuration, batchCount]
     */
    public java.util.List<Object[]> saveAllTasksForReload() {
        java.util.List<Object[]> savedTasks = new java.util.ArrayList<>();
        
        for (Map.Entry<UUID, java.util.List<SmeltingTask>> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            for (SmeltingTask task : entry.getValue()) {
                if (task == null) continue;
                savedTasks.add(new Object[] {
                    uuid,
                    task.getRecipe().getId(),
                    (int) task.getRemainingTicks(),
                    (int) task.getTotalTicks(),
                    task.getBatchCount()
                });
                task.cancel();
            }
        }
        
        activeTasks.clear();
        plugin.debug("Saved " + savedTasks.size() + " smelting tasks for reload");
        return savedTasks;
    }
    
    /**
     * RELOAD PERSISTENCE: Restore tasks sau khi reload với recipe references mới
     * @param savedTasks List of [uuid, recipeId, remainingTicks, totalTicks, batchCount]
     */
    public void restoreTasksAfterReload(java.util.List<Object[]> savedTasks) {
        if (savedTasks == null || savedTasks.isEmpty()) return;
        
        var db = getDatabase();
        java.util.Map<UUID, java.util.List<Object[]>> offlineByUuid = new java.util.HashMap<>();
        int restored = 0;
        
        long now = System.currentTimeMillis();
        for (Object[] data : savedTasks) {
            UUID uuid = (UUID) data[0];
            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                long remain = ((Number) data[2]).longValue(), total = ((Number) data[3]).longValue();
                long startedAt = now - (total - remain) * 50L;
                offlineByUuid.computeIfAbsent(uuid, k -> new java.util.ArrayList<>())
                    .add(new Object[]{(String) data[1], data[2], data[3], data[4], startedAt});
                continue;
            }
            
            String recipeId = (String) data[1];
            long remainingTicks = ((Number) data[2]).longValue();
            long totalTicks = ((Number) data[3]).longValue();
            int batchCount = (int) data[4];
            
            // Tìm recipe mới (sau reload)
            SmeltingRecipe recipe = getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("[ForgeStation] Cannot restore smelting task: recipe '" + recipeId + "' not found after reload. Notifying " + player.getName());
                plugin.getMessageUtil().send(player, "smelt.recipe-removed", "recipe", recipeId);
                continue;
            }
            
            // Tạo task mới với recipe reference mới (ticks)
            SmeltingTask task = new SmeltingTask(plugin, player, recipe, Math.max(1, remainingTicks), batchCount);
            task.setTotalTicks(totalTicks);
            // REFUND FIX: Rebuild refund snapshot cho task restore sau reload
            buildAndSetRefundSnapshotForRestoredTask(player, recipe, batchCount, task);
            activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>()).add(task);
            task.initialize();
            
            restored++;
            plugin.debug("Restored smelting task for " + player.getName() + ": " + recipeId);
        }
        
        if (db != null && !offlineByUuid.isEmpty()) {
            for (java.util.Map.Entry<UUID, java.util.List<Object[]>> e : offlineByUuid.entrySet()) {
                db.saveAllActiveSmeltingTasks(e.getKey(), e.getValue());
            }
        }
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " smelting tasks after reload");
        }
    }
    
    /**
     * Lưu tất cả tasks + queue vào DB (không cancel). Dùng cho periodic save khi server crash.
     */
    public void saveAllTasksToDatabase() {
        var db = getDatabase();
        if (db == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, java.util.List<SmeltingTask>> entry : activeTasks.entrySet()) {
            java.util.List<SmeltingTask> tasks = entry.getValue();
            if (tasks == null || tasks.isEmpty()) continue;
            java.util.List<Object[]> entries = new java.util.ArrayList<>();
            for (SmeltingTask t : tasks) {
                if (t == null) continue;
                long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                long startedAt = now - (total - remain) * 50L;
                entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
            }
            if (!entries.isEmpty()) db.saveAllActiveSmeltingTasks(entry.getKey(), entries);
        }
    }

    /**
     * Cancel all tasks (on plugin disable)
     * SMELTING PERSISTENCE FIX: Lưu tasks trước khi cancel
     * Ghi trực tiếp vào DB (không dùng queue vì có thể đã shutdown)
     */
    public void cancelAllTasks() {
        // PERFORMANCE FIX: Dừng global timer trước
        stopGlobalTimer();
        
        int savedCount = 0;
        var db = getDatabase();
        
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, java.util.List<SmeltingTask>> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            java.util.List<SmeltingTask> tasks = entry.getValue();
            if (db != null && tasks != null && !tasks.isEmpty()) {
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (SmeltingTask t : tasks) {
                    if (t != null) {
                        savedCount++;
                        long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                        long startedAt = now - (total - remain) * 50L;
                        entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
                    }
                }
                if (!entries.isEmpty()) db.saveAllActiveSmeltingTasks(uuid, entries);
            }
            for (SmeltingTask task : tasks) {
                if (task != null) task.cancel();
            }
        }
        activeTasks.clear();
        if (savedCount > 0) {
            plugin.getLogger().info("Saved " + savedCount + " smelting tasks for server restart");
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SMELTING PERSISTENCE FIX: Event handlers cho player join/quit
    // ═══════════════════════════════════════════════════════════════════════
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        java.util.List<SmeltingTask> list = activeTasks.remove(uuid);
        
        if (list != null && !list.isEmpty()) {
            var db = getDatabase();
            if (db != null) {
                long now = System.currentTimeMillis();
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (SmeltingTask t : list) {
                    long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                    long startedAt = now - (total - remain) * 50L;
                    entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
                }
                db.saveAllActiveSmeltingTasks(uuid, entries);
                plugin.getLogger().info("[ForgeStation] Lưu " + list.size() + " smelting task(s) cho " + event.getPlayer().getName() + " khi thoát");
            }
            for (SmeltingTask task : list) task.cancel();
        }
        // Hàng chờ thống nhất được lưu trong CraftingExecutor.onPlayerQuit (plugin disable gọi crafting sau smelting nên queue đã clear — thực ra order là smelting rồi crafting; queue clear trong crafting.cancelAllTasks)
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Schedule để chạy sau khi player fully joined
        plugin.getScheduler().runLater(() -> {
            if (!player.isOnline()) return;
            restoreTaskForPlayer(player);
        }, 20L); // 1 second delay
    }
    
    /**
     * EXTERNAL RELOAD FIX: Restore task cho một player cụ thể
     * Có thể gọi từ onPlayerJoin hoặc từ plugin enable (external reload)
     * @return true nếu có task được restore
     */
    public boolean restoreTaskForPlayer(Player player) {
        if (player == null || !player.isOnline()) return false;
        
        var db = getDatabase();
        if (db == null) return false;
        
        // 1. Giao pending outputs trước
        deliverPendingOutputs(player);
        
        // 2. Hàng chờ thống nhất — restore trong CraftingExecutor.restoreTaskForPlayer
        // 3. Restore active task nếu có
        return restoreSmeltingTask(player);
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Giao pending outputs cho player khi rejoin
     */
    private void deliverPendingOutputs(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return;
        
        List<String[]> pendingOutputs = db.loadPendingSmeltingOutputs(uuid);
        if (pendingOutputs.isEmpty()) return;
        
        int totalItems = 0;
        for (String[] output : pendingOutputs) {
            String recipeId = output[0];
            String outputMaterial = output[1];
            int amount = Integer.parseInt(output[2]);
            String outputType = output[3];
            
            try {
                switch (outputType.toUpperCase()) {
                    case "MMOITEMS":
                        // Tìm recipe để lấy mmoitems type/id
                        SmeltingRecipe recipe = getRecipe(recipeId);
                        if (recipe != null) {
                            ItemStack mmoItem = com.phmyhu1710.forgestation.util.ItemBuilder.buildMMOItem(
                                plugin, recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId(), 1);
                            if (mmoItem != null && mmoItem.getType() != Material.STONE) {
                                int maxStack = mmoItem.getMaxStackSize();
                                int remaining = amount;
                                while (remaining > 0) {
                                    int batch = Math.min(remaining, maxStack);
                                    ItemStack stack = mmoItem.clone();
                                    stack.setAmount(batch);
                                    plugin.getOutputRouter().routeOutput(player, stack, com.phmyhu1710.forgestation.hook.ExtraStorageHook.buildMmoItemsKey(recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId()));
                                    remaining -= batch;
                                }
                                totalItems += amount;
                            }
                        }
                        break;
                    case "EXTRA_STORAGE":
                        if (plugin.getExtraStorageHook().isAvailable()) {
                            plugin.getExtraStorageHook().addItems(player, outputMaterial, amount);
                            totalItems += amount;
                        }
                        break;
                    default: // VANILLA
                        Material mat = Material.valueOf(outputMaterial.toUpperCase());
                        ItemStack item = new ItemStack(mat, amount);
                        plugin.getOutputRouter().routeOutput(player, item, outputMaterial);
                        totalItems += amount;
                        break;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deliver pending smelting output " + outputMaterial + " to " + player.getName() + ": " + e.getMessage());
            }
        }
        
        // Clear pending outputs
        db.clearPendingSmeltingOutputs(uuid);
        
        if (totalItems > 0) {
            plugin.getMessageUtil().send(player, "smelt.pending-delivered", 
                "count", String.valueOf(totalItems));
        }
    }
    
    /**
     * Khôi phục tất cả smelting tasks khi player rejoin (hỗ trợ song song)
     */
    private boolean restoreSmeltingTask(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return false;
        
        java.util.List<Object[]> allTasks = db.loadAllActiveSmeltingTasks(uuid);
        if (allTasks.isEmpty()) return false;
        
        db.clearActiveSmeltingTask(uuid);
        boolean offlineProgress = plugin.getConfigManager().getMainConfig().getBoolean("offline-progress", true);
        int restored = 0;
        
        for (Object[] taskData : allTasks) {
            String recipeId = (String) taskData[0];
            long remainingTicks = ((Number) taskData[1]).longValue();
            long totalTicks = ((Number) taskData[2]).longValue();
            long startedAt = ((Number) taskData[3]).longValue();
            int batchCount = ((Number) taskData[4]).intValue();
            
            long actualRemainingTicks;
            if (offlineProgress) {
                long offlineSeconds = (System.currentTimeMillis() - startedAt) / 1000;
                actualRemainingTicks = remainingTicks - offlineSeconds * 20;
            } else {
                actualRemainingTicks = remainingTicks;
            }
            
            SmeltingRecipe recipe = getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("[ForgeStation] Cannot restore smelting task: recipe '" + recipeId + "' not found (deleted/renamed?). Notifying " + player.getName());
                plugin.getMessageUtil().send(player, "smelt.recipe-removed", "recipe", recipeId);
                continue;
            }
            
            if (offlineProgress && actualRemainingTicks <= 0) {
                giveSmeltingOutputDirect(player, recipe, batchCount);
                plugin.getMessageUtil().send(player, "smelt.complete-while-offline",
                    "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
                restored++;
                continue;
            }
            
            SmeltingTask task = new SmeltingTask(plugin, player, recipe, Math.max(1, actualRemainingTicks), batchCount);
            task.setTotalTicks(totalTicks);
            // REFUND FIX: Task restore từ DB không có snapshot — rebuild từ recipe + batchCount để /fs cancel hoàn trả đúng
            buildAndSetRefundSnapshotForRestoredTask(player, recipe, batchCount, task);
            activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>()).add(task);
            task.initialize();
            restored++;
            plugin.getMessageUtil().send(player, "smelt.restored",
                "item", recipe.getDisplayName(),
                "time", com.phmyhu1710.forgestation.util.TimeUtil.formatTicks(actualRemainingTicks));
        }
        
        if (restored > 0) {
            plugin.getLogger().info("[ForgeStation] Khôi phục " + restored + " smelting task(s) cho " + player.getName());
        }
        return restored > 0;
    }

    /**
     * REFUND FIX: Rebuild refund snapshot cho smelting task được restore từ DB.
     * DB chỉ lưu recipeId + batchCount, không lưu actual items đã bị trừ.
     * Method này tính lại từ recipe definition để /fs cancel hoàn trả đúng.
     */
    private void buildAndSetRefundSnapshotForRestoredTask(Player player, SmeltingRecipe recipe, int batchCount, SmeltingTask task) {
        java.util.List<ItemStack> refundInputItems = new java.util.ArrayList<>();
        java.util.List<ItemStack> refundFuelItems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> refundInputES = new java.util.HashMap<>();
        java.util.Map<String, Integer> refundFuelES = new java.util.HashMap<>();

        // Rebuild input refund
        int totalInput = recipe.getInputAmount() * batchCount;
        if ("EXTRA_STORAGE".equalsIgnoreCase(recipe.getInputType())) {
            refundInputES.put(recipe.getInputMaterial(), totalInput);
        } else if ("VANILLA".equalsIgnoreCase(recipe.getInputType())) {
            try {
                Material mat = Material.valueOf(recipe.getInputMaterial().toUpperCase());
                if (mat != Material.AIR) {
                    int maxStack = mat.getMaxStackSize();
                    int remaining = totalInput;
                    while (remaining > 0) {
                        int give = Math.min(remaining, maxStack);
                        refundInputItems.add(new ItemStack(mat, give));
                        remaining -= give;
                    }
                }
            } catch (IllegalArgumentException ignored) { }
        } else if ("MMOITEMS".equalsIgnoreCase(recipe.getInputType())) {
            com.phmyhu1710.forgestation.hook.ItemHook hook = plugin.getHookManager().getItemHookByPrefix("mmoitems");
            if (hook != null) {
                String typeId = (recipe.getInputMmoitemsType() != null ? recipe.getInputMmoitemsType() : "")
                        + ":" + (recipe.getInputMmoitemsId() != null ? recipe.getInputMmoitemsId() : "");
                ItemStack template = hook.getItem(typeId);
                if (template != null && template.getType() != Material.AIR) {
                    int maxStack = template.getMaxStackSize() > 0 ? template.getMaxStackSize() : 64;
                    int remaining = totalInput;
                    while (remaining > 0) {
                        int give = Math.min(remaining, maxStack);
                        ItemStack stack = template.clone();
                        stack.setAmount(give);
                        refundInputItems.add(stack);
                        remaining -= give;
                    }
                }
            }
        }

        // Rebuild fuel refund
        if (recipe.isFuelRequired()) {
            int totalFuel = recipe.getFuelAmount() * batchCount;
            if ("EXTRA_STORAGE".equalsIgnoreCase(recipe.getFuelType())) {
                refundFuelES.put(recipe.getFuelMaterial(), totalFuel);
            } else if ("VANILLA".equalsIgnoreCase(recipe.getFuelType())) {
                try {
                    Material mat = Material.valueOf(recipe.getFuelMaterial().toUpperCase());
                    if (mat != Material.AIR) {
                        int maxStack = mat.getMaxStackSize();
                        int remaining = totalFuel;
                        while (remaining > 0) {
                            int give = Math.min(remaining, maxStack);
                            refundFuelItems.add(new ItemStack(mat, give));
                            remaining -= give;
                        }
                    }
                } catch (IllegalArgumentException ignored) { }
            } else if ("MMOITEMS".equalsIgnoreCase(recipe.getFuelType())) {
                com.phmyhu1710.forgestation.hook.ItemHook hook = plugin.getHookManager().getItemHookByPrefix("mmoitems");
                if (hook != null) {
                    String typeId = (recipe.getFuelMaterial() != null ? recipe.getFuelMaterial() : "");
                    ItemStack template = hook.getItem(typeId);
                    if (template != null && template.getType() != Material.AIR) {
                        int maxStack = template.getMaxStackSize() > 0 ? template.getMaxStackSize() : 64;
                        int remaining = totalFuel;
                        while (remaining > 0) {
                            int give = Math.min(remaining, maxStack);
                            ItemStack stack = template.clone();
                            stack.setAmount(give);
                            refundFuelItems.add(stack);
                            remaining -= give;
                        }
                    }
                }
            }
        }

        task.setRefundSnapshot(refundInputItems, refundFuelItems, refundInputES, refundFuelES);
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Give output trực tiếp (khi task đã complete offline)
     * BATCH FIX: Nhân output với batchCount
     */
    private void giveSmeltingOutputDirect(Player player, SmeltingRecipe recipe, int batchCount) {
        try {
            int totalAmount = recipe.getOutputAmount() * batchCount;
            
            switch (recipe.getOutputType().toUpperCase()) {
                case "MMOITEMS":
                    ItemStack mmoItem = com.phmyhu1710.forgestation.util.ItemBuilder.buildMMOItem(
                        plugin, recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId(), 1);
                    if (mmoItem != null && mmoItem.getType() != Material.STONE) {
                        int maxStack = mmoItem.getMaxStackSize();
                        int remaining = totalAmount;
                        while (remaining > 0) {
                            int batch = Math.min(remaining, maxStack);
                            ItemStack stack = mmoItem.clone();
                            stack.setAmount(batch);
                            plugin.getOutputRouter().routeOutput(player, stack, com.phmyhu1710.forgestation.hook.ExtraStorageHook.buildMmoItemsKey(recipe.getOutputMmoitemsType(), recipe.getOutputMmoitemsId()));
                            remaining -= batch;
                        }
                    } else {
                        plugin.getLogger().warning("[ForgeStation] Failed to create MMOItem for offline smelting output: " 
                            + recipe.getOutputMmoitemsType() + ":" + recipe.getOutputMmoitemsId());
                    }
                    break;
                case "EXTRA_STORAGE":
                    if (plugin.getExtraStorageHook().isAvailable()) {
                        plugin.getExtraStorageHook().addItems(player, recipe.getOutputMaterial(), totalAmount);
                    }
                    break;
                default: // VANILLA
                    Material mat = Material.valueOf(recipe.getOutputMaterial().toUpperCase());
                    ItemStack output = new ItemStack(mat, totalAmount);
                    plugin.getOutputRouter().routeOutput(player, output, mat.name());
                    break;
            }
            
            plugin.debug("Gave offline smelting output: " + totalAmount + "x " + recipe.getOutputMaterial() + " to " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give smelting output: " + e.getMessage());
        }
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Save pending output khi player offline lúc complete
     * BATCH FIX: Nhân output với batchCount
     */
    public void savePendingOutput(UUID uuid, SmeltingRecipe recipe, int batchCount) {
        var db = getDatabase();
        if (db != null) {
            // BATCH FIX: Tính tổng output = recipe amount * batch count
            int totalAmount = recipe.getOutputAmount() * batchCount;
            db.savePendingSmeltingOutput(uuid, recipe.getId(), 
                recipe.getOutputMaterial(), totalAmount, recipe.getOutputType());
        }
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Helper để lấy database từ PlayerDataManager
     */
    private com.phmyhu1710.forgestation.database.SQLiteDatabase getDatabase() {
        return plugin.getPlayerDataManager().getDatabase();
    }

    /**
     * CRASH PROTECTION: Lưu tất cả active smelting tasks của player ngay vào DB.
     * Gọi khi task mới bắt đầu để đảm bảo không mất nếu server crash trước periodic save.
     */
    private void immediatelySaveTask(UUID uuid) {
        var db = getDatabase();
        if (db == null) return;
        java.util.List<SmeltingTask> tasks = activeTasks.get(uuid);
        if (tasks == null || tasks.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.List<Object[]> entries = new java.util.ArrayList<>();
        for (SmeltingTask t : tasks) {
            if (t == null || t.isCancelled()) continue;
            long total = t.getTotalTicks(), remain = t.getRemainingTicks();
            long startedAt = now - (total - remain) * 50L;
            entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
        }
        if (!entries.isEmpty()) db.saveAllActiveSmeltingTasks(uuid, entries);
    }
}
