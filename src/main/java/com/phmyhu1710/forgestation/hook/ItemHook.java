package com.phmyhu1710.forgestation.hook;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for custom item hooks (ItemsAdder, Oraxen, Nexo, etc.)
 */
public interface ItemHook {

    /**
     * Get an item from the hook plugin
     * @param arguments The item identifier (e.g., "namespace:item_id" for ItemsAdder)
     * @return The ItemStack or null if not found
     */
    @Nullable
    default ItemStack getItem(@NotNull final String... arguments) {
        return new ItemStack(Material.STONE);
    }

    /**
     * Check if an item matches the given identifier
     * @param item The item to check
     * @param arguments The identifiers to match against
     * @return true if the item matches
     */
    boolean itemMatches(@NotNull ItemStack item, @NotNull final String... arguments);

    /**
     * Get the prefix used to identify this hook in material strings
     * Example: "itemsadder-" for ItemsAdder items
     * @return The prefix string
     */
    @NotNull
    String getPrefix();
}
