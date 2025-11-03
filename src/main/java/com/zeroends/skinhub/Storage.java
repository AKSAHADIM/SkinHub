package com.zeroends.skinhub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Storage {

    private final SkinHub plugin;
    private final Gson gson;
    private final File dataFile;
    private final File backupFile;

    // Kunci: UUID Pemain, Nilai: DataPemain (termasuk koleksi skin)
    private ConcurrentMap<UUID, PlayerData> playerDataMap;

    private static final Type DATA_TYPE = new TypeToken<ConcurrentHashMap<UUID, PlayerData>>() {}.getType();

    public Storage(SkinHub plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.dataFile = new File(plugin.getDataFolder(), "skins.json");
        this.backupFile = new File(plugin.getDataFolder(), "skins.json.bak");
        this.playerDataMap = new ConcurrentHashMap<>();
    }

    /**
     * Memuat data skin dari file skins.json.
     * @return true jika berhasil dimuat atau file tidak ada, false jika gagal.
     */
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
                // File ada tapi kosong atau korup
                this.playerDataMap = new ConcurrentHashMap<>();
                plugin.logDebug("skins.json was empty or malformed. Initializing new map.");
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read skins.json: " + e.getMessage());
            // Coba pulihkan dari backup
            return loadBackup();
        }
    }

    /**
     * Menyimpan data skin ke file skins.json.
     * @return true jika berhasil disimpan, false jika gagal.
     */
    public boolean saveData() {
        // 1. Buat backup file saat ini jika ada
        if (dataFile.exists()) {
            try {
                Files.copy(dataFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.logDebug("Created backup: skins.json.bak");
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create backup file: " + e.getMessage());
            }
        }

        // 2. Tulis data saat ini ke file utama
        try (BufferedWriter writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(playerDataMap, DATA_TYPE, writer);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data to skins.json: " + e.getMessage());
            return false;
        }
    }

    /**
     * Mencoba memuat dari file backup jika file utama gagal.
     */
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
                // Coba simpan data yang berhasil dimuat dari backup ke file utama
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

    /**
     * Mendapatkan data pemain berdasarkan UUID.
     * Jika tidak ada, buat data baru.
     */
    public PlayerData getPlayerData(UUID uuid) {
        return playerDataMap.computeIfAbsent(uuid, k -> new PlayerData());
    }
}
