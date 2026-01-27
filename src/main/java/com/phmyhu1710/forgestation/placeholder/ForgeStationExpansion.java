package com.phmyhu1710.forgestation.placeholder;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.player.PlayerDataManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for ForgeStation
 */
public class ForgeStationExpansion extends PlaceholderExpansion {

    private final ForgeStationPlugin plugin;

    public ForgeStationExpansion(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "forgestation";
    }

    @Override
    public @NotNull String getAuthor() {
        return "phmyhu_1710";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // ISSUE-002 FIX: Tránh giữ expansion qua reload vì nó giữ reference tới plugin
        // persist=true gây classloader leak khi plugin reload
        return false;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) return "";
        
        Player player = offlinePlayer.getPlayer();
        if (player == null) return "";
        
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        
        // %forgestation_crafting_level%
        if (params.equals("crafting_level")) {
            return String.valueOf(data.getUpgradeLevel("crafting_speed"));
        }
        
        // %forgestation_smelting_level%
        if (params.equals("smelting_level")) {
            return String.valueOf(data.getUpgradeLevel("smelting_speed"));
        }
        
        // %forgestation_rank%
        if (params.equals("rank")) {
            return getRankId(player);
        }
        
        // %forgestation_rank_display%
        if (params.equals("rank_display")) {
            return getRankDisplay(player);
        }
        
        // %forgestation_multiplier%
        if (params.equals("multiplier")) {
            return String.valueOf(getMultiplier(player));
        }
        
        // %forgestation_smelting_status%
        if (params.equals("smelting_status")) {
            if (plugin.getSmeltingManager().isSmelting(player)) {
                var task = plugin.getSmeltingManager().getActiveTask(player);
                return "§e" + task.getProgress() + "%";
            }
            return "§7Rảnh";
        }
        
        // %forgestation_smelting_time%
        if (params.equals("smelting_time")) {
            if (plugin.getSmeltingManager().isSmelting(player)) {
                var task = plugin.getSmeltingManager().getActiveTask(player);
                return String.valueOf(task.getRemainingTime());
            }
            return "0";
        }
        
        return null;
    }

    private String getRankId(Player player) {
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        var priority = config.getStringList("priority-order");
        
        for (String rankId : priority) {
            var rankSection = config.getConfigurationSection("ranks." + rankId);
            if (rankSection == null) continue;
            
            String permission = rankSection.getString("permission");
            if (permission != null && player.hasPermission(permission)) {
                return rankId;
            }
        }
        
        return "default";
    }

    private String getRankDisplay(Player player) {
        String rankId = getRankId(player);
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        return config.getString("ranks." + rankId + ".display-name", rankId);
    }

    private double getMultiplier(Player player) {
        String rankId = getRankId(player);
        var config = plugin.getConfigManager().getRankMultipliersConfig();
        return config.getDouble("ranks." + rankId + ".multiplier", 1.0);
    }
}
