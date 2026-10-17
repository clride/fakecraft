package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.ai.AiProvider;
import com.fakemcplayer.config.BotConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Greets a joiner after a natural delay — but only when it's just the bot
 * and that one player online, and only on a random roll so it doesn't
 * happen every time.
 */
public class JoinGreeter implements Listener {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private final ConversationMemory memory;
    private final BotChatService chatService;
    // one pending greeting per player so re-joins don't stack
    private final ConcurrentHashMap<UUID, Integer> pending = new ConcurrentHashMap<>();

    public JoinGreeter(FakeMCPlayer plugin, BotConfig config, ConversationMemory memory,
                       BotChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.chatService = chatService;
    }

    public void setConfig(BotConfig config) { this.config = config; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!config.isEnabled() || !config.isAloneModeEnabled() || !config.isJoinGreetingEnabled()) return;
        if (player.getName().equalsIgnoreCase(config.getBotName())) return;

        // Roll the chance up-front so most joins schedule nothing at all.
        if (ThreadLocalRandom.current().nextDouble() >= clampChance(config.getJoinGreetingChance())) return;

        double minS = Math.max(0.0, config.getJoinGreetingDelayMinSeconds());
        double maxS = Math.max(minS, config.getJoinGreetingDelayMaxSeconds());
        long delayTicks = (long) ((minS + Math.random() * (maxS - minS)) * 20L);
        if (delayTicks <= 0) delayTicks = 20L;

        UUID id = player.getUniqueId();
        cancelPending(id);
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            greetIfStillAlone(player);
        }, delayTicks).getTaskId();
        pending.put(id, taskId);
    }

    private void greetIfStillAlone(Player player) {
        try {
            if (!player.isOnline()) return;
            if (!config.isEnabled() || !config.isAloneModeEnabled() || !config.isJoinGreetingEnabled()) return;
            // Only the two of them: bot + this one player.
            if (Bukkit.getOnlinePlayers().size() != Math.max(1, config.getAloneThreshold())) return;
            // Already talking to them (replied, proactive, earlier greeting):
            // greeting now would land as a second message in a row — scripted look.
            boolean alreadyTalking = memory.historyFor(player.getName(), 3).stream()
                    .anyMatch(m -> "assistant".equals(m.role()));
            if (alreadyTalking) return;
            if (!chatService.checkCooldown(player.getName())) return;
            chatService.sendJoinGreeting(player.getName());
        } catch (Exception e) {
            plugin.getLogger().fine("Join greeting skipped: " + e.getMessage());
        }
    }

    private void cancelPending(UUID id) {
        Integer old = pending.remove(id);
        if (old != null) {
            try { Bukkit.getScheduler().cancelTask(old); } catch (Exception ignored) {}
        }
    }

    private static double clampChance(double c) {
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }
}
