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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Level;

public class SkinHub extends JavaPlugin implements CommandExecutor {

    private SkinsRestorer skinsRestorer;
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

        // Matikan logging SLF4J agar tidak spam
        try {
            Class.forName("org.slf4j.impl.SimpleLogger");
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", getConfig().getBoolean("debug", false) ? "debug" : "warn");
        } catch (ClassNotFoundException ignored) { }

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

        // 3. Inisialisasi SkinManager SEMENTARA
        this.skinManager = new SkinManager(this, storage, null, null);

        // 4. Setup SkinsRestorer dengan cara benar
        if (!setupSkinsRestorer()) {
            getLogger().severe("SkinsRestorer not found or API is unavailable. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.skinManager = new SkinManager(this, storage, skinsRestorer, null);

        // 5. Inisialisasi Web Server
        this.webServer = new WebServer(this, pinManager, skinManager);

        // 6. Mulai Web Server (Async)
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

    // Getters untuk dependency WebServer
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
