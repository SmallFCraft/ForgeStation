package com.phmyhu1710.forgestation.command;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.crafting.Recipe;
import com.phmyhu1710.forgestation.smelting.SmeltingRecipe;
import com.phmyhu1710.forgestation.upgrade.UpgradeManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Main command handler for /forgestation
 */
public class ForgeStationCommand implements CommandExecutor, TabCompleter {

    private final ForgeStationPlugin plugin;
    private final List<String> subCommands = Arrays.asList("reload", "help", "menu", "category", "cancel", "setlevel", "addlevel");

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
            
            if (!player.isOp() && !player.hasPermission("forgestation.use")) {
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
            case "menu":
                handleMenu(sender, args);
                break;
            case "category":
            case "cat":
                handleCategory(sender, args);
                break;
            case "cancel":
                handleCancel(sender);
                break;
            case "setlevel":
                handleSetLevel(sender, args);
                break;
            case "addlevel":
                handleAddLevel(sender, args);
                break;
            default:
                plugin.getMessageUtil().send(sender, "invalid-args", "usage", "/fs [reload|help|menu|category|cancel|setlevel|addlevel]");
                break;
        }
        
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!(sender.isOp() || sender.hasPermission("forgestation.reload"))) {
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
        sender.sendMessage("§8│ §e/fs menu [recipes|upgrades|smelting] §8- §7Mở menu  §8│");
        sender.sendMessage("§8│ §e/fs reload    §8- §7Reload configs               §8│");
        sender.sendMessage("§8│ §e/fs help      §8- §7Hiện trợ giúp                §8│");
        sender.sendMessage("§8│ §e/fs cat <id>  §8- §7Mở menu theo category       §8│");
        sender.sendMessage("§8│ §e/fs cancel    §8- §7Hủy chế tạo/nung & hoàn trả   §8│");
        sender.sendMessage("§8│ §e/fs setlevel <player> <upgrade> <level> §8- §7Đặt level nâng cấp (admin) §8│");
        sender.sendMessage("§8│ §e/fs addlevel <player> <upgrade> <amount> §8- §7Cộng level nâng cấp (admin) §8│");
        sender.sendMessage("§8└───────────────────────────────────┘");
        sender.sendMessage("");
        sender.sendMessage("§7Author: §ephmyhu_1710 §8| §7v" + plugin.getDescription().getVersion());
        sender.sendMessage("");
    }

    private static final List<String> MENU_TYPES = Arrays.asList("recipes", "upgrades", "smelting");

    private void handleMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageUtil().send(sender, "player-only");
            return;
        }
        if (!player.isOp() && !player.hasPermission("forgestation.use")) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        if (args.length >= 2) {
            String type = args[1].toLowerCase();
            switch (type) {
                case "recipes":
                    plugin.getMenuManager().openMenu(player, "crafting-menu");
                    return;
                case "upgrades":
                    plugin.getMenuManager().openMenu(player, "upgrade-menu");
                    return;
                case "smelting":
                    plugin.getMenuManager().openMenu(player, "smelting-menu");
                    return;
                default:
                    sender.sendMessage("§cMenu không tồn tại: §e" + type);
                    sender.sendMessage("§7Dùng: §e/fs menu [recipes|upgrades|smelting]");
                    return;
            }
        }
        plugin.getMenuManager().openMainMenu(player);
    }

    private void handleCancel(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageUtil().send(sender, "player-only");
            return;
        }
        if (!player.isOp() && !player.hasPermission("forgestation.cancel")) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        boolean hadCraft = plugin.getCraftingExecutor().isCrafting(player);
        boolean hadSmelt = plugin.getSmeltingManager().isSmelting(player);
        if (hadCraft) plugin.getCraftingExecutor().cancelCrafting(player);
        if (hadSmelt) plugin.getSmeltingManager().cancelSmelting(player);
        if (!hadCraft && !hadSmelt) {
            plugin.getMessageUtil().send(sender, "craft.no-active-task");
        }
    }

    /**
     * Admin: /fs setlevel <player> <upgrade_id> <level>
     * Đặt level nâng cấp cho player (online hoặc offline). Cần forgestation.setlevel.
     */
    private void handleSetLevel(CommandSender sender, String[] args) {
        if (!(sender.isOp() || sender.hasPermission("forgestation.setlevel"))) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        if (args.length < 4) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.usage");
            return;
        }
        String tenNguoiChoi = args[1];
        String upgradeId = args[2].toLowerCase();
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.usage");
            return;
        }
        if (level < 0) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-level", "max", "∞");
            return;
        }

        UpgradeManager upgradeManager = plugin.getUpgradeManager();

        // upgrade_id = "*" → đặt tất cả upgrade (mỗi cái cap theo max-level của nó)
        if ("*".equals(upgradeId)) {
            var allUpgrades = upgradeManager.getAllUpgrades();
            if (allUpgrades.isEmpty()) {
                plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-upgrade", "upgrade", "*", "list", "-");
                return;
            }
            if ("*".equals(tenNguoiChoi)) {
                List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                for (Player p : online) {
                    var data = plugin.getPlayerDataManager().getPlayerData(p.getUniqueId());
                    for (var e : allUpgrades.entrySet()) {
                        int cap = Math.min(level, e.getValue().getMaxLevel());
                        data.setUpgradeLevel(e.getKey(), cap);
                    }
                }
                plugin.getMessageUtil().send(sender, "admin.setlevel.success-all-upgrades",
                    "count", String.valueOf(allUpgrades.size()),
                    "level", String.valueOf(level),
                    "target", online.size() + " player(s)");
            } else {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(tenNguoiChoi);
                UUID uuid = offline.getUniqueId();
                if (uuid == null || (offline.getName() == null && !offline.hasPlayedBefore())) {
                    plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-player", "player", tenNguoiChoi);
                    return;
                }
                var data = plugin.getPlayerDataManager().getPlayerData(uuid);
                for (var e : allUpgrades.entrySet()) {
                    int cap = Math.min(level, e.getValue().getMaxLevel());
                    data.setUpgradeLevel(e.getKey(), cap);
                }
                String tenHienThi = offline.getName() != null ? offline.getName() : tenNguoiChoi;
                plugin.getMessageUtil().send(sender, "admin.setlevel.success-all-upgrades",
                    "count", String.valueOf(allUpgrades.size()),
                    "level", String.valueOf(level),
                    "target", tenHienThi);
            }
            return;
        }

        UpgradeManager.Upgrade upgrade = upgradeManager.getUpgrade(upgradeId);
        if (upgrade == null) {
            String danhSach = String.join(", ", upgradeManager.getAllUpgrades().keySet());
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-upgrade",
                "upgrade", upgradeId,
                "list", danhSach.isEmpty() ? "-" : danhSach);
            return;
        }
        int maxLevel = upgrade.getMaxLevel();
        if (level > maxLevel) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-level", "max", String.valueOf(maxLevel));
            return;
        }

        String displayName = upgrade.getDisplayName();
        String tenUpgrade = (displayName != null && !displayName.isEmpty())
            ? org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName))
            : upgradeId;

        if ("*".equals(tenNguoiChoi)) {
            List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            for (Player p : online) {
                plugin.getPlayerDataManager().getPlayerData(p.getUniqueId()).setUpgradeLevel(upgradeId, level);
            }
            plugin.getMessageUtil().send(sender, "admin.setlevel.success-all",
                "upgrade", tenUpgrade,
                "level", String.valueOf(level),
                "count", String.valueOf(online.size()));
            return;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(tenNguoiChoi);
        UUID uuid = offline.getUniqueId();
        if (uuid == null || (offline.getName() == null && !offline.hasPlayedBefore())) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-player", "player", tenNguoiChoi);
            return;
        }

        var data = plugin.getPlayerDataManager().getPlayerData(uuid);
        data.setUpgradeLevel(upgradeId, level);

        String tenHienThi = offline.getName() != null ? offline.getName() : tenNguoiChoi;
        plugin.getMessageUtil().send(sender, "admin.setlevel.success",
            "player", tenHienThi,
            "upgrade", tenUpgrade,
            "level", String.valueOf(level));
    }

    /**
     * Admin: /fs addlevel <player> <upgrade_id> <amount>
     * Cộng level nâng cấp (amount có thể âm để trừ). Cap theo max-level. Cần forgestation.addlevel.
     */
    private void handleAddLevel(CommandSender sender, String[] args) {
        if (!(sender.isOp() || sender.hasPermission("forgestation.addlevel"))) {
            plugin.getMessageUtil().send(sender, "no-permission");
            return;
        }
        if (args.length < 4) {
            plugin.getMessageUtil().send(sender, "admin.addlevel.usage");
            return;
        }
        String tenNguoiChoi = args[1];
        String upgradeId = args[2].toLowerCase();
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.getMessageUtil().send(sender, "admin.addlevel.usage");
            return;
        }

        UpgradeManager upgradeManager = plugin.getUpgradeManager();

        if ("*".equals(upgradeId)) {
            var allUpgrades = upgradeManager.getAllUpgrades();
            if (allUpgrades.isEmpty()) {
                plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-upgrade", "upgrade", "*", "list", "-");
                return;
            }
            if ("*".equals(tenNguoiChoi)) {
                List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                for (Player p : online) {
                    var data = plugin.getPlayerDataManager().getPlayerData(p.getUniqueId());
                    for (var e : allUpgrades.entrySet()) {
                        int cur = data.getUpgradeLevel(e.getKey());
                        int max = e.getValue().getMaxLevel();
                        int newLevel = Math.min(max, Math.max(0, cur + amount));
                        data.setUpgradeLevel(e.getKey(), newLevel);
                    }
                }
                plugin.getMessageUtil().send(sender, "admin.addlevel.success-all-upgrades",
                    "amount", String.valueOf(amount),
                    "count", String.valueOf(allUpgrades.size()),
                    "target", online.size() + " player(s)");
            } else {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(tenNguoiChoi);
                UUID uuid = offline.getUniqueId();
                if (uuid == null || (offline.getName() == null && !offline.hasPlayedBefore())) {
                    plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-player", "player", tenNguoiChoi);
                    return;
                }
                var data = plugin.getPlayerDataManager().getPlayerData(uuid);
                for (var e : allUpgrades.entrySet()) {
                    int cur = data.getUpgradeLevel(e.getKey());
                    int max = e.getValue().getMaxLevel();
                    int newLevel = Math.min(max, Math.max(0, cur + amount));
                    data.setUpgradeLevel(e.getKey(), newLevel);
                }
                String tenHienThi = offline.getName() != null ? offline.getName() : tenNguoiChoi;
                plugin.getMessageUtil().send(sender, "admin.addlevel.success-all-upgrades",
                    "amount", String.valueOf(amount),
                    "count", String.valueOf(allUpgrades.size()),
                    "target", tenHienThi);
            }
            return;
        }

        UpgradeManager.Upgrade upgrade = upgradeManager.getUpgrade(upgradeId);
        if (upgrade == null) {
            String danhSach = String.join(", ", upgradeManager.getAllUpgrades().keySet());
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-upgrade",
                "upgrade", upgradeId,
                "list", danhSach.isEmpty() ? "-" : danhSach);
            return;
        }

        String displayName = upgrade.getDisplayName();
        String tenUpgrade = (displayName != null && !displayName.isEmpty())
            ? org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', displayName))
            : upgradeId;
        int maxLevel = upgrade.getMaxLevel();

        if ("*".equals(tenNguoiChoi)) {
            List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            int levelShown = 0;
            for (Player p : online) {
                var data = plugin.getPlayerDataManager().getPlayerData(p.getUniqueId());
                int cur = data.getUpgradeLevel(upgradeId);
                int newLevel = Math.min(maxLevel, Math.max(0, cur + amount));
                data.setUpgradeLevel(upgradeId, newLevel);
                levelShown = newLevel;
            }
            plugin.getMessageUtil().send(sender, "admin.addlevel.success-all",
                "amount", String.valueOf(amount),
                "upgrade", tenUpgrade,
                "level", String.valueOf(levelShown),
                "count", String.valueOf(online.size()));
            return;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(tenNguoiChoi);
        UUID uuid = offline.getUniqueId();
        if (uuid == null || (offline.getName() == null && !offline.hasPlayedBefore())) {
            plugin.getMessageUtil().send(sender, "admin.setlevel.invalid-player", "player", tenNguoiChoi);
            return;
        }

        var data = plugin.getPlayerDataManager().getPlayerData(uuid);
        int cur = data.getUpgradeLevel(upgradeId);
        int newLevel = Math.min(maxLevel, Math.max(0, cur + amount));
        data.setUpgradeLevel(upgradeId, newLevel);

        String tenHienThi = offline.getName() != null ? offline.getName() : tenNguoiChoi;
        plugin.getMessageUtil().send(sender, "admin.addlevel.success",
            "player", tenHienThi,
            "upgrade", tenUpgrade,
            "amount", String.valueOf(amount),
            "level", String.valueOf(newLevel));
    }

    private void handleCategory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageUtil().send(sender, "player-only");
            return;
        }
        
        if (!player.isOp() && !player.hasPermission("forgestation.use")) {
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
            String prefix = args[1].toLowerCase();

            if (subCmd.equals("menu")) {
                return MENU_TYPES.stream()
                    .filter(t -> t.startsWith(prefix))
                    .collect(Collectors.toList());
            }
            if (subCmd.equals("category") || subCmd.equals("cat")) {
                java.util.Set<String> allCategories = plugin.getMenuManager().getAllCategories();
                return allCategories.stream()
                    .filter(cat -> cat.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
            }
            if (subCmd.equals("setlevel") || subCmd.equals("addlevel")) {
                List<String> suggestions = new ArrayList<>();
                if ("*".startsWith(prefix) || "*".toLowerCase().startsWith(prefix)) {
                    suggestions.add("*");
                }
                Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n != null && n.toLowerCase().startsWith(prefix))
                    .forEach(suggestions::add);
                return suggestions;
            }
        }

        if (args.length == 3 && ("setlevel".equals(args[0].toLowerCase()) || "addlevel".equals(args[0].toLowerCase()))) {
            String prefix = args[2].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            if ("*".startsWith(prefix) || "*".toLowerCase().startsWith(prefix)) {
                suggestions.add("*");
            }
            plugin.getUpgradeManager().getAllUpgrades().keySet().stream()
                .filter(id -> id.toLowerCase().startsWith(prefix))
                .forEach(suggestions::add);
            return suggestions;
        }

        if (args.length == 4 && ("setlevel".equals(args[0].toLowerCase()) || "addlevel".equals(args[0].toLowerCase()))) {
            String prefix = args[3];
            if ("addlevel".equals(args[0].toLowerCase())) {
                List<String> amounts = Arrays.asList("1", "5", "10", "50", "-1", "-5", "-10");
                return amounts.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            int max = 0;
            if ("*".equals(args[2])) {
                for (UpgradeManager.Upgrade u : plugin.getUpgradeManager().getAllUpgrades().values()) {
                    max = Math.max(max, u.getMaxLevel());
                }
            } else {
                UpgradeManager.Upgrade up = plugin.getUpgradeManager().getUpgrade(args[2].toLowerCase());
                if (up != null) max = up.getMaxLevel();
            }
            if (max >= 0) {
                List<String> levels = new ArrayList<>();
                for (int i = 0; i <= max; i++) {
                    String s = String.valueOf(i);
                    if (s.startsWith(prefix)) levels.add(s);
                }
                return levels;
            }
        }

        return new ArrayList<>();
    }
}

