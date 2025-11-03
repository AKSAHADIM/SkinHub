package com.zeroends.skinhub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public class PinManager {

    private final SkinHub plugin;
    private final Map<UUID, String> pinMap; // Maps player UUIDs to PINs
    private final Map<String, UUID> pinToUuidMap; // Reverse lookup
    private final Map<String, UserInfo> sessionMap; // Maps session tokens to user info

    public PinManager(SkinHub plugin) {
        this.plugin = plugin;
        this.pinMap = new ConcurrentHashMap<>();
        this.pinToUuidMap = new ConcurrentHashMap<>();
        this.sessionMap = new ConcurrentHashMap<>();
    }

    public String getOrCreatePin(UUID uuid) {
        String pin = pinMap.get(uuid);
        if (pin != null) {
            plugin.logDebug("PIN ditemukan untuk " + uuid + ": " + pin);
            return pin;
        }
        pin = generatePin();
        pinMap.put(uuid, pin);
        pinToUuidMap.put(pin, uuid);
        plugin.logDebug("Membuat PIN baru untuk " + uuid + ": " + pin);
        return pin;
    }

    public boolean validatePin(UUID uuid, String pin) {
        String currentPin = pinMap.get(uuid);
        boolean valid = (currentPin != null && currentPin.equals(pin));
        plugin.logDebug("Validasi PIN " + pin + " untuk " + uuid + ": " + valid);
        return valid;
    }

    public UUID getUuidByPin(String pin) {
        UUID uuid = pinToUuidMap.get(pin);
        plugin.logDebug("UUID untuk PIN " + pin + ": " + uuid);
        return uuid;
    }

    public void removePin(UUID uuid) {
        String pin = pinMap.remove(uuid);
        if (pin != null) {
            pinToUuidMap.remove(pin);
            plugin.logDebug("Hapus PIN untuk " + uuid + ": " + pin);
        }
    }

    private String generatePin() {
        // Generate random 6-digit PIN
        int number = ThreadLocalRandom.current().nextInt(100000, 1000000); // 6 digits
        return String.valueOf(number);
    }

    // === Session Management for Web ===
    public void createSession(String sessionToken, UUID uuid, String username) {
        sessionMap.put(sessionToken, new UserInfo(uuid, username));
        plugin.logDebug("Create session for " + username + " with token: " + sessionToken);
    }

    public UserInfo validateSession(String sessionToken) {
        UserInfo info = sessionMap.get(sessionToken);
        plugin.logDebug("Validasi session untuk token: " + sessionToken + " hasil: " + (info != null ? info.uuid : "null"));
        return info;
    }

    public void removeSession(String sessionToken) {
        UserInfo info = sessionMap.remove(sessionToken);
        plugin.logDebug("Session removed for token: " + sessionToken + " user: " + (info != null ? info.username : "null"));
    }

    public record UserInfo(UUID uuid, String username) {}
}
