package com.phmyhu1710.forgestation.util;

/**
 * Utility class cho time formatting
 * Dùng chung giữa Smelting, Crafting, và các components khác
 */
public final class TimeUtil {
    
    private TimeUtil() {} // Prevent instantiation
    
    /**
     * Format seconds thành dạng dễ đọc
     * Ví dụ: 45 -> "45s", 125 -> "2m 5s", 3725 -> "1h 2m"
     * 
     * @param seconds Số giây
     * @return Formatted string
     */
    public static String format(int seconds) {
        if (seconds < 0) return "0s";
        
        if (seconds < 60) {
            return seconds + "s";
        }
        
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return s > 0 ? m + "m " + s + "s" : m + "m";
        }
        
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        
        if (m > 0) {
            return h + "h " + m + "m";
        }
        return h + "h";
    }
    
    /**
     * Format seconds thành dạng compact cho BossBar
     * Ví dụ: 45 -> "45s", 125 -> "2:05", 3725 -> "1:02:05"
     * 
     * @param seconds Số giây
     * @return Compact formatted string
     */
    public static String formatCompact(int seconds) {
        if (seconds < 0) return "0s";
        
        if (seconds < 60) {
            return seconds + "s";
        }
        
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return String.format("%d:%02d", m, s);
        }
        
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }
    
    /**
     * Format duration cho hiển thị trong config/messages
     * Ví dụ: 30 -> "30 giây", 120 -> "2 phút", 3600 -> "1 giờ"
     * 
     * @param seconds Số giây
     * @return Formatted string với đơn vị tiếng Việt
     */
    public static String formatVi(int seconds) {
        if (seconds < 0) return "0 giây";
        
        if (seconds < 60) {
            return seconds + " giây";
        }
        
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            if (s > 0) {
                return m + " phút " + s + " giây";
            }
            return m + " phút";
        }
        
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        if (m > 0) {
            return h + " giờ " + m + " phút";
        }
        return h + " giờ";
    }

    /**
     * Format ticks thành dạng dễ đọc, hỗ trợ số thập phân cho giây
     * @param ticks Số ticks (1s = 20 ticks)
     * @return Formatted string (e.g. "0.5s", "1.5s")
     */
    public static String formatTicks(long ticks) {
        if (ticks < 0) return "0s";
        
        double seconds = ticks / 20.0;
        if (seconds < 60) {
            // Nếu là số nguyên thì không hiện thập phân
            if (seconds == (int) seconds) {
                return (int) seconds + "s";
            }
            return String.format("%.1fs", seconds);
        }
        
        return format((int) Math.ceil(seconds));
    }

    /**
     * Format ticks cho BossBar
     */
    public static String formatTicksCompact(long ticks) {
         if (ticks < 0) return "0s";
         
         double seconds = ticks / 20.0;
         if (seconds < 60) {
             if (seconds == (int) seconds) {
                 return (int) seconds + "s";
             }
             return String.format("%.1fs", seconds);
         }
         return formatCompact((int) Math.ceil(seconds));
    }
}
