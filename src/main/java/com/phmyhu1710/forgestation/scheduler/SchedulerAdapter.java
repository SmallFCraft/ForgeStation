package com.phmyhu1710.forgestation.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Scheduler abstraction for Folia/Spigot compatibility
 */
public interface SchedulerAdapter {
    
    /**
     * Run task on main thread (or entity's thread in Folia)
     */
    void runSync(Runnable task);
    
    /**
     * Run task asynchronously
     */
    void runAsync(Runnable task);
    
    /**
     * Run task after delay
     * @param delayTicks delay in ticks
     */
    void runLater(Runnable task, long delayTicks);
    
    /**
     * Run task async after delay
     */
    void runLaterAsync(Runnable task, long delayTicks);
    
    /**
     * Run repeating task
     * @param delayTicks initial delay
     * @param periodTicks period between runs
     * @return task id for cancellation
     */
    int runTimer(Runnable task, long delayTicks, long periodTicks);
    
    /**
     * Run task on entity's thread (Folia) or main thread (Bukkit)
     */
    void runAtEntity(Entity entity, Runnable task);
    
    /**
     * Run task on location's region thread (Folia) or main thread (Bukkit)
     */
    void runAtLocation(Location location, Runnable task);
    
    /**
     * Cancel a task by id
     */
    void cancelTask(int taskId);
}
