package com.phmyhu1710.forgestation.hook;

import com.phmyhu1710.forgestation.util.MissingItemUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

public class HeadDatabaseHook implements ItemHook {

    private Object api;
    private Method getItemHeadMethod;

    public HeadDatabaseHook() {
        try {
            Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            this.api = apiClass.getDeclaredConstructor().newInstance();
            this.getItemHeadMethod = apiClass.getMethod("getItemHead", String.class);
        } catch (Exception e) {
            // HDB not loaded
        }
    }

    @Override
    public ItemStack getItem(@NotNull String... args) {
        if (args.length == 0) return MissingItemUtil.create("HeadDatabase", "None");
        String id = args[0];
        
        if (api == null || getItemHeadMethod == null) return MissingItemUtil.create("HeadDatabase", id);
        
        try {
            Object result = getItemHeadMethod.invoke(api, id);
            if (result instanceof ItemStack) {
                return (ItemStack) result;
            }
        } catch (Exception ignored) {}
        
        return MissingItemUtil.create("HeadDatabase", id);
    }
    
    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... args) {
        return false;
    }

    @Override
    public @NotNull String getPrefix() {
        return "hdb-";
    }
}
