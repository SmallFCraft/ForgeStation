package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * ExtraStorage integration using reflection to avoid hard dependency
 * ISSUE-010 FIX: Cache reflection methods để giảm CPU overhead
 */
public class ExtraStorageHook {

    private final ForgeStationPlugin plugin;
    private boolean available = false;
    
    // ISSUE-010 FIX: Cached reflection components
    private Class<?> apiClass;
    private Method mGetInstance;
    private Method mGetUser;
    private Method mGetStorage;
    private Method mGetItem;
    private Method mIsPresent;
    private Method mGet;
    private Method mGetQuantity;
    private Method mAdd;
    private Method mSubtract;
    private Method mCanStore;
    private Method mGetFreeSpace;

    public ExtraStorageHook(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        try {
            apiClass = Class.forName("me.hsgamer.extrastorage.api.StorageAPI");
            mGetInstance = apiClass.getMethod("getInstance");
            mGetUser = apiClass.getMethod("getUser", org.bukkit.OfflinePlayer.class);
            available = true;
            plugin.getLogger().info("ExtraStorage hook initialized with cached methods!");
        } catch (ClassNotFoundException e) {
            plugin.debug("ExtraStorage not available");
        } catch (NoSuchMethodException e) {
            plugin.debug("ExtraStorage API methods not found: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available && plugin.getHookManager().isExtraStorageEnabled();
    }

    /**
     * Get item count from player's ExtraStorage
     * @param player the player
     * @param storageId the item key (e.g., "DIAMOND" or "DIAMOND:0")
     * @return item count, or 0 if not available
     * ISSUE-010 FIX: Sử dụng cached methods thay vì lookup mỗi lần
     */
    public long getItemCount(Player player, String storageId) {
        if (!isAvailable()) return 0;
        
        try {
            Object api = mGetInstance.invoke(null);
            Object user = mGetUser.invoke(api, player);
            
            // Cache storage methods on first use (lazy init)
            if (mGetStorage == null) {
                mGetStorage = user.getClass().getMethod("getStorage");
            }
            Object storage = mGetStorage.invoke(user);
            
            // Cache getItem method
            if (mGetItem == null) {
                mGetItem = storage.getClass().getMethod("getItem", Object.class);
            }
            Object optionalItem = mGetItem.invoke(storage, storageId);
            
            // Cache Optional methods
            if (mIsPresent == null) {
                mIsPresent = optionalItem.getClass().getMethod("isPresent");
                mGet = optionalItem.getClass().getMethod("get");
            }
            boolean isPresent = (boolean) mIsPresent.invoke(optionalItem);
            
            if (!isPresent) return 0;
            
            Object item = mGet.invoke(optionalItem);
            
            // Cache getQuantity method
            if (mGetQuantity == null) {
                mGetQuantity = item.getClass().getMethod("getQuantity");
            }
            return (long) mGetQuantity.invoke(item);
            
        } catch (Exception e) {
            plugin.debug("ExtraStorage getItemCount failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Add items to player's ExtraStorage
     * @param player the player
     * @param storageId the item key
     * @param amount the amount to add
     * @return true if successful
     * ISSUE-010 FIX: Sử dụng cached methods
     */
    public boolean addItems(Player player, String storageId, long amount) {
        if (!isAvailable()) return false;
        
        try {
            Object api = mGetInstance.invoke(null);
            Object user = mGetUser.invoke(api, player);
            
            if (mGetStorage == null) {
                mGetStorage = user.getClass().getMethod("getStorage");
            }
            Object storage = mGetStorage.invoke(user);
            
            // Cache add method
            if (mAdd == null) {
                mAdd = storage.getClass().getMethod("add", Object.class, long.class);
            }
            mAdd.invoke(storage, storageId, amount);
            return true;
            
        } catch (Exception e) {
            plugin.debug("ExtraStorage addItems failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove items from player's ExtraStorage
     * @param player the player
     * @param storageId the item key
     * @param amount the amount to remove
     * @return true if successful
     * ISSUE-010 FIX: Sử dụng cached methods
     */
    public boolean removeItems(Player player, String storageId, long amount) {
        if (!isAvailable()) return false;
        
        // Check if player has enough
        long current = getItemCount(player, storageId);
        if (current < amount) return false;
        
        try {
            Object api = mGetInstance.invoke(null);
            Object user = mGetUser.invoke(api, player);
            
            if (mGetStorage == null) {
                mGetStorage = user.getClass().getMethod("getStorage");
            }
            Object storage = mGetStorage.invoke(user);
            
            // Cache subtract method
            if (mSubtract == null) {
                mSubtract = storage.getClass().getMethod("subtract", Object.class, long.class);
            }
            mSubtract.invoke(storage, storageId, amount);
            return true;
            
        } catch (Exception e) {
            plugin.debug("ExtraStorage removeItems failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if player can store items in ExtraStorage
     * @param player the player
     * @param storageId the item key
     * @return true if can store
     * ISSUE-010 FIX: Sử dụng cached methods
     */
    public boolean canStore(Player player, String storageId) {
        if (!isAvailable()) return false;
        
        try {
            Object api = mGetInstance.invoke(null);
            Object user = mGetUser.invoke(api, player);
            
            if (mGetStorage == null) {
                mGetStorage = user.getClass().getMethod("getStorage");
            }
            Object storage = mGetStorage.invoke(user);
            
            // Cache canStore method
            if (mCanStore == null) {
                mCanStore = storage.getClass().getMethod("canStore", Object.class);
            }
            return (boolean) mCanStore.invoke(storage, storageId);
            
        } catch (Exception e) {
            plugin.debug("ExtraStorage canStore failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if storage has free space
     * @param player the player
     * @return free space, or -1 if unlimited
     * ISSUE-010 FIX: Sử dụng cached methods
     */
    public long getFreeSpace(Player player) {
        if (!isAvailable()) return 0;
        
        try {
            Object api = mGetInstance.invoke(null);
            Object user = mGetUser.invoke(api, player);
            
            if (mGetStorage == null) {
                mGetStorage = user.getClass().getMethod("getStorage");
            }
            Object storage = mGetStorage.invoke(user);
            
            // Cache getFreeSpace method
            if (mGetFreeSpace == null) {
                mGetFreeSpace = storage.getClass().getMethod("getFreeSpace");
            }
            return (long) mGetFreeSpace.invoke(storage);
            
        } catch (Exception e) {
            plugin.debug("ExtraStorage getFreeSpace failed: " + e.getMessage());
            return 0;
        }
    }
}
