package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.util.MissingItemUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hook for Oraxen plugin
 * Prefix: "oraxen-"
 * Usage: material: "oraxen-item_id"
 */
public class OraxenHook implements ItemHook {

    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ItemStack getItem(@NotNull final String... arguments) {
        if (arguments.length == 0) {
            return MissingItemUtil.create("Oraxen", "None");
        }

        final ItemStack cached = cache.get(arguments[0]);
        if (cached != null) {
            return cached.clone();
        }

        try {
            // Use reflection to avoid hard dependency
            Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Object builder = oraxenItemsClass.getMethod("getItemById", String.class)
                .invoke(null, arguments[0]);

            if (builder == null) {
                return MissingItemUtil.create("Oraxen", arguments[0]);
            }

            // Call build() method on ItemBuilder
            ItemStack item = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            
            if (item != null) {
                ItemStack cloned = item.clone();
                cache.put(arguments[0], cloned);
                return cloned.clone();
            }
        } catch (Exception e) {
            // Oraxen not available or item not found
        }

        return MissingItemUtil.create("Oraxen", arguments[0]);
    }

    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... arguments) {
        if (arguments.length == 0) {
            return false;
        }

        try {
            Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            String itemId = (String) oraxenItemsClass.getMethod("getIdByItem", ItemStack.class)
                .invoke(null, item);
            
            return itemId != null && arguments[0].equalsIgnoreCase(itemId);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @NotNull
    public String getPrefix() {
        return "oraxen-";
    }

    /**
     * Clear the item cache
     */
    public void clearCache() {
        cache.clear();
    }
}
