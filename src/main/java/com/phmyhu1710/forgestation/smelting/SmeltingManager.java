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
    private final Map<UUID, SmeltingTask> activeTasks = new ConcurrentHashMap<>();

    /** Hàng chờ nung: recipeId + batchCount (chưa consume) */
    private final Map<UUID, java.util.List<PendingSmeltingTask>> smeltingQueue = new ConcurrentHashMap<>();

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
     * ISSUE-005 FIX: Sử dụng Iterator thay vì ArrayList snapshot để giảm GC pressure
     */
    private void startGlobalTimer() {
        globalTimerTaskId = plugin.getScheduler().runTimer(() -> {
            if (activeTasks.isEmpty()) return;
            
            // OPTIMIZED: Direct iteration với Iterator, không tạo ArrayList snapshot
            java.util.Iterator<java.util.Map.Entry<UUID, SmeltingTask>> iterator = activeTasks.entrySet().iterator();
            while (iterator.hasNext()) {
                java.util.Map.Entry<UUID, SmeltingTask> entry = iterator.next();
                SmeltingTask task = entry.getValue();
                
                if (task == null || task.isCancelled()) {
                    iterator.remove();
                    continue;
                }
                
                // Tick task (giảm thời gian, update BossBar)
                task.tick();
                
                // Check completion
                if (task.getRemainingTicks() <= 0) {
                    task.complete();
                }
            }
        }, 1L, 1L); // Every tick for smoother progress
    }
    
    /**
     * PERFORMANCE FIX: Stop global timer (on plugin disable)
     */
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
        
        // 2. Lấy smelting level của player
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        int smeltingLevel = data.getUpgradeLevel("smelting_speed");
        
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

        // Đang có task chạy → thêm vào hàng chờ
        if (activeTasks.containsKey(uuid)) {
            int maxQueue = plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
            java.util.List<PendingSmeltingTask> queue = smeltingQueue.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
            if (queue.size() >= maxQueue) {
                plugin.getMessageUtil().send(player, "smelt.queue-full");
                return false;
            }
            queue.add(new PendingSmeltingTask(recipe.getId(), batchCount));
            plugin.getMessageUtil().send(player, "smelt.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(batchCount),
                "queue", String.valueOf(queue.size()));
            return true;
        }

        // Check permission
        if (!recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
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
        activeTasks.put(uuid, task);

        task.initialize();

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
        SmeltingTask task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.refund(player);
            task.cancel();
            plugin.getMessageUtil().send(player, "smelt.cancelled-refund");
        }
    }

    /**
     * Called when smelting completes. Nếu có hàng chờ thì bắt đầu task tiếp theo.
     */
    public void onSmeltingComplete(UUID uuid) {
        activeTasks.remove(uuid);
        java.util.List<PendingSmeltingTask> queue = smeltingQueue.get(uuid);
        if (queue != null && !queue.isEmpty()) {
            PendingSmeltingTask next = queue.remove(0);
            Player player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                startSmeltFromQueue(player, next.getRecipeId(), next.getBatchCount());
            }
        }
    }

    /**
     * Bắt đầu nung từ hàng chờ (đã pop queue).
     */
    private boolean startSmeltFromQueue(Player player, String recipeId, int batchCount) {
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
        activeTasks.put(player.getUniqueId(), task);
        task.initialize();
        return true;
    }

    public java.util.List<PendingSmeltingTask> getSmeltingQueue(Player player) {
        java.util.List<PendingSmeltingTask> list = smeltingQueue.get(player.getUniqueId());
        return list != null ? new java.util.ArrayList<>(list) : new java.util.ArrayList<>();
    }

    public void cancelSmeltingQueueSlot(Player player, int index) {
        java.util.List<PendingSmeltingTask> queue = smeltingQueue.get(player.getUniqueId());
        if (queue == null || index < 0 || index >= queue.size()) return;
        queue.remove(index);
        plugin.getMessageUtil().send(player, "smelt.queue-slot-cancelled");
    }

    /**
     * Get active smelting task for player
     */
    public SmeltingTask getActiveTask(Player player) {
        return activeTasks.get(player.getUniqueId());
    }

    /**
     * Check if player is currently smelting
     */
    public boolean isSmelting(Player player) {
        return activeTasks.containsKey(player.getUniqueId());
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
        
        for (Map.Entry<UUID, SmeltingTask> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            SmeltingTask task = entry.getValue();
            
            savedTasks.add(new Object[] {
                uuid,
                task.getRecipe().getId(),
                task.getRemainingTime(),
                task.getTotalDuration(),
                task.getBatchCount()
            });
            
            // Cancel task (stop timer, remove bossbar)
            task.cancel();
        }
        
        activeTasks.clear();
        plugin.debug("Saved " + savedTasks.size() + " smelting tasks for reload");
        return savedTasks;
    }
    
    /**
     * RELOAD PERSISTENCE: Restore tasks sau khi reload với recipe references mới
     * @param savedTasks List of [uuid, recipeId, remainingTime, totalDuration, batchCount]
     */
    public void restoreTasksAfterReload(java.util.List<Object[]> savedTasks) {
        if (savedTasks == null || savedTasks.isEmpty()) return;
        
        int restored = 0;
        for (Object[] data : savedTasks) {
            UUID uuid = (UUID) data[0];
            String recipeId = (String) data[1];
            int remainingTime = (int) data[2];
            int totalDuration = (int) data[3];
            int batchCount = (int) data[4];
            
            // Tìm player online
            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                // Player offline, lưu vào database để restore khi họ rejoin
                var db = getDatabase();
                if (db != null) {
                    db.saveActiveSmeltingTask(uuid, recipeId, remainingTime, totalDuration, batchCount);
                }
                continue;
            }
            
            // Tìm recipe mới (sau reload)
            SmeltingRecipe recipe = getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("Cannot restore smelting task: recipe " + recipeId + " not found after reload");
                continue;
            }
            
            // Tạo task mới với recipe reference mới
            SmeltingTask task = new SmeltingTask(plugin, player, recipe, remainingTime, batchCount);
            task.setTotalDuration(totalDuration);
            activeTasks.put(uuid, task);
            task.initialize();
            
            restored++;
            plugin.debug("Restored smelting task for " + player.getName() + ": " + recipeId);
        }
        
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " smelting tasks after reload");
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
        
        int savedCount = activeTasks.size();
        var db = getDatabase();
        
        // Save all active tasks before cancelling
        for (Map.Entry<UUID, SmeltingTask> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            SmeltingTask task = entry.getValue();
            
            // Ghi trực tiếp vào DB (không qua queue vì queue có thể đã dừng)
            // BATCH FIX: Lưu cả batchCount. TICK-SUPPORT: Save ticks
            if (db != null) {
                db.saveActiveSmeltingTask(uuid, task.getRecipe().getId(), 
                    (int)(task.getRemainingTicks()), (int)(task.getTotalTicks()), task.getBatchCount());
            }
            
            task.cancel();
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
        SmeltingTask task = activeTasks.get(uuid);
        
        if (task != null) {
            // Save current progress to database
            // BATCH FIX: Lưu cả batchCount. TICK-SUPPORT: Save ticks
            var db = getDatabase();
            if (db != null) {
                db.saveActiveSmeltingTask(uuid, task.getRecipe().getId(),
                    (int)(task.getRemainingTicks()), (int)(task.getTotalTicks()), task.getBatchCount());
            }
            
            // Cancel the task (timer will stop)
            task.cancel();
            activeTasks.remove(uuid);
            
            plugin.debug("Saved smelting task for " + event.getPlayer().getName() + 
                ": " + task.getRecipe().getId() + " (" + task.getRemainingTime() + "s remaining)");
        }
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
        
        // 2. Restore active task nếu có
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
            
            // Create and give item
            try {
                if ("VANILLA".equals(outputType)) {
                    Material mat = Material.valueOf(outputMaterial.toUpperCase());
                    ItemStack item = new ItemStack(mat, amount);
                    plugin.getOutputRouter().routeOutput(player, item, outputMaterial);
                    totalItems += amount;
                }
                // TODO: Handle MMOITEMS, EXTRA_STORAGE
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deliver pending output " + outputMaterial + " to " + player.getName() + ": " + e.getMessage());
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
     * SMELTING PERSISTENCE FIX: Khôi phục smelting task khi player rejoin
     * BATCH FIX: Khôi phục cả batchCount
     * CONFIG: Hỗ trợ bật/tắt offline progress
     * @return true nếu có task được restore
     */
    private boolean restoreSmeltingTask(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return false;
        
        String[] taskData = db.loadActiveSmeltingTask(uuid);
        if (taskData == null) return false;
        
        String recipeId = taskData[0];
        // TICK-SUPPORT: Assume ticks if loading new data, or seconds if old?
        // Let's assume the DB values are now ticks. 
        // Note: For backward compatibility, if value is small (< 100000) and it was seconds, 
        // translating 30s as 30 ticks is bad. But usually seconds stored.
        // Transition: For now, let's treat stored values as TICKS.
        // User should clear DB or we migrate. Or simpler:
        // We just store as ticks.
        long remainingTicks = Long.parseLong(taskData[1]);
        long totalTicks = Long.parseLong(taskData[2]);
        long startedAt = Long.parseLong(taskData[3]);
        // BATCH FIX: Đọc batchCount từ database
        int batchCount = taskData.length > 4 ? Integer.parseInt(taskData[4]) : 1;
        
        // CONFIG: Check if offline progress is enabled (unified config)
        boolean offlineProgress = plugin.getConfigManager().getMainConfig().getBoolean("offline-progress", true);
        
        long actualRemainingTicks;
        if (offlineProgress) {
            // Tính thời gian offline và trừ đi
            long offlineSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            long offlineTicks = offlineSeconds * 20;
            actualRemainingTicks = remainingTicks - offlineTicks;
        } else {
            // Không tính offline time - giữ nguyên remaining time
            actualRemainingTicks = remainingTicks;
        }
        
        SmeltingRecipe recipe = getRecipe(recipeId);
        if (recipe == null) {
            plugin.getLogger().warning("Cannot restore smelting task: recipe " + recipeId + " not found");
            db.clearActiveSmeltingTask(uuid);
            return false;
        }
        
        // Clear saved task
        db.clearActiveSmeltingTask(uuid);
        
        // If task should have completed while offline (only if offline progress enabled)
        // BATCH FIX: Truyền batchCount để give đúng số lượng
        if (offlineProgress && actualRemainingTicks <= 0) {
            giveSmeltingOutputDirect(player, recipe, batchCount);
            plugin.getMessageUtil().send(player, "smelt.complete-while-offline",
                "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
            return true; // Task was completed offline
        }
        
        // Otherwise restore the task with adjusted time
        // BATCH FIX: Sử dụng constructor có batchCount
        SmeltingTask task = new SmeltingTask(plugin, player, recipe, Math.max(1, actualRemainingTicks), batchCount);
        task.setTotalTicks(totalTicks); // Keep original total for progress display
        activeTasks.put(uuid, task);
        // PERFORMANCE FIX: Chỉ khởi tạo, global timer sẽ tick
        task.initialize();
        
        plugin.getMessageUtil().send(player, "smelt.restored",
            "item", recipe.getDisplayName(),
            "time", com.phmyhu1710.forgestation.util.TimeUtil.formatTicks(actualRemainingTicks));
        
        return true;
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Give output trực tiếp (khi task đã complete offline)
     * BATCH FIX: Nhân output với batchCount
     */
    private void giveSmeltingOutputDirect(Player player, SmeltingRecipe recipe, int batchCount) {
        try {
            // BATCH FIX: Tính tổng output = recipe amount * batch count
            int totalAmount = recipe.getOutputAmount() * batchCount;
            Material mat = Material.valueOf(recipe.getOutputMaterial().toUpperCase());
            ItemStack output = new ItemStack(mat, totalAmount);
            plugin.getOutputRouter().routeOutput(player, output, mat.name());
            
            plugin.debug("Gave offline smelting output: " + totalAmount + "x " + mat.name() + " to " + player.getName());
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
}
