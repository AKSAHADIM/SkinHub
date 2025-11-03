package com.zeroends.skinhub;

import com.google.gson.Gson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Storage {

    private final SkinHub plugin;
    private final Gson gson;
    private ConcurrentMap<UUID, PlayerData> playerDataMap;
    private final File dataFile;
    private final File backupFile;
    private static final java.lang.reflect.Type DATA_TYPE =
            new com.google.gson.reflect.TypeToken<ConcurrentMap<UUID, PlayerData>>() {}.getType();

    public Storage(SkinHub plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.playerDataMap = new ConcurrentHashMap<>();
        this.dataFile = new File(plugin.getDataFolder(), "skins.json");
        this.backupFile = new File(plugin.getDataFolder(), "skins.json.bak");
    }

    /** Memuat data skin dari file skins.json. */
    public boolean loadData() {
        if (!dataFile.exists()) {
            plugin.logDebug("skins.json not found. A new one will be created on save.");
            return true;
        }

        try (BufferedReader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
            ConcurrentMap<UUID, PlayerData> loadedMap = gson.fromJson(reader, DATA_TYPE);
            if (loadedMap != null) {
                this.playerDataMap = loadedMap;
                plugin.logDebug("Successfully loaded " + playerDataMap.size() + " player data entries.");
            } else {
                this.playerDataMap = new ConcurrentHashMap<>();
                plugin.logDebug("skins.json was empty or malformed. Initializing new map.");
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read skins.json: " + e.getMessage());
            return loadBackup();
        }
    }

    /** Menyimpan data skin ke file skins.json. */
    public boolean saveData() {
        if (dataFile.exists()) {
            try {
                Files.copy(dataFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.logDebug("Created backup: skins.json.bak");
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create backup file: " + e.getMessage());
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(playerDataMap, DATA_TYPE, writer);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data to skins.json: " + e.getMessage());
            return false;
        }
    }

    /** Mencoba memuat dari file backup jika file utama gagal. */
    private boolean loadBackup() {
        if (!backupFile.exists()) {
            plugin.getLogger().severe("skins.json is corrupted and no backup (skins.json.bak) was found.");
            return false;
        }
        plugin.getLogger().warning("Attempting to load data from backup file (skins.json.bak)...");
        try (BufferedReader reader = Files.newBufferedReader(backupFile.toPath(), StandardCharsets.UTF_8)) {
            ConcurrentMap<UUID, PlayerData> loadedMap = gson.fromJson(reader, DATA_TYPE);
            if (loadedMap != null) {
                this.playerDataMap = loadedMap;
                plugin.getLogger().info("Successfully loaded data from backup.");
                saveData();
                return true;
            } else {
                plugin.getLogger().severe("Backup file is also corrupted.");
                return false;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read backup file: " + e.getMessage());
            return false;
        }
    }

    /** Mendapatkan data pemain berdasarkan UUID. */
    public PlayerData getPlayerData(UUID uuid) {
        return playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
    }
}
