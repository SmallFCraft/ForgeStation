package com.phmyhu1710.forgestation.util;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for building ItemStacks from config
 */
public class ItemBuilder {

    /**
     * Build result item from recipe result config
     */
    public static ItemStack buildResult(ForgeStationPlugin plugin, Player player, Recipe.RecipeResult result) {
        switch (result.getType()) {
            case "MMOITEMS":
                return buildMMOItem(plugin, result.getMmoitemsType(), result.getMmoitemsId(), result.getAmount());
            case "EXTRA_STORAGE":
                // ExtraStorage doesn't return ItemStack, it adds directly
                return new ItemStack(Material.STONE, 1);
            default: // VANILLA
                return buildVanillaItem(player, result);
        }
    }

    /**
     * Build vanilla item with custom name, lore, and enchantments
     */
    public static ItemStack buildVanillaItem(Player player, Recipe.RecipeResult result) {
        Material mat;
        try {
            mat = Material.valueOf(result.getMaterial().toUpperCase());
        } catch (Exception e) {
            mat = Material.STONE;
        }

        ItemStack item = new ItemStack(mat, result.getAmount());
        ItemMeta meta = item.getItemMeta();

        // Custom name
        if (result.getName() != null && !result.getName().isEmpty()) {
            String name = result.getName().replace("%player%", player.getName());
            meta.setDisplayName(MessageUtil.colorize(name));
        }

        // Custom lore
        if (result.getLore() != null && !result.getLore().isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String line : result.getLore()) {
                line = line.replace("%player%", player.getName());
                lore.add(MessageUtil.colorize(line));
            }
            meta.setLore(lore);
        }

        item.setItemMeta(meta);

        // Enchantments (format: "enchantment:level")
        for (String enchStr : result.getEnchantments()) {
            try {
                String[] parts = enchStr.split(":");
                String enchName = parts[0].toLowerCase();
                int level = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;

                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(enchName));
                if (ench != null) {
                    item.addUnsafeEnchantment(ench, level);
                }
            } catch (Exception e) {
                // Invalid enchantment, skip
            }
        }

        return item;
    }

    /**
     * Build MMOItem
     */
    public static ItemStack buildMMOItem(ForgeStationPlugin plugin, String type, String id, int amount) {
        if (!plugin.getHookManager().isMMOItemsEnabled()) {
            plugin.getLogger().warning("MMOItems not available, returning stone");
            return new ItemStack(Material.STONE, amount);
        }

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
                return new ItemStack(Material.STONE, amount);
            }

            // Get Item
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Object item = mmoitemsClass.getMethod("getItem", typeClass, String.class)
                .invoke(mmoitemsInstance, typeObj, id.toUpperCase());

            if (item == null) {
                plugin.getLogger().warning("MMOItem not found: " + type + ":" + id);
                return new ItemStack(Material.STONE, amount);
            }

            ItemStack result = (ItemStack) item;
            result.setAmount(amount);
            return result;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create MMOItem: " + e.getMessage());
            return new ItemStack(Material.STONE, amount);
        }
    }

    /**
     * Check if item matches ingredient (for MMOItems)
     */
    public static boolean matchesMMOItem(ForgeStationPlugin plugin, ItemStack item, String type, String id) {
        if (!plugin.getHookManager().isMMOItemsEnabled() || item == null) {
            return false;
        }

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
}
