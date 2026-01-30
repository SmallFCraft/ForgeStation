package com.phmyhu1710.forgestation.crafting;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.expression.ExpressionParser;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import com.phmyhu1710.forgestation.util.ItemBuilder;
import com.phmyhu1710.forgestation.util.MessageUtil;
import com.phmyhu1710.forgestation.util.InventoryUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * Handles crafting recipe execution
 * TIMER MODE: Hỗ trợ crafting với timer và BossBar (như Smelting)
 * PERSISTENCE: Lưu/restore tasks khi player quit/join
 */
public class CraftingExecutor implements Listener {

    private final ForgeStationPlugin plugin;
    private final Map<UUID, CraftingTask> activeTasks = new ConcurrentHashMap<>();

    /** Hàng chờ craft: recipeId + batchCount (chưa consume, khi promote mới consume). Cả craft và exchange đều dùng chung. */
    private final Map<UUID, java.util.List<PendingCraftingTask>> craftingQueue = new ConcurrentHashMap<>();

    // Global timer cho crafting (1 hệ thống: craft + exchange đều là CraftingTask)
    private int globalTimerTaskId = -1;

    public static final class PendingCraftingTask {
        private final String recipeId;
        private final int batchCount;
        public PendingCraftingTask(String recipeId, int batchCount) {
            this.recipeId = recipeId;
            this.batchCount = Math.max(1, batchCount);
        }
        public String getRecipeId() { return recipeId; }
        public int getBatchCount() { return batchCount; }
    }

    public CraftingExecutor(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        // Register events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Start global timer (crafting luôn dùng timer như smelting)
        startGlobalTimer();
    }
    
