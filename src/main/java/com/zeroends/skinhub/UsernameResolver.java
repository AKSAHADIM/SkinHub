package com.zeroends.skinhub;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Utility to resolve Minecraft username to UUID, compatible with Bukkit and offline players.
 */
public class UsernameResolver {

    /**
     * Resolve UUID from username.
     * If username is directly a UUID string, parse.
     * Otherwise, attempt to lookup Bukkit player cache (online or offline).
     *
     * @param username Minecraft username or UUID string
     * @return UUID or null if not found
     */
    public static UUID resolve(String username) {
        if (username == null || username.isEmpty()) return null;
        try {
            // Accept direct UUID strings
            return UUID.fromString(username);
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, try resolve via server
        }
        // First, try server's online player list
        OfflinePlayer player = Bukkit.getOfflinePlayer(username);
        if (player.hasPlayedBefore() || player.isOnline()) {
            return player.getUniqueId();
        }
        // If not found, could implement fallback (database, web API, etc)
        return null;
    }
}
