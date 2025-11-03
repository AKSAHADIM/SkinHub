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
        }).start(plugin.getWebPort());
    }

    public void stop() {
        if (app != null) {
            app.stop();
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
                        // PERBAIKAN: tampilkan error internal dari UploadResult dengan code 400 jika error user, 500 jika error internal
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

    // Handler login, logout, data, apply skin, delete skin tetap seperti sebelumnya
    private void handleLogin(Context ctx) { /* ... */ }
    private void handleLogout(Context ctx) { /* ... */ }
    private void handleDashboardData(Context ctx) { /* ... */ }
    private void handleApplySkin(Context ctx) { /* ... */ }
    private void handleDeleteSkin(Context ctx) { /* ... */ }
}
