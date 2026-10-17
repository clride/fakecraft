package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Lets the bot react to public server events (deaths, advancement
 * announcements) the way a normal player would.
 *
 * <p>Never reacts to the bot itself: bot entries carry the bot's name and
 * the bot has no real entity, so any event attributed to that name is skipped.</p>
 */
public class SocialEventListener implements Listener {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private ConversationMemory memory;
    private final BotChatService chatService;
    private final DashboardEvents events;

    public SocialEventListener(FakeMCPlayer plugin, BotConfig config,
                               ConversationMemory memory, BotChatService chatService,
                               DashboardEvents events) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.chatService = chatService;
        this.events = events;
    }

    public void setConfig(BotConfig config) { this.config = config; }
    public void setMemory(ConversationMemory memory) { this.memory = memory; }

    /** Vanilla join/leave lines are public chat — mirror them to the console. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinLeave(PlayerJoinEvent event) {
        String name = event.getPlayer().getName();
        if (name.equalsIgnoreCase(config.getBotName())) return;
        events.add("player_message", DashboardEvents.ev(name, name + " joined the game")
                .bool("system", true));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        if (name.equalsIgnoreCase(config.getBotName())) return;
        events.add("player_message", DashboardEvents.ev(name, name + " left the game")
                .bool("system", true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!config.isEnabled() || !config.isEventReactionsEnabled()) return;
        String victim = event.getPlayer().getName();
        if (isSelf(victim)) return;

        Component msg = null;
        try {
            msg = event.deathMessage();
        } catch (NoSuchMethodError e) {
            // very old API: fall back to legacy string
        }
        String plain;
        if (msg != null) {
            plain = PlainTextComponentSerializer.plainText().serialize(msg);
        } else {
            try {
                plain = event.getDeathMessage();
            } catch (Exception e) {
                return;
            }
        }
        if (plain == null || plain.isBlank()) return;

        // Public death announcement — feed the operator console. Rendered as
        // a system row; the reaction chance below only decides whether the
        // bot reacts.
        events.add("player_message", DashboardEvents.ev(victim, plain).bool("system", true));

        if (ThreadLocalRandom.current().nextDouble() >= clamp(config.getDeathChance())) return;
        if (!chatService.checkCooldown(victim)) return;

        memory.addPlayerMessage(victim, plain);
        chatService.reactToEvent(victim, plain);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!config.isEnabled() || !config.isEventReactionsEnabled()) return;
        String player = event.getPlayer().getName();
        if (isSelf(player)) return;

        // message() is the actual chat announcement; null = no broadcast (recipe etc.)
        Component announcement = null;
        try {
            announcement = event.message();
        } catch (NoSuchMethodError ignored) {
            // old API without announcement accessor: fall back to display check
        }
        String plain;
        if (announcement != null) {
            plain = PlainTextComponentSerializer.plainText().serialize(announcement);
        } else {
            // Fallback for APIs without the announcement accessor: only react to
            // advancements that announce to chat (skips recipes etc.).
            try {
                io.papermc.paper.advancement.AdvancementDisplay display =
                        event.getAdvancement().getDisplay();
                if (display == null || !display.doesAnnounceToChat()) return;
                String title = PlainTextComponentSerializer.plainText().serialize(display.title());
                plain = player + " has made the advancement [" + title + "]";
            } catch (Exception e) {
                return;
            }
        }
        if (plain == null || plain.isBlank()) return;

        // Public advancement announcement — feed the operator console.
        events.add("player_message", DashboardEvents.ev(player, plain).bool("system", true));

        if (ThreadLocalRandom.current().nextDouble() >= clamp(config.getAdvancementChance())) return;
        if (!chatService.checkCooldown(player)) return;

        memory.addPlayerMessage(player, plain);
        chatService.reactToEvent(player, plain);
    }

    /** The bot's own lines go out via server broadcast, never as real events — but guard anyway. */
    private boolean isSelf(String name) {
        if (Bukkit.getOnlinePlayers().stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(name))) return false;
        return name.equalsIgnoreCase(config.getBotName());
    }

    private static double clamp(double c) {
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }
}
