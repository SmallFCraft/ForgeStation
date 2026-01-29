package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.util.MissingItemUtil;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hook for MMOItems plugin
 * Prefix: "mmoitems-"
 * Usage: material: "mmoitems-TYPE:ID"
 */
public class MMOItemsHook implements ItemHook {

    private final ForgeStationPlugin plugin;

    public MMOItemsHook(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @Nullable
    public ItemStack getItem(@NotNull String... arguments) {
        if (arguments.length == 0) {
            return MissingItemUtil.create("MMOItems", "None");
        }

        String fullId = arguments[0];
        String[] parts = fullId.split(":");
        if (parts.length < 2) {
             plugin.getLogger().warning("Invalid MMOItem format: " + fullId + ". Use TYPE:ID");
             return MissingItemUtil.create("MMOItems", fullId);
        }

        String type = parts[0];
        String id = parts[1];

        try {
            // Use reflection to avoid hard dependency
            Class<?> mmoitemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Object mmoitemsInstance = mmoitemsClass.getField("plugin").get(null);
            
            // Get Type
            Object typesManager = mmoitemsClass.getMethod("getTypes").invoke(mmoitemsInstance);
            Object typeObj = typesManager.getClass().getMethod("get", String.class)
                .invoke(typesManager, type.toUpperCase().replace("-", "_").replace(" ", "_"));
            
            if (typeObj == null) {
                plugin.getLogger().warning("MMOItems type not found: " + type);
                return MissingItemUtil.create("MMOItems", fullId);
            }

            // Get Item
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Object item = mmoitemsClass.getMethod("getItem", typeClass, String.class)
                .invoke(mmoitemsInstance, typeObj, id.toUpperCase());

            if (item == null) {
                plugin.getLogger().warning("MMOItem not found: " + fullId);
                return MissingItemUtil.create("MMOItems", fullId);
            }

            return (ItemStack) item;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create MMOItem (" + fullId + "): " + e.getMessage());
            return MissingItemUtil.create("MMOItems", fullId);
        }
    }

    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... arguments) {
        if (arguments.length == 0 || item == null) {
            return false;
        }

        String fullId = arguments[0];
        String[] parts = fullId.split(":");
        if (parts.length < 2) return false;

        String type = parts[0];
        String id = parts[1];

        try {
            Class<?> nbtClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            Object nbtItem = nbtClass.getMethod("get", ItemStack.class).invoke(null, item);
            
            String itemType = (String) nbtClass.getMethod("getString", String.class)
                .invoke(nbtItem, "MMOITEMS_ITEM_TYPE");
            String itemId = (String) nbtClass.getMethod("getString", String.class)
                .invoke(nbtItem, "MMOITEMS_ITEM_ID");

            return type.equalsIgnoreCase(itemType) && id.equalsIgnoreCase(itemId);

        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @NotNull
    public String getPrefix() {
        return "mmoitems-";
    }
}
