package com.phmyhu1710.forgestation.util;

import org.bukkit.entity.Player;

/**
 * Utility for handling Minecraft experience
 */
public class ExperienceUtil {

    /**
     * Get total experience points of a player
     */
    public static int getTotalExperience(Player player) {
        return Math.round(getExpAtLevel(player.getLevel()) + (getExpAtLevel(player.getLevel() + 1) - getExpAtLevel(player.getLevel())) * player.getExp());
    }

    /**
     * Set total experience points for a player
     */
    public static void setTotalExperience(Player player, int exp) {
        if (exp < 0) {
            throw new IllegalArgumentException("Experience is negative!");
        }
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);

        int amount = exp;
        while (amount > 0) {
            int expToLevel = getExpAtLevel(player.getLevel() + 1) - getExpAtLevel(player.getLevel());
            amount -= expToLevel;
            if (amount >= 0) {
                player.giveExp(expToLevel);
            } else {
                amount += expToLevel;
                player.giveExp(amount);
                amount = 0;
            }
        }
    }

    private static int getExpAtLevel(int level) {
        if (level <= 16) {
            return (int) (Math.pow(level, 2) + 6 * level);
        } else if (level <= 31) {
            return (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360.0);
        } else {
            return (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220.0);
        }
    }
}
