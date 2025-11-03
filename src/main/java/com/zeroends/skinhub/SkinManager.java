package com.zeroends.skinhub;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinVariant;
import net.skinsrestorer.api.property.SkinType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.concurrent.ExecutionException;

public class SkinManager {

    private final SkinHub plugin;
    private final Storage storage;
    private final SkinsRestorer skinsRestorerApi;
    private final HttpClient httpClient;
    private final Gson gson;
    private final String mineskinApiKey;
    private final int maxSkins;
    private final boolean require64x64;
    private final long maxFileSize;
    private final Cache<UUID, Long> uploadCooldowns;

    public SkinManager(SkinHub plugin, Storage storage, SkinsRestorer skinsRestorerApi, Object mineskinClientPlaceholder) {
        this.plugin = plugin;
        this.storage = storage;
        this.skinsRestorerApi = skinsRestorerApi;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        this.gson = new Gson();
        this.mineskinApiKey = plugin.getConfig().getString("mineskin.api-key", "");

        this.maxSkins = plugin.getConfig().getInt("skin-management.max-skins", 5);
        this.require64x64 = plugin.getConfig().getBoolean("skin-management.require-64x64", true);
        this.maxFileSize = plugin.getConfig().getLong("skin-management.max-file-size-kb", 1024) * 1024;
        long cooldownSeconds = plugin.getConfig().getLong("skin-management.upload-cooldown-seconds", 60);

        this.uploadCooldowns = CacheBuilder.newBuilder()
                .expireAfterWrite(cooldownSeconds, TimeUnit.SECONDS)
                .build();
    }

    public List<PlayerData.SkinInfo> getSkinCollection(UUID playerUuid) {
        return storage.getPlayerData(playerUuid).getSkinSlots();
    }

    public CompletableFuture<Boolean> applySkin(UUID playerUuid, long skinId) {
        PlayerData playerData = storage.getPlayerData(playerUuid);
        PlayerData.SkinInfo skinInfo = playerData.getSkinById(skinId);

        if (skinInfo == null) {
            plugin.logDebug("Apply failed: Skin ID " + skinId + " not found for " + playerUuid);
            return CompletableFuture.completedFuture(false);
        }

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
            return applySkinToPlayer(offlinePlayer, skinInfo);
        }

