package com.fakemcplayer.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public class BotConfig {

    private final JavaPlugin plugin;
    private final FileConfiguration c;

    public BotConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.c = plugin.getConfig();
    }

    // ---- bot identity ----
    public String getBotName() { return c.getString("bot.name", "Alex"); }
    public boolean isShowInTab() { return c.getBoolean("bot.show-in-tab", true); }
    public int getTabLatency() { return c.getInt("bot.tab-latency", 20); }
    public boolean isRealisticPing() { return c.getBoolean("bot.realistic-ping", true); }
    public boolean isShowBotOnServerList() { return c.getBoolean("server-list.show-bot", true); }

    // ---- operator dashboard ----
    public boolean isDashboardEnabled() { return c.getBoolean("dashboard.enabled", true); }
    public String getDashboardHost() { return c.getString("dashboard.host", "0.0.0.0"); }
    public int getDashboardPort() { return c.getInt("dashboard.port", 8642); }
    public String getDashboardToken() { return c.getString("dashboard.token", ""); }
    public int getDashboardBufferSize() { return c.getInt("dashboard.buffer-size", 2000); }
    public String getDashboardMode() { return c.getString("dashboard.mode", "auto"); }
    public boolean isFakeJoinMessage() { return c.getBoolean("bot.fake-join-message", true); }
    public String getChatFormat() { return c.getString("bot.chat-format", "<{name}> {message}"); }

    public UUID getBotUuid() {
        String raw = c.getString("bot.uuid", "");
        if (raw != null && !raw.isBlank()) {
            try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) {}
        }
        // stable offline-style uuid derived from name
        return UUID.nameUUIDFromBytes(("FakePlayer:" + getBotName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Manual skin override (Mineskin / session dump). Blank = not set. */
    public String getSkinValue() { return c.getString("bot.skin.value", ""); }
    public String getSkinSignature() { return c.getString("bot.skin.signature", ""); }
    /** Real Java account to copy the skin from. One of these is enough. */
    public String getSkinSourceUsername() { return c.getString("bot.skin.source-username", ""); }
    public String getSkinSourceUuid() { return c.getString("bot.skin.source-uuid", ""); }

    // ---- personality ----
    public String getSystemPrompt() {
        String base = c.getString("personality.system-prompt",
                "You are {name}, a chill Minecraft regular. Calm, laid-back, informal. Keep replies short (1-2 sentences). You are undercover: never bring up AI or bots.");
        return base.replace("{name}", getBotName());
    }
    public List<String> getPersonalityTraits() { return c.getStringList("personality.traits"); }

    // ---- provider ----
    public String getProvider() { return c.getString("provider", "openrouter"); }

    public String getOpenRouterKey() { return c.getString("openrouter.api-key", ""); }
    public String getOpenRouterModel() { return c.getString("openrouter.model", "openai/gpt-4o-mini"); }
    public String getOpenRouterJudgeModel() { return c.getString("openrouter.judge-model", "openai/gpt-4o-mini"); }

    public String getOllamaHost() { return c.getString("ollama.host", "http://localhost:11434"); }
    public String getOllamaModel() { return c.getString("ollama.model", "llama3.1"); }
    public String getOllamaJudgeModel() { return c.getString("ollama.judge-model", "llama3.1"); }

    public double getTemperature() { return c.getDouble("ai.temperature", 0.8); }
    public int getMaxTokens() { return c.getInt("ai.max-tokens", 150); }
    public int getRequestTimeoutSeconds() { return c.getInt("ai.request-timeout-seconds", 30); }
    public boolean isReasoningEnabled() { return c.getBoolean("ai.reasoning.enabled", false); }
    public String getReasoningEffort() { return c.getString("ai.reasoning.effort", "low"); }
    public boolean isReasoningExclude() { return c.getBoolean("ai.reasoning.exclude", true); }
    public String getSiteUrl() { return c.getString("openrouter.site-url", "https://localhost"); }
    public String getSiteName() { return c.getString("openrouter.site-name", "FakeMCPlayer"); }

    // ---- interaction ----
    /** Address decider for group chat: "llm" (YES/NO judge) or "jev" (TypeSafe Noul). */
    public String getAddressDecider() { return c.getString("interaction.address-decider", "llm"); }
    /** Extra mention triggers besides the bot's name (plain "contains", case-insensitive). */
    public List<String> getMentionTriggers() { return c.getStringList("interaction.mention-triggers"); }
    public boolean isJudgeEnabled() { return c.getBoolean("interaction.group-mode.judge-enabled", true); }
    public String getJudgePromptTemplate() {
        return c.getString("interaction.group-mode.judge-prompt",
                "You are a classifier. Bot name is '{name}'. "
                + "Decide if the chat message below is addressed to the bot (name mention, @mention, direct question to bot, reply to bot). "
                + "Answer with exactly YES or NO.\nMessage: \"{message}\"");
    }
    public long getPerPlayerCooldownSeconds() { return c.getLong("interaction.cooldowns.per-player-seconds", 5); }
    public long getGlobalCooldownSeconds() { return c.getLong("interaction.cooldowns.global-seconds", 2); }
    public double getResponseDelayMinSeconds() { return c.getDouble("interaction.response-delay.min-seconds", 3.0); }
    public double getResponseDelayMaxSeconds() { return c.getDouble("interaction.response-delay.max-seconds", 7.0); }
    public int getMaxHistory() { return c.getInt("interaction.memory.max-messages", 12); }
    public int getHistoryTimeoutMinutes() { return c.getInt("interaction.memory.timeout-minutes", 10); }

    public boolean isAloneModeEnabled() { return c.getBoolean("interaction.alone-mode.enabled", true); }
    public int getAloneThreshold() { return c.getInt("interaction.alone-mode.threshold", 1); }
    public long getAloneProactiveMinutes() { return c.getLong("interaction.alone-mode.proactive-interval-minutes", 3); }
    public long getAloneIdleMinutes() { return c.getLong("interaction.alone-mode.idle-minutes", 2); }
    public boolean isJoinGreetingEnabled() { return c.getBoolean("interaction.alone-mode.join-greeting.enabled", true); }
    public double getJoinGreetingChance() { return c.getDouble("interaction.alone-mode.join-greeting.chance", 0.5); }
    public double getJoinGreetingDelayMinSeconds() { return c.getDouble("interaction.alone-mode.join-greeting.delay-min-seconds", 12.0); }
    public double getJoinGreetingDelayMaxSeconds() { return c.getDouble("interaction.alone-mode.join-greeting.delay-max-seconds", 35.0); }

    public boolean isGroupRandomChatter() { return c.getBoolean("interaction.group-mode.random-chatter.enabled", false); }
    public double getGroupRandomChance() { return c.getDouble("interaction.group-mode.random-chatter.chance-per-message", 0.0); }

    public String getJevModel() { return c.getString("interaction.jev.model", "typesafe/jev-1.14"); }
    public double getJevThreshold() { return c.getDouble("interaction.jev.threshold", 0.6); }
    public int getJevTimeoutSeconds() { return c.getInt("interaction.jev.timeout-seconds", 10); }

    public boolean isEventReactionsEnabled() { return c.getBoolean("interaction.event-reactions.enabled", true); }
    public double getDeathChance() { return c.getDouble("interaction.event-reactions.death-chance", 0.6); }
    public double getAdvancementChance() { return c.getDouble("interaction.event-reactions.advancement-chance", 0.6); }

    public boolean isEnabled() { return c.getBoolean("enabled", true); }
}
