package com.zeroends.skinhub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.exception.DataRequestException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public class SkinHub extends JavaPlugin implements CommandExecutor {

    private SkinsRestorer skinsRestorerApi;
    private Storage storage;
    private PinManager pinManager;
    private SkinManager skinManager;
    private WebServer webServer;
    private int webPort;

    @Override
    public void onEnable() {
        // 1. Setup Konfigurasi
        saveDefaultConfig();
        this.webPort = getConfig().getInt("web.port", 8123);

        // Matikan logging SLF4J (MineSkin) agar tidak spam
        try {
            Class.forName("org.slf4j.impl.SimpleLogger");
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", getConfig().getBoolean("debug", false) ? "debug" : "warn");
        } catch (ClassNotFoundException ignored) {
            // Ignore if SimpleLogger is not on classpath
        }

        // 2. Setup Tools
        Gson gson = new GsonBuilder().create();
        this.storage = new Storage(this, gson);
        this.pinManager = new PinManager(this);

        // Muat data sebelum Mineskin Client diinisialisasi
        if (!storage.loadData()) {
            getLogger().severe("Failed to load skin data. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Inisialisasi SkinManager SEMENTARA
        this.skinManager = new SkinManager(this, storage, null, null);

        // 4. Setup SkinsRestorer
        if (!setupSkinsRestorer()) {
            getLogger().severe("SkinsRestorer not found or API is unavailable. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Setelah SkinsRestorerApi berhasil diambil, buat ulang SkinManager dengan API yang sudah ada
        this.skinManager = new SkinManager(this, storage, skinsRestorerApi, null);

        // 5. Inisialisasi Web Server
        this.webServer = new WebServer(this, pinManager, skinManager);

        // 6. Mulai Web Server (Async)
        try {
            webServer.start();
            getLogger().info("Web server started successfully on port " + webPort);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start Web Server on port " + webPort + ". Is the port already in use?", e);
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

        // Debug opsional
        if (getConfig().getBoolean("debug", false)) {
            // Tambahkan pengujian/opsional di sini
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Implementasi eksekusi command /skinhub pin dll
        // Silakan update logic sesuai kebutuhan project
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

    private boolean setupSkinsRestorer() {
        Plugin plugin = getServer().getPluginManager().getPlugin("SkinsRestorer");
        if (plugin == null) {
            return false;
        }
        try {
            this.skinsRestorerApi = (SkinsRestorer) plugin.getClass().getMethod("getApi").invoke(plugin);
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Tidak dapat menginisialisasi SkinsRestorer API", e);
            return false;
        }
    }
}