        return applySkinToPlayer(player, skinInfo);
    }

    private CompletableFuture<Boolean> applySkinToPlayer(OfflinePlayer player, PlayerData.SkinInfo skinInfo) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        SkinProperty skinProperty = SkinProperty.of(skinInfo.texture(), skinInfo.signature());

        String lastKnownName = player.getName() != null ? player.getName() : "Unknown";
        skinsRestorerApi.getSkinStorage().setPlayerSkinData(
                player.getUniqueId(),
                lastKnownName,
                skinProperty,
                System.currentTimeMillis()
        );

        SkinVariant variant = SkinVariant.values().length > 0 ? SkinVariant.values()[0] : null;
        SkinType type = SkinType.values().length > 0 ? SkinType.values()[0] : null;

        SkinIdentifier skinIdentifier = SkinIdentifier.of(
                skinInfo.name(),
                variant,
                type
        );
        skinsRestorerApi.getPlayerStorage().setSkinIdOfPlayer(player.getUniqueId(), skinIdentifier);

        if (player.isOnline()) {
            skinsRestorerApi.getSkinApplier(Player.class).applySkin(player.getPlayer(), skinProperty);
        }

        plugin.logDebug("Applied skin " + skinInfo.name() + " to " + player.getName());
        future.complete(true);

        return future;
    }

    public boolean deleteSkin(UUID playerUuid, long skinId) {
        PlayerData playerData = storage.getPlayerData(playerUuid);
        PlayerData.SkinInfo skinInfo = playerData.getSkinById(skinId);

        if (skinInfo != null) {
            skinsRestorerApi.getPlayerStorage().removeSkinIdOfPlayer(playerUuid);
        }

        boolean removed = playerData.removeSkin(skinId);
        if (removed) {
            plugin.logDebug("Deleted skin ID " + skinId + " for " + playerUuid);
            scheduleSave();
        }
        return removed;
    }

    public CompletableFuture<UploadResult> processUploadedSkin(UUID playerUuid, byte[] fileData, String fileName) {
        if (uploadCooldowns.getIfPresent(playerUuid) != null) {
            return CompletableFuture.completedFuture(new UploadResult(false, "Please wait before uploading again.", null));
        }
        if (fileData.length > maxFileSize) {
            return CompletableFuture.completedFuture(new UploadResult(false, "File size exceeds " + (maxFileSize / 1024) + " KB limit.", null));
        }
        PlayerData playerData = storage.getPlayerData(playerUuid);
        if (playerData.getSkinSlots().size() >= maxSkins) {
            return CompletableFuture.completedFuture(new UploadResult(false, "Skin collection is full (Max " + maxSkins + ").", null));
        }

        try (ByteArrayInputStream is = new ByteArrayInputStream(fileData)) {
            BufferedImage image = ImageIO.read(is);
            if (image == null || (require64x64 && (image.getWidth() != 64 || image.getHeight() != 64))) {
                return CompletableFuture.completedFuture(new UploadResult(false, "Invalid skin file (must be 64x64 .png).", null));
            }
        } catch (IOException e) {
            return CompletableFuture.completedFuture(new UploadResult(false, "Error reading image file.", null));
        }

        uploadCooldowns.put(playerUuid, System.currentTimeMillis());

        String boundary = "---MineskinBoundary" + System.currentTimeMillis();
        HttpRequest request = buildMultipartRequest(fileData, fileName, boundary);
        plugin.logDebug("Sending manual Mineskin request for " + fileName);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 201) {
                        String msg = parseApiError(response, this.gson);
                        plugin.getLogger().log(Level.WARNING, String.format("Mineskin API Failed (Status: %d, Body: %s)", response.statusCode(), msg));
                        if (response.statusCode() == 429) {
                            return new UploadResult(false, "Rate limit hit. Please wait based on API response headers.", null);
                        }
                        if (response.statusCode() == 400 || response.statusCode() == 401) {
                            return new UploadResult(false, "API Rejected: Invalid file format or missing API key.", null);
                        }
                        return new UploadResult(false, "API Error (" + response.statusCode() + "): " + msg, null);
                    }
                    MineSkinResponse apiResponse = gson.fromJson(response.body(), MineSkinResponse.class);

                    if (apiResponse == null || apiResponse.data == null) {
                        plugin.getLogger().warning("Mineskin returned invalid JSON or null data. Body: " + response.body().substring(0, Math.min(response.body().length(), 100)));
                        return new UploadResult(false, "Failed to parse API response.", null);
                    }
                    MineSkinData data = apiResponse.data;

                    String apiSkinName = data.name;
                    if (apiSkinName == null || apiSkinName.isEmpty()) {
                        apiSkinName = fileName;
                    }

                    PlayerData.SkinInfo newSkinInfo = new PlayerData.SkinInfo(
                            apiSkinName,
                            System.currentTimeMillis(),
                            data.texture.value,
                            data.texture.signature
                    );

                    if (playerData.addSkin(newSkinInfo, maxSkins)) {
                        scheduleSave();
                        return new UploadResult(true, "Skin uploaded successfully!", newSkinInfo);
                    } else {
                        return new UploadResult(false, "Failed to add skin to collection (duplicate?).", null);
                    }
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    plugin.getLogger().log(Level.SEVERE, "HTTP Client execution failed.", cause);

                    String message = "Network error: " + cause.getClass().getSimpleName();

                    if (mineskinApiKey == null || mineskinApiKey.isEmpty() || mineskinApiKey.contains("DUMMY_API_KEY")) {
                        message = "API Key untuk Mineskin belum diisi di config.yml!";
                    }
                    return new UploadResult(false, message, null);
                });
    }

    private HttpRequest buildMultipartRequest(byte[] fileData, String fileName, String boundary) {
        String skinName = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;

        StringBuilder builder = new StringBuilder();
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"name\"").append("\r\n\r\n");
        builder.append(skinName).append("\r\n");
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"visibility\"").append("\r\n\r\n");
        builder.append("1").append("\r\n");
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
        builder.append("Content-Type: image/png").append("\r\n\r\n");

        byte[] metadataBytes = builder.toString().getBytes();
        byte[] closingBoundary = ("\r\n--" + boundary + "--\r\n").getBytes();

        byte[] requestBody = new byte[metadataBytes.length + fileData.length + closingBoundary.length];
        System.arraycopy(metadataBytes, 0, requestBody, 0, metadataBytes.length);
        System.arraycopy(fileData, 0, requestBody, metadataBytes.length, fileData.length);
        System.arraycopy(closingBoundary, 0, requestBody, metadataBytes.length + fileData.length, closingBoundary.length);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mineskin.org/generate/upload"))
                .header("User-Agent", "SkinHub-Plugin/1.0")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.ofByteArray(requestBody));

        if (mineskinApiKey != null && !mineskinApiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + mineskinApiKey);
        }
        return requestBuilder.build();
    }

    private String parseApiError(HttpResponse<String> response, Gson gson) {
        try {
            MineSkinResponse apiResponse = gson.fromJson(response.body(), MineSkinResponse.class);
            if (apiResponse != null && apiResponse.error != null) {
                return response.statusCode() + ": " + apiResponse.error;
            }
        } catch (Exception ignored) {}
        return response.statusCode() + " (Body: " + response.body().substring(0, Math.min(response.body().length(), 100)) + ")";
    }

    private void scheduleSave() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!storage.saveData()) {
                plugin.getLogger().warning("Failed to save skin data after modification.");
            }
        });
    }

    public record UploadResult(boolean success, String message, PlayerData.SkinInfo skinInfo) {}

    private static class MineSkinResponse {
        String error;
        MineSkinData data;
    }

    private static class MineSkinData {
        String name;
        MineSkinTexture texture;
    }

    private static class MineSkinTexture {
        String value;
        String signature;
    }
}