    /**
     * Start global timer để tick tất cả active tasks (crafting + exchange)
     * ISSUE-002 FIX: Sử dụng Iterator thay vì ArrayList snapshot để giảm GC pressure
     */
    private void startGlobalTimer() {
        globalTimerTaskId = plugin.getScheduler().runTimer(() -> {
            // Tick crafting tasks (cả craft và exchange đều là CraftingTask)
            if (!activeTasks.isEmpty()) {
                java.util.Iterator<java.util.Map.Entry<UUID, CraftingTask>> it = activeTasks.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<UUID, CraftingTask> entry = it.next();
                    CraftingTask task = entry.getValue();
                    if (task == null || task.isCancelled()) {
                        it.remove();
                        continue;
                    }
                    task.tick();
                    if (task.getRemainingTicks() <= 0) {
                        task.complete();
                    }
                }
            }
        }, 1L, 1L); // Every tick
    }
    
    /**
     * Stop global timer (on plugin disable)
     */
    public void stopGlobalTimer() {
        if (globalTimerTaskId != -1) {
            plugin.getScheduler().cancelTask(globalTimerTaskId);
            globalTimerTaskId = -1;
        }
    }

    /**
     * Execute a crafting recipe for player
     * Luôn dùng timer mode (như smelting)
     */
    public boolean executeCraft(Player player, Recipe recipe) {
        return executeCraft(player, recipe, 1);
    }
    
    /**
     * Execute crafting with batch support
     * Luôn dùng timer mode với BossBar (giống smelting)
     */
    public boolean executeCraft(Player player, Recipe recipe, int batchCount) {
        // Check permission
        if (!recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
            plugin.getMessageUtil().send(player, "no-permission");
            return false;
        }
        
        batchCount = Math.max(1, batchCount);

        UUID uuid = player.getUniqueId();

        // Đang có task chạy → thêm vào hàng chờ (nếu còn slot). Một hệ thống craft (cả recipe result và rewards).
        if (activeTasks.containsKey(uuid)) {
            int maxQueue = plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
            java.util.List<PendingCraftingTask> queue = craftingQueue.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
            if (queue.size() >= maxQueue) {
                plugin.getMessageUtil().send(player, "craft.queue-full");
                return false;
            }
            queue.add(new PendingCraftingTask(recipe.getId(), batchCount));
            plugin.getMessageUtil().send(player, "craft.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(batchCount),
                "queue", String.valueOf(queue.size()));
            return true;
        }

        // Check if has ingredients for batch
        if (!hasIngredientsForBatch(player, recipe, batchCount)) {
            plugin.getMessageUtil().send(player, "craft.failed");
            return false;
        }

        // Feature: Inventory Space Check
        com.phmyhu1710.forgestation.crafting.Recipe.RecipeResult result = recipe.getResult();
        if (result != null && "VANILLA".equalsIgnoreCase(result.getType())) {
            int totalAmount = result.getAmount() * batchCount;
            Material mat = Material.valueOf(result.getMaterial().toUpperCase());
            ItemStack outputItem = new ItemStack(mat, totalAmount);
            
            if (!com.phmyhu1710.forgestation.util.InventoryUtil.hasSpace(player, java.util.Collections.singletonList(outputItem))) {
                plugin.getMessageUtil().send(player, "craft.no-space");
                return false;
            }
        } else if (result != null && "MMOITEMS".equalsIgnoreCase(result.getType())) {
             // For MMOItems we check logic slightly differently or skip if too complex
             // Ideally we build 1 mmoitem and check space for totalAmount
             ItemStack mmoItem = ItemBuilder.buildMMOItem(plugin, result.getMmoitemsType(), result.getMmoitemsId(), 1);
             if (mmoItem != null && mmoItem.getType() != Material.STONE) {
                 int totalAmount = result.getAmount() * batchCount;
                 mmoItem.setAmount(totalAmount);
                 if (!com.phmyhu1710.forgestation.util.InventoryUtil.hasSpace(player, java.util.Collections.singletonList(mmoItem))) {
                    plugin.getMessageUtil().send(player, "craft.no-space");
                    return false;
                 }
             }
        }


        // Check cost for batch
        Map<String, Double> vars = getVariables(player);
        double vaultCost = ExpressionParser.evaluate(recipe.getVaultCostExpr(), vars) * batchCount;
        int ppCost = ExpressionParser.evaluateInt(recipe.getPlayerPointsCostExpr(), vars) * batchCount;
        double coinEngineCost = ExpressionParser.evaluate(recipe.getCoinEngineCostExpr(), vars) * batchCount;

        if (!plugin.getEconomyManager().canAfford(player, vaultCost, ppCost, 
                recipe.getCoinEngineCurrency(), coinEngineCost)) {
            plugin.getMessageUtil().send(player, "craft.no-money", 
                "currency", com.phmyhu1710.forgestation.util.MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins")),
                "amount", com.phmyhu1710.forgestation.util.MessageUtil.formatNumber(vaultCost));
            return false;
        }

        // === TRANSACTION START: Thu thập snapshot hoàn trả trước khi remove (để hủy thì hoàn tác) ===
        java.util.List<ItemStack> refundItems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> refundExtraStorage = new java.util.HashMap<>();
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int toRemove = (ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount()) * batchCount;
            if ("EXTRA_STORAGE".equalsIgnoreCase(ing.getType())) {
                String esKey = ing.getStorageId() != null && !ing.getStorageId().isEmpty() ? ing.getStorageId() : ing.getMaterial();
                refundExtraStorage.merge(esKey, toRemove, Integer::sum);
            }
            refundItems.addAll(com.phmyhu1710.forgestation.util.InventoryUtil.removeItemsAndCollect(plugin, player, ing, toRemove));
        }

        // Withdraw money
        plugin.getEconomyManager().withdrawAll(player, vaultCost, ppCost,
            recipe.getCoinEngineCurrency(), coinEngineCost);

        // BATCH CRAFTING (đồng bộ smelting): Thời gian = duration per item * batch count
        long durationPerItemTicks = plugin.getRecipeManager().getDurationTicks(player, recipe);
        if (durationPerItemTicks <= 0) durationPerItemTicks = 100;
        long totalDurationTicks = durationPerItemTicks * batchCount;

        CraftingTask task = new CraftingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundItems, refundExtraStorage, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
        activeTasks.put(player.getUniqueId(), task);
        task.initialize();

        return true;
    }

    /**
     * Thực hiện recipe dạng exchange (input → rewards). Dùng chung CraftingTask, 1 hệ thống với craft.
     */
    public boolean executeExchange(Player player, Recipe recipe, int requestedAmount) {
        if (requestedAmount <= 0) requestedAmount = 1;
        UUID uuid = player.getUniqueId();

        plugin.debug("=== EXCHANGE EXECUTION ===");
        plugin.debug("Player: " + player.getName());
        plugin.debug("Recipe: " + recipe.getId());
        plugin.debug("Requested amount: " + requestedAmount);

        // Check permission
        if (!recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
            plugin.getMessageUtil().send(player, "no-permission");
            return false;
        }

        // Get input ingredient
        if (recipe.getIngredients().isEmpty()) return false;
        Recipe.Ingredient input = recipe.getIngredients().get(0);
        int baseAmountPerExchange = input.getBaseAmount() > 0 ? input.getBaseAmount() : input.getAmount();

        // Count player's items
        int playerHas = countItems(player, input);
        plugin.debug("Player has: " + playerHas + " items");
        plugin.debug("Base amount per exchange: " + baseAmountPerExchange);

        // Đang có task chạy → thêm vào hàng chờ (1 hệ thống craft)
        if (activeTasks.containsKey(uuid)) {
            if (playerHas < baseAmountPerExchange) {
                plugin.getMessageUtil().send(player, "exchange.not-enough",
                    "required", String.valueOf(baseAmountPerExchange));
                return false;
            }
            int maxExchanges = playerHas / baseAmountPerExchange;
            int requestedExchanges = requestedAmount / baseAmountPerExchange;
            int actualExchanges = Math.min(requestedExchanges, maxExchanges);
            if (actualExchanges <= 0) {
                plugin.getMessageUtil().send(player, "exchange.not-enough",
                    "required", String.valueOf(baseAmountPerExchange));
                return false;
            }
            int maxQueue = plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
            java.util.List<PendingCraftingTask> queue = craftingQueue.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
            if (queue.size() >= maxQueue) {
                plugin.getMessageUtil().send(player, "craft.queue-full");
                return false;
            }
            queue.add(new PendingCraftingTask(recipe.getId(), actualExchanges));
            plugin.getMessageUtil().send(player, "craft.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(actualExchanges),
                "queue", String.valueOf(queue.size()));
            return true;
        }

        if (playerHas < baseAmountPerExchange) {
            plugin.getMessageUtil().send(player, "exchange.not-enough",
                "required", String.valueOf(baseAmountPerExchange));
            return false;
        }

        // Calculate max possible exchanges
        int maxExchanges = playerHas / baseAmountPerExchange;
        plugin.debug("Max possible exchanges: " + maxExchanges);

        // Validate requested amount
        if (requestedAmount < baseAmountPerExchange) {
            plugin.getMessageUtil().send(player, "exchange.not-enough",
                "required", String.valueOf(baseAmountPerExchange));
            return false;
        }

        // Calculate requested exchanges (round down)
        int requestedExchanges = requestedAmount / baseAmountPerExchange;
        plugin.debug("Requested exchanges: " + requestedExchanges);

        // Cap at max possible
        int actualExchanges = Math.min(requestedExchanges, maxExchanges);
        plugin.debug("Actual exchanges: " + actualExchanges);

        if (actualExchanges <= 0) {
            plugin.getMessageUtil().send(player, "exchange.not-enough",
                "required", String.valueOf(baseAmountPerExchange));
            return false;
        }

        // Calculate items to use
        int itemsUsed = actualExchanges * baseAmountPerExchange;
        int remainder = playerHas - itemsUsed;
        
        // Feature: Inventory Space Check for Exchange
        java.util.List<ItemStack> potentialRewards = new java.util.ArrayList<>();
        double multiplier = recipe.usesRankMultiplier() 
            ? plugin.getRecipeManager().getRankMultiplier(player, recipe) 
            : 1.0;
        
        plugin.debug("Rank multiplier: " + multiplier);
            
        for (Recipe.Reward reward : recipe.getRewards()) {
             // Only check physical items
             if ("VANILLA".equalsIgnoreCase(reward.getType()) || "MMOITEMS".equalsIgnoreCase(reward.getType())) { // Assuming Rewards support these types in future, currently mainly VAULT/POINTS/COMMAND/EXTRA_STORAGE
                  // Exchange rewards usually command/currency/extra_storage. 
                  // If reward has material (like custom item), we should check.
                  // Current Reward class structure supports custom types?
                  // Looking at Reward class: type, baseAmount, currency, command, material (for EXTRA_STORAGE)
                  // It seems Exchange is mostly for currency/points/extra_storage which don't need inv space.
                  // BUT if there is a VANILLA reward type (future proofing):
             }
        }
        
        // If refunds produce items, check space for remainder? 
        // Remainder is items NOT used relative to playerHas. They are already in inventory.
        // So no need to check space for remainder.

        
        plugin.debug("Items to use: " + itemsUsed);
        plugin.debug("Remainder: " + remainder);

        // Thu thập refund (để hủy thì hoàn trả) rồi consume input — dùng chung CraftingTask
        java.util.List<ItemStack> refundItems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> refundExtraStorage = new java.util.HashMap<>();
        if ("EXTRA_STORAGE".equalsIgnoreCase(input.getType())) {
            String esKey = input.getStorageId() != null && !input.getStorageId().isEmpty() ? input.getStorageId() : input.getMaterial();
            refundExtraStorage.put(esKey, itemsUsed);
        }
        refundItems.addAll(com.phmyhu1710.forgestation.util.InventoryUtil.removeItemsAndCollect(plugin, player, input, itemsUsed));

        long durationPerExchangeTicks = plugin.getRecipeManager().getDurationTicks(player, recipe);
        plugin.debug("Exchange duration per exchange: " + (durationPerExchangeTicks / 20) + "s, exchanges: " + actualExchanges);

        if (durationPerExchangeTicks <= 0) {
            giveExchangeRewardsInstant(player, recipe, actualExchanges, multiplier, remainder);
            return true;
        }

        long totalDurationTicks = durationPerExchangeTicks * actualExchanges;
        CraftingTask task = new CraftingTask(plugin, player, recipe, totalDurationTicks, actualExchanges);
        task.setExchangeMode(multiplier, remainder);
        task.setRefundSnapshot(refundItems, refundExtraStorage, 0, 0, "", 0);
        activeTasks.put(uuid, task);
        task.initialize();

        plugin.debug("Craft (exchange) task started with total duration: " + (totalDurationTicks / 20) + "s");
        return true;
    }
    
    /**
     * Give exchange rewards instantly (when duration = 0)
     */
    private void giveExchangeRewardsInstant(Player player, Recipe recipe, int actualExchanges, double multiplier, int remainder) {
        for (Recipe.Reward reward : recipe.getRewards()) {
            int baseReward = reward.getBaseAmount() * actualExchanges;
            int finalReward = (int) Math.floor(baseReward * multiplier);
            
            plugin.debug("Reward type: " + reward.getType() + " x" + finalReward);

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
                        }
                    }
                    break;
            }
        }
        
        if (remainder > 0) {
            plugin.getMessageUtil().send(player, "exchange.refund",
                "amount", String.valueOf(remainder));
        }
        
        plugin.debug("=== EXCHANGE COMPLETE (instant) ===");
    }
    
    /**
     * Save pending exchange output for offline player (recipe dạng rewards)
     */
    public void savePendingExchangeOutput(UUID uuid, Recipe recipe, int actualExchanges, double multiplier) {
        // TODO: Implement persistence for exchange outputs
        plugin.debug("Saving pending exchange output for " + uuid + ": " + recipe.getId() + " x" + actualExchanges);
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // TIMER MODE: Task management
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Cancel crafting task for player và hoàn trả nguyên liệu + tiền
     */
    public void cancelCrafting(Player player) {
        CraftingTask task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.refund(player);
            task.cancel();
            plugin.getMessageUtil().send(player, "craft.cancelled-refund");
        }
    }
    
    /**
     * Called when crafting completes. Nếu có hàng chờ thì bắt đầu task tiếp theo.
     */
    public void onCraftingComplete(UUID uuid) {
        activeTasks.remove(uuid);
        java.util.List<PendingCraftingTask> queue = craftingQueue.get(uuid);
        if (queue != null && !queue.isEmpty()) {
            PendingCraftingTask next = queue.remove(0);
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                startCraftFromQueue(player, next.getRecipeId(), next.getBatchCount());
            }
        }
    }

    /**
     * Bắt đầu craft/exchange từ hàng chờ (đã pop queue): kiểm tra nguyên liệu/tiền rồi consume + start.
     * Nếu recipe là exchange (rewards) thì gọi executeExchange; ngược lại tạo CraftingTask.
     */
    private boolean startCraftFromQueue(org.bukkit.entity.Player player, String recipeId, int batchCount) {
        Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
        if (recipe == null) return false;
        if (recipe.isExchangeRecipe() && !recipe.getIngredients().isEmpty()) {
            Recipe.Ingredient input = recipe.getIngredients().get(0);
            int baseAmount = input.getBaseAmount() > 0 ? input.getBaseAmount() : input.getAmount();
            int requestedAmount = baseAmount * batchCount;
            return executeExchange(player, recipe, requestedAmount);
        }
        if (!hasIngredientsForBatch(player, recipe, batchCount)) {
            plugin.getMessageUtil().send(player, "craft.failed");
            return false;
        }
        Map<String, Double> vars = getVariables(player);
        double vaultCost = ExpressionParser.evaluate(recipe.getVaultCostExpr(), vars) * batchCount;
        int ppCost = ExpressionParser.evaluateInt(recipe.getPlayerPointsCostExpr(), vars) * batchCount;
        double coinEngineCost = ExpressionParser.evaluate(recipe.getCoinEngineCostExpr(), vars) * batchCount;
        if (!plugin.getEconomyManager().canAfford(player, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost)) {
            plugin.getMessageUtil().send(player, "craft.no-money",
                "currency", com.phmyhu1710.forgestation.util.MessageUtil.colorize(plugin.getConfigManager().getMainConfig().getString("currency.vault", "Coins")),
                "amount", com.phmyhu1710.forgestation.util.MessageUtil.formatNumber(vaultCost));
            return false;
        }
        java.util.List<ItemStack> refundItems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> refundExtraStorage = new java.util.HashMap<>();
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int toRemove = (ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount()) * batchCount;
            if ("EXTRA_STORAGE".equalsIgnoreCase(ing.getType())) {
                String esKey = ing.getStorageId() != null && !ing.getStorageId().isEmpty() ? ing.getStorageId() : ing.getMaterial();
                refundExtraStorage.merge(esKey, toRemove, Integer::sum);
            }
            refundItems.addAll(com.phmyhu1710.forgestation.util.InventoryUtil.removeItemsAndCollect(plugin, player, ing, toRemove));
        }
        plugin.getEconomyManager().withdrawAll(player, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
        long durationPerItemTicks = plugin.getRecipeManager().getDurationTicks(player, recipe);
        if (durationPerItemTicks <= 0) durationPerItemTicks = 100;
        long totalDurationTicks = durationPerItemTicks * batchCount;
        CraftingTask task = new CraftingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundItems, refundExtraStorage, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
        activeTasks.put(player.getUniqueId(), task);
        task.initialize();
        return true;
    }

    /**
     * Số slot hàng chờ đã mở (từ upgrade queue_slots).
     */
    public int getQueueSlotsUnlocked(org.bukkit.entity.Player player) {
        return plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
    }

    /**
     * Danh sách task trong hàng chờ (để hiển thị GUI). Index = vị trí slot.
     */
    public java.util.List<PendingCraftingTask> getCraftingQueue(org.bukkit.entity.Player player) {
        java.util.List<PendingCraftingTask> list = craftingQueue.get(player.getUniqueId());
        return list != null ? new java.util.ArrayList<>(list) : new java.util.ArrayList<>();
    }

    /**
     * Hủy 1 slot trong hàng chờ (index 0-based).
     */
    public void cancelCraftingQueueSlot(org.bukkit.entity.Player player, int index) {
        java.util.List<PendingCraftingTask> queue = craftingQueue.get(player.getUniqueId());
        if (queue == null || index < 0 || index >= queue.size()) return;
        queue.remove(index);
        plugin.getMessageUtil().send(player, "craft.queue-slot-cancelled");
    }

    /**
     * Get active crafting task for player
     */
    public CraftingTask getActiveTask(Player player) {
        return activeTasks.get(player.getUniqueId());
    }
    
    /**
     * Check if player is currently crafting
     */
    public boolean isCrafting(Player player) {
        return activeTasks.containsKey(player.getUniqueId());
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RELOAD PERSISTENCE: Save/restore tasks khi reload plugin
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * RELOAD PERSISTENCE: Lưu tất cả active tasks để restore sau reload
     * Không cancel tasks ngay, chỉ lưu data và trả về list
     * @return List of [uuid, recipeId, remainingTime, totalDuration, batchCount]
     */
    public java.util.List<Object[]> saveAllTasksForReload() {
        java.util.List<Object[]> savedTasks = new java.util.ArrayList<>();
        
        for (Map.Entry<UUID, CraftingTask> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            CraftingTask task = entry.getValue();
            
            savedTasks.add(new Object[] {
                uuid,
                task.getRecipe().getId(),
                (int) task.getRemainingTicks(),
                (int) task.getTotalTicks(),
                task.getBatchCount()
            });
            
            // Cancel task (stop timer, remove bossbar)
            task.cancel();
        }
        
        activeTasks.clear();
        plugin.debug("Saved " + savedTasks.size() + " crafting tasks for reload");
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
            // TICK-SUPPORT: Assume data stored as ticks if new, but here it's from memory so we know it's ticks
            long remainingTicks = ((Number) data[2]).longValue();
            long totalTicks = ((Number) data[3]).longValue();
            int batchCount = (int) data[4];
            
            // Tìm player online
            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                // Player offline, lưu vào database để restore khi họ rejoin
                var db = getDatabase();
                if (db != null) {
                    db.saveActiveCraftingTask(uuid, recipeId, (int) remainingTicks, (int) totalTicks, batchCount);
                }
                continue;
            }
            
            // Tìm recipe mới (sau reload)
            Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("Cannot restore crafting task: recipe " + recipeId + " not found after reload");
                continue;
            }
            
            // Tạo task mới với recipe reference mới
            CraftingTask task = new CraftingTask(plugin, player, recipe, remainingTicks, batchCount);
            task.setTotalTicks(totalTicks);
            activeTasks.put(uuid, task);
            task.initialize();
            
            restored++;
            plugin.debug("Restored crafting task for " + player.getName() + ": " + recipeId);
        }
        
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " crafting tasks after reload");
        }
    }
    
    /**
     * Cancel all tasks (on plugin disable)
     */
    public void cancelAllTasks() {
        stopGlobalTimer();
        int savedCount = activeTasks.size();
        var db = getDatabase();
        for (Map.Entry<UUID, CraftingTask> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            CraftingTask task = entry.getValue();
            if (db != null) {
                db.saveActiveCraftingTask(uuid, task.getRecipe().getId(),
                    (int) task.getRemainingTicks(), (int) task.getTotalTicks(), task.getBatchCount());
            }
            task.cancel();
        }
        activeTasks.clear();
        if (savedCount > 0) {
            plugin.getLogger().info("Saved " + savedCount + " crafting tasks");
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE: Event handlers
    // ═══════════════════════════════════════════════════════════════════════
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        
        // Handle crafting task
        CraftingTask craftTask = activeTasks.get(uuid);
        if (craftTask != null) {
            var db = getDatabase();
            if (db != null) {
                db.saveActiveCraftingTask(uuid, craftTask.getRecipe().getId(),
                    (int) craftTask.getRemainingTicks(), (int) craftTask.getTotalTicks(), craftTask.getBatchCount());
            }
            
            craftTask.cancel();
            activeTasks.remove(uuid);
            
            plugin.debug("Saved crafting task for " + event.getPlayer().getName() + 
                ": " + craftTask.getRecipe().getId() + " (" + craftTask.getRemainingTicks() + " ticks remaining)");
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        plugin.getScheduler().runLater(() -> {
            if (!player.isOnline()) return;
            restoreTaskForPlayer(player);
        }, 20L);
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
        
        // 1. Deliver pending outputs
        deliverPendingOutputs(player);
        
        // 2. Restore active task
        return restoreCraftingTask(player);
    }
    
    /**
     * Deliver pending crafting outputs to player
     */
    private void deliverPendingOutputs(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return;
        
        List<String[]> pendingOutputs = db.loadPendingCraftingOutputs(uuid);
        if (pendingOutputs.isEmpty()) return;
        
        int totalItems = 0;
        for (String[] output : pendingOutputs) {
            String recipeId = output[0];
            String outputMaterial = output[1];
            int amount = Integer.parseInt(output[2]);
            String outputType = output[3];
            
            try {
                // TRY TO USE RECIPE DEFINITION FIRST (Correct way for Custom Items)
                Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
                boolean handled = false;

                if (recipe != null && recipe.getResult() != null) {
                    Recipe.RecipeResult result = recipe.getResult();
                    // Double check if this pending output matches the recipe result context
                    // (It should, unless config changed while offline. Even then, recipe is safer)
                    
                    if (result.getType().equalsIgnoreCase(outputType)) {
                         // Build item using ItemBuilder (Handles MMOItems, etc.)
                        ItemStack itemTemplate = ItemBuilder.buildResult(plugin, player, result);
                        
                        if (itemTemplate != null) {
                            // Valid item stack result
                             if (itemTemplate.getType() != Material.STONE || outputType.equalsIgnoreCase("VANILLA")) {
                                int maxStack = itemTemplate.getMaxStackSize();
                                int remaining = amount;
                                
                                while (remaining > 0) {
                                    int batch = Math.min(remaining, maxStack);
                                    ItemStack stack = itemTemplate.clone();
                                    stack.setAmount(batch);
                                    plugin.getOutputRouter().routeOutput(player, stack, result.getType() + "_" + result.getMaterial());
                                    remaining -= batch;
                                }
                                totalItems += amount;
                                handled = true;
                             }
                        } else if ("EXTRA_STORAGE".equalsIgnoreCase(outputType)) {
                             // Handle ExtraStorage special case
                             plugin.getExtraStorageHook().addItems(player, result.getStorageId(), amount);
                             totalItems += amount;
                             handled = true;
                        }
                    }
                }
                
                // FALLBACK: If recipe missing or simple vanilla type recorded in DB
                if (!handled && "VANILLA".equals(outputType)) {
                    Material mat = Material.valueOf(outputMaterial.toUpperCase());
                    ItemStack item = new ItemStack(mat, amount);
                    plugin.getOutputRouter().routeOutput(player, item, outputMaterial);
                    totalItems += amount;
                }
                
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deliver pending crafting output for recipe " + recipeId + ": " + e.getMessage());
            }
        }
        
        db.clearPendingCraftingOutputs(uuid);
        
        if (totalItems > 0) {
            plugin.getMessageUtil().send(player, "craft.pending-delivered", 
                "count", String.valueOf(totalItems));
        }
    }
    
    /**
     * Restore crafting task when player rejoins
     * @return true nếu có task được restore
     */
    private boolean restoreCraftingTask(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return false;
        
        String[] taskData = db.loadActiveCraftingTask(uuid);
        if (taskData == null) return false;
        
        String recipeId = taskData[0];
        // TICK-SUPPORT: Assume DB stores ticks now
        long remainingTicks = Long.parseLong(taskData[1]);
        long totalTicks = Long.parseLong(taskData[2]);
        long startedAt = Long.parseLong(taskData[3]);
        int batchCount = taskData.length > 4 ? Integer.parseInt(taskData[4]) : 1;
        
        // Check if offline progress is enabled
        boolean offlineProgress = plugin.getConfigManager().getMainConfig().getBoolean("offline-progress", true);
        
        long actualRemainingTicks;
        if (offlineProgress) {
            long offlineSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            long offlineTicks = offlineSeconds * 20;
            actualRemainingTicks = remainingTicks - offlineTicks;
        } else {
            actualRemainingTicks = remainingTicks;
        }
        
        Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
        if (recipe == null) {
            plugin.getLogger().warning("Cannot restore crafting task: recipe " + recipeId + " not found");
            db.clearActiveCraftingTask(uuid);
            return false;
        }
        
        db.clearActiveCraftingTask(uuid);
        
        // If task completed while offline
        if (offlineProgress && actualRemainingTicks <= 0) {
            giveCraftingOutputDirect(player, recipe, batchCount);
            plugin.getMessageUtil().send(player, "craft.complete-while-offline",
                "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
            return true;
        }
        
        // Restore the task
        CraftingTask task = new CraftingTask(plugin, player, recipe, Math.max(1, actualRemainingTicks), batchCount);
        task.setTotalTicks(totalTicks);
        activeTasks.put(uuid, task);
        task.initialize();
        
        plugin.getMessageUtil().send(player, "craft.restored",
            "item", recipe.getDisplayName(),
            "time", com.phmyhu1710.forgestation.util.TimeUtil.formatTicks(actualRemainingTicks));
        
        return true;
    }
    
    /**
     * Give crafting output directly (when task completed offline)
     */
    private void giveCraftingOutputDirect(Player player, Recipe recipe, int batchCount) {
        try {
            Recipe.RecipeResult result = recipe.getResult();
            if (result == null) return;
            
            int totalAmount = result.getAmount() * batchCount;

            // USE ITEMBUILDER for correct item generation (MMOItems, etc.)
            ItemStack itemTemplate = ItemBuilder.buildResult(plugin, player, result);
            
            if (itemTemplate != null) {
                // It's a physical item (Vanilla or MMOItem)
                int maxStack = itemTemplate.getMaxStackSize();
                int remaining = totalAmount;
                
                String logName = result.getType() + ":" + result.getMaterial();
                if ("MMOITEMS".equalsIgnoreCase(result.getType())) {
                    logName = "MMOITEMS:" + result.getMmoitemsType() + ":" + result.getMmoitemsId();
                }

                while (remaining > 0) {
                    int batch = Math.min(remaining, maxStack);
                    ItemStack stack = itemTemplate.clone();
                    stack.setAmount(batch);
                    plugin.getOutputRouter().routeOutput(player, stack, logName);
                    remaining -= batch;
                }
                plugin.debug("Gave offline crafting output: " + totalAmount + "x " + logName + " to " + player.getName());

            } else if ("EXTRA_STORAGE".equalsIgnoreCase(result.getType())) {
                // Special case for non-item rewards like ExtraStorage
                plugin.getExtraStorageHook().addItems(player, result.getStorageId(), totalAmount);
                plugin.debug("Gave offline crafting output: " + totalAmount + "x ExtraStorage:" + result.getStorageId() + " to " + player.getName());
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give crafting output: " + e.getMessage());
        }
    }
    
    /**
     * Save pending output when player offline during completion
     */
    public void savePendingOutput(UUID uuid, Recipe recipe, int batchCount) {
        var db = getDatabase();
        if (db != null && recipe.getResult() != null) {
            Recipe.RecipeResult result = recipe.getResult();
            int totalAmount = result.getAmount() * batchCount;
            db.savePendingCraftingOutput(uuid, recipe.getId(), 
                result.getMaterial(), totalAmount, result.getType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════

    private boolean hasIngredients(Player player, Recipe recipe) {
        return hasIngredientsForBatch(player, recipe, 1);
    }
    
    private boolean hasIngredientsForBatch(Player player, Recipe recipe, int batchCount) {
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int required = (ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount()) * batchCount;
            int current = InventoryUtil.countItems(plugin, player, ing);
            if (current < required) {
                int missing = required - current;
                String matName = ing.getMaterial();
                if ("MMOITEMS".equalsIgnoreCase(ing.getType())) {
                    matName = ing.getMmoitemsType() + ":" + ing.getMmoitemsId();
                }
                
                plugin.getMessageUtil().send(player, "craft.missing-material", 
                    "amount", String.valueOf(missing),
                    "material", matName);
                return false;
            }
        }
        return true;
    }

    private int countItems(Player player, Recipe.Ingredient ing) {
        return InventoryUtil.countItems(plugin, player, ing);
    }

    private void removeIngredients(Player player, Recipe recipe) {
        removeIngredientsForBatch(player, recipe, 1);
    }
    
    private void removeIngredientsForBatch(Player player, Recipe recipe, int batchCount) {
        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int toRemove = (ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount()) * batchCount;
            InventoryUtil.removeItems(plugin, player, ing, toRemove);
        }
    }

    private void removeItems(Player player, Recipe.Ingredient ing, int amount) {
        InventoryUtil.removeItems(plugin, player, ing, amount);
    }

    private void giveItem(Player player, ItemStack item) {
        String storageId = item.getType().name();
        plugin.getOutputRouter().routeOutput(player, item, storageId);
    }

    private Map<String, Double> getVariables(Player player) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        return ExpressionParser.createVariables(
            data.getUpgradeLevel("crafting_speed"),
            data.getUpgradeLevel("smelting_speed"),
            0
        );
    }
    
    private com.phmyhu1710.forgestation.database.SQLiteDatabase getDatabase() {
        return plugin.getPlayerDataManager().getDatabase();
    }
}
