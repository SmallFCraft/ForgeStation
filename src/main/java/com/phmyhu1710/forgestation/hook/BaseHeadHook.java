package com.phmyhu1710.forgestation.hook;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BaseHeadHook implements ItemHook {

    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    @Override
    public ItemStack getItem(@NotNull String... args) {
        if (args.length == 0) return new ItemStack(Material.PLAYER_HEAD);
        String base64 = args[0];
        return cache.computeIfAbsent(base64, this::createBaseHead).clone();
    }

    @Override
    public boolean itemMatches(@NotNull ItemStack item, @NotNull String... args) {
        return false;
    }

    @Override
    public @NotNull String getPrefix() {
        return "basehead-";
    }

    private ItemStack createBaseHead(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (base64 == null || base64.isEmpty()) return head;

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            
            String decoded = new String(Base64.getDecoder().decode(base64));
            JsonObject json = GSON.fromJson(decoded, JsonObject.class);
            
            if (json.has("textures")) {
                JsonObject texturesObj = json.getAsJsonObject("textures");
                if (texturesObj.has("SKIN")) {
                    JsonObject skinObj = texturesObj.getAsJsonObject("SKIN");
                    if (skinObj.has("url")) {
                        String urlStr = skinObj.get("url").getAsString();
                        textures.setSkin(new URL(urlStr));
                    }
                }
            }
            
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return head;
    }
}
