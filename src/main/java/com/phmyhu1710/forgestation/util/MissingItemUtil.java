package com.phmyhu1710.forgestation.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility to create a placeholder item when a requested item from a hook is missing.
 */
public class MissingItemUtil {

    /**
     * Create a descriptive missing item stack
     * @param source The source plugin/hook (e.g. MMOItems, ItemsAdder)
     * @param id The ID that was requested
     * @return A red stained glass pane with error info
     */
    public static ItemStack create(String source, String id) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize("&c⚠ VẬT PHẨM LỖI &7(" + source + ")"));
            List<String> lore = new ArrayList<>();
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            lore.add(MessageUtil.colorize("&7ID: &e" + id));
            lore.add(MessageUtil.colorize("&7Trạng thái: &cKhông tìm thấy"));
            lore.add("");
            lore.add(MessageUtil.colorize("&cVui lòng kiểm tra lại config"));
            lore.add(MessageUtil.colorize("&choặc cài đặt plugin " + source + "!"));
            lore.add(MessageUtil.colorize("&8━━━━━━━━━━━━━━━━━━━━━━━━━"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }
}
