package com.zeroends.skinhub;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PinManager {

    private final SkinHub plugin;
    private final SecureRandom random = new SecureRandom();

    // Cache untuk PIN: Pin (String) -> UserInfo (UUID, Username)
    private final Cache<String, UserInfo> pinCache;

    // Cache untuk Sesi: Token (String) -> UserInfo (UUID, Username)
    private final Cache<String, UserInfo> sessionCache;

    // Menyimpan token aktif per UUID untuk manajemen sesi
    private final Map<UUID, String> activeSessions = new HashMap<>();

    public PinManager(SkinHub plugin) {
        this.plugin = plugin;
        long pinExpiry = plugin.getConfig().getLong("web.pin-expiry-seconds", 600);
        long sessionExpiry = plugin.getConfig().getLong("web.session-expiry-days", 30);

        this.pinCache = CacheBuilder.newBuilder()
                .expireAfterWrite(pinExpiry, TimeUnit.SECONDS)
                .build();
        
        this.sessionCache = CacheBuilder.newBuilder()
                .expireAfterWrite(sessionExpiry, TimeUnit.DAYS)
                .build();
    }

    /**
     * Membuat PIN 6 digit baru untuk pemain.
     */
    public String generatePin(UUID uuid, String username) {
        String pin;
        do {
            pin = String.format("%06d", random.nextInt(999999));
        } while (pinCache.getIfPresent(pin) != null); // Pastikan PIN unik

        pinCache.put(pin, new UserInfo(uuid, username));
        plugin.logDebug("Generated PIN " + pin + " for " + username);
        return pin;
    }

    /**
     * Memvalidasi PIN dan username.
     * Jika valid, menghapus PIN dan membuat Token Sesi baru.
     * @return Token Sesi jika valid, null jika tidak.
     */
    public String validatePin(String pin, String username) {
        UserInfo userInfo = pinCache.getIfPresent(pin);

        if (userInfo != null && userInfo.username().equalsIgnoreCase(username)) {
            // PIN valid. Hapus PIN dan buat sesi.
            pinCache.invalidate(pin);
            plugin.logDebug("Validated PIN " + pin + " for " + username);
            return createSession(userInfo.uuid(), userInfo.username());
        }
        
        plugin.logDebug("Failed validation for PIN " + pin + " and user " + username);
        return null;
    }

    /**
     * Membuat token sesi baru untuk pengguna.
     */
    private String createSession(UUID uuid, String username) {
        // Hapus sesi lama jika ada
        String oldToken = activeSessions.remove(uuid);
        if (oldToken != null) {
            sessionCache.invalidate(oldToken);
        }

        // Buat token baru
        String token = generateSafeToken();
        UserInfo userInfo = new UserInfo(uuid, username);

        sessionCache.put(token, userInfo);
        activeSessions.put(uuid, token);

        plugin.logDebug("Created session token for " + username);
        return token;
    }

    /**
     * Memvalidasi token sesi.
     * @return UserInfo jika token valid, null jika tidak.
     */
    public UserInfo validateSession(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return sessionCache.getIfPresent(token);
    }

    /**
     * Menghapus sesi (logout).
     */
    public void invalidateSession(String token) {
        UserInfo userInfo = sessionCache.getIfPresent(token);
        if (userInfo != null) {
            activeSessions.remove(userInfo.uuid());
            sessionCache.invalidate(token);
            plugin.logDebug("Invalidated session for " + userInfo.username());
        }
    }

    private String generateSafeToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Data record untuk menyimpan info pengguna di cache.
     */
    public record UserInfo(UUID uuid, String username) {}
}
