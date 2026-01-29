package com.phmyhu1710.forgestation.hook;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hook for Nexo plugin
 * Prefix: "nexo-"
 * Usage: material: "nexo-item_id"
 */
public class NexoHook implements ItemHook {

    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ItemStack getItem(@NotNull final String... arguments) {
        if (arguments.length == 0) {
            return new ItemStack(Material.STONE);
        }

        final ItemStack cached = cache.get(arguments[0]);
        if (cached != null) {
            return cached.clone();
        }

        try {
            // Use reflection to avoid hard dependency
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object builder = nexoItemsClass.getMethod("itemFromId", String.class)
                .invoke(null, arguments[0]);

            if (builder == null) {
                return new ItemStack(Material.STONE);
            }

            // Call build() method on ItemBuilder
            ItemStack item = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            
            if (item != null) {
                ItemStack cloned = item.clone();
                cache.put(arguments[0], cloned);
                return cloned.clone();
            }
        } catch (Exception e) {
            // Nexo not available or item not found
        }

        return new ItemStack(Material.STONE);
    }

    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... arguments) {
        if (arguments.length == 0) {
            return false;
        }

        try {
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");
            String itemId = (String) nexoItemsClass.getMethod("idFromItem", ItemStack.class)
                .invoke(null, item);
            
            return itemId != null && arguments[0].equalsIgnoreCase(itemId);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @NotNull
    public String getPrefix() {
        return "nexo-";
    }

    /**
     * Clear the item cache
     */
    public void clearCache() {
        cache.clear();
    }
}
