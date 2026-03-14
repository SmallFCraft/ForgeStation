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
    /** Mỗi player có List tasks chạy song song. Size <= getMaxParallelTasks(player). */
    private final Map<UUID, java.util.List<CraftingTask>> activeTasks = new ConcurrentHashMap<>();

    /** Hàng chờ thống nhất (craft + smelt) — lấy từ TaskQueueManager */

    // Global timer cho crafting (1 hệ thống: craft + exchange đều là CraftingTask)
    private int globalTimerTaskId = -1;

    /** @deprecated Dùng TaskQueueManager.PendingTask. Giữ để tương thích API. */
    @Deprecated
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
     */
    private void startGlobalTimer() {
        globalTimerTaskId = plugin.getScheduler().runTimer(() -> {
            if (activeTasks.isEmpty()) return;
            java.util.List<CraftingTask> toComplete = new java.util.ArrayList<>();
            for (java.util.Map.Entry<UUID, java.util.List<CraftingTask>> entry : new java.util.ArrayList<>(activeTasks.entrySet())) {
                java.util.List<CraftingTask> list = entry.getValue();
                if (list == null) continue;
                java.util.Iterator<CraftingTask> it = list.iterator();
                while (it.hasNext()) {
                    CraftingTask task = it.next();
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
            for (CraftingTask task : toComplete) task.complete();
        }, 1L, 1L);
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
        // Check permission (OP bypass)
        if (!player.isOp() && !recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
            plugin.getMessageUtil().send(player, "no-permission");
            return false;
        }
        
        batchCount = Math.max(1, batchCount);

        UUID uuid = player.getUniqueId();
        int maxParallel = plugin.getUpgradeManager().getMaxParallelTasks(player);
        java.util.List<CraftingTask> list = activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());

        if (list.size() >= maxParallel) {
            if (!plugin.getTaskQueueManager().addToQueue(player, com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.CRAFT, recipe.getId(), batchCount)) {
                plugin.getMessageUtil().send(player, "craft.queue-full");
                return false;
            }
            plugin.getMessageUtil().send(player, "craft.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(batchCount),
                "queue", String.valueOf(plugin.getTaskQueueManager().getQueueSize(player)));
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
        // duration: 0 = hoàn thành ngay (1 tick), không ép thành 100 ticks
        long durationPerItemTicks = plugin.getRecipeManager().getDurationTicks(player, recipe);
        long totalDurationTicks = durationPerItemTicks * batchCount;

        CraftingTask task = new CraftingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundItems, refundExtraStorage, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
        list.add(task);
        task.initialize();

        // CRASH PROTECTION: Save task ngay vào DB để không mất nếu server crash trước periodic save
        immediatelySaveTask(player.getUniqueId());

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

        // Check permission (OP bypass)
        if (!player.isOp() && !recipe.getPermission().isEmpty() && !player.hasPermission(recipe.getPermission())) {
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

        int maxParallelEx = plugin.getUpgradeManager().getMaxParallelTasks(player);
        java.util.List<CraftingTask> listEx = activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
        if (listEx.size() >= maxParallelEx) {
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
            if (!plugin.getTaskQueueManager().addToQueue(player, com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.CRAFT, recipe.getId(), actualExchanges)) {
                plugin.getMessageUtil().send(player, "craft.queue-full");
                return false;
            }
            plugin.getMessageUtil().send(player, "craft.added-to-queue",
                "item", recipe.getDisplayName(),
                "count", String.valueOf(actualExchanges),
                "queue", String.valueOf(plugin.getTaskQueueManager().getQueueSize(player)));
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
        listEx.add(task);
        task.initialize();

        // CRASH PROTECTION: Save task ngay vào DB
        immediatelySaveTask(player.getUniqueId());

        plugin.debug("Craft (exchange) task started with total duration: " + (totalDurationTicks / 20) + "s");
        return true;
    }
    
    /**
     * Give exchange rewards instantly (when duration = 0)
     * Áp dụng chance: roll per exchange, chỉ give theo số successes
     */
    private void giveExchangeRewardsInstant(Player player, Recipe recipe, int actualExchanges, double multiplier, int remainder) {
        double effectiveChance = plugin.getRecipeManager().getEffectiveSuccessChance(player, recipe);
        int successes = rollSuccesses(actualExchanges, effectiveChance);
        if (successes <= 0) {
            plugin.getMessageUtil().send(player, "craft.failed-chance",
                "item", recipe.getDisplayName(),
                "chance", String.format("%.1f", effectiveChance));
            if (remainder > 0) {
                plugin.getMessageUtil().send(player, "exchange.refund", "amount", String.valueOf(remainder));
            }
            return;
        }
        for (Recipe.Reward reward : recipe.getRewards()) {
            int baseReward = reward.getBaseAmount() * successes;
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
                    com.phmyhu1710.forgestation.util.CommandUtil.safeDispatchConsole(cmd, "exchange:instant:" + recipe.getId());
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
        if (successes < actualExchanges) {
            plugin.getMessageUtil().send(player, "craft.partial-success",
                "success", String.valueOf(successes), "total", String.valueOf(actualExchanges), "item", recipe.getDisplayName());
        }
        plugin.debug("=== EXCHANGE COMPLETE (instant) ===");
    }
    
    private static final java.util.Random RANDOM = new java.util.Random();
    private int rollSuccesses(int batchCount, double chancePercent) {
        if (chancePercent >= 100.0) return batchCount;
        if (chancePercent <= 0.0) return 0;
        int successes = 0;
        for (int i = 0; i < batchCount; i++) {
            if (RANDOM.nextDouble() * 100 < chancePercent) successes++;
        }
        return successes;
    }
    
    /**
     * Save pending exchange output for offline player (recipe dạng rewards)
     */
    public void savePendingExchangeOutput(UUID uuid, Recipe recipe, int actualExchanges, double multiplier) {
        var db = getDatabase();
        if (db == null) {
            plugin.getLogger().warning("[ForgeStation] Cannot save pending exchange output — DB unavailable! Player " + uuid + " may lose rewards for recipe " + recipe.getId());
            return;
        }
        // Lưu từng reward dưới dạng pending crafting output để deliverPendingOutputs xử lý
        for (Recipe.Reward reward : recipe.getRewards()) {
            int baseReward = reward.getBaseAmount() * actualExchanges;
            int finalReward = (int) Math.floor(baseReward * multiplier);
            if (finalReward <= 0) continue;
            // Lưu vào pending_crafting_outputs với format: material = reward info, type = reward type
            switch (reward.getType().toUpperCase()) {
                case "PLAYER_POINTS":
                    db.savePendingCraftingOutput(uuid, recipe.getId(), "PLAYER_POINTS", finalReward, "PLAYER_POINTS");
                    break;
                case "VAULT":
                    db.savePendingCraftingOutput(uuid, recipe.getId(), "VAULT", finalReward, "VAULT");
                    break;
                case "EXTRA_STORAGE":
                    String material = reward.getMaterial();
                    if (material != null && !material.isEmpty()) {
                        db.savePendingCraftingOutput(uuid, recipe.getId(), material, finalReward, "EXTRA_STORAGE");
                    }
                    break;
                case "COMMAND":
                    // Commands cần player online, lưu command template để chạy khi join
                    String cmd = reward.getCommand();
                    if (cmd != null && !cmd.isEmpty()) {
                        db.savePendingCraftingOutput(uuid, recipe.getId(), cmd, finalReward, "COMMAND");
                    }
                    break;
                default:
                    break;
            }
        }
        plugin.debug("Saved pending exchange output for " + uuid + ": " + recipe.getId() + " x" + actualExchanges + " (multiplier=" + multiplier + ")");
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // TIMER MODE: Task management
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Cancel tất cả crafting tasks của player và hoàn trả nguyên liệu + tiền
     */
    public void cancelCrafting(Player player) {
        UUID uuid = player.getUniqueId();
        java.util.List<CraftingTask> list = activeTasks.remove(uuid);
        if (list != null && !list.isEmpty()) {
            for (CraftingTask task : list) {
                task.refund(player);
                task.cancel();
            }
            plugin.getMessageUtil().send(player, "craft.cancelled-refund");
        }
        // ANTI-DUPE: Clear DB entry để tránh restore lại task đã cancel
        var db = getDatabase();
        if (db != null) db.clearAllActiveCraftingTasks(uuid);
    }
    
    /**
     * Called when crafting completes. Promote từ queue chung (chỉ item type CRAFT)
     */
    public void onCraftingComplete(UUID uuid) {
        // ANTI-DUPE: Sync DB với memory — xóa task đã complete, giữ lại task còn active
        var db = getDatabase();
        if (db != null) {
            java.util.List<CraftingTask> remaining = activeTasks.get(uuid);
            if (remaining == null || remaining.isEmpty() || remaining.stream().allMatch(CraftingTask::isCancelled)) {
                db.clearAllActiveCraftingTasks(uuid);
            } else {
                immediatelySaveTask(uuid);
            }
        }
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        plugin.getTaskQueueManager().promoteNext(player, com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.CRAFT);
    }

    /**
     * Bắt đầu craft/exchange từ hàng chờ. Gọi từ TaskQueueManager khi promote.
     */
    public boolean startFromQueue(org.bukkit.entity.Player player, String recipeId, int batchCount) {
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
        long totalDurationTicks = durationPerItemTicks * batchCount;
        CraftingTask task = new CraftingTask(plugin, player, recipe, totalDurationTicks, batchCount);
        task.setRefundSnapshot(refundItems, refundExtraStorage, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
        activeTasks.computeIfAbsent(player.getUniqueId(), k -> new java.util.ArrayList<>()).add(task);
        task.initialize();
        // CRASH PROTECTION: Save task ngay vào DB
        immediatelySaveTask(player.getUniqueId());
        return true;
    }

    /**
     * Số slot hàng chờ đã mở (từ upgrade queue_slots).
     */
    public int getQueueSlotsUnlocked(org.bukkit.entity.Player player) {
        return plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
    }

    /**
     * Hàng chờ thống nhất — dùng getTaskQueueManager().getQueue()
     * @deprecated Dùng plugin.getTaskQueueManager().getQueue(player)
     */
    @Deprecated
    public java.util.List<PendingCraftingTask> getCraftingQueue(org.bukkit.entity.Player player) {
        java.util.List<com.phmyhu1710.forgestation.queue.TaskQueueManager.PendingTask> all = plugin.getTaskQueueManager().getQueue(player);
        java.util.List<PendingCraftingTask> out = new java.util.ArrayList<>();
        for (var pt : all) {
            if (pt.getType() == com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.CRAFT) {
                out.add(new PendingCraftingTask(pt.getRecipeId(), pt.getBatchCount()));
            }
        }
        return out;
    }

    /**
     * Hủy 1 slot trong hàng chờ (index 0-based). Queue chung — gọi qua TaskQueueManager.
     */
    public void cancelQueueSlot(org.bukkit.entity.Player player, int index) {
        plugin.getTaskQueueManager().removeSlot(player, index);
        plugin.getMessageUtil().send(player, "craft.queue-slot-cancelled");
    }

    /**
     * Get active crafting task đầu tiên (để tương thích)
     */
    public CraftingTask getActiveTask(Player player) {
        java.util.List<CraftingTask> list = activeTasks.get(player.getUniqueId());
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }
    
    /**
     * Check if player is currently crafting
     */
    public boolean isCrafting(Player player) {
        java.util.List<CraftingTask> list = activeTasks.get(player.getUniqueId());
        return list != null && !list.isEmpty();
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
        
        for (Map.Entry<UUID, java.util.List<CraftingTask>> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            for (CraftingTask task : entry.getValue()) {
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
        plugin.debug("Saved " + savedTasks.size() + " crafting tasks for reload");
        return savedTasks;
    }
    
    /**
     * RELOAD PERSISTENCE: Restore tasks sau khi reload với recipe references mới
     * @param savedTasks List of [uuid, recipeId, remainingTime, totalDuration, batchCount]
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
            Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("Cannot restore crafting task: recipe " + recipeId + " not found after reload");
                continue;
            }
            
            // Tạo task mới với recipe reference mới
            CraftingTask task = new CraftingTask(plugin, player, recipe, remainingTicks, batchCount);
            task.setTotalTicks(totalTicks);
            activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>()).add(task);
            task.initialize();
            
            restored++;
            plugin.debug("Restored crafting task for " + player.getName() + ": " + recipeId);
        }
        
        if (db != null && !offlineByUuid.isEmpty()) {
            for (java.util.Map.Entry<UUID, java.util.List<Object[]>> e : offlineByUuid.entrySet()) {
                db.saveAllActiveCraftingTasks(e.getKey(), e.getValue());
            }
        }
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " crafting tasks after reload");
        }
    }
    
    /**
     * Lưu tất cả tasks + queue vào DB (không cancel). Dùng cho periodic save khi server crash.
     */
    public void saveAllTasksToDatabase() {
        var db = getDatabase();
        if (db == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, java.util.List<CraftingTask>> entry : activeTasks.entrySet()) {
            java.util.List<CraftingTask> tasks = entry.getValue();
            if (tasks == null || tasks.isEmpty()) continue;
            java.util.List<Object[]> entries = new java.util.ArrayList<>();
            for (CraftingTask t : tasks) {
                if (t == null) continue;
                long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                long startedAt = now - (total - remain) * 50L;
                entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
            }
            if (!entries.isEmpty()) db.saveAllActiveCraftingTasks(entry.getKey(), entries);
        }
        var qm = plugin.getTaskQueueManager();
        for (var eq : qm.getQueueMap().entrySet()) {
            java.util.List<Object[]> entries = new java.util.ArrayList<>();
            for (var pt : eq.getValue()) {
                entries.add(new Object[]{ pt.getType().name(), pt.getRecipeId(), pt.getBatchCount() });
            }
            if (!entries.isEmpty()) db.saveUnifiedQueue(eq.getKey(), entries);
        }
    }

    /**
     * Cancel all tasks (on plugin disable)
     */
    public void cancelAllTasks() {
        stopGlobalTimer();
        int savedCount = 0;
        var db = getDatabase();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, java.util.List<CraftingTask>> entry : activeTasks.entrySet()) {
            UUID uuid = entry.getKey();
            java.util.List<CraftingTask> tasks = entry.getValue();
            if (db != null && tasks != null && !tasks.isEmpty()) {
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (CraftingTask t : tasks) {
                    if (t != null) {
                        savedCount++;
                        long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                        long startedAt = now - (total - remain) * 50L;
                        entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
                    }
                }
                if (!entries.isEmpty()) db.saveAllActiveCraftingTasks(uuid, entries);
            }
            for (CraftingTask task : tasks) {
                if (task != null) task.cancel();
            }
        }
        activeTasks.clear();
        // Lưu hàng chờ thống nhất vào DB (server stop/reload)
        if (db != null) {
            var qm = plugin.getTaskQueueManager();
            for (var eq : qm.getQueueMap().entrySet()) {
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (var pt : eq.getValue()) {
                    entries.add(new Object[]{ pt.getType().name(), pt.getRecipeId(), pt.getBatchCount() });
                }
                if (!entries.isEmpty()) db.saveUnifiedQueue(eq.getKey(), entries);
            }
            qm.clear();
        }
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
        
        java.util.List<CraftingTask> list = activeTasks.remove(uuid);
        if (list != null && !list.isEmpty()) {
            var db = getDatabase();
            if (db != null) {
                long now = System.currentTimeMillis();
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (CraftingTask t : list) {
                    long total = t.getTotalTicks(), remain = t.getRemainingTicks();
                    long startedAt = now - (total - remain) * 50L;
                    entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
                }
                db.saveAllActiveCraftingTasks(uuid, entries);
                plugin.getLogger().info("[ForgeStation] Lưu " + list.size() + " crafting task(s) cho " + event.getPlayer().getName() + " khi thoát");
            }
            for (CraftingTask task : list) task.cancel();
            plugin.debug("Saved crafting task for " + event.getPlayer().getName() + " (parallel: " + list.size() + ")");
        }
        var qm = plugin.getTaskQueueManager();
        java.util.List<com.phmyhu1710.forgestation.queue.TaskQueueManager.PendingTask> queue = qm.getQueueMap().remove(uuid);
        if (queue != null && !queue.isEmpty()) {
            var db = getDatabase();
            if (db != null) {
                java.util.List<Object[]> entries = new java.util.ArrayList<>();
                for (var pt : queue) {
                    entries.add(new Object[]{ pt.getType().name(), pt.getRecipeId(), pt.getBatchCount() });
                }
                db.saveUnifiedQueue(uuid, entries);
                plugin.getLogger().info("[ForgeStation] Lưu " + queue.size() + " item(s) hàng chờ cho " + event.getPlayer().getName());
            }
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
        
        // 2. Restore hàng chờ thống nhất
        restoreUnifiedQueue(player);
        
        // 3. Restore active task
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
                    // output-destination: EXTRA_STORAGE — giao vào Extra Storage dù result type là MMOITEMS/VANILLA
                    if (result.isDeliverToExtraStorage() && plugin.getExtraStorageHook().isAvailable()) {
                        boolean added = plugin.getExtraStorageHook().addItems(player, result.getExtraStorageKey(), amount);
                        if (added) {
                            totalItems += amount;
                            handled = true;
                        } else {
                            plugin.getLogger().warning("ExtraStorage addItems failed for key '" + result.getExtraStorageKey() + "' (pending delivery, recipe: " + recipeId + "), will try inventory below");
                        }
                    }
                    if (!handled && result.getType().equalsIgnoreCase(outputType)) {
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
                                    plugin.getOutputRouter().routeOutput(player, stack, result.getExtraStorageKey());
                                    remaining -= batch;
                                }
                                totalItems += amount;
                                handled = true;
                             }
                        } else if ("EXTRA_STORAGE".equalsIgnoreCase(outputType)) {
                             plugin.getExtraStorageHook().addItems(player, result.getExtraStorageKey(), amount);
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
                    handled = true;
                }

                // EXCHANGE REWARD TYPES: Handle pending exchange outputs
                if (!handled) {
                    switch (outputType.toUpperCase()) {
                        case "PLAYER_POINTS":
                            plugin.getEconomyManager().givePlayerPoints(player, amount);
                            totalItems += amount;
                            handled = true;
                            break;
                        case "VAULT":
                            plugin.getEconomyManager().depositVault(player, amount);
                            totalItems += amount;
                            handled = true;
                            break;
                        case "EXTRA_STORAGE":
                            if (plugin.getExtraStorageHook().isAvailable()) {
                                plugin.getExtraStorageHook().addItems(player, outputMaterial, amount);
                            }
                            totalItems += amount;
                            handled = true;
                            break;
                        case "COMMAND":
                            // outputMaterial chứa command template
                            String cmd = outputMaterial
                                .replace("%player%", player.getName())
                                .replace("%amount%", String.valueOf(amount));
                            com.phmyhu1710.forgestation.util.CommandUtil.safeDispatchConsole(cmd, "pending:exchange:" + recipeId);
                            totalItems += amount;
                            handled = true;
                            break;
                    }
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
    
    /** Restore hàng chờ thống nhất từ DB khi player join */
    private void restoreUnifiedQueue(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return;
        
        java.util.List<Object[]> entries = db.loadUnifiedQueue(uuid);
        if (entries.isEmpty()) return;
        
        var qm = plugin.getTaskQueueManager();
        var list = qm.getQueueMap().computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
        for (Object[] e : entries) {
            var type = "CRAFT".equals(e[0]) ? com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.CRAFT : com.phmyhu1710.forgestation.queue.TaskQueueManager.TaskType.SMELT;
            list.add(new com.phmyhu1710.forgestation.queue.TaskQueueManager.PendingTask(type, (String) e[1], (Integer) e[2]));
        }
        db.clearUnifiedQueue(uuid);
        plugin.getLogger().info("[ForgeStation] Khôi phục " + list.size() + " item(s) hàng chờ cho " + player.getName());
    }
    
    /**
     * Restore tất cả crafting tasks khi player rejoins (hỗ trợ song song)
     * @return true nếu có ít nhất 1 task được restore
     */
    private boolean restoreCraftingTask(Player player) {
        UUID uuid = player.getUniqueId();
        var db = getDatabase();
        if (db == null) return false;
        
        java.util.List<Object[]> allTasks = db.loadAllActiveCraftingTasks(uuid);
        if (allTasks.isEmpty()) return false;
        
        db.clearActiveCraftingTask(uuid);
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
            
            Recipe recipe = plugin.getRecipeManager().getRecipe(recipeId);
            if (recipe == null) {
                plugin.getLogger().warning("[ForgeStation] Cannot restore crafting task: recipe '" + recipeId + "' not found (deleted/renamed?). Attempting refund for " + player.getName());
                // REFUND FIX: Recipe bị xóa → không thể restore → thông báo player
                plugin.getMessageUtil().send(player, "craft.recipe-removed", "recipe", recipeId);
                continue;
            }
            
            if (offlineProgress && actualRemainingTicks <= 0) {
                giveCraftingOutputDirect(player, recipe, batchCount);
                plugin.getMessageUtil().send(player, "craft.complete-while-offline",
                    "item", recipe.getDisplayName(), "count", String.valueOf(batchCount));
                restored++;
                continue;
            }
            
            CraftingTask task = new CraftingTask(plugin, player, recipe, Math.max(1, actualRemainingTicks), batchCount);
            task.setTotalTicks(totalTicks);
            // REFUND FIX: Task restore từ DB không có snapshot — tính từ recipe + batchCount để /fs cancel hoàn trả đúng
            buildAndSetRefundSnapshotForRestoredTask(player, recipe, batchCount, task);
            activeTasks.computeIfAbsent(uuid, k -> new java.util.ArrayList<>()).add(task);
            task.initialize();
            restored++;
            plugin.getMessageUtil().send(player, "craft.restored",
                "item", recipe.getDisplayName(),
                "time", com.phmyhu1710.forgestation.util.TimeUtil.formatTicks(actualRemainingTicks));
        }
        
        if (restored > 0) {
            plugin.getLogger().info("[ForgeStation] Khôi phục " + restored + " crafting task(s) cho " + player.getName());
        }
        return restored > 0;
    }

    /**
     * Give crafting output directly (when task completed offline)
     */
    private void giveCraftingOutputDirect(Player player, Recipe recipe, int batchCount) {
        try {
            Recipe.RecipeResult result = recipe.getResult();
            if (result == null) return;
            
            int totalAmount = result.getAmount() * batchCount;

            // output-destination: EXTRA_STORAGE — giao vào Extra Storage (item vẫn là MMO/VANILLA)
            if (result.isDeliverToExtraStorage() && plugin.getExtraStorageHook().isAvailable()) {
                boolean added = plugin.getExtraStorageHook().addItems(player, result.getExtraStorageKey(), totalAmount);
                if (added) {
                    plugin.debug("Gave offline crafting output: " + totalAmount + "x " + result.getExtraStorageKey() + " to ExtraStorage for " + player.getName());
                    return;
                }
                plugin.getLogger().warning("ExtraStorage addItems failed for key '" + result.getExtraStorageKey() + "', giving to inventory (recipe: " + recipe.getId() + ")");
            }

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
                plugin.getExtraStorageHook().addItems(player, result.getExtraStorageKey(), totalAmount);
                plugin.debug("Gave offline crafting output: " + totalAmount + "x ExtraStorage:" + result.getExtraStorageKey() + " to " + player.getName());
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

    /**
     * REFUND FIX: Task restore từ DB không có snapshot — tính từ recipe + batchCount
     * để khi player /fs cancel vẫn hoàn trả đúng (EXTRA_STORAGE, tiền, và item nếu có).
     */
    private void buildAndSetRefundSnapshotForRestoredTask(Player player, Recipe recipe, int batchCount, CraftingTask task) {
        java.util.List<ItemStack> refundItems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> refundExtraStorage = new java.util.HashMap<>();
        Map<String, Double> vars = getVariables(player);
        double vaultCost = ExpressionParser.evaluate(recipe.getVaultCostExpr(), vars) * batchCount;
        int ppCost = ExpressionParser.evaluateInt(recipe.getPlayerPointsCostExpr(), vars) * batchCount;
        double coinEngineCost = ExpressionParser.evaluate(recipe.getCoinEngineCostExpr(), vars) * batchCount;

        for (Recipe.Ingredient ing : recipe.getIngredients()) {
            int total = (ing.getBaseAmount() > 0 ? ing.getBaseAmount() : ing.getAmount()) * batchCount;
            if ("EXTRA_STORAGE".equalsIgnoreCase(ing.getType())) {
                String esKey = ing.getStorageId() != null && !ing.getStorageId().isEmpty() ? ing.getStorageId() : ing.getMaterial();
                refundExtraStorage.merge(esKey, total, Integer::sum);
                continue;
            }
            if ("VANILLA".equalsIgnoreCase(ing.getType())) {
                Material mat = null;
                try {
                    mat = Material.valueOf(ing.getMaterial());
                } catch (IllegalArgumentException ignored) { }
                if (mat != null && mat != Material.AIR) {
                    int maxStack = mat.getMaxStackSize();
                    int remaining = total;
                    while (remaining > 0) {
                        int give = Math.min(remaining, maxStack);
                        refundItems.add(new ItemStack(mat, give));
                        remaining -= give;
                    }
                }
                continue;
            }
            if ("MMOITEMS".equalsIgnoreCase(ing.getType())) {
                com.phmyhu1710.forgestation.hook.ItemHook hook = plugin.getHookManager().getItemHookByPrefix("mmoitems");
                if (hook != null) {
                    String typeId = (ing.getMmoitemsType() != null ? ing.getMmoitemsType() : "") + ":" + (ing.getMmoitemsId() != null ? ing.getMmoitemsId() : "");
                    ItemStack template = hook.getItem(typeId);
                    if (template != null && template.getType() != Material.AIR) {
                        int maxStack = template.getMaxStackSize() > 0 ? template.getMaxStackSize() : 64;
                        int remaining = total;
                        while (remaining > 0) {
                            int give = Math.min(remaining, maxStack);
                            ItemStack stack = template.clone();
                            stack.setAmount(give);
                            refundItems.add(stack);
                            remaining -= give;
                        }
                    }
                }
            }
        }
        task.setRefundSnapshot(refundItems, refundExtraStorage, vaultCost, ppCost, recipe.getCoinEngineCurrency(), coinEngineCost);
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
        return ExpressionParser.createVariables(
            plugin.getUpgradeManager().getEffectiveLevel(player, "crafting_speed"),
            plugin.getUpgradeManager().getEffectiveLevel(player, "smelting_speed"),
            0
        );
    }
    
    private com.phmyhu1710.forgestation.database.SQLiteDatabase getDatabase() {
        return plugin.getPlayerDataManager().getDatabase();
    }

    /**
     * CRASH PROTECTION: Lưu tất cả active tasks của player ngay vào DB.
     * Gọi khi task mới bắt đầu để đảm bảo không mất nếu server crash trước periodic save.
     */
    private void immediatelySaveTask(UUID uuid) {
        var db = getDatabase();
        if (db == null) return;
        java.util.List<CraftingTask> tasks = activeTasks.get(uuid);
        if (tasks == null || tasks.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.List<Object[]> entries = new java.util.ArrayList<>();
        for (CraftingTask t : tasks) {
            if (t == null || t.isCancelled()) continue;
            long total = t.getTotalTicks(), remain = t.getRemainingTicks();
            long startedAt = now - (total - remain) * 50L;
            entries.add(new Object[]{ t.getRecipe().getId(), (int) remain, (int) total, t.getBatchCount(), startedAt });
        }
        if (!entries.isEmpty()) db.saveAllActiveCraftingTasks(uuid, entries);
    }
}
