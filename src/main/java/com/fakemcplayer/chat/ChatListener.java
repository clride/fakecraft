package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.ai.JudgeService;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import com.fakemcplayer.dashboard.DashboardState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;

/**
 * Decides whether the bot should answer a chat message.
 *
 * <ul>
 *   <li>1 real player online (alone with bot) -&gt; always answer (subject to cooldowns).</li>
 *   <li>Many players -&gt; answer direct mentions immediately, otherwise ask the
 *       judge (Jev or LLM) whether the message was addressed to the bot.</li>
 * </ul>
 *
 * <p>Every decision is also recorded in {@link DashboardEvents} so the
 * operator console can show why the bot spoke or stayed silent.</p>
 */
public class ChatListener implements Listener {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private ConversationMemory memory;
    private final BotChatService chatService;
    private final DashboardState dashState;
    private final DashboardEvents events;

    public ChatListener(FakeMCPlayer plugin, BotConfig config, ConversationMemory memory,
                        BotChatService chatService, DashboardState dashState,
                        DashboardEvents events) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.chatService = chatService;
        this.dashState = dashState;
        this.events = events;
    }

    public void setConfig(BotConfig config) { this.config = config; }
    public void setMemory(ConversationMemory memory) { this.memory = memory; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String sender = event.getPlayer().getName();
        String msg = event.getMessage();
        if (sender.equalsIgnoreCase(config.getBotName())) return;

        memory.addPlayerMessage(sender, msg);
        // Feed the operator console — this must run BEFORE any early return so
        // every player message is visible there regardless of mode/skips.
        events.add("player_message", DashboardEvents.ev(sender, msg));

        // Bot has left the server: memory + console record the message, but
        // there is no one here to react (and no judge API call is spent).
        if (dashState.isBotLeft()) return;
        if (!config.isEnabled()) return;
        if (dashState.isPaused()) {
            events.add("skip", DashboardEvents.ev(sender, msg).str("reason", "paused"));
            return;
        }
        if (dashState.isMuted(sender)) {
            events.add("skip", DashboardEvents.ev(sender, msg).str("reason", "muted"));
            return;
        }
        // Manual mode: never gate triggers on cooldown — the operator decides.
        if (!dashState.isManual() && !chatService.checkCooldown(sender)) {
            events.add("skip", DashboardEvents.ev(sender, msg).str("reason", "cooldown"));
            return;
        }

        int online = Bukkit.getOnlinePlayers().size();
        boolean alone = online <= Math.max(1, config.getAloneThreshold());

        if (alone && config.isAloneModeEnabled()) {
            // Alone with the bot: be social, answer everything.
            events.add("trigger", DashboardEvents.ev(sender, msg).str("reason", "alone"));
            chatService.respondTo(sender, msg);
            return;
        }

        // Group mode
        if (isDirectMention(msg)) {
            events.add("trigger", DashboardEvents.ev(sender, msg).str("reason", "mention"));
            chatService.respondTo(sender, msg);
            return;
        }

        if (config.isGroupRandomChatter() && Math.random() < config.getGroupRandomChance()) {
            events.add("trigger", DashboardEvents.ev(sender, msg).str("reason", "random"));
            chatService.respondTo(sender, msg);
            return;
        }

        if (!config.isJudgeEnabled()) {
            events.add("skip", DashboardEvents.ev(sender, msg).str("reason", "judge-disabled"));
            return; // "otherwise it doesnt respond"
        }

        // Jev (TypeSafe System One Noul): typed P(addressed) in ~70-500ms.
        // Falls back to the LLM YES/NO judge if Jev is unreachable.
        if ("jev".equalsIgnoreCase(config.getAddressDecider())
                && plugin.getJevClient() != null && plugin.getJevClient().isConfigured()) {
            long t0 = System.currentTimeMillis();
            double threshold = config.getJevThreshold();
            plugin.getJevClient().isAddressed(config.getBotName(), sender, msg, memory.recentGlobal(4))
                    .thenAccept(p -> {
                        boolean yes = p >= threshold;
                        events.add("judge", DashboardEvents.ev(sender, msg)
                                .str("engine", "jev")
                                .num("probability", p)
                                .num("threshold", threshold)
                                .str("verdict", yes ? "yes" : "no")
                                .num("ms", System.currentTimeMillis() - t0));
                        if (yes) chatService.respondTo(sender, msg);
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        plugin.getLogger().warning("Jev failed (" + cause.getMessage()
                                + "), falling back to LLM judge");
                        events.add("error", DashboardEvents.ev(sender, msg)
                                .str("where", "jev")
                                .str("message", String.valueOf(cause.getMessage())));
                        runLlmJudge(sender, msg);
                        return null;
                    });
            return;
        }

        // Async LLM judge: does this message address the bot?
        runLlmJudge(sender, msg);
    }

    private void runLlmJudge(String sender, String msg) {
        String judgeModel = "ollama".equalsIgnoreCase(config.getProvider())
                ? config.getOllamaJudgeModel() : config.getOpenRouterJudgeModel();
        long t0 = System.currentTimeMillis();
        JudgeService.isAddressedToBot(plugin.getJudgeProvider(), judgeModel,
                config.getJudgePromptTemplate(), config.getBotName(),
                sender, msg).thenAccept(addressed -> {
            events.add("judge", DashboardEvents.ev(sender, msg)
                    .str("engine", "llm")
                    .str("model", judgeModel)
                    .str("verdict", addressed ? "yes" : "no")
                    .num("ms", System.currentTimeMillis() - t0));
            if (addressed) chatService.respondTo(sender, msg);
        }).exceptionally(ex -> {
            events.add("error", DashboardEvents.ev(sender, msg)
                    .str("where", "judge-llm")
                    .str("message", String.valueOf(ex.getMessage())));
            return null;
        });
    }

    /** Cheap heuristic before paying for a judge call: the bot's name + extra triggers. */
    private boolean isDirectMention(String msg) {
        String lower = msg.toLowerCase();
        String bot = config.getBotName().toLowerCase();
        if (lower.contains(bot)) return true;
        List<String> triggers = config.getMentionTriggers();
        if (triggers != null) {
            for (String t : triggers) {
                if (t == null || t.isBlank()) continue; // "" would match everything
                if (lower.contains(t.toLowerCase())) return true;
            }
        }
        return false;
    }

    /** Called by /bot ask so commands also feed memory. */
    public void recordCommandConversation(Player player, String question) {
        memory.addPlayerMessage(player.getName(), question);
        // visible in the console feed too (it triggers a reply)
        events.add("player_message", DashboardEvents.ev(player.getName(), question));
    }
}
