package com.phmyhu1710.forgestation.util;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message utility for sending formatted messages
 * ISSUE-011 FIX: Sử dụng volatile immutable Map để đảm bảo thread-safety
 */
public class MessageUtil {

    private final ForgeStationPlugin plugin;
    private volatile String prefix;
    // ISSUE-011 FIX: Volatile immutable map thay vì HashMap có thể bị race condition
    private volatile Map<String, String> messageCache = Collections.emptyMap();
    
    // Pattern for %placeholder% replacement
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([^%]+)%");

    public MessageUtil(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfigManager().getMessagesConfig();
        prefix = colorize(config.getString("prefix", "&8[&6&lForge&e&lStation&8] "));
        
        // ISSUE-011 FIX: Build map mới rồi swap atomically (publish-immutable pattern)
        Map<String, String> newCache = new HashMap<>();
        cacheMessages(config, newCache);
        messageCache = Collections.unmodifiableMap(newCache);
    }

    private void cacheMessages(FileConfiguration config, Map<String, String> cache) {
        for (String key : config.getKeys(true)) {
            if (config.isString(key)) {
                String message = config.getString(key);
                cache.put(key, message);
            }
        }
    }

    public String getMessage(String key) {
        String message = messageCache.getOrDefault(key, key);
        return colorize(message.replace("%prefix%", prefix));
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        // Get raw message first to avoid colorizing before replacement
        String message = messageCache.getOrDefault(key, key).replace("%prefix%", prefix);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        // Colorize LAST
        return colorize(message);
    }

    public void send(CommandSender sender, String key) {
        String message = getMessage(key);
        if (sender instanceof Player player && plugin.getHookManager().isPlaceholderAPIEnabled()) {
            message = PlaceholderAPI.setPlaceholders(player, message);
        }
        sender.sendMessage(message);
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String message = getMessage(key, placeholders);
        if (sender instanceof Player player && plugin.getHookManager().isPlaceholderAPIEnabled()) {
            message = PlaceholderAPI.setPlaceholders(player, message);
        }
        sender.sendMessage(message);
    }

    public void send(CommandSender sender, String key, String... replacements) {
        Map<String, String> placeholders = new HashMap<>();
        for (int i = 0; i < replacements.length - 1; i += 2) {
            placeholders.put(replacements[i], replacements[i + 1]);
        }
        send(sender, key, placeholders);
    }

    public String getPrefix() {
        return prefix;
    }

    public static String colorize(String text) {
        if (text == null) return "";
        
        // Handle hex colors &#RRGGBB or &x&R&R&G&G&B&B
        text = translateHexColors(text);
        
        // Handle standard color codes
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String translateHexColors(String text) {
        // Pattern for &#RRGGBB
        Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = hexPattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    public static String stripColor(String text) {
        return ChatColor.stripColor(colorize(text));
    }

    /**
     * Format time in seconds to readable string
     */
    public static String formatTime(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            int mins = seconds / 60;
            int secs = seconds % 60;
            return mins + "m " + secs + "s";
        } else {
            int hours = seconds / 3600;
            int mins = (seconds % 3600) / 60;
            return hours + "h " + mins + "m";
        }
    }

    /**
     * Format number with commas
     */
    public static String formatNumber(double number) {
        if (number == (long) number) {
            return String.format("%,d", (long) number);
        }
        return String.format("%,.2f", number);
    }
}
