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

// Import Mineskin
// import org.mineskin.MineSkinClient; DIHAPUS

public class SkinHub extends JavaPlugin implements CommandExecutor {

    private SkinsRestorer skinsRestorerApi;
    private Storage storage;
    private PinManager pinManager;
    private SkinManager skinManager; // JANGAN DIHAPUS
    private WebServer webServer;
    // MineSkinClient mineskinClient; DIHAPUS
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
        
        // 3. Inisialisasi SkinManager SEMENTARA (agar tidak ada error ClassNotFound)
        this.skinManager = new SkinManager(this, storage, null, null); 
        
        // 4. Setup SkinsRestorer
        if (!setupSkinsRestorer()) {
            getLogger().severe("SkinsRestorer not found or API is unavailable. Shutting down.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // PERBAIKAN: Setelah SkinsRestorerApi berhasil diambil, buat ulang SkinManager dengan API yang sudah ada
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
        PluginCommand pinCommand = getCommand("skin");
        if (pinCommand != null) {
            pinCommand.setExecutor(this); 
        } else {
             getLogger().severe("Command 'skin' not found! Check plugin.yml.");
        }
    
        // Test apply (Opsional: untuk debug)
        if (getConfig().getBoolean("debug", false)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                 if (Bukkit.getOnlinePlayers().isEmpty()) return;
                 Player p = Bukkit.getOnlinePlayers().iterator().next();
                 getLogger().info("Debug: Simulating skin apply test for " + p.getName());
            }, 100L);
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (storage != null) {
            storage.saveData();
            getLogger().info("Skin data saved.");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return true;
        }
        
        if (args.length > 0 && args[0].equalsIgnoreCase("pin")) {
            String pin = pinManager.generatePin(player.getUniqueId(), player.getName());
            
            player.sendMessage(ChatColor.GREEN + "========================================");
            player.sendMessage(ChatColor.AQUA + "           Your SkinHub PIN");
            player.sendMessage(ChatColor.WHITE + "  PIN Anda: " + ChatColor.YELLOW + ChatColor.BOLD + pin);
            player.sendMessage(ChatColor.GRAY + "  Buka browser dan masukkan username + PIN di:");
            player.sendMessage(ChatColor.YELLOW + "  (URL Server) :" + webPort);
            player.sendMessage(ChatColor.GRAY + "  PIN ini berlaku selama 10 menit.");
            player.sendMessage(ChatColor.GREEN + "========================================");
            
            return true;
        }
        
        player.sendMessage(ChatColor.YELLOW + "Usage: /skin pin" + ChatColor.GRAY + " - Dapatkan PIN untuk login ke web.");
        return true;
    }

    private boolean setupSkinsRestorer() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        
        try {
            skinsRestorerApi = net.skinsrestorer.api.SkinsRestorerProvider.get();
            getLogger().info("SkinsRestorer API v" + skinsRestorerApi.getVersion() + " found.");
            return true;
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Getter untuk SkinManager, diperlukan oleh WebServer.
     */
    public SkinManager getSkinManager() {
        return skinManager;
    }

    public void logDebug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public int getWebPort() {
        return webPort;
    }
}
