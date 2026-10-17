package com.fakemcplayer;

import com.fakemcplayer.ai.AiProvider;
import com.fakemcplayer.ai.JevClient;
import com.fakemcplayer.ai.OllamaClient;
import com.fakemcplayer.ai.OpenRouterClient;
import com.fakemcplayer.chat.BotChatService;
import com.fakemcplayer.chat.ChatListener;
import com.fakemcplayer.chat.ConversationMemory;
import com.fakemcplayer.chat.JoinGreeter;
import com.fakemcplayer.chat.PresenceManager;
import com.fakemcplayer.chat.ProactiveScheduler;
import com.fakemcplayer.chat.SocialEventListener;
import com.fakemcplayer.command.BotCommand;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import com.fakemcplayer.dashboard.DashboardServer;
import com.fakemcplayer.dashboard.DashboardState;
import com.fakemcplayer.dashboard.DraftRegistry;
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
    private ChatListener chatListener;
    private PresenceManager presence;
    private HttpClient httpClient;
    private DashboardState dashboardState;
    private DashboardEvents dashboardEvents;
    private DraftRegistry draftRegistry;
    private DashboardServer dashboardServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.botConfig = new BotConfig(this);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.memory = new ConversationMemory(botConfig.getMaxHistory(), botConfig.getHistoryTimeoutMinutes());

        // Operator console: event log + live state persist across /bot reload.
        this.dashboardState = new DashboardState(this, botConfig.getDashboardMode());
        this.dashboardEvents = new DashboardEvents(botConfig.getDashboardBufferSize());
        this.draftRegistry = new DraftRegistry();

        buildProviders();
        // Jev runs through the OpenRouter Decisions API on the same key/account.
        this.jevClient = new JevClient(httpClient, botConfig.getOpenRouterKey(),
                botConfig.getJevModel(), botConfig.getJevTimeoutSeconds(),
                botConfig.getSiteUrl(), botConfig.getSiteName());

        this.chatService = new BotChatService(this, botConfig, memory, () -> aiProvider,
                dashboardState, dashboardEvents, draftRegistry);
        this.chatListener = new ChatListener(this, botConfig, memory, chatService,
                dashboardState, dashboardEvents);
        getServer().getPluginManager().registerEvents(chatListener, this);
        // Skin first (async Mojang fetch), then tab-list spoof
        this.skinService = new SkinService(this, httpClient);
        skinService.loadFromConfig(botConfig);
        // Tab-list needs listener too (join refresh)
        this.tablistManager = new TablistManager(this, botConfig, skinService, dashboardEvents,
                dashboardState);
        getServer().getPluginManager().registerEvents(tablistManager, this);
        if (dashboardState.isBotLeft()) {
            getLogger().info("Bot left the server last session — press Rejoin in the console "
                    + "or use /bot rejoin to bring it back.");
        } else {
            tablistManager.addToAll();
        }

        this.serverListSpoofer = new ServerListSpoofer(this, botConfig, skinService, dashboardState);
        getServer().getPluginManager().registerEvents(serverListSpoofer, this);

        // Leave/rejoin session toggle (dashboard button + /bot leave|rejoin)
        this.presence = new PresenceManager(this, botConfig, tablistManager,
                dashboardState, dashboardEvents);

        this.proactiveScheduler = new ProactiveScheduler(this, botConfig, memory, chatService);
        proactiveScheduler.start();

        this.joinGreeter = new JoinGreeter(this, botConfig, memory, chatService);
        getServer().getPluginManager().registerEvents(joinGreeter, this);

        this.socialEvents = new SocialEventListener(this, botConfig, memory, chatService, dashboardEvents);
        getServer().getPluginManager().registerEvents(socialEvents, this);

        BotCommand cmd = new BotCommand(this, botConfig, chatService);
        Objects.requireNonNull(getCommand("bot")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("bot")).setTabCompleter(cmd);

        this.dashboardServer = new DashboardServer(this, dashboardState, dashboardEvents,
                draftRegistry, chatService);
        if (botConfig.isDashboardEnabled()) dashboardServer.start();

        getLogger().info("FakeMCPlayer enabled as '" + botConfig.getBotName()
                + "' provider=" + botConfig.getProvider()
                + " tablist=" + botConfig.isShowInTab());
    }

    @Override
    public void onDisable() {
        if (proactiveScheduler != null) proactiveScheduler.stop();
        if (dashboardServer != null) dashboardServer.stop();
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
        this.chatService.setMemory(this.memory);
        if (this.presence != null) this.presence.setConfig(botConfig);
        if (this.chatListener != null) {
            this.chatListener.setConfig(botConfig);
            this.chatListener.setMemory(this.memory);
        }
        if (this.dashboardServer != null) {
            this.dashboardServer.stop();
            this.dashboardServer = new DashboardServer(this, dashboardState, dashboardEvents,
                    draftRegistry, chatService);
            if (botConfig.isDashboardEnabled()) this.dashboardServer.start();
        }
        this.skinService.refreshFromConfig(botConfig);
        this.tablistManager.setConfig(botConfig);
        this.tablistManager.setSkins(skinService);
        this.tablistManager.addToAllSilent();
        if (this.serverListSpoofer != null) this.serverListSpoofer.setConfig(botConfig);
        this.proactiveScheduler.setConfig(botConfig);
        this.proactiveScheduler.setMemory(this.memory);
        if (this.joinGreeter != null) {
            this.joinGreeter.setConfig(botConfig);
            this.joinGreeter.setMemory(this.memory);
        }
        if (this.socialEvents != null) {
            this.socialEvents.setConfig(botConfig);
            this.socialEvents.setMemory(this.memory);
        }
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

    public PresenceManager getPresenceManager() {
        return presence;
    }

    public TablistManager getTablistManager() {
        return tablistManager;
    }

    public BotConfig getBotConfig() {
        return botConfig;
    }

    public DashboardState getDashboardState() {
        return dashboardState;
    }

    public DashboardEvents getDashboardEvents() {
        return dashboardEvents;
    }

    public DraftRegistry getDraftRegistry() {
        return draftRegistry;
    }

    public DashboardServer getDashboardServer() {
        return dashboardServer;
    }
}
