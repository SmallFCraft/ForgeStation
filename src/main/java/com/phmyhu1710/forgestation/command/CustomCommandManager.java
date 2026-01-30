package com.phmyhu1710.forgestation.command;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages dynamic custom commands for opening specific category menus
 */
public class CustomCommandManager {

    private final ForgeStationPlugin plugin;
    private CommandMap commandMap;
    private final Map<String, Command> registeredCommands = new HashMap<>();

    public CustomCommandManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        setupCommandMap();
    }

    private void setupCommandMap() {
        try {
            Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            bukkitCommandMap.setAccessible(true);
            this.commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
        } catch (Exception e) {
            plugin.getLogger().severe("Could not access Bukkit CommandMap: " + e.getMessage());
        }
    }

    /**
     * Register a custom command linked to a category
     * @param commandName The command name (e.g. "nungquang")
     * @param categoryName The category name (file name without extension, e.g. "ores")
     */
    public void registerCommand(String commandName, String categoryName) {
        if (commandMap == null) return;

        // Unregister if exists (for reload)
        if (registeredCommands.containsKey(commandName)) {
            // Note: Bukkit doesn't support easy unregistering, 
            // but we can overwrite or handle logic.
            // For now, we just don't register duplicate logic if it's the same.
        }

        CustomCategoryCommand cmd = new CustomCategoryCommand(commandName, categoryName);
        commandMap.register(plugin.getName(), cmd);
        registeredCommands.put(commandName, cmd);
        
        plugin.debug("Registered custom command: /" + commandName + " -> Category: " + categoryName);
    }

    /**
     * Unregister all custom commands (called on disable/reload)
     * Note: Bukkit CommandMap doesn't have a clean unregister, 
     * usually we have to remove from knownCommands map via reflection if we want to be clean.
     * But for now, we can clear our local map.
     */
    public void unregisterAll() {
        // Advanced unregistering requires more reflection into SimpleCommandMap's knownCommands
        // For simplicity/stability, we might just leave them or try to clean up if possible.
        // Let's try to clean up knownCommands to avoid "duplicate command" errors on reload.
        
        if (commandMap == null) return;

        try {
            Field knownCommandsField;
            try {
                knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
            } catch (NoSuchFieldException e) {
                // Try superclass (SimpleCommandMap)
                try {
                    knownCommandsField = commandMap.getClass().getSuperclass().getDeclaredField("knownCommands");
                } catch (NoSuchFieldException ex) {
                     plugin.debug("Could not find knownCommands field: " + ex.getMessage());
                     return;
                }
            }
            
            knownCommandsField.setAccessible(true);
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);

            for (String cmdName : registeredCommands.keySet()) {
                knownCommands.remove(cmdName);
                knownCommands.remove(plugin.getName().toLowerCase() + ":" + cmdName);
            }
        } catch (Exception e) {
            plugin.debug("Failed to unregister commands: " + e.getMessage());
        }
        
        registeredCommands.clear();
    }

    private class CustomCategoryCommand extends Command {
        private final String categoryName;

        public CustomCategoryCommand(String name, String categoryName) {
            super(name);
            this.categoryName = categoryName;
            this.setDescription("Open " + categoryName + " menu");
            this.setUsage("/" + name);
            // Dùng chung forgestation.use (cùng /fs) — permission trong plugin.yml default: op
            this.setPermission("forgestation.use"); 
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis command is only for players.");
                return true;
            }

            Player player = (Player) sender;
            
            if (!testPermission(player)) {
                return true;
            }

            // Find menu containing this category
            // We need to use "categoryName" (which is typically the file name)
            // MenuManager logic relies on auto-detected categories matching file names.
            
            String menuName = plugin.getMenuManager().findMenuWithCategory(categoryName);
            if (menuName == null) {
                // Try searching with folder source?
                // If the user set "open_command" in "ores.yml", category name passed here is "ores".
                // MenuManager should have detected "ores".
                plugin.getMessageUtil().send(player, "menu.category-not-found", "category", categoryName);
                return true;
            }

            plugin.getMenuManager().openMenuWithCategory(player, menuName, categoryName);
            return true;
        }
    }
}
