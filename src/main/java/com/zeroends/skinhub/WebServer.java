package com.zeroends.skinhub;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.util.JavalinBindException; // FIX: gunakan paket asli Javalin

import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

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
        int port = plugin.getWebPort();
        boolean retryOnBind = plugin.getConfig().getBoolean("web.retry-bind-on-port-in-use", true);
        int retryWaitMs = plugin.getConfig().getInt("web.retry-bind-wait-ms", 1500);

        try {
            startOnPort(port);
        } catch (JavalinBindException bindEx) {
            plugin.getLogger().warning("Port " + port + " is already in use.");
            if (!retryOnBind) {
                throw bindEx;
            }
            // Retry sekali setelah jeda kecil (umumnya saat reload cepat, port belum rilis)
            try {
                plugin.getLogger().info("Retrying to bind port " + port + " after " + retryWaitMs + " ms ...");
                Thread.sleep(retryWaitMs);
            } catch (InterruptedException ignored) {}
            startOnPort(port); // Jika masih gagal, biarkan exception naik
        }
    }

    private void startOnPort(int port) {
        this.app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
            long maxFileSize = plugin.getConfig().getLong("skin-management.max-file-size-kb", 1024) * 1024;
            config.http.maxRequestSize = maxFileSize + 1024;
        }).routes(() -> {
            get("/", ctx -> ctx.redirect("/index.html"));

            path("api", () -> {
                post("login", this::handleLogin);
                post("logout", this::handleLogout);

                path("dashboard", () -> {
                    before("/*", this::authenticate);
                    get("data", this::handleDashboardData);
                    post("upload", this::handleUpload);
                    post("apply", this::handleApplySkin);
                    post("delete", this::handleDeleteSkin);
                });
            });
        }).start(port);
    }

    public void stop() {
        if (app != null) {
            try {
                app.stop();
            } finally {
                app = null;
            }
        }
    }

    private void authenticate(Context ctx) {
        String token = ctx.cookie("skinhub_session");
        PinManager.UserInfo userInfo = pinManager.validateSession(token);

        if (userInfo == null) {
            plugin.logDebug("Auth failed for token: " + token);
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Session expired or invalid. Please login again."));
            ctx.res().setHeader("Connection", "close");
            return;
        }
        ctx.attribute("userInfo", userInfo);
    }

    // Handler untuk POST /api/login
    private void handleLogin(Context ctx) {
        String username = ctx.formParam("username");
        String pin = ctx.formParam("pin");

        if (username == null || pin == null) {
            try {
                Map body = ctx.bodyAsClass(Map.class);
                username = body.get("username") != null ? String.valueOf(body.get("username")) : null;
                pin = body.get("pin") != null ? String.valueOf(body.get("pin")) : null;
            } catch (Exception ignored) {}
        }

        if (username == null || username.isEmpty() || pin == null || pin.isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", "Username and PIN are required."));
            return;
        }

        // Resolve UUID via Bukkit offline cache
        java.util.UUID uuid = org.bukkit.Bukkit.getOfflinePlayer(username).getUniqueId();
        if (uuid == null) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Invalid username."));
            return;
        }

        if (!pinManager.validatePin(uuid, pin)) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("success", false, "message", "Invalid PIN for this username."));
            return;
        }

        String sessionToken = java.util.UUID.randomUUID().toString();
        pinManager.createSession(sessionToken, uuid, username);
        // Cookie 30 hari
        ctx.cookie("skinhub_session", sessionToken, 60 * 60 * 24 * 30);
        ctx.json(Map.of("success", true, "message", "Login successful!"));
    }

    private void handleLogout(Context ctx) {
        String token = ctx.cookie("skinhub_session");
        pinManager.removeSession(token);
        ctx.removeCookie("skinhub_session");
        ctx.json(Map.of("success", true, "message", "Logged out."));
    }

    // Handler untuk POST /api/dashboard/upload
    private void handleUpload(Context ctx) {
        PinManager.UserInfo userInfo = ctx.attribute("userInfo");
        if (userInfo == null) {
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

            plugin.getSkinManager().processUploadedSkin(userInfo.uuid(), fileData, fileName)
                .thenAccept(result -> {
                    if (result.success()) {
                        ctx.json(Map.of("success", true, "message", result.message(), "newSkin", result.skinInfo()));
                    } else {
                        if (result.message().contains("API Key untuk Mineskin belum diisi")) {
                            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .json(Map.of("success", false, "message", "API Key untuk Mineskin belum diisi di config.yml! Silakan isi dan restart server!"));
                        } else if (result.message().contains("Invalid skin file") || result.message().contains("File size") || result.message().contains("collection is full")) {
                            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("success", false, "message", result.message()));
                        } else {
                            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("success", false, "message", result.message()));
                        }
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Fatal error processing upload for " + userInfo.username(), ex);
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("success", false, "message", "An unknown server error occurred during processing: " + ex.getMessage()));
                    return null;
                });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error reading file stream:", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("success", false, "message", "Internal server error during file read."));
        }
    }

    // Placeholder handlers (implement sesuai kebutuhan repo Anda)
    private void handleDashboardData(Context ctx) { /* ... */ }
    private void handleApplySkin(Context ctx) { /* ... */ }
    private void handleDeleteSkin(Context ctx) { /* ... */ }
}
