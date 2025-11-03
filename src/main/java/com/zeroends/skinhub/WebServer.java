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
import java.util.UUID;
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

    /**
     * Handler untuk POST /api/login
     * Memproses login dari dashboard web. Membaca username/UUID dan PIN dari POST body, bukan cookie.
     * Jika sukses, generate session token dan set cookie.
     */
    private void handleLogin(Context ctx) {
        // Coba ambil dari formParam, atau body JSON jika JS frontend menggunakan JSON
        String username = ctx.formParam("username");
        String pin = ctx.formParam("pin");

        if (username == null || pin == null) {
            // Jika body JSON, parse manual
            try {
                Map<String, String> body = ctx.bodyAsClass(Map.class);
                username = body.get("username");
                pin = body.get("pin");
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST)
                        .json(Map.of("success", false, "message", "Invalid request body."));
                return;
            }
        }

        if (username == null || pin == null) {
            ctx.status(HttpStatus.BAD_REQUEST)
                    .json(Map.of("success", false, "message", "Username and PIN required."));
            return;
        }

        // Try resolve UUID from username. (Assume username is Minecraft username)
        UUID uuid = UsernameResolver.resolve(username);
        if (uuid == null) {
            ctx.status(HttpStatus.UNAUTHORIZED)
                    .json(Map.of("success", false, "message", "Invalid username. Please check spelling."));
            return;
        }

        // Validasi PIN untuk UUID
        if (!pinManager.validatePin(uuid, pin)) {
            ctx.status(HttpStatus.UNAUTHORIZED)
                    .json(Map.of("success", false, "message", "Invalid PIN for this username."));
            return;
        }

        // Generate session token (can be UUID.randomUUID or any other)
        String sessionToken = UUID.randomUUID().toString();
        pinManager.createSession(sessionToken, uuid, username);

        // Set session cookie, expires 30 days
        ctx.cookie("skinhub_session", sessionToken, 60 * 60 * 24 * 30);

        ctx.json(Map.of("success", true, "message", "Login successful!"));
    }

    // Handler untuk logout
    private void handleLogout(Context ctx) {
        String token = ctx.cookie("skinhub_session");
        pinManager.removeSession(token);
        ctx.removeCookie("skinhub_session");
        ctx.json(Map.of("success", true, "message", "Logged out."));
    }

    // Handler lainnya seperti sebelumnya (dashboardData, apply, delete, upload)
    private void handleDashboardData(Context ctx) { /* ... */ }
    private void handleApplySkin(Context ctx) { /* ... */ }
    private void handleDeleteSkin(Context ctx) { /* ... */ }
    private void handleUpload(Context ctx) { /* ... */ }
}
