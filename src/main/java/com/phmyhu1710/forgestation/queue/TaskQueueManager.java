package com.phmyhu1710.forgestation.queue;

import com.phmyhu1710.forgestation.ForgeStationPlugin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hàng chờ thống nhất cho cả crafting và smelting.
 * Một queue chung — khi đầy slot craft thì có thể thêm smelt vào queue và ngược lại.
 */
public class TaskQueueManager {

    public enum TaskType { CRAFT, SMELT }

    public static final class PendingTask {
        private final TaskType type;
        private final String recipeId;
        private final int batchCount;

        public PendingTask(TaskType type, String recipeId, int batchCount) {
            this.type = type;
            this.recipeId = recipeId;
            this.batchCount = Math.max(1, batchCount);
        }

        public TaskType getType() { return type; }
        public String getRecipeId() { return recipeId; }
        public int getBatchCount() { return batchCount; }
    }

    private final ForgeStationPlugin plugin;
    private final Map<UUID, List<PendingTask>> queue = new ConcurrentHashMap<>();

    public TaskQueueManager(ForgeStationPlugin plugin) {
        this.plugin = plugin;
    }

    public List<PendingTask> getQueue(Player player) {
        List<PendingTask> list = queue.get(player.getUniqueId());
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public boolean addToQueue(Player player, TaskType type, String recipeId, int batchCount) {
        int maxSlots = plugin.getUpgradeManager().getQueueSlotsUnlocked(player);
        List<PendingTask> list = queue.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        if (list.size() >= maxSlots) return false;
        list.add(new PendingTask(type, recipeId, batchCount));
        return true;
    }

    public void removeSlot(Player player, int index) {
        List<PendingTask> list = queue.get(player.getUniqueId());
        if (list == null || index < 0 || index >= list.size()) return;
        list.remove(index);
    }

    /**
     * Promote 1 task cùng type từ queue (khi có slot trống).
     * Tìm item đầu tiên cùng freedType, remove và start.
     */
    public void promoteNext(Player player, TaskType freedType) {
        List<PendingTask> list = queue.get(player.getUniqueId());
        if (list == null || list.isEmpty()) return;

        for (int i = 0; i < list.size(); i++) {
            PendingTask next = list.get(i);
            if (next.getType() != freedType) continue;
            list.remove(i);
            boolean ok = next.getType() == TaskType.CRAFT
                ? plugin.getCraftingExecutor().startFromQueue(player, next.getRecipeId(), next.getBatchCount())
                : plugin.getSmeltingManager().startFromQueue(player, next.getRecipeId(), next.getBatchCount());
            if (!ok) list.add(i, next);
            return;
        }
    }

    public int getQueueSize(Player player) {
        List<PendingTask> list = queue.get(player.getUniqueId());
        return list != null ? list.size() : 0;
    }

    /** Lấy queue thô (để lưu DB, iterate, etc) */
    public Map<UUID, List<PendingTask>> getQueueMap() {
        return queue;
    }

    public void clear() {
        queue.clear();
    }
}
