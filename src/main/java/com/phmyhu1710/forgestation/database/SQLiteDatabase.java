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
            
            plugin.debug("SQLite database connected");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC driver not found! This should not happen.");
            plugin.getLogger().severe("Falling back to direct connection...");
            
            // Fallback: try direct connection without explicit driver loading
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                createTables();
                plugin.debug("SQLite database connected (fallback mode)");
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

            // Active tasks multi — hỗ trợ NHIỀU task song song per player (uuid+task_index)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS active_crafting_tasks_multi (" +
                "uuid TEXT NOT NULL, task_index INTEGER NOT NULL, recipe_id TEXT NOT NULL, " +
                "remaining_time INTEGER NOT NULL, total_duration INTEGER NOT NULL, batch_count INTEGER NOT NULL, started_at BIGINT NOT NULL, " +
                "PRIMARY KEY (uuid, task_index))"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS active_smelting_tasks_multi (" +
                "uuid TEXT NOT NULL, task_index INTEGER NOT NULL, recipe_id TEXT NOT NULL, " +
                "remaining_time INTEGER NOT NULL, total_duration INTEGER NOT NULL, batch_count INTEGER NOT NULL, started_at BIGINT NOT NULL, " +
                "PRIMARY KEY (uuid, task_index))"
            );

            // Hàng chờ thống nhất (craft + smelt chung)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS task_queue (" +
                "uuid TEXT NOT NULL, slot_index INTEGER NOT NULL, task_type TEXT NOT NULL, recipe_id TEXT NOT NULL, batch_count INTEGER NOT NULL, " +
                "PRIMARY KEY (uuid, slot_index))"
            );

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cooldowns_expiry ON player_cooldowns(expiry_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_smelting_uuid ON pending_smelting_outputs(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_crafting_uuid ON pending_crafting_outputs(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_queue_uuid ON task_queue(uuid)");
        }
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                // PLUGMAN FIX: Force SQLite flush/checkpoint trước khi đóng
                // Đảm bảo dữ liệu được ghi ra disk khi reload plugin (PlugMan)
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) { /* WAL có thể không bật */ }
                connection.close();
                plugin.debug("SQLite database disconnected");
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
    
    /** Lưu NHIỀU active smelting tasks. entries: List of [recipeId, remainingTicks, totalTicks, batchCount] */
    public void saveAllActiveSmeltingTasks(UUID uuid, java.util.List<Object[]> entries) {
        String del = "DELETE FROM active_smelting_tasks_multi WHERE uuid = ?";
        String ins = "INSERT INTO active_smelting_tasks_multi (uuid, task_index, recipe_id, remaining_time, total_duration, batch_count, started_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            try (PreparedStatement stmt = connection.prepareStatement(del)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }
            if (entries != null && !entries.isEmpty()) {
                long now = System.currentTimeMillis();
                try (PreparedStatement stmt = connection.prepareStatement(ins)) {
                    for (int i = 0; i < entries.size(); i++) {
                        Object[] e = entries.get(i);
                        stmt.setString(1, uuid.toString());
                        stmt.setInt(2, i);
                        stmt.setString(3, (String) e[0]);
                        stmt.setInt(4, ((Number) e[1]).intValue());
                        stmt.setInt(5, ((Number) e[2]).intValue());
                        stmt.setInt(6, (Integer) e[3]);
                        stmt.setLong(7, now);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save smelting tasks: " + e.getMessage());
        }
    }

    public java.util.List<Object[]> loadAllActiveSmeltingTasks(UUID uuid) {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        String sql = "SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_smelting_tasks_multi WHERE uuid = ? ORDER BY task_index";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getInt(5)});
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load smelting tasks: " + e.getMessage());
        }
        return out;
    }

    public void clearAllActiveSmeltingTasks(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM active_smelting_tasks_multi WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear smelting tasks: " + e.getMessage());
        }
    }

    public void saveActiveSmeltingTask(UUID uuid, String recipeId, int remainingTime, int totalDuration, int batchCount) {
        saveAllActiveSmeltingTasks(uuid, java.util.Collections.singletonList(new Object[]{recipeId, remainingTime, totalDuration, batchCount}));
    }
    
    public String[] loadActiveSmeltingTask(UUID uuid) {
        java.util.List<Object[]> all = loadAllActiveSmeltingTasks(uuid);
        if (all.isEmpty()) {
            String[] leg = loadActiveSmeltingTaskLegacy(uuid);
            if (leg != null) {
                saveAllActiveSmeltingTasks(uuid, java.util.Collections.singletonList(new Object[]{leg[0], Integer.parseInt(leg[1]), Integer.parseInt(leg[2]), Integer.parseInt(leg[4])}));
                return leg;
            }
            return null;
        }
        Object[] first = all.get(0);
        return new String[]{(String)first[0], String.valueOf(first[1]), String.valueOf(first[2]), String.valueOf(first[3]), String.valueOf(first[4])};
    }
    
    private String[] loadActiveSmeltingTaskLegacy(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_smelting_tasks WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), String.valueOf(rs.getInt(2)), String.valueOf(rs.getInt(3)), String.valueOf(rs.getLong(4)), String.valueOf(rs.getInt(5))};
                }
            }
        } catch (SQLException e) { }
        return null;
    }
    
    public void clearActiveSmeltingTask(UUID uuid) {
        clearAllActiveSmeltingTasks(uuid);
    }
    
    /**
     * Get all active smelting tasks (for server shutdown handling)
     */
    /**
     * Load all active smelting tasks (for server shutdown/recovery).
     * Returns map: UUID -> [recipeId, remainingTime, totalDuration, startedAt, batchCount]
     */
    public java.util.Map<UUID, String[]> loadAllActiveSmeltingTasks() {
        java.util.Map<UUID, String[]> tasks = new HashMap<>();
        String sql = "SELECT uuid, recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_smelting_tasks";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                tasks.put(uuid, new String[]{
                    rs.getString("recipe_id"),
                    String.valueOf(rs.getInt("remaining_time")),
                    String.valueOf(rs.getInt("total_duration")),
                    String.valueOf(rs.getLong("started_at")),
                    String.valueOf(rs.getInt("batch_count"))
                });
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("batch_count")) {
                plugin.debug("Falling back to loadAllActiveSmeltingTasks without batch_count (old DB)");
                loadAllActiveSmeltingTasksLegacy(tasks);
            } else {
                plugin.getLogger().severe("Failed to load all active smelting tasks: " + e.getMessage());
            }
        }
        
        return tasks;
    }
    
    private void loadAllActiveSmeltingTasksLegacy(java.util.Map<UUID, String[]> tasks) {
        String sql = "SELECT uuid, recipe_id, remaining_time, total_duration, started_at FROM active_smelting_tasks";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                tasks.put(uuid, new String[]{
                    rs.getString("recipe_id"),
                    String.valueOf(rs.getInt("remaining_time")),
                    String.valueOf(rs.getInt("total_duration")),
                    String.valueOf(rs.getLong("started_at")),
                    "1"
                });
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to load active smelting tasks (legacy): " + ex.getMessage());
        }
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
     * Lưu NHIỀU active crafting tasks (hỗ trợ song song). entries: List of [recipeId, remainingTicks, totalTicks, batchCount]
     */
    public void saveAllActiveCraftingTasks(UUID uuid, java.util.List<Object[]> entries) {
        String del = "DELETE FROM active_crafting_tasks_multi WHERE uuid = ?";
        String ins = "INSERT INTO active_crafting_tasks_multi (uuid, task_index, recipe_id, remaining_time, total_duration, batch_count, started_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            try (PreparedStatement stmt = connection.prepareStatement(del)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }
            if (entries != null && !entries.isEmpty()) {
                long now = System.currentTimeMillis();
                try (PreparedStatement stmt = connection.prepareStatement(ins)) {
                    for (int i = 0; i < entries.size(); i++) {
                        Object[] e = entries.get(i);
                        stmt.setString(1, uuid.toString());
                        stmt.setInt(2, i);
                        stmt.setString(3, (String) e[0]);
                        stmt.setInt(4, ((Number) e[1]).intValue());
                        stmt.setInt(5, ((Number) e[2]).intValue());
                        stmt.setInt(6, (Integer) e[3]);
                        stmt.setLong(7, now);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save crafting tasks: " + e.getMessage());
        }
    }

    /**
     * Load tất cả active crafting tasks. Returns List of [recipeId, remainingTime, totalDuration, startedAt, batchCount]
     */
    public java.util.List<Object[]> loadAllActiveCraftingTasks(UUID uuid) {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        String sql = "SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_crafting_tasks_multi WHERE uuid = ? ORDER BY task_index";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[]{
                        rs.getString("recipe_id"),
                        rs.getInt("remaining_time"),
                        rs.getInt("total_duration"),
                        rs.getLong("started_at"),
                        rs.getInt("batch_count")
                    });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load crafting tasks: " + e.getMessage());
        }
        return out;
    }

    public void clearAllActiveCraftingTasks(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM active_crafting_tasks_multi WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear crafting tasks: " + e.getMessage());
        }
    }

    /**
     * Save active crafting task (legacy - chỉ 1 task, dùng cho restore offline)
     */
    public void saveActiveCraftingTask(UUID uuid, String recipeId, int remainingTime, int totalDuration, int batchCount) {
        saveAllActiveCraftingTasks(uuid, java.util.Collections.singletonList(new Object[]{recipeId, remainingTime, totalDuration, batchCount}));
    }
    
    /** Load task đầu tiên — tương thích. Dùng loadAllActiveCraftingTasks để lấy tất cả. */
    public String[] loadActiveCraftingTask(UUID uuid) {
        java.util.List<Object[]> all = loadAllActiveCraftingTasks(uuid);
        if (all.isEmpty()) {
            Object[] leg = loadActiveCraftingTaskLegacy(uuid);
            if (leg != null) {
                saveAllActiveCraftingTasks(uuid, java.util.Collections.singletonList(new Object[]{leg[0], Integer.parseInt((String)leg[1]), Integer.parseInt((String)leg[2]), Integer.parseInt((String)leg[4])}));
                return (String[]) leg;
            }
            return null;
        }
        Object[] first = all.get(0);
        return new String[]{(String)first[0], String.valueOf(first[1]), String.valueOf(first[2]), String.valueOf(first[3]), String.valueOf(first[4])};
    }
    
    private String[] loadActiveCraftingTaskLegacy(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT recipe_id, remaining_time, total_duration, started_at, batch_count FROM active_crafting_tasks WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), String.valueOf(rs.getInt(2)), String.valueOf(rs.getInt(3)), String.valueOf(rs.getLong(4)), String.valueOf(rs.getInt(5))};
                }
            }
        } catch (SQLException e) { /* old table có thể không tồn tại */ }
        return null;
    }
    
    public void clearActiveCraftingTask(UUID uuid) {
        clearAllActiveCraftingTasks(uuid);
    }
    
    /** Lưu hàng chờ craft khi player quit. entries: List of [recipeId, batchCount] */
    public void saveCraftingQueue(UUID uuid, java.util.List<Object[]> entries) {
        String del = "DELETE FROM crafting_queue WHERE uuid = ?";
        String ins = "INSERT INTO crafting_queue (uuid, slot_index, recipe_id, batch_count) VALUES (?, ?, ?, ?)";
        try {
            try (PreparedStatement stmt = connection.prepareStatement(del)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }
            if (entries != null && !entries.isEmpty()) {
                try (PreparedStatement stmt = connection.prepareStatement(ins)) {
                    for (int i = 0; i < entries.size(); i++) {
                        Object[] e = entries.get(i);
                        stmt.setString(1, uuid.toString());
                        stmt.setInt(2, i);
                        stmt.setString(3, (String) e[0]);
                        stmt.setInt(4, (Integer) e[1]);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save crafting queue: " + e.getMessage());
        }
    }
    
    /** Lưu hàng chờ thống nhất. entries: List of [type, recipeId, batchCount] */
    public void saveUnifiedQueue(UUID uuid, java.util.List<Object[]> entries) {
        String del = "DELETE FROM task_queue WHERE uuid = ?";
        String ins = "INSERT INTO task_queue (uuid, slot_index, task_type, recipe_id, batch_count) VALUES (?, ?, ?, ?, ?)";
        try {
            try (PreparedStatement stmt = connection.prepareStatement(del)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }
            if (entries != null && !entries.isEmpty()) {
                try (PreparedStatement stmt = connection.prepareStatement(ins)) {
                    for (int i = 0; i < entries.size(); i++) {
                        Object[] e = entries.get(i);
                        stmt.setString(1, uuid.toString());
                        stmt.setInt(2, i);
                        stmt.setString(3, (String) e[0]);
                        stmt.setString(4, (String) e[1]);
                        stmt.setInt(5, (Integer) e[2]);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save task queue: " + e.getMessage());
        }
    }

    /** Load hàng chờ thống nhất. Returns List of [type, recipeId, batchCount]. Migrate từ crafting_queue+smelting_queue nếu task_queue rỗng. */
    public java.util.List<Object[]> loadUnifiedQueue(UUID uuid) {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        String sql = "SELECT task_type, recipe_id, batch_count FROM task_queue WHERE uuid = ? ORDER BY slot_index";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[]{ rs.getString("task_type"), rs.getString("recipe_id"), rs.getInt("batch_count") });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load task queue: " + e.getMessage());
        }
        if (out.isEmpty()) migrateOldQueuesToUnified(uuid, out);
        return out;
    }

    private void migrateOldQueuesToUnified(UUID uuid, java.util.List<Object[]> out) {
        try {
            try (PreparedStatement st = connection.prepareStatement("SELECT recipe_id, batch_count FROM crafting_queue WHERE uuid = ? ORDER BY slot_index")) {
                st.setString(1, uuid.toString());
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) out.add(new Object[]{ "CRAFT", rs.getString("recipe_id"), rs.getInt("batch_count") });
                }
            }
            try (PreparedStatement st = connection.prepareStatement("SELECT recipe_id, batch_count FROM smelting_queue WHERE uuid = ? ORDER BY slot_index")) {
                st.setString(1, uuid.toString());
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) out.add(new Object[]{ "SMELT", rs.getString("recipe_id"), rs.getInt("batch_count") });
                }
            }
            if (!out.isEmpty()) {
                saveUnifiedQueue(uuid, out);
                try (PreparedStatement del1 = connection.prepareStatement("DELETE FROM crafting_queue WHERE uuid = ?");
                     PreparedStatement del2 = connection.prepareStatement("DELETE FROM smelting_queue WHERE uuid = ?")) {
                    del1.setString(1, uuid.toString());
                    del2.setString(1, uuid.toString());
                    del1.executeUpdate();
                    del2.executeUpdate();
                } catch (SQLException ignored) {}
            }
        } catch (SQLException ignored) {}
    }

    public void clearUnifiedQueue(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM task_queue WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear task queue: " + e.getMessage());
        }
    }
    
    /** @deprecated Dùng loadUnifiedQueue, filter CRAFT */
    public java.util.List<Object[]> loadCraftingQueue(UUID uuid) {
        java.util.List<Object[]> all = loadUnifiedQueue(uuid);
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        for (Object[] e : all) {
            if ("CRAFT".equals(e[0])) out.add(new Object[]{ e[1], e[2] });
        }
        return out;
    }
    
    /** Lưu hàng chờ smelt khi player quit. entries: List of [recipeId, batchCount] */
    public void saveSmeltingQueue(UUID uuid, java.util.List<Object[]> entries) {
        String del = "DELETE FROM smelting_queue WHERE uuid = ?";
        String ins = "INSERT INTO smelting_queue (uuid, slot_index, recipe_id, batch_count) VALUES (?, ?, ?, ?)";
        try {
            try (PreparedStatement stmt = connection.prepareStatement(del)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }
            if (entries != null && !entries.isEmpty()) {
                try (PreparedStatement stmt = connection.prepareStatement(ins)) {
                    for (int i = 0; i < entries.size(); i++) {
                        Object[] e = entries.get(i);
                        stmt.setString(1, uuid.toString());
                        stmt.setInt(2, i);
                        stmt.setString(3, (String) e[0]);
                        stmt.setInt(4, (Integer) e[1]);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save smelting queue: " + e.getMessage());
        }
    }
    
    /** Xóa hàng chờ smelt khỏi DB (sau khi đã restore vào memory) */
    public void clearSmeltingQueue(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM smelting_queue WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to clear smelting queue: " + e.getMessage());
        }
    }
    
    /** Load hàng chờ smelt. Returns List of [recipeId, batchCount] theo thứ tự. */
    public java.util.List<Object[]> loadSmeltingQueue(UUID uuid) {
        java.util.List<Object[]> out = new java.util.ArrayList<>();
        String sql = "SELECT recipe_id, batch_count FROM smelting_queue WHERE uuid = ? ORDER BY slot_index";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[]{ rs.getString("recipe_id"), rs.getInt("batch_count") });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load smelting queue: " + e.getMessage());
        }
        return out;
    }
    
    /**
     * ISSUE-004 FIX: Deprecated - không expose connection thô để tránh resource leak.
     * Sử dụng các method public của SQLiteDatabase thay thế.
     * @deprecated Sẽ bị xóa trong phiên bản tương lai
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public Connection getConnection() {
        return connection;
    }
}
