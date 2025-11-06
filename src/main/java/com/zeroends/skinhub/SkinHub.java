package com.zeroends.skinhub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public class SkinHub extends JavaPlugin implements CommandExecutor {

    private SkinsRestorer skinsRestorer;
    private Storage storage;
    private PinManager pinManager;
    private SkinManager skinManager;
    private WebServer webServer;
    private int webPort;

    // Task ID untuk autosave
    private int autosaveTaskId = -1;

    @Override
    public void onEnable() {
        // 1. Setup Konfigurasi
        saveDefaultConfig();
        this.webPort = getConfig().getInt("web.port", 8123);

        // Set SimpleLogger level jika tersedia (perhatikan kemungkinan relocation)
        try {
            boolean debug = getConfig().getBoolean("debug", false);
            try {
                Class.forName("org.slf4j.impl.SimpleLogger");
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", debug ? "debug" : "warn");
            } catch (ClassNotFoundException e) {
                // Coba versi yang di-relocate oleh shading
                Class.forName("com.zeroends.skinhub.libs.slf4j.impl.SimpleLogger");
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", debug ? "debug" : "warn");
            }
        } catch (ClassNotFoundException ignored) {
            // Jika tidak ada SimpleLogger, Javalin akan memberi saran di log, non-fatal
        }

        // 2. Setup Tools
        Gson gson = new GsonBuilder().create();
        this.storage = new Storage(this, gson);
        this.pinManager = new PinManager(this);

        // Muat data skin
        if (!storage.loadData()) {
            getLogger().severe("Failed to load skin data. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Inisialisasi SkinManager sementara (nullsafe)
        this.skinManager = new SkinManager(this, storage, null, null);

        // 4. Setup SkinsRestorer dengan cara benar (v15+)
        if (!setupSkinsRestorer()) {
            getLogger().severe("SkinsRestorer not found or API is unavailable. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.skinManager = new SkinManager(this, storage, skinsRestorer, null);

        // 5. Inisialisasi Web Server
        this.webServer = new WebServer(this, pinManager, skinManager);

        // 6. Mulai Web Server
        try {
            webServer.start();
            getLogger().info("Web server started successfully on port " + webPort);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start Web Server on port " + webPort, e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 7. Register Command
        PluginCommand pinCommand = getCommand("skinhub");
        if (pinCommand != null) {
            pinCommand.setExecutor(this);
        } else {
            getLogger().severe("Command 'skinhub' not found! Check plugin.yml.");
        }

        // 8. Jadwalkan autosave sesuai config (asinkron)
        int saveIntervalMin = Math.max(1, getConfig().getInt("storage.save-interval-minutes", 15));
        long periodTicks = saveIntervalMin * 60L * 20L;
        this.autosaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!storage.saveData()) {
                getLogger().warning("Autosave failed: could not save skins.json");
            } else {
                logDebug("Autosave completed.");
            }
        }, periodTicks, periodTicks).getTaskId();
        logDebug("Autosave scheduled every " + saveIntervalMin + " minute(s).");
    }

    @Override
    public void onDisable() {
        // Batalkan autosave dan simpan terakhir kali
        try {
            if (autosaveTaskId != -1) {
                Bukkit.getScheduler().cancelTask(autosaveTaskId);
                autosaveTaskId = -1;
            }
        } catch (Exception ignored) {}
        try {
            if (storage != null) {
                storage.saveData();
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error while saving data on shutdown", e);
        }

        // Pastikan web server berhenti agar port dilepas
        try {
            if (webServer != null) {
                webServer.stop();
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error while stopping web server", e);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("pin")) {
            if (sender instanceof Player player) {
                String pin = pinManager.getOrCreatePin(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "PIN kamu: " + ChatColor.YELLOW + pin);
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + "Command ini hanya untuk pemain.");
                return true;
            }
        }
        sender.sendMessage(ChatColor.AQUA + "Perintah SkinHub (gunakan /skinhub pin untuk melihat PIN Anda)");
        return true;
    }

    // Tambahkan logDebug untuk debug
    public void logDebug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    // Getters
    public SkinManager getSkinManager() { return skinManager; }
    public int getWebPort() { return webPort; }

    private boolean setupSkinsRestorer() {
        try {
            this.skinsRestorer = SkinsRestorerProvider.get();
            if (this.skinsRestorer == null) {
                getLogger().severe("SkinsRestorer API instance is null!");
                return false;
            }
            getLogger().info("SkinsRestorer API found and initialized using SkinsRestorerProvider.");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Tidak dapat menginisialisasi SkinsRestorer API", e);
            return false;
        }
    }
}
