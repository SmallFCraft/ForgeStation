package com.phmyhu1710.forgestation.database;

import com.phmyhu1710.forgestation.ForgeStationPlugin;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite database manager for player data
 */
public class SQLiteDatabase {

    private final ForgeStationPlugin plugin;
    private Connection connection;
    private final File dbFile;

    public SQLiteDatabase(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "playerdata.db");
    }

    public void connect() {
        try {
            if (connection != null && !connection.isClosed()) {
                return;
            }

            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            
            createTables();
            
            plugin.getLogger().info("SQLite database connected");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC driver not found! This should not happen.");
            plugin.getLogger().severe("Falling back to direct connection...");
            
            // Fallback: try direct connection without explicit driver loading
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                createTables();
                plugin.getLogger().info("SQLite database connected (fallback mode)");
            } catch (SQLException ex) {
                plugin.getLogger().severe("Failed to connect to SQLite: " + ex.getMessage());
                ex.printStackTrace();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Upgrades table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS player_upgrades (" +
                "uuid TEXT NOT NULL, " +
                "upgrade_id TEXT NOT NULL, " +
                "level INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (uuid, upgrade_id))"
            );

            // Cooldowns table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS player_cooldowns (" +
                "uuid TEXT NOT NULL, " +
                "recipe_id TEXT NOT NULL, " +
                "expiry_time BIGINT NOT NULL, " +
                "PRIMARY KEY (uuid, recipe_id))"
            );
            
            // SMELTING PERSISTENCE FIX: Pending smelting outputs table
            // Lưu output khi player offline, giao khi rejoin
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pending_smelting_outputs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "uuid TEXT NOT NULL, " +
                "recipe_id TEXT NOT NULL, " +
                "output_material TEXT NOT NULL, " +
                "output_amount INTEGER NOT NULL, " +
                "output_type TEXT NOT NULL DEFAULT 'VANILLA', " +
                "created_at BIGINT NOT NULL)"
            );
            
            // SMELTING PERSISTENCE FIX: Active smelting tasks table
            // Lưu task đang chạy khi player quit/server stop
            // BATCH FIX: Thêm batch_count để lưu số lượng batch
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS active_smelting_tasks (" +
                "uuid TEXT PRIMARY KEY, " +
                "recipe_id TEXT NOT NULL, " +
                "remaining_time INTEGER NOT NULL, " +
                "total_duration INTEGER NOT NULL, " +
                "batch_count INTEGER NOT NULL DEFAULT 1, " +
                "started_at BIGINT NOT NULL)"
            );
            
            // BATCH FIX: Migration - thêm cột batch_count nếu chưa có (cho DB cũ)
            try {
                stmt.execute("ALTER TABLE active_smelting_tasks ADD COLUMN batch_count INTEGER NOT NULL DEFAULT 1");
            } catch (SQLException ignored) {
                // Column already exists - ignore
            }
            
            // ═══════════════════════════════════════════════════════════════════════
            // CRAFTING TIMER: Tables cho crafting persistence (đồng bộ với smelting)
            // ═══════════════════════════════════════════════════════════════════════
            
            // Pending crafting outputs table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pending_crafting_outputs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "uuid TEXT NOT NULL, " +
                "recipe_id TEXT NOT NULL, " +
                "output_material TEXT NOT NULL, " +
                "output_amount INTEGER NOT NULL, " +
                "output_type TEXT NOT NULL DEFAULT 'VANILLA', " +
                "created_at BIGINT NOT NULL)"
            );
            
            // Active crafting tasks table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS active_crafting_tasks (" +
                "uuid TEXT PRIMARY KEY, " +
                "recipe_id TEXT NOT NULL, " +
                "remaining_time INTEGER NOT NULL, " +
                "total_duration INTEGER NOT NULL, " +
                "batch_count INTEGER NOT NULL DEFAULT 1, " +
                "started_at BIGINT NOT NULL)"
            );

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cooldowns_expiry ON player_cooldowns(expiry_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_smelting_uuid ON pending_smelting_outputs(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_crafting_uuid ON pending_crafting_outputs(uuid)");
        }
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("SQLite database disconnected");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing database: " + e.getMessage());
        }
    }

    /**
     * Load upgrade levels for player
     */
    public Map<String, Integer> loadUpgrades(UUID uuid) {
        Map<String, Integer> upgrades = new HashMap<>();
        
        String sql = "SELECT upgrade_id, level FROM player_upgrades WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    upgrades.put(rs.getString("upgrade_id"), rs.getInt("level"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load upgrades for " + uuid + ": " + e.getMessage());
        }
        
        return upgrades;
    }

    /**
     * Save upgrade level for player
     */
    public void saveUpgrade(UUID uuid, String upgradeId, int level) {
        String sql = "INSERT OR REPLACE INTO player_upgrades (uuid, upgrade_id, level) VALUES (?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, upgradeId);
            stmt.setInt(3, level);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save upgrade for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Load cooldowns for player
     */
    public Map<String, Long> loadCooldowns(UUID uuid) {
        Map<String, Long> cooldowns = new HashMap<>();
        long now = System.currentTimeMillis();
        
        String sql = "SELECT recipe_id, expiry_time FROM player_cooldowns WHERE uuid = ? AND expiry_time > ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, now);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    cooldowns.put(rs.getString("recipe_id"), rs.getLong("expiry_time"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load cooldowns for " + uuid + ": " + e.getMessage());
        }
        
        return cooldowns;
    }

    /**
     * Save cooldown for player
     */
    public void saveCooldown(UUID uuid, String recipeId, long expiryTime) {
        String sql = "INSERT OR REPLACE INTO player_cooldowns (uuid, recipe_id, expiry_time) VALUES (?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, recipeId);
            stmt.setLong(3, expiryTime);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save cooldown for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Clean expired cooldowns
     */
    public void cleanExpiredCooldowns() {
        String sql = "DELETE FROM player_cooldowns WHERE expiry_time < ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, System.currentTimeMillis());
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                plugin.debug("Cleaned " + deleted + " expired cooldowns");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clean cooldowns: " + e.getMessage());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SMELTING PERSISTENCE FIX: Pending outputs for offline players
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Save pending smelting output for offline player
     */
    public void savePendingSmeltingOutput(UUID uuid, String recipeId, String outputMaterial, int outputAmount, String outputType) {
        String sql = "INSERT INTO pending_smelting_outputs (uuid, recipe_id, output_material, output_amount, output_type, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, recipeId);
            stmt.setString(3, outputMaterial);
            stmt.setInt(4, outputAmount);
            stmt.setString(5, outputType);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
            plugin.debug("Saved pending smelting output for " + uuid + ": " + outputAmount + "x " + outputMaterial);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save pending smelting output: " + e.getMessage());
        }
    }
    
    /**
     * Load pending smelting outputs for player
     * Returns list of [recipeId, outputMaterial, outputAmount, outputType]
     */
    public java.util.List<String[]> loadPendingSmeltingOutputs(UUID uuid) {
        java.util.List<String[]> outputs = new java.util.ArrayList<>();
        String sql = "SELECT recipe_id, output_material, output_amount, output_type FROM pending_smelting_outputs WHERE uuid = ? ORDER BY created_at ASC";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    outputs.add(new String[]{
                        rs.getString("recipe_id"),
                        rs.getString("output_material"),
                        String.valueOf(rs.getInt("output_amount")),
                        rs.getString("output_type")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load pending smelting outputs for " + uuid + ": " + e.getMessage());
        }
        
        return outputs;
    }
    
    /**
     * Clear pending smelting outputs for player (after delivery)
     */
    public void clearPendingSmeltingOutputs(UUID uuid) {
        String sql = "DELETE FROM pending_smelting_outputs WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                plugin.debug("Cleared " + deleted + " pending smelting outputs for " + uuid);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear pending smelting outputs: " + e.getMessage());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SMELTING PERSISTENCE FIX: Active tasks persistence
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Save active smelting task (when player quits or server stops)
     * BATCH FIX: Thêm batchCount parameter
     */
    public void saveActiveSmeltingTask(UUID uuid, String recipeId, int remainingTime, int totalDuration, int batchCount) {
        String sql = "INSERT OR REPLACE INTO active_smelting_tasks (uuid, recipe_id, remaining_time, total_duration, batch_count, started_at) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, recipeId);
            stmt.setInt(3, remainingTime);
            stmt.setInt(4, totalDuration);
            stmt.setInt(5, batchCount);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
            plugin.debug("Saved active smelting task for " + uuid + ": " + recipeId + " x" + batchCount + " (" + remainingTime + "s remaining)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save active smelting task: " + e.getMessage());
        }
    }
    
    /**
     * Load active smelting task for player
     * BATCH FIX: Returns [recipeId, remainingTime, totalDuration, startedAt, batchCount] or null if none
     */
    public String[] loadActiveSmeltingTask(UUID uuid) {
        String sql = "SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_smelting_tasks WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new String[]{
                        rs.getString("recipe_id"),
                        String.valueOf(rs.getInt("remaining_time")),
                        String.valueOf(rs.getInt("total_duration")),
                        String.valueOf(rs.getLong("started_at")),
                        String.valueOf(rs.getInt("batch_count"))
                    };
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load active smelting task for " + uuid + ": " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Clear active smelting task (when restored or completed)
     */
    public void clearActiveSmeltingTask(UUID uuid) {
        String sql = "DELETE FROM active_smelting_tasks WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear active smelting task: " + e.getMessage());
        }
    }
    
    /**
     * Get all active smelting tasks (for server shutdown handling)
     */
    public java.util.Map<UUID, String[]> loadAllActiveSmeltingTasks() {
        java.util.Map<UUID, String[]> tasks = new HashMap<>();
        String sql = "SELECT uuid, recipe_id, remaining_time, total_duration, started_at FROM active_smelting_tasks";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                tasks.put(uuid, new String[]{
                    rs.getString("recipe_id"),
                    String.valueOf(rs.getInt("remaining_time")),
                    String.valueOf(rs.getInt("total_duration")),
                    String.valueOf(rs.getLong("started_at"))
                });
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load all active smelting tasks: " + e.getMessage());
        }
        
        return tasks;
    }
    
    /**
     * Clear all active smelting tasks
     */
    public void clearAllActiveSmeltingTasks() {
        String sql = "DELETE FROM active_smelting_tasks";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear all active smelting tasks: " + e.getMessage());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CRAFTING TIMER: Persistence methods (đồng bộ với Smelting)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Save pending crafting output for offline player
     */
    public void savePendingCraftingOutput(UUID uuid, String recipeId, String outputMaterial, int outputAmount, String outputType) {
        String sql = "INSERT INTO pending_crafting_outputs (uuid, recipe_id, output_material, output_amount, output_type, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, recipeId);
            stmt.setString(3, outputMaterial);
            stmt.setInt(4, outputAmount);
            stmt.setString(5, outputType);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
            plugin.debug("Saved pending crafting output for " + uuid + ": " + outputAmount + "x " + outputMaterial);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save pending crafting output: " + e.getMessage());
        }
    }
    
    /**
     * Load pending crafting outputs for player
     * Returns list of [recipeId, outputMaterial, outputAmount, outputType]
     */
    public java.util.List<String[]> loadPendingCraftingOutputs(UUID uuid) {
        java.util.List<String[]> outputs = new java.util.ArrayList<>();
        String sql = "SELECT recipe_id, output_material, output_amount, output_type FROM pending_crafting_outputs WHERE uuid = ? ORDER BY created_at ASC";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    outputs.add(new String[]{
                        rs.getString("recipe_id"),
                        rs.getString("output_material"),
                        String.valueOf(rs.getInt("output_amount")),
                        rs.getString("output_type")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load pending crafting outputs: " + e.getMessage());
        }
        
        return outputs;
    }
    
    /**
     * Clear pending crafting outputs for player
     */
    public void clearPendingCraftingOutputs(UUID uuid) {
        String sql = "DELETE FROM pending_crafting_outputs WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear pending crafting outputs: " + e.getMessage());
        }
    }
    
    /**
     * Save active crafting task (when player quits or server stops)
     */
    public void saveActiveCraftingTask(UUID uuid, String recipeId, int remainingTime, int totalDuration, int batchCount) {
        String sql = "INSERT OR REPLACE INTO active_crafting_tasks (uuid, recipe_id, remaining_time, total_duration, batch_count, started_at) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, recipeId);
            stmt.setInt(3, remainingTime);
            stmt.setInt(4, totalDuration);
            stmt.setInt(5, batchCount);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
            plugin.debug("Saved active crafting task for " + uuid + ": " + recipeId + " x" + batchCount + " (" + remainingTime + "s remaining)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save active crafting task: " + e.getMessage());
        }
    }
    
    /**
     * Load active crafting task for player
     * Returns [recipeId, remainingTime, totalDuration, startedAt, batchCount] or null if none
     */
    public String[] loadActiveCraftingTask(UUID uuid) {
        String sql = "SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_crafting_tasks WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new String[]{
                        rs.getString("recipe_id"),
                        String.valueOf(rs.getInt("remaining_time")),
                        String.valueOf(rs.getInt("total_duration")),
                        String.valueOf(rs.getLong("started_at")),
                        String.valueOf(rs.getInt("batch_count"))
                    };
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load active crafting task for " + uuid + ": " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Clear active crafting task (when restored or completed)
     */
    public void clearActiveCraftingTask(UUID uuid) {
        String sql = "DELETE FROM active_crafting_tasks WHERE uuid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear active crafting task: " + e.getMessage());
        }
    }
    
    /**
     * Get database connection (for SmeltingManager direct access)
     */
    public Connection getConnection() {
        return connection;
    }
}
