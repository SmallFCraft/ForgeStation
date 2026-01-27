package com.phmyhu1710.forgestation.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia scheduler implementation using RegionScheduler
 */
public class FoliaScheduler implements SchedulerAdapter {

    private final Plugin plugin;
    private final Map<Integer, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(0);

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runSync(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
    }

    @Override
    public void runLaterAsync(Runnable task, long delayTicks) {
        long delayMs = delayTicks * 50; // Convert ticks to milliseconds
        Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public int runTimer(Runnable task, long delayTicks, long periodTicks) {
        int taskId = taskIdCounter.incrementAndGet();
        ScheduledTask scheduledTask = Bukkit.getGlobalRegionScheduler()
            .runAtFixedRate(plugin, st -> task.run(), delayTicks, periodTicks);
        tasks.put(taskId, scheduledTask);
        return taskId;
    }

    @Override
    public void runAtEntity(Entity entity, Runnable task) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            runSync(task);
            return;
        }
        Bukkit.getRegionScheduler().run(plugin, location, scheduledTask -> task.run());
    }

    @Override
    public void cancelTask(int taskId) {
        ScheduledTask task = tasks.remove(taskId);
        if (task != null) {
            task.cancel();
        }
    }
}
