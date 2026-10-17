package com.fakemcplayer.tab;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardState;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Shows the bot in the multiplayer server preview (ping hover) as one
 * additional online player: player count +1 and a sample entry carrying
 * the bot's name, UUID and copied skin.
 */
public class ServerListSpoofer implements Listener {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private final SkinService skins;
    private final DashboardState dashState;

    public ServerListSpoofer(FakeMCPlayer plugin, BotConfig config, SkinService skins,
                             DashboardState dashState) {
        this.plugin = plugin;
        this.config = config;
        this.skins = skins;
        this.dashState = dashState;
    }

    public void setConfig(BotConfig config) { this.config = config; }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPing(com.destroystokyo.paper.event.server.PaperServerListPingEvent event) {
        if (!config.isEnabled() || !config.isShowBotOnServerList()) return;
        // Bot has left: it is not in the player count nor the sample list.
        if (dashState != null && dashState.isBotLeft()) return;
        try {
            event.setNumPlayers(event.getNumPlayers() + 1);
            // Bukkit factory returns the Bukkit type; on Paper the impl also
            // implements the Paper profile type the sample list needs.
            Object raw = Bukkit.createPlayerProfile(config.getBotUuid(), config.getBotName());
            if (raw instanceof com.destroystokyo.paper.profile.PlayerProfile pp) {
                if (skins != null && skins.hasSkin()) {
                    pp.setProperty(new ProfileProperty("textures",
                            skins.getValue(), skins.getSignature()));
                }
                event.getPlayerSample().add(pp);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Server-list spoof failed: " + e.getMessage());
        }
    }
}
