package com.phmyhu1710.forgestation.util;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for inventory operations
 * ISSUE-006 FIX: Thêm snapshot method để precompute inventory counts
 */
public class InventoryUtil {

    /**
     * ISSUE-009 FIX: Parse Material an toàn, trả về null nếu invalid
     */
    private static Material parseMatSafe(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Material.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * ISSUE-006 FIX: Tạo snapshot của tất cả VANILLA items trong inventory
     * Sử dụng để precompute counts 1 lần thay vì quét inventory mỗi lần check ingredient
     * @return Map từ Material name (uppercase) -> total count
     */
    public static Map<String, Integer> createInventorySnapshot(Player player) {
        Map<String, Integer> snapshot = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                String key = item.getType().name();
                snapshot.merge(key, item.getAmount(), Integer::sum);
            }
        }
        
        return snapshot;
    }
    
    /**
     * ISSUE-006 FIX: Count items từ snapshot thay vì quét inventory
     * Chỉ dùng cho VANILLA ingredients
     */
    public static int countFromSnapshot(Map<String, Integer> snapshot, String material) {
        if (material == null || material.isEmpty()) return 0;
        return snapshot.getOrDefault(material.toUpperCase(), 0);
    }
    
    /**
     * PERF-FIX: Snapshot data cho cả input và fuel của SmeltingRecipe
     * Tránh scan inventory nhiều lần
     */
    public static class SmeltingSnapshot {
        public final int inputCount;
        public final int fuelCount;
        public final int maxBatch;
        
        public SmeltingSnapshot(int inputCount, int fuelCount, int maxBatch) {
            this.inputCount = inputCount;
            this.fuelCount = fuelCount;
            this.maxBatch = maxBatch;
        }
    }
    
    /**
     * PERF-FIX: Tạo snapshot cho smelting recipe - scan inventory 1 lần duy nhất
     */
    public static SmeltingSnapshot createSmeltingSnapshot(ForgeStationPlugin plugin, Player player, 
            com.phmyhu1710.forgestation.smelting.SmeltingRecipe recipe) {
        
        int inputCount = 0;
        int fuelCount = 0;
        
        // Lấy materials cần đếm
        String inputMat = recipe.getInputMaterial();
        String inputType = recipe.getInputType();
        String fuelMat = recipe.getFuelMaterial();
        String fuelType = recipe.getFuelType();
        boolean needFuel = recipe.isFuelRequired();
        
        // VANILLA: Scan 1 lần cho cả input và fuel
        if ("VANILLA".equals(inputType) || (needFuel && "VANILLA".equals(fuelType))) {
            Material inputMaterial = null;
            Material fuelMaterial = null;
            
            try {
                if ("VANILLA".equals(inputType)) {
                    inputMaterial = Material.valueOf(inputMat.toUpperCase());
                }
            } catch (Exception ignored) {}
            
            try {
                if (needFuel && "VANILLA".equals(fuelType)) {
                    fuelMaterial = Material.valueOf(fuelMat.toUpperCase());
                }
            } catch (Exception ignored) {}
            
            // Single pass qua inventory
            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null || item.getType() == Material.AIR) continue;
                
                if (inputMaterial != null && item.getType() == inputMaterial) {
                    inputCount += item.getAmount();
                }
                if (fuelMaterial != null && item.getType() == fuelMaterial) {
                    fuelCount += item.getAmount();
                }
            }
        }
        
        // MMOITEMS input
        if ("MMOITEMS".equals(inputType)) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && ItemBuilder.matchesMMOItem(plugin, item, 
                        recipe.getInputMmoitemsType(), recipe.getInputMmoitemsId())) {
                    inputCount += item.getAmount();
                }
            }
        }
        
        // EXTRA_STORAGE input
        if ("EXTRA_STORAGE".equals(inputType)) {
            inputCount = (int) plugin.getExtraStorageHook().getItemCount(player, inputMat);
        }
        
        // EXTRA_STORAGE fuel
        if (needFuel && "EXTRA_STORAGE".equals(fuelType)) {
            fuelCount = (int) plugin.getExtraStorageHook().getItemCount(player, fuelMat);
        }
        
        // Calculate max batch
        int inputNeed = recipe.getInputAmount();
        int maxByInput = inputNeed > 0 ? inputCount / inputNeed : 0;
        
        int maxBatch;
        if (!needFuel) {
            maxBatch = maxByInput;
        } else {
            int fuelNeed = recipe.getFuelAmount();
            int maxByFuel = fuelNeed > 0 ? fuelCount / fuelNeed : Integer.MAX_VALUE;
            maxBatch = Math.min(maxByInput, maxByFuel);
        }
        
        return new SmeltingSnapshot(inputCount, fuelCount, maxBatch);
    }

    /**
     * Count items in player inventory based on ingredient type
     */
    public static int countItems(ForgeStationPlugin plugin, Player player, Recipe.Ingredient ing) {
        int count = 0;
        
        switch (ing.getType()) {
            case "VANILLA":
                // ISSUE-009 FIX: Sử dụng parseMatSafe thay vì Material.valueOf trực tiếp
                Material mat = parseMatSafe(ing.getMaterial());
                if (mat == null) {
                    plugin.debug("Invalid material '" + ing.getMaterial() + "' in ingredient, returning 0");
                    return 0;
                }
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == mat) {
                        count += item.getAmount();
                    }
                }
                break;
            case "MMOITEMS":
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && ItemBuilder.matchesMMOItem(plugin, item, 
                            ing.getMmoitemsType(), ing.getMmoitemsId())) {
                        count += item.getAmount();
                    }
                }
                break;
            case "EXTRA_STORAGE":
                String esKey = ing.getStorageId().isEmpty() ? ing.getMaterial() : ing.getStorageId();
                count = (int) plugin.getExtraStorageHook().getItemCount(player, esKey);
                break;
        }
        
        return count;
    }

    /**
     * Remove items from player inventory
     */
    public static void removeItems(ForgeStationPlugin plugin, Player player, Recipe.Ingredient ing, int amount) {
        int remaining = amount;
        
        switch (ing.getType()) {
            case "VANILLA":
                // ISSUE-009 FIX: Sử dụng parseMatSafe thay vì Material.valueOf trực tiếp
                Material mat = parseMatSafe(ing.getMaterial());
                if (mat == null) {
                    plugin.debug("Invalid material '" + ing.getMaterial() + "' in ingredient, cannot remove items");
                    return;
                }
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() == mat && remaining > 0) {
                        int take = Math.min(item.getAmount(), remaining);
                        item.setAmount(item.getAmount() - take);
                        remaining -= take;
                    }
                }
                break;
            case "MMOITEMS":
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && remaining > 0 && ItemBuilder.matchesMMOItem(plugin, item, 
                            ing.getMmoitemsType(), ing.getMmoitemsId())) {
                        int take = Math.min(item.getAmount(), remaining);
                        item.setAmount(item.getAmount() - take);
                        remaining -= take;
                    }
                }
                break;
            case "EXTRA_STORAGE":
                String esKey = ing.getStorageId().isEmpty() ? ing.getMaterial() : ing.getStorageId();
                plugin.getExtraStorageHook().removeItems(player, esKey, amount);
                break;
        }
    }
}
