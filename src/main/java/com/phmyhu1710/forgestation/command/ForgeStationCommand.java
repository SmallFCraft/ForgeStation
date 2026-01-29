package com.phmyhu1710.forgestation.command;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import com.phmyhu1710.forgestation.smelting.SmeltingRecipe;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main command handler for /forgestation
 */
public class ForgeStationCommand implements CommandExecutor, TabCompleter {

    private final ForgeStationPlugin plugin;
    private final List<String> subCommands = Arrays.asList("reload", "help", "category");

    public ForgeStationCommand(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                             @NotNull String label, @NotNull String[] args) {
        
        if (args.length == 0) {
            // Open main menu
            if (!(sender instanceof Player player)) {
                plugin.getMessageUtil().send(sender, "player-only");
                return true;
            }
            
            if (!player.hasPermission("forgestation.use")) {
                plugin.getMessageUtil().send(sender, "no-permission");
                return true;
            }
            
            plugin.getMenuManager().openMainMenu(player);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "reload":
                handleReload(sender);
                break;
            case "help":
                handleHelp(sender);
                break;
            case "category":
            case "cat":
                handleCategory(sender, args);
                break;
            default:
                plugin.getMessageUtil().send(sender, "invalid-args", "usage", "/fs [reload|help|category]");
                break;
        }
        
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("forgestation.reload")) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        
        long start = System.currentTimeMillis();
        plugin.reload();
        long took = System.currentTimeMillis() - start;
        
        plugin.getMessageUtil().send(sender, "reload", "time", String.valueOf(took));
    }

    private void handleHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§6§l╔═══════════════════════════════╗");
        sender.sendMessage("§6§l║     §e§lFORGE§6§lSTATION §7- §fCommands             §6§l║");
        sender.sendMessage("§6§l╚═══════════════════════════════╝");
        sender.sendMessage("");
        sender.sendMessage("§8┌───────────────────────────────────┐");
        sender.sendMessage("§8│ §e/fs           §8- §7Mở menu chính               §8│");
        sender.sendMessage("§8│ §e/fs reload    §8- §7Reload configs               §8│");
        sender.sendMessage("§8│ §e/fs help      §8- §7Hiện trợ giúp                §8│");
        sender.sendMessage("§8│ §e/fs category  §8- §7Mở category                  §8│");
        sender.sendMessage("§8└───────────────────────────────────┘");
        sender.sendMessage("");
        sender.sendMessage("§7Author: §ephmyhu_1710 §8| §7v" + plugin.getDescription().getVersion());
        sender.sendMessage("");
    }

    private void handleCategory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageUtil().send(sender, "player-only");
            return;
        }
        
        if (!player.hasPermission("forgestation.use")) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cSử dụng: /fs category <tên category>");
            sender.sendMessage("§7Danh sách categories:");
            
            // List all categories from all menus
            java.util.Set<String> allCategories = plugin.getMenuManager().getAllCategories();
            
            if (allCategories.isEmpty()) {
                sender.sendMessage("§7  Không có category nào");
            } else {
                sender.sendMessage("§e  " + String.join(", ", allCategories));
            }
            return;
        }
        
        String categoryName = args[1].toLowerCase();
        
        // Find menu that contains this category
        String menuName = plugin.getMenuManager().findMenuWithCategory(categoryName);
        
        if (menuName == null) {
            sender.sendMessage("§cKhông tìm thấy category: §e" + categoryName);
            sender.sendMessage("§7Danh sách categories:");
            
            java.util.Set<String> allCategories = plugin.getMenuManager().getAllCategories();
            
            if (!allCategories.isEmpty()) {
                sender.sendMessage("§e  " + String.join(", ", allCategories));
            }
            return;
        }
        
        // Open menu with category
        plugin.getMenuManager().openMenuWithCategory(player, menuName, categoryName);
    }


    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, 
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return subCommands.stream()
                .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            
            // Tab complete category names
            if (subCmd.equals("category") || subCmd.equals("cat")) {
                java.util.Set<String> allCategories = plugin.getMenuManager().getAllCategories();
                
                return allCategories.stream()
                    .filter(cat -> cat.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            }
        }
        
        return new ArrayList<>();
    }
}

