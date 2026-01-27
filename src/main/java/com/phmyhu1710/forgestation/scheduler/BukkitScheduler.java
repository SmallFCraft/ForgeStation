package com.phmyhu1710.forgestation.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit/Spigot scheduler implementation
 */
public class BukkitScheduler implements SchedulerAdapter {

    private final Plugin plugin;
    private final Map<Integer, BukkitTask> tasks = new ConcurrentHashMap<>();

    public BukkitScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void runLaterAsync(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    @Override
    public int runTimer(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        tasks.put(bukkitTask.getTaskId(), bukkitTask);
        return bukkitTask.getTaskId();
    }

    @Override
    public void runAtEntity(Entity entity, Runnable task) {
        // In Bukkit, just run on main thread
        runSync(task);
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        // In Bukkit, just run on main thread
        runSync(task);
    }

    @Override
    public void cancelTask(int taskId) {
        BukkitTask task = tasks.remove(taskId);
        if (task != null) {
            task.cancel();
        } else {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }
}
