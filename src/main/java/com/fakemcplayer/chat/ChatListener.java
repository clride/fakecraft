package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.ai.JudgeService;
import com.fakemcplayer.config.BotConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Decides whether the bot should answer a chat message.
 *
 * <ul>
 *   <li>1 real player online (alone with bot) -&gt; always answer (subject to cooldowns).</li>
 *   <li>Many players -&gt; answer direct mentions immediately, otherwise ask the
 *       judge LLM whether the message was addressed to the bot.</li>
 * </ul>
 */
public class ChatListener implements Listener {

    private final FakeMCPlayer plugin;
    private final BotConfig config;
    private final ConversationMemory memory;
    private final BotChatService chatService;

    public ChatListener(FakeMCPlayer plugin, BotConfig config, ConversationMemory memory,
                        BotChatService chatService) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.chatService = chatService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String sender = event.getPlayer().getName();
        String msg = event.getMessage();
        if (sender.equalsIgnoreCase(config.getBotName())) return;

        memory.addPlayerMessage(sender, msg);

        if (!config.isEnabled()) return;
        if (!chatService.checkCooldown(sender)) return;

        int online = Bukkit.getOnlinePlayers().size();
        boolean alone = online <= Math.max(1, config.getAloneThreshold());

        if (alone && config.isAloneModeEnabled()) {
            // Alone with the bot: be social, answer everything.
            chatService.respondTo(sender, msg);
            return;
        }

        // Group mode
        if (isDirectMention(msg)) {
            chatService.respondTo(sender, msg);
            return;
        }

        if (config.isGroupRandomChatter() && Math.random() < config.getGroupRandomChance()) {
            chatService.respondTo(sender, msg);
            return;
        }

        if (!config.isJudgeEnabled()) return; // "otherwise it doesnt respond"

        // Jev (TypeSafe System One Noul): typed P(addressed) in ~70-500ms.
        // Falls back to the LLM YES/NO judge if Jev is unreachable.
        if ("jev".equalsIgnoreCase(config.getAddressDecider())
                && plugin.getJevClient() != null && plugin.getJevClient().isConfigured()) {
            double threshold = config.getJevThreshold();
            plugin.getJevClient().isAddressed(config.getBotName(), sender, msg, memory.recentGlobal(4))
                    .thenAccept(p -> {
                        plugin.getLogger().fine("Jev addressed_to_bot=" + p + " <" + sender + "> " + msg);
                        if (p >= threshold) chatService.respondTo(sender, msg);
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        plugin.getLogger().warning("Jev failed (" + cause.getMessage()
                                + "), falling back to LLM judge");
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
        JudgeService.isAddressedToBot(plugin.getJudgeProvider(), judgeModel,
                config.getJudgePromptTemplate(), config.getBotName(),
                sender, msg).thenAccept(addressed -> {
            if (addressed) chatService.respondTo(sender, msg);
        });
    }

    /** Cheap heuristic before paying for a judge call: the bot's name, nothing else. */
    private boolean isDirectMention(String msg) {
        String lower = msg.toLowerCase();
        String bot = config.getBotName().toLowerCase();
        return lower.contains(bot);
    }

    /** Called by /bot ask so commands also feed memory. */
    public void recordCommandConversation(Player player, String question) {
        memory.addPlayerMessage(player.getName(), question);
    }
}
