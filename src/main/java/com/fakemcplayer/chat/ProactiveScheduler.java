package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/** Proactive pings when the bot is alone with one player and chat went quiet. */
public class ProactiveScheduler {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private final ConversationMemory memory;
    private final BotChatService chatService;
    private int taskId = -1;

    public ProactiveScheduler(FakeMCPlayer plugin, BotConfig config,
                              ConversationMemory memory, BotChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.chatService = chatService;
    }

    public void setConfig(BotConfig config) { this.config = config; }

    public void start() {
        stop();
        // check every minute
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L * 60, 20L * 60).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        try {
            if (!config.isEnabled() || !config.isAloneModeEnabled()) return;
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            if (online.size() != Math.max(1, config.getAloneThreshold())) return;

            Player lone = online.iterator().next();
            Instant last = memory.getLastChatAt();
            long idleMinutes = Duration.between(last, Instant.now()).toMinutes();
            if (idleMinutes < config.getAloneIdleMinutes()) return;
            if (!chatService.checkCooldown(lone.getName())) return;

            chatService.sendProactive(lone.getName());
        } catch (Exception e) {
            plugin.getLogger().fine("Proactive tick failed: " + e.getMessage());
        }
    }
}
