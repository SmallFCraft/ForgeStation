package com.phmyhu1710.forgestation.player;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import com.phmyhu1710.forgestation.database.SQLiteDatabase;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages player data (upgrades, cooldowns, etc.) using SQLite
 */
public class PlayerDataManager implements Listener {

    private final ForgeStationPlugin plugin;
    private final SQLiteDatabase database;
    
    // Cache player data in memory
    private final Cache<UUID, PlayerData> playerCache;
    
    private int cleanCooldownTaskId = -1;
    
    private final ExecutorService dbExecutor;
    
    private final BlockingQueue<Runnable> writeQueue;
    private final AtomicBoolean dbWorkerRunning;
    private Thread dbWorkerThread;
    private int flushTaskId = -1;

    public PlayerDataManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
        this.database = new SQLiteDatabase(plugin);
        this.database.connect();
        
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ForgeStation-DB");
            t.setDaemon(true);
            return t;
        });
        this.writeQueue = new LinkedBlockingQueue<>();
        this.dbWorkerRunning = new AtomicBoolean(true);
        
        // Start DB worker thread để process write queue
        startDbWorker();
        
        // Setup cache with configurable size
        int maxCache = plugin.getConfigManager().getMainConfig().getInt("performance.max-player-cache", 1000);
        this.playerCache = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(maxCache)
            .build();
        
        // Register listener for cleanup
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        cleanCooldownTaskId = plugin.getScheduler().runTimer(() -> {
            dbExecutor.execute(() -> database.cleanExpiredCooldowns());
        }, 6000, 6000); // Every 5 minutes
        
        // Schedule periodic flush của write queue (mỗi 3 giây)
        flushTaskId = plugin.getScheduler().runTimer(this::flushWriteQueue, 60, 60); // 3 seconds
    }
    
    private void startDbWorker() {
        dbWorkerThread = new Thread(() -> {
            while (dbWorkerRunning.get() || !writeQueue.isEmpty()) {
                try {
                    Runnable task = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("DB write error: " + e.getMessage());
                }
            }
        }, "ForgeStation-DBWriter");
        dbWorkerThread.setDaemon(true);
        dbWorkerThread.start();
    }
    
    private void flushWriteQueue() {
        // Queue sẽ tự được process bởi worker thread
        // Method này chỉ để debug/monitoring
        int size = writeQueue.size();
        if (size > 100) {
            plugin.debug("Write queue size: " + size + " (consider increasing flush frequency)");
        }
    }
    
    public void enqueueWrite(Runnable writeTask) {
        writeQueue.offer(writeTask);
    }

    public PlayerData getPlayerData(Player player) {
        return getPlayerData(player.getUniqueId());
    }

    public PlayerData getPlayerData(UUID uuid) {
        return playerCache.get(uuid, this::loadPlayerData);
    }

    private PlayerData loadPlayerData(UUID uuid) {
        // ISSUE-003/004 FIX: Pass manager reference để PlayerData có thể enqueue writes
        PlayerData data = new PlayerData(uuid, database, this);
        
        // Load from database
        Map<String, Integer> upgrades = database.loadUpgrades(uuid);
        for (Map.Entry<String, Integer> entry : upgrades.entrySet()) {
            data.setUpgradeLevelSilent(entry.getKey(), entry.getValue());
        }
        
        Map<String, Long> cooldowns = database.loadCooldowns(uuid);
        for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
            data.setCooldownSilent(entry.getKey(), entry.getValue());
        }
        
        data.setDirty(false);
        return data;
    }

    public void savePlayerData(UUID uuid) {
        PlayerData data = playerCache.getIfPresent(uuid);
        if (data == null) return;
        
        // SQLite saves immediately on each change, so just mark as clean
        data.setDirty(false);
    }

    public void saveAll() {
        playerCache.asMap().keySet().forEach(this::savePlayerData);
    }
    
    public void shutdown() {
        // ISSUE-005 FIX: Cancel tasks trước khi shutdown
        if (cleanCooldownTaskId != -1) {
            plugin.getScheduler().cancelTask(cleanCooldownTaskId);
            cleanCooldownTaskId = -1;
        }
        if (flushTaskId != -1) {
            plugin.getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        

        dbWorkerRunning.set(false);
        
        // Wait for queue to drain (max 5 seconds)
        long start = System.currentTimeMillis();
        while (!writeQueue.isEmpty() && System.currentTimeMillis() - start < 5000) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Shutdown executor
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
        }
        
        saveAll();
        database.disconnect();
    }
    
    /**
     * SMELTING PERSISTENCE FIX: Getter cho database để SmeltingManager có thể access
     */
    public SQLiteDatabase getDatabase() {
        return database;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        
        // Save and remove from cache
        savePlayerData(uuid);
        playerCache.invalidate(uuid);
    }

    /**
     * Player data container
   
     */
    public static class PlayerData {
        private final UUID uuid;
        private final SQLiteDatabase database;
        private final PlayerDataManager manager;
        private final Map<String, Integer> upgradeLevels = new HashMap<>();
        private final Map<String, Long> cooldowns = new HashMap<>();
        private boolean dirty = false;

        public PlayerData(UUID uuid, SQLiteDatabase database, PlayerDataManager manager) {
            this.uuid = uuid;
            this.database = database;
            this.manager = manager;
        }

        public UUID getUuid() {
            return uuid;
        }

        public int getUpgradeLevel(String upgradeId) {
            return upgradeLevels.getOrDefault(upgradeId, 0);
        }

        public void setUpgradeLevel(String upgradeId, int level) {
            upgradeLevels.put(upgradeId, level);
            dirty = true;

            final UUID playerUuid = uuid;
            final String upId = upgradeId;
            final int lvl = level;
            manager.enqueueWrite(() -> database.saveUpgrade(playerUuid, upId, lvl));
        }
        
        // Silent version for loading from database
        void setUpgradeLevelSilent(String upgradeId, int level) {
            upgradeLevels.put(upgradeId, level);
        }

        public Map<String, Integer> getUpgradeLevels() {
            return new HashMap<>(upgradeLevels);
        }

        public boolean isOnCooldown(String recipeId) {
            Long expiry = cooldowns.get(recipeId);
            return expiry != null && expiry > System.currentTimeMillis();
        }

        public long getCooldownRemaining(String recipeId) {
            Long expiry = cooldowns.get(recipeId);
            if (expiry == null) return 0;
            return Math.max(0, expiry - System.currentTimeMillis());
        }

        public void setCooldown(String recipeId, long expiryTime) {
            cooldowns.put(recipeId, expiryTime);
            dirty = true;
          
            final UUID playerUuid = uuid;
            final String rId = recipeId;
            final long exp = expiryTime;
            manager.enqueueWrite(() -> database.saveCooldown(playerUuid, rId, exp));
        }
        
        // Silent version for loading from database
        void setCooldownSilent(String recipeId, long expiryTime) {
            cooldowns.put(recipeId, expiryTime);
        }

        public void setCooldownSeconds(String recipeId, int seconds) {
            setCooldown(recipeId, System.currentTimeMillis() + (seconds * 1000L));
        }

        public Map<String, Long> getCooldowns() {
            return new HashMap<>(cooldowns);
        }

        public boolean isDirty() {
            return dirty;
        }

        public void setDirty(boolean dirty) {
            this.dirty = dirty;
        }
    }
}
