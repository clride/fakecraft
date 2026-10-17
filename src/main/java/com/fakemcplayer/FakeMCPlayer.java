package com.fakemcplayer;

import com.fakemcplayer.ai.AiProvider;
import com.fakemcplayer.ai.JevClient;
import com.fakemcplayer.ai.OllamaClient;
import com.fakemcplayer.ai.OpenRouterClient;
import com.fakemcplayer.chat.BotChatService;
import com.fakemcplayer.chat.ChatListener;
import com.fakemcplayer.chat.ConversationMemory;
import com.fakemcplayer.chat.JoinGreeter;
import com.fakemcplayer.chat.ProactiveScheduler;
import com.fakemcplayer.chat.SocialEventListener;
import com.fakemcplayer.command.BotCommand;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.tab.ServerListSpoofer;
import com.fakemcplayer.tab.SkinService;
import com.fakemcplayer.tab.TablistManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public class FakeMCPlayer extends JavaPlugin {

    private BotConfig botConfig;
    private ConversationMemory memory;
    private AiProvider aiProvider;
    private AiProvider judgeProvider;
    private JevClient jevClient;
    private BotChatService chatService;
    private SkinService skinService;
    private TablistManager tablistManager;
    private ServerListSpoofer serverListSpoofer;
    private ProactiveScheduler proactiveScheduler;
    private JoinGreeter joinGreeter;
    private SocialEventListener socialEvents;
    private HttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.botConfig = new BotConfig(this);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.memory = new ConversationMemory(botConfig.getMaxHistory(), botConfig.getHistoryTimeoutMinutes());

        buildProviders();
        // Jev runs through the OpenRouter Decisions API on the same key/account.
        this.jevClient = new JevClient(httpClient, botConfig.getOpenRouterKey(),
                botConfig.getJevModel(), botConfig.getJevTimeoutSeconds(),
                botConfig.getSiteUrl(), botConfig.getSiteName());

        this.chatService = new BotChatService(this, botConfig, memory, () -> aiProvider);
        ChatListener listener = new ChatListener(this, botConfig, memory, chatService);
        getServer().getPluginManager().registerEvents(listener, this);
        // Skin first (async Mojang fetch), then tab-list spoof
        this.skinService = new SkinService(this, httpClient);
        skinService.loadFromConfig(botConfig);
        // Tab-list needs listener too (join refresh)
        this.tablistManager = new TablistManager(this, botConfig, skinService);
        getServer().getPluginManager().registerEvents(tablistManager, this);
        tablistManager.addToAll();

        this.serverListSpoofer = new ServerListSpoofer(this, botConfig, skinService);
        getServer().getPluginManager().registerEvents(serverListSpoofer, this);

        this.proactiveScheduler = new ProactiveScheduler(this, botConfig, memory, chatService);
        proactiveScheduler.start();

        this.joinGreeter = new JoinGreeter(this, botConfig, memory, chatService);
        getServer().getPluginManager().registerEvents(joinGreeter, this);

        this.socialEvents = new SocialEventListener(this, botConfig, memory, chatService);
        getServer().getPluginManager().registerEvents(socialEvents, this);

        BotCommand cmd = new BotCommand(this, botConfig, chatService);
        Objects.requireNonNull(getCommand("bot")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("bot")).setTabCompleter(cmd);

        getLogger().info("FakeMCPlayer enabled as '" + botConfig.getBotName()
                + "' provider=" + botConfig.getProvider()
                + " tablist=" + botConfig.isShowInTab());
    }

    @Override
    public void onDisable() {
        if (proactiveScheduler != null) proactiveScheduler.stop();
        if (tablistManager != null) tablistManager.removeFromAll();
        getLogger().info("FakeMCPlayer disabled.");
    }

    public void reloadBot() {
        reloadConfig();
        this.botConfig = new BotConfig(this);
        this.memory = new ConversationMemory(botConfig.getMaxHistory(), botConfig.getHistoryTimeoutMinutes());
        buildProviders();
        // Jev runs through the OpenRouter Decisions API on the same key/account.
        this.jevClient = new JevClient(httpClient, botConfig.getOpenRouterKey(),
                botConfig.getJevModel(), botConfig.getJevTimeoutSeconds(),
                botConfig.getSiteUrl(), botConfig.getSiteName());
        this.chatService.setProviderSupplier(() -> aiProvider);
        this.chatService.setConfig(botConfig);
        this.skinService.refreshFromConfig(botConfig);
        this.tablistManager.setConfig(botConfig);
        this.tablistManager.setSkins(skinService);
        this.tablistManager.addToAllSilent();
        if (this.serverListSpoofer != null) this.serverListSpoofer.setConfig(botConfig);
        this.proactiveScheduler.setConfig(botConfig);
        if (this.joinGreeter != null) this.joinGreeter.setConfig(botConfig);
        if (this.socialEvents != null) this.socialEvents.setConfig(botConfig);
    }

    private void buildProviders() {
        String provider = botConfig.getProvider();
        if ("ollama".equalsIgnoreCase(provider)) {
            aiProvider = new OllamaClient(httpClient,
                    botConfig.getOllamaHost(),
                    botConfig.getOllamaModel(),
                    botConfig.getTemperature(),
                    botConfig.getMaxTokens(),
                    botConfig.isReasoningEnabled());
            // Judge stays a fast no-think classifier.
            judgeProvider = new OllamaClient(httpClient,
                    botConfig.getOllamaHost(),
                    botConfig.getOllamaJudgeModel(),
                    0.0,
                    10,
                    false);
        } else {
            aiProvider = new OpenRouterClient(httpClient,
                    botConfig.getOpenRouterKey(),
                    botConfig.getOpenRouterModel(),
                    botConfig.getTemperature(),
                    botConfig.getMaxTokens(),
                    botConfig.getSiteUrl(),
                    botConfig.getSiteName(),
                    botConfig.isReasoningEnabled(),
                    botConfig.getReasoningEffort(),
                    botConfig.isReasoningExclude());
            judgeProvider = new OpenRouterClient(httpClient,
                    botConfig.getOpenRouterKey(),
                    botConfig.getOpenRouterJudgeModel(),
                    0.0,
                    10,
                    botConfig.getSiteUrl(),
                    botConfig.getSiteName(),
                    false,
                    "low",
                    true);
        }
    }

    public AiProvider getJudgeProvider() {
        return judgeProvider;
    }

    public JevClient getJevClient() {
        return jevClient;
    }

    public TablistManager getTablistManager() {
        return tablistManager;
    }

    public BotConfig getBotConfig() {
        return botConfig;
    }
}
