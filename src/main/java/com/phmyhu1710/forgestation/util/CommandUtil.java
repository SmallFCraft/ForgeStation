package com.phmyhu1710.forgestation.util;

import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ISSUE-006 FIX: Safe command dispatch với validation để chống command injection
 */
public final class CommandUtil {

    private static final Set<String> BLOCKED_COMMANDS = new HashSet<>(Arrays.asList(
            "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
            "whitelist", "stop", "reload", "restart", "minecraft:op", "minecraft:deop",
            "plugins", "pl", "ver", "version", "about", "help", "?"
    ));

    /**
     * Dispatch command an toàn với quyền Console. Chặn các lệnh nguy hiểm.
     *
     * @param command Lệnh cần thực thi (có thể chứa %player%, %amount% đã được replace)
     * @param context Mô tả ngữ cảnh để log (ví dụ: "recipe:id")
     * @return true nếu đã thực thi, false nếu bị chặn
     */
    public static boolean safeDispatchConsole(String command, String context) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        String firstPart = normalized.split("\\s+")[0];
        // Bỏ prefix / nếu có
        if (firstPart.startsWith("/")) {
            firstPart = firstPart.substring(1);
        }
        // Bỏ namespace minecraft: nếu có
        if (firstPart.contains(":")) {
            firstPart = firstPart.substring(firstPart.indexOf(':') + 1);
        }
        if (BLOCKED_COMMANDS.contains(firstPart)) {
            Bukkit.getLogger().warning("[ForgeStation] Blocked dangerous command in " + context + ": " + command);
            return false;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        return true;
    }
}
