package com.phmyhu1710.forgestation.util;

/**
 * Utility for parsing number strings with shorthand notation
 */
public class NumberParser {

    /**
     * Parse number string with support for k (thousand) and m (million)
     * Examples: "1000" -> 1000, "1k" -> 1000, "1.5k" -> 1500, "1m" -> 1000000
     * 
     * @param input Input string
     * @return Parsed number, or -1 if invalid
     */
    public static int parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return -1;
        }
        
        input = input.trim().toLowerCase();
        
        try {
            // Check for 'k' suffix (thousands)
            if (input.endsWith("k")) {
                String numPart = input.substring(0, input.length() - 1);
                double value = Double.parseDouble(numPart);
                return (int) (value * 1000);
            }
            
            // Check for 'm' suffix (millions)
            if (input.endsWith("m")) {
                String numPart = input.substring(0, input.length() - 1);
                double value = Double.parseDouble(numPart);
                return (int) (value * 1000000);
            }
            
            // Plain number
            return Integer.parseInt(input);
            
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Check if input is valid number format
     */
    public static boolean isValid(String input) {
        return parse(input) > 0;
    }
}
