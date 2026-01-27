package com.phmyhu1710.forgestation.util;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

/**
 * ISSUE-014 FIX: Utility class để route output items theo config
 * Hỗ trợ: INVENTORY, EXTRA_STORAGE, DROP, HOPPER
 */
public class OutputRouter {

    private final ForgeStationPlugin plugin;

    public OutputRouter(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Route output item theo config setting
     * @param player Player nhận item
     * @param item ItemStack cần route
     * @param storageId ID cho ExtraStorage (nếu dùng)
     * @return true nếu thành công
     */
    public boolean routeOutput(Player player, ItemStack item, String storageId) {
        String outputType = plugin.getConfigManager().getMainConfig()
            .getString("output.type", "INVENTORY").toUpperCase();
        
        switch (outputType) {
            case "EXTRA_STORAGE":
                return routeToExtraStorage(player, item, storageId);
            case "DROP":
                return routeToDrop(player, item);
            case "HOPPER":
                return routeToHopper(player, item);
            case "INVENTORY":
            default:
                return routeToInventory(player, item);
        }
    }

    /**
     * Route to player inventory (default)
     */
    private boolean routeToInventory(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            // Drop overflow items
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            plugin.getMessageUtil().send(player, "craft.no-space");
        }
        return true;
    }

    /**
     * Route to ExtraStorage plugin
     */
    private boolean routeToExtraStorage(Player player, ItemStack item, String storageId) {
        if (!plugin.getExtraStorageHook().isAvailable()) {
            plugin.debug("ExtraStorage not available, falling back to inventory");
            return routeToInventory(player, item);
        }
        
        // Get storage ID from config if not provided
        if (storageId == null || storageId.isEmpty()) {
            storageId = plugin.getConfigManager().getMainConfig()
                .getString("output.extra-storage.storage-id", item.getType().name());
        }
        
        boolean success = plugin.getExtraStorageHook().addItems(player, storageId, item.getAmount());
        if (!success) {
            plugin.debug("ExtraStorage add failed, falling back to inventory");
            return routeToInventory(player, item);
        }
        
        return true;
    }

    /**
     * Route to drop on ground
     */
    private boolean routeToDrop(Player player, ItemStack item) {
        player.getWorld().dropItemNaturally(player.getLocation(), item);
        return true;
    }

    /**
     * Route to nearby hopper
     */
    private boolean routeToHopper(Player player, ItemStack item) {
        int searchRadius = plugin.getConfigManager().getMainConfig()
            .getInt("output.hopper.search-radius", 3);
        
        Location loc = player.getLocation();
        Block hopperBlock = findNearbyHopper(loc, searchRadius);
        
        if (hopperBlock != null && hopperBlock.getState() instanceof Hopper) {
            Hopper hopper = (Hopper) hopperBlock.getState();
            HashMap<Integer, ItemStack> overflow = hopper.getInventory().addItem(item);
            
            if (!overflow.isEmpty()) {
                // Hopper full, fall back to inventory
                for (ItemStack remaining : overflow.values()) {
                    routeToInventory(player, remaining);
                }
            }
            return true;
        }
        
        // No hopper found, fall back to inventory
        plugin.debug("No hopper found within " + searchRadius + " blocks, falling back to inventory");
        return routeToInventory(player, item);
    }

    /**
     * Find nearest hopper within radius
     */
    private Block findNearbyHopper(Location center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = center.getWorld().getBlockAt(
                        center.getBlockX() + x,
                        center.getBlockY() + y,
                        center.getBlockZ() + z
                    );
                    if (block.getType() == Material.HOPPER) {
                        return block;
                    }
                }
            }
        }
        return null;
    }
}
