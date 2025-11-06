package com.zeroends.skinhub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class PinManager {

    private final SkinHub plugin;

    // PIN storage dengan timestamp untuk expiry
    private final Map<UUID, PinEntry> pinMap;         // Maps player UUIDs to PIN entries
    private final Map<String, UUID> pinToUuidMap;     // Reverse lookup pin -> UUID

    // Session storage dengan expiry
    private final Map<String, SessionEntry> sessionMap; // Maps session tokens to session entries

    // TTL/Expiry (ms)
    private final long pinExpiryMillis;
    private final long sessionExpiryMillis;

    public PinManager(SkinHub plugin) {
        this.plugin = plugin;
        this.pinMap = new ConcurrentHashMap<>();
        this.pinToUuidMap = new ConcurrentHashMap<>();
        this.sessionMap = new ConcurrentHashMap<>();

        int pinExpirySeconds = Math.max(1, plugin.getConfig().getInt("web.pin-expiry-seconds", 600));
        int sessionExpiryDays = Math.max(1, plugin.getConfig().getInt("web.session-expiry-days", 30));
        this.pinExpiryMillis = pinExpirySeconds * 1000L;
        this.sessionExpiryMillis = sessionExpiryDays * 24L * 60L * 60L * 1000L;
    }

    public String getOrCreatePin(UUID uuid) {
        long now = System.currentTimeMillis();
        PinEntry entry = pinMap.get(uuid);

        if (entry != null) {
            if (!isPinExpired(entry, now)) {
                plugin.logDebug("PIN ditemukan untuk " + uuid + ": " + entry.pin());
                return entry.pin();
            }
            // Expired -> remove old mapping
            removePin(uuid);
        }

        String pin = generatePin();
        PinEntry newEntry = new PinEntry(pin, now);
        pinMap.put(uuid, newEntry);
        pinToUuidMap.put(pin, uuid);
        plugin.logDebug("Membuat PIN baru untuk " + uuid + ": " + pin);
        return pin;
    }

    public boolean validatePin(UUID uuid, String pin) {
        long now = System.currentTimeMillis();
        PinEntry entry = pinMap.get(uuid);

        if (entry == null) {
            plugin.logDebug("Validasi PIN " + pin + " untuk " + uuid + ": false (tidak ada PIN)");
            return false;
        }
        if (isPinExpired(entry, now)) {
            plugin.logDebug("PIN untuk " + uuid + " sudah kadaluarsa, menghapus.");
            removePin(uuid);
            return false;
        }
        boolean valid = entry.pin().equals(pin);
        plugin.logDebug("Validasi PIN " + pin + " untuk " + uuid + ": " + valid);
        return valid;
    }

    public UUID getUuidByPin(String pin) {
        UUID uuid = pinToUuidMap.get(pin);
        // Catatan: reverse lookup ini tidak memeriksa expiry; gunakan validatePin untuk validasi penuh
        plugin.logDebug("UUID untuk PIN " + pin + ": " + uuid);
        return uuid;
    }

    public void removePin(UUID uuid) {
        PinEntry removed = pinMap.remove(uuid);
        if (removed != null) {
            pinToUuidMap.remove(removed.pin());
            plugin.logDebug("Hapus PIN untuk " + uuid + ": " + removed.pin());
        }
    }

    private boolean isPinExpired(PinEntry entry, long now) {
        return (now - entry.createdAtMillis()) > pinExpiryMillis;
    }

    private String generatePin() {
        // Generate random 6-digit PIN
        int number = ThreadLocalRandom.current().nextInt(100000, 1000000); // 6 digits
        return String.valueOf(number);
    }

    // === Session Management for Web (dengan expiry) ===
    public void createSession(String sessionToken, UUID uuid, String username) {
        long now = System.currentTimeMillis();
        long expiresAt = now + sessionExpiryMillis;
        sessionMap.put(sessionToken, new SessionEntry(new UserInfo(uuid, username), expiresAt));
        plugin.logDebug("Create session for " + username + " with token: " + sessionToken + " (expiresAt=" + expiresAt + ")");
    }

    public UserInfo validateSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            plugin.logDebug("Validasi session gagal: token null/empty");
            return null;
        }
        long now = System.currentTimeMillis();
        SessionEntry entry = sessionMap.get(sessionToken);
        if (entry == null) {
            plugin.logDebug("Validasi session untuk token: " + sessionToken + " hasil: null");
            return null;
        }
        if (now > entry.expiresAtMillis()) {
            sessionMap.remove(sessionToken);
            plugin.logDebug("Session expired untuk token: " + sessionToken + ", removing.");
            return null;
        }
        plugin.logDebug("Validasi session untuk token: " + sessionToken + " hasil: " + entry.userInfo().uuid());
        return entry.userInfo();
    }

    public void removeSession(String sessionToken) {
        SessionEntry info = sessionMap.remove(sessionToken);
        plugin.logDebug("Session removed for token: " + sessionToken + " user: " + (info != null ? info.userInfo().username() : "null"));
    }

    // === Records ===
    private record PinEntry(String pin, long createdAtMillis) {}

    private record SessionEntry(UserInfo userInfo, long expiresAtMillis) {}

    public record UserInfo(UUID uuid, String username) {}
}
