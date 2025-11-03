package com.zeroends.skinhub;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level; // <--- PERBAIKAN: Menambahkan import Level

import static io.javalin.apibuilder.ApiBuilder.*;

public class WebServer {

    private final SkinHub plugin;
    private final PinManager pinManager;
    private final SkinManager skinManager;
    private Javalin app;

    public WebServer(SkinHub plugin, PinManager pinManager, SkinManager skinManager) {
        this.plugin = plugin;
        this.pinManager = pinManager;
        this.skinManager = skinManager;
    }

    public void start() {
        // Matikan logging default Javalin yang berisik jika debug false
        // (Logging SLF4J sekarang ditangani melalui System.setProperty di SkinHub.java)

        this.app = Javalin.create(config -> {
            // Sajikan file statis (HTML/CSS/JS) dari dalam JAR (folder resources/web)
            config.staticFiles.add("/web", Location.CLASSPATH);
            
            // Konfigurasi ukuran file upload
            long maxFileSize = plugin.getConfig().getLong("skin-management.max-file-size-kb", 1024) * 1024;
            config.http.maxRequestSize = maxFileSize + 1024; 
            
        }).routes(() -> { 
            // Halaman utama
            get("/", ctx -> ctx.redirect("/index.html"));

            // API Endpoint
            path("api", () -> {
                // POST /api/login
                post("login", this::handleLogin);
                
                // POST /api/logout
                post("logout", this::handleLogout);

                // Grup endpoint yang memerlukan otentikasi
                path("dashboard", () -> {
                    before("/*", this::authenticate); // Middleware dipasang sebelum handler dashboard
                    
                    // GET /api/dashboard/data
                    get("data", this::handleDashboardData);
                    // POST /api/dashboard/upload
                    post("upload", this::handleUpload);
                    // POST /api/dashboard/apply
                    post("apply", this::handleApplySkin);
                    // POST /api/dashboard/delete
                    post("delete", this::handleDeleteSkin);
                });
            });

        }).start(plugin.getWebPort());
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /**
     * Middleware untuk memvalidasi token sesi.
     * Jika gagal, respons status 401 dan mengakhiri request.
     */
    private void authenticate(Context ctx) {
        String token = ctx.cookie("skinhub_session");
        PinManager.UserInfo userInfo = pinManager.validateSession(token);

        if (userInfo == null) {
            plugin.logDebug("Auth failed for token: " + token);
            // Mengirim respons 401 dan mengakhiri eksekusi chain
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Session expired or invalid. Please login again."));
            ctx.res().setHeader("Connection", "close"); // Optional: sinyal ke Javalin untuk menutup koneksi jika perlu
            // NOTE: Di Javalin v5+, mengeset status/body di middleware dan return akan menghentikan chain.
            // Kita tidak bisa menggunakan ctx.skipRemainingHandlers() karena tidak ada. Kita andalkan return.
            return; 
        } else {
            // Simpan info pengguna di atribut konteks agar bisa diakses handler lain
            ctx.attribute("userInfo", userInfo);
            plugin.logDebug("Auth success for user: " + userInfo.username());
        }
    }

    /**
     * Handler untuk POST /api/login
     */
    private void handleLogin(Context ctx) {
        LoginRequest request = ctx.bodyAsClass(LoginRequest.class);
        String token = pinManager.validatePin(request.pin(), request.username());

        if (token != null) {
            // Set cookie sesi
            long maxAgeDays = plugin.getConfig().getLong("web.session-expiry-days", 30);
            ctx.cookie("skinhub_session", token, (int) (maxAgeDays * 24 * 60 * 60));
            ctx.json(Map.of("success", true, "message", "Login successful."));
        } else {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Invalid username or PIN."));
        }
    }

    /**
     * Handler untuk POST /api/logout
     */
    private void handleLogout(Context ctx) {
        String token = ctx.cookie("skinhub_session");
        if (token != null) {
            pinManager.invalidateSession(token);
        }
        ctx.removeCookie("skinhub_session");
        ctx.json(Map.of("success", true, "message", "Logged out."));
    }

    /**
     * Handler untuk GET /api/dashboard/data
     */
    private void handleDashboardData(Context ctx) {
        PinManager.UserInfo userInfo = ctx.attribute("userInfo");
        List<PlayerData.SkinInfo> skins = plugin.getSkinManager().getSkinCollection(userInfo.uuid()); // Mengganti skinManager langsung

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("username", userInfo.username());
        response.put("skins", skins);
        response.put("maxSkins", plugin.getConfig().getInt("skin-management.max-skins", 5));
        
        ctx.json(response);
    }

    /**
     * Handler untuk POST /api/dashboard/apply
     */
    private void handleApplySkin(Context ctx) {
        PinManager.UserInfo userInfo = ctx.attribute("userInfo");
        ApplyDeleteRequest request;
        try {
            request = ctx.bodyAsClass(ApplyDeleteRequest.class);
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", "Invalid request."));
            return;
        }

        plugin.getSkinManager().applySkin(userInfo.uuid(), request.skinId()).thenAccept(success -> { // Mengganti skinManager langsung
            if (success) {
                ctx.json(Map.of("success", true, "message", "Skin applied successfully!"));
            } else {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("success", false, "message", "Failed to apply skin."));
            }
        });
    }

    /**
     * Handler untuk POST /api/dashboard/delete
     */
    private void handleDeleteSkin(Context ctx) {
        PinManager.UserInfo userInfo = ctx.attribute("userInfo");
        ApplyDeleteRequest request;
        try {
            request = ctx.bodyAsClass(ApplyDeleteRequest.class);
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", "Invalid request."));
            return;
        }

        boolean success = plugin.getSkinManager().deleteSkin(userInfo.uuid(), request.skinId()); // Mengganti skinManager langsung
        if (success) {
            ctx.json(Map.of("success", true, "message", "Skin deleted."));
        } else {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("success", false, "message", "Skin not found."));
        }
    }

    /**
     * Handler untuk POST /api/dashboard/upload
     */
    private void handleUpload(Context ctx) {
        PinManager.UserInfo userInfo = ctx.attribute("userInfo"); 
        
        if (userInfo == null) {
            // Ini adalah langkah pengamanan jika middleware authenticate gagal dan ctx.skipRemainingHandlers() bermasalah.
            // Seharusnya tidak tercapai jika authenticate bekerja.
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Session check failed at handler."));
            return;
        }
        
        UploadedFile uploadedFile = ctx.uploadedFile("skinFile");

        if (uploadedFile == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", "No file uploaded."));
            return;
        }

        if (!Objects.equals(uploadedFile.contentType(), "image/png")) {
             ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", "Only .png files are allowed."));
             return;
        }
        
        try {
            byte[] fileData = uploadedFile.content().readAllBytes();
            String fileName = uploadedFile.filename();

            plugin.getSkinManager().processUploadedSkin(userInfo.uuid(), fileData, fileName) // Mengganti skinManager langsung
                .thenAccept(result -> {
                    if (result.success()) {
                        ctx.json(Map.of("success", true, "message", result.message(), "newSkin", result.skinInfo()));
                    } else {
                        ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", result.message()));
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Fatal error processing upload for " + userInfo.username(), ex);
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("success", false, "message", "An unknown server error occurred during processing."));
                    return null;
                });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error reading file stream:", e); // Perbaikan logging level
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("success", false, "message", "Internal server error during file read."));
        }
    }


    // --- Data Classes untuk JSON Binding ---

    private record LoginRequest(String username, String pin) {}
    private record ApplyDeleteRequest(long skinId) {}

}
