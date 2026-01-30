package com.phmyhu1710.forgestation.expression;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mathematical expression parser using exp4j
 * ISSUE-008 FIX: Cache parsed Expression để giảm CPU khi evaluate nhiều lần
 */
public class ExpressionParser {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final int CACHE_MAX_SIZE = 512;
    private static final Map<String, Expression> EXPR_CACHE = new ConcurrentHashMap<>();

    /**
     * Evaluate an expression with given variables
     * @param expressionStr the expression string (e.g., "1000 * level")
     * @param variables map of variable names to values
     * @return the evaluated result
     */
    public static double evaluate(String expressionStr, Map<String, Double> variables) {
        if (expressionStr == null || expressionStr.isEmpty()) {
            return 0;
        }
        
        // Try to parse as simple number first
        try {
            return Double.parseDouble(expressionStr.trim());
        } catch (NumberFormatException ignored) {
            // Continue with expression parsing
        }
        
        try {
            Expression expression = getOrBuildExpression(expressionStr);
            synchronized (expression) {
                for (Map.Entry<String, Double> entry : variables.entrySet()) {
                    try {
                        expression.setVariable(entry.getKey(), entry.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // Variable not in expression, ignore
                    }
                }
                return expression.evaluate();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * ISSUE-008: Lấy Expression từ cache hoặc build mới
     */
    private static Expression getOrBuildExpression(String expressionStr) {
        return EXPR_CACHE.computeIfAbsent(expressionStr, s -> {
            if (EXPR_CACHE.size() >= CACHE_MAX_SIZE) {
                EXPR_CACHE.clear();
            }
            Matcher matcher = VARIABLE_PATTERN.matcher(s);
            ExpressionBuilder builder = new ExpressionBuilder(s);
            while (matcher.find()) {
                String varName = matcher.group();
                if (!isMathFunction(varName)) {
                    builder.variable(varName);
                }
            }
            return builder.build();
        });
    }

    /**
     * Evaluate with single variable
     */
    public static double evaluate(String expressionStr, String varName, double varValue) {
        Map<String, Double> vars = new HashMap<>();
        vars.put(varName, varValue);
        return evaluate(expressionStr, vars);
    }

    /**
     * Evaluate integer result
     */
    public static int evaluateInt(String expressionStr, Map<String, Double> variables) {
        return (int) Math.floor(evaluate(expressionStr, variables));
    }

    /**
     * Parse time expression (e.g., "60s", "5m", "1h") or math expression
     */
    public static int parseTimeSeconds(String timeStr, Map<String, Double> variables) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        
        timeStr = timeStr.trim().toLowerCase();
        
        // Check for suffix
        if (timeStr.endsWith("s")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return evaluateInt(value, variables);
        } else if (timeStr.endsWith("m")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return evaluateInt(value, variables) * 60;
        } else if (timeStr.endsWith("h")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return evaluateInt(value, variables) * 3600;
        }
        
        // Plain number or expression (assume seconds)
        return evaluateInt(timeStr, variables);
    }

    /**
     * TICK-SUPPORT: Parse time expression to ticks
     * 支持 "0.5s" -> 10 ticks
     */
    public static long parseTimeTicks(String timeStr, Map<String, Double> variables) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        
        timeStr = timeStr.trim().toLowerCase();
        
        // Check for suffix
        if (timeStr.endsWith("s")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return (long) (evaluate(value, variables) * 20);
        } else if (timeStr.endsWith("m")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return (long) (evaluate(value, variables) * 60 * 20);
        } else if (timeStr.endsWith("h")) {
            String value = timeStr.substring(0, timeStr.length() - 1);
            return (long) (evaluate(value, variables) * 3600 * 20);
        }
        
        // Plain number or expression (assume seconds)
        return (long) (evaluate(timeStr, variables) * 20);
    }

    private static boolean isMathFunction(String name) {
        return switch (name.toLowerCase()) {
            case "abs", "acos", "asin", "atan", "ceil", "cos", "cosh", "exp",
                 "floor", "log", "log10", "log2", "sin", "sinh", "sqrt", "tan",
                 "tanh", "signum", "max", "min", "pow" -> true;
            default -> false;
        };
    }

    /**
     * Create a variables map with common player data
     */
    public static Map<String, Double> createVariables(int craftingLevel, int smeltingLevel, int level) {
        Map<String, Double> vars = new HashMap<>();
        vars.put("crafting_level", (double) craftingLevel);
        vars.put("smelting_level", (double) smeltingLevel);
        vars.put("level", (double) level);
        return vars;
    }
}
