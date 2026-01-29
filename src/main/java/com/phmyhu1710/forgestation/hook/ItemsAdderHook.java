package com.phmyhu1710.forgestation.hook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.phmyhu1710.forgestation.util.MissingItemUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Hook for ItemsAdder plugin
 * Prefix: "itemsadder-"
 * Usage: material: "itemsadder-namespace:item_id"
 * ISSUE-009 FIX: Sử dụng Caffeine cache với TTL và max size thay vì ConcurrentHashMap
 */
public class ItemsAdderHook implements ItemHook {

    // ISSUE-009 FIX: Caffeine cache với TTL 30 phút và max size 500
    private final Cache<String, ItemStack> cache = Caffeine.newBuilder()
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .maximumSize(500)
        .build();

    @Override
    @Nullable
    public ItemStack getItem(@NotNull final String... arguments) {
        if (arguments.length == 0) {
            return MissingItemUtil.create("ItemsAdder", "None");
        }

        final ItemStack cached = cache.getIfPresent(arguments[0]);
        if (cached != null) {
            return cached.clone();
        }

        try {
            // Use reflection to avoid hard dependency
            Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object customStack = customStackClass.getMethod("getInstance", String.class)
                .invoke(null, arguments[0]);

            if (customStack == null) {
                return MissingItemUtil.create("ItemsAdder", arguments[0]);
            }

            ItemStack item = (ItemStack) customStackClass.getMethod("getItemStack")
                .invoke(customStack);
            
            if (item != null) {
                ItemStack cloned = item.clone();
                cache.put(arguments[0], cloned);
                return cloned.clone();
            }
        } catch (Exception e) {
            // ItemsAdder not available or item not found
        }

        return MissingItemUtil.create("ItemsAdder", arguments[0]);
    }

    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... arguments) {
        if (arguments.length == 0) {
            return false;
        }

        try {
            Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = customStackClass.getMethod("byItemStack", ItemStack.class)
                .invoke(null, item);
            
            if (stack == null) {
                return false;
            }

            String id = (String) customStackClass.getMethod("getId").invoke(stack);
            return id != null && id.equalsIgnoreCase(arguments[0]);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @NotNull
    public String getPrefix() {
        return "itemsadder-";
    }

    /**
     * Clear the item cache
     */
    public void clearCache() {
        cache.invalidateAll();
    }
}
