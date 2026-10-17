package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import com.fakemcplayer.dashboard.DashboardState;
import com.fakemcplayer.tab.TablistManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

/**
 * The bot's leave/rejoin "session" toggle, driven by the operator console.
 *
 * <p>While {@code left}, the bot is fully absent, like a player who left:
 * it shows nowhere (tab list, server-list count/sample), announces
 * "{name} left the game" in chat, and stops every interaction (no judge
 * calls, no proactive, no reactions, no operator sends). The state is
 * persisted in dashboard-state.yml, so after a restart the bot stays left
 * until someone presses Rejoin.</p>
 */
public class PresenceManager {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private final TablistManager tablist;
    private final DashboardState dashState;
    private final DashboardEvents events;

    public PresenceManager(FakeMCPlayer plugin, BotConfig config, TablistManager tablist,
                           DashboardState dashState, DashboardEvents events) {
        this.plugin = plugin;
        this.config = config;
        this.tablist = tablist;
        this.dashState = dashState;
        this.events = events;
    }

    public void setConfig(BotConfig config) { this.config = config; }

    public boolean isLeft() { return dashState.isBotLeft(); }

    /** Bot walks out: chat line, tab removal, server-list removal, all interactions off. */
    public void leave() {
        runOnMain(this::doLeave);
    }

    /** Bot comes back: chat line, tab re-inject, interactions on again. */
    public void rejoin() {
        runOnMain(this::doRejoin);
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    private void doLeave() {
        if (dashState.isBotLeft()) return;
        dashState.setBotLeft(true);
        announceLine("left the game");
        // Entries disappear for every viewer; the periodic refresh task is
        // cancelled too so the bot cannot silently sneak back in.
        tablist.removeFromAll();
        events.add("presence", DashboardEvents.p().bool("left", true));
        plugin.getLogger().info("Bot left the server (operator action).");
    }

    private void doRejoin() {
        if (!dashState.isBotLeft()) return;
        dashState.setBotLeft(false);
        // Silent inject first, then the join line a beat later — a real
        // client announces within a second of appearing in the list.
        tablist.showAll();
        Bukkit.getScheduler().runTaskLater(plugin, this::doJoinAnnounce, 10L);
        events.add("presence", DashboardEvents.p().bool("left", false));
        plugin.getLogger().info("Bot rejoined the server (operator action).");
    }

    private void doJoinAnnounce() {
        if (dashState.isBotLeft()) return; // turned around mid-animation
        announceLine("joined the game");
    }

    /** Public yellow system line + matching console feed row. */
    private void announceLine(String what) {
        String line = config.getBotName() + " " + what;
        Bukkit.getServer().sendMessage(
                Component.text(line, NamedTextColor.YELLOW));
        events.add("player_message",
                DashboardEvents.ev(config.getBotName(), line).bool("system", true));
    }
}
