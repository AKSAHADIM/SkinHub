package com.zeroends.skinhub;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {
    private final SkinManager skinManager;

    public PlayerJoinListener(SkinManager skinManager) {
        this.skinManager = skinManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        skinManager.applyActiveSkinIfAny(event.getPlayer().getUniqueId());
    }
}
