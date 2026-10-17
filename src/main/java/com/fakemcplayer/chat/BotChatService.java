package com.fakemcplayer.chat;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.ai.AiProvider;
import com.fakemcplayer.config.BotConfig;
import com.fakemcplayer.dashboard.DashboardEvents;
import com.fakemcplayer.dashboard.DashboardState;
import com.fakemcplayer.dashboard.DraftRegistry;
import com.fakemcplayer.dashboard.DraftRegistry.Draft;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Generates replies via the AI provider and delivers them as fake-player chat.
 * Handles cooldowns + human-like delays + message splitting.
 *
 * <p>Operator-console modes: {@code auto} replies on its own, {@code approve}
 * holds replies as drafts for review, {@code manual} keeps the bot quiet and
 * lets the operator write. All decisions are recorded in {@link DashboardEvents}.</p>
 */
public class BotChatService {

    private final FakeMCPlayer plugin;
    private BotConfig config;
    private ConversationMemory memory;
    private Supplier<AiProvider> providers;
    private final DashboardState dashState;
    private final DashboardEvents events;
    private final DraftRegistry drafts;

    private final Map<String, Instant> lastPerPlayer = new ConcurrentHashMap<>();
    private volatile Instant lastGlobal = Instant.EPOCH;

    // One reply "turn" at a time. Cooldowns restart when a reply is DELIVERED,
    // and burst messages arriving mid-turn collapse into a single follow-up.
    private final Object turnLock = new Object();
    private boolean busy = false;
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    private record Pending(String player, String message) {}

    public BotChatService(FakeMCPlayer plugin, BotConfig config, ConversationMemory memory,
                          Supplier<AiProvider> providers, DashboardState dashState,
                          DashboardEvents events, DraftRegistry drafts) {
        this.plugin = plugin;
        this.config = config;
        this.memory = memory;
        this.providers = providers;
        this.dashState = dashState;
        this.events = events;
        this.drafts = drafts;
    }

    public void setConfig(BotConfig config) { this.config = config; }
    public void setProviderSupplier(Supplier<AiProvider> providers) { this.providers = providers; }
    public void setMemory(ConversationMemory memory) { this.memory = memory; }

    public boolean checkCooldown(String playerName) {
        Instant now = Instant.now();
        Instant pp = lastPerPlayer.getOrDefault(playerName.toLowerCase(), Instant.EPOCH);
        if (now.minusSeconds(config.getPerPlayerCooldownSeconds()).isBefore(pp)) return false;
        if (now.minusSeconds(config.getGlobalCooldownSeconds()).isBefore(lastGlobal)) return false;
        return true;
    }

    public void markResponded(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            lastPerPlayer.put(playerName.toLowerCase(), Instant.now());
        }
        lastGlobal = Instant.now();
    }

    /** Normal reactive reply. Burst-safe: at most one turn in flight. */
    public void respondTo(String playerName, String triggerMessage) {
        if (!config.isEnabled() || dashState.isBotLeft()) return;
        if (dashState.isPaused()) {
            events.add("skip", DashboardEvents.ev(playerName, triggerMessage)
                    .str("reason", "paused"));
            return;
        }
        if (dashState.isMuted(playerName)) {
            events.add("skip", DashboardEvents.ev(playerName, triggerMessage)
                    .str("reason", "muted"));
            return;
        }
        if (dashState.isManual()) {
            events.add("held", DashboardEvents.ev(playerName, triggerMessage)
                    .str("reason", "manual-mode"));
            return;
        }
        // (manual mode already returned above, so cooldowns only gate auto/approve)
        if (!checkCooldown(playerName)) {
            events.add("skip", DashboardEvents.ev(playerName, triggerMessage)
                    .str("reason", "cooldown"));
            return;
        }
        synchronized (turnLock) {
            if (busy) {
                // Mid-reply burst: remember only the latest message.
                // One follow-up max, sent after a fresh timeout once this turn lands.
                pending.set(new Pending(playerName, triggerMessage));
                return;
            }
            busy = true;
        }

        String system = buildSystemPrompt(playerName);
        List<AiProvider.ChatMessage> history = memory.historyFor(playerName, config.getMaxHistory());
        // Just the chat line — all behavior rules live in the system prompt.
        // (Putting instructions here is what makes models narrate/explain the reply.)
        String userContent = playerName + ": " + triggerMessage;

        generateAndSend(playerName, system, history, userContent, triggerMessage.length(), "reply");
    }

    /** Claims the turn for an unprompted message; skips (no queue) if busy. */
    private boolean claimTurn() {
        synchronized (turnLock) {
            if (busy) return false;
            busy = true;
            return true;
        }
    }

    /**
     * Unprompted messages (proactive, greetings, reactions) respect the mode:
     * manual suppresses them outright, approve turns them into drafts later.
     *
     * @return true when the caller must skip.
     */
    private boolean blockedUnprompted(String player, String kind) {
        String reason = null;
        if (dashState.isPaused()) reason = "paused";
        else if (dashState.isManual()) reason = "manual-mode";
        else if (dashState.isMuted(player)) reason = "muted";
        if (reason == null) return false;
        events.add("suppressed", DashboardEvents.ev(player, null)
                .str("kind", kind).str("reason", reason));
        return true;
    }

    /** Proactive opener when alone with one player. */
    public void sendProactive(String lonePlayer) {
        if (!config.isEnabled() || dashState.isBotLeft()) return;
        if (blockedUnprompted(lonePlayer, "proactive")) return;
        if (!claimTurn()) return;
        String system = buildSystemPrompt(lonePlayer)
                + " You are alone on the server with " + lonePlayer + ". "
                + "Establish a connection on your own: greet them warmly, ask an open question about "
                + "what they are building or doing. Keep it to 1-2 sentences, casual, no AI talk.";
        List<AiProvider.ChatMessage> history = memory.historyFor(lonePlayer, config.getMaxHistory());
        List<String> recent = memory.recentGlobal(6);
        String userContent = "Recent server chat:\n" + String.join("\n", recent)
                + "\n" + lonePlayer + " is around. Say something to them.";
        generateAndSend(lonePlayer, system, history, userContent, 0, "proactive");
    }

    /** React to a public server event (death, advancement) like a normal player would. */
    public void reactToEvent(String contextPlayer, String eventLine) {
        if (!config.isEnabled() || dashState.isBotLeft()) return;
        if (blockedUnprompted(contextPlayer, "reaction")) return;
        if (!claimTurn()) return;
        String system = buildSystemPrompt(contextPlayer)
                + " Something just happened on the server: " + eventLine
                + " React briefly the way a normal player would (rip, gg, nice, lol...)."
                + " 1-2 short sentences max, often just a few words. Do not narrate the event back formally.";
        List<AiProvider.ChatMessage> history = memory.historyFor(contextPlayer, config.getMaxHistory());
        generateAndSend(contextPlayer, system, history, eventLine, eventLine.length(), "reaction");
    }

    /** Join greeting when it's just the bot and the newcomer. */
    public void sendJoinGreeting(String joiner) {
        if (!config.isEnabled() || dashState.isBotLeft()) return;
        if (blockedUnprompted(joiner, "greeting")) return;
        if (!claimTurn()) return;
        String system = buildSystemPrompt(joiner)
                + " " + joiner + " just joined the server and right now it is only you two online. "
                + "Greet them casually like a regular (welcome, hey, what's up), maybe ask what they are up to."
                + " Keep it to 1-2 short sentences, calm and informal, no AI talk.";
        List<AiProvider.ChatMessage> history = memory.historyFor(joiner, config.getMaxHistory());
        String userContent = joiner + " just joined the server.";
        generateAndSend(joiner, system, history, userContent, 0, "greeting");
    }

    private void generateAndSend(String contextPlayer, String system,
                                 List<AiProvider.ChatMessage> history, String userContent,
                                 int triggerLength, String source) {
        AiProvider provider = providers.get();
        provider.chat(system, history, userContent).whenComplete((reply, err) -> {
            if (err != null) {
                plugin.getLogger().warning("AI reply failed (" + provider.providerName() + "): " + err.getMessage());
                events.add("error", DashboardEvents.ev(contextPlayer, null)
                        .str("where", "ai:" + source)
                        .str("message", String.valueOf(err.getMessage())));
                endTurnWithoutDelivery();
                return;
            }
            if (reply == null || reply.isBlank()) {
                endTurnWithoutDelivery();
                return;
            }
            String clean = sanitize(reply, contextPlayer);
            if (dashState.isApprove()) {
                // Hold it for review instead of sending. New drafts for the same
                // player replace the old one (they reflect the latest context).
                Draft d = drafts.put(contextPlayer, clean, userContent, source);
                events.add("draft_created", draftPayload(d));
                endTurnWithoutDelivery();
                return;
            }
            memory.addBotMessage(clean, contextPlayer);
            deliverWithDelay(clean, triggerLength, contextPlayer, source,
                    () -> onDelivered(contextPlayer));
        });
    }

    // ------------------------------------------------------------- operator

    /** Send a message as the bot right now (operator console). Main thread. */
    public void sendAsBot(String target, String rawText) {
        if (!config.isEnabled()) return;
        String text = sanitizeManual(rawText);
        if (text.isBlank()) return;
        String contextPlayer = (target == null || target.isBlank()
                || target.equalsIgnoreCase("#server") || target.equalsIgnoreCase("server"))
                ? null : target;
        memory.addBotMessage(text, contextPlayer);
        deliverImmediate(text, contextPlayer, "operator");
    }

    /** Approve or edit a held draft. Main thread. */
    public void deliverDraft(Draft d, String overrideText) {
        if (d == null) return;
        String text = (overrideText == null || overrideText.isBlank())
                ? d.text() : sanitizeManual(overrideText);
        if (text.isBlank()) {
            events.add("draft_resolved", draftResolved(d, "discarded-empty"));
            return;
        }
        boolean edited = overrideText != null && !overrideText.isBlank() && !text.equals(d.text());
        memory.addBotMessage(text, d.player());
        deliverImmediate(text, d.player(), edited ? "edited" : "approved");
        events.add("draft_resolved", draftResolved(d, edited ? "edited" : "sent"));
    }

    /** Discard a held draft without sending. Main thread. */
    public void discardDraft(Draft d) {
        if (d == null) return;
        events.add("draft_resolved", draftResolved(d, "discarded"));
    }

    /** Ask the AI to regenerate a draft (approve mode). Main thread. */
    public void regenerateDraft(Draft d) {
        if (d == null) return;
        String system = buildSystemPrompt(d.player());
        List<AiProvider.ChatMessage> history = memory.historyFor(d.player(), config.getMaxHistory());
        String userContent = (d.trigger() == null || d.trigger().isBlank())
                ? d.player() + ": " : d.trigger();
        AiProvider provider = providers.get();
        provider.chat(system, history, userContent).whenComplete((reply, err) -> {
            if (err != null || reply == null || reply.isBlank()) {
                if (err != null) {
                    events.add("error", DashboardEvents.ev(d.player(), null)
                            .str("where", "ai:regenerate")
                            .str("message", String.valueOf(err.getMessage())));
                }
                return;
            }
            String clean = sanitize(reply, d.player());
            Draft updated = drafts.replaceText(d.id(), clean);
            if (updated != null) {
                events.add("draft_created", draftPayload(updated).bool("replaced", true));
            }
        });
    }

    /**
     * AI suggestion for the operator's composer (manual mode "Suggest" button).
     * Never sends; the text lands in the dashboard input box. Thread-safe.
     */
    public CompletableFuture<String> suggestReply(String player) {
        String system = buildSystemPrompt(player);
        List<AiProvider.ChatMessage> history = memory.historyFor(player, config.getMaxHistory());
        String lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                lastUser = history.get(i).content();
                break;
            }
        }
        String userContent = lastUser != null
                ? lastUser
                : player + " is around. Say something casual to them.";
        AiProvider provider = providers.get();
        return provider.chat(system, history, userContent).thenApply(reply -> {
            if (reply == null || reply.isBlank()) return "";
            return sanitize(reply, player);
        });
    }

    // ------------------------------------------------------------ internals

    private DashboardEvents.P draftPayload(Draft d) {
        return DashboardEvents.ev(d.player(), d.text())
                .num("draftId", d.id())
                .str("source", d.source())
                .num("at", d.at());
    }

    private DashboardEvents.P draftResolved(Draft d, String action) {
        return DashboardEvents.ev(d.player(), null)
                .num("draftId", d.id())
                .str("action", action)
                .str("source", d.source());
    }

    /** Approved drafts + operator sends: no human pause, goes out now. Main thread. */
    private void deliverImmediate(String text, String contextPlayer, String source) {
        List<String> parts = split(text);
        sendChatLine(parts.get(0));
        events.add("bot_message", DashboardEvents.ev(contextPlayer, text)
                .str("source", source)
                .num("parts", parts.size())
                .num("delayMs", 0));
        long atMs = 0;
        for (int i = 1; i < parts.size(); i++) {
            final String part = parts.get(i);
            atMs += nextPartGapMs(part);
            final long sendAt = atMs;
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendChatLine(part), sendAt / 50);
        }
        markResponded(contextPlayer);
    }

    /** Reply hit chat: restart the timeout from HERE, then send at most one follow-up. */
    private void onDelivered(String contextPlayer) {
        Pending followUp;
        synchronized (turnLock) {
            busy = false;
            markResponded(contextPlayer);
            followUp = pending.getAndSet(null);
        }
        if (followUp != null) scheduleFollowUp(followUp);
    }

    /** AI call failed or came back empty: free the turn, still honor one queued follow-up. */
    private void endTurnWithoutDelivery() {
        Pending followUp;
        synchronized (turnLock) {
            busy = false;
            followUp = pending.getAndSet(null);
        }
        if (followUp != null) scheduleFollowUp(followUp);
    }

    /** Follow-up waits out the normal timeout before it may fire — never rapid-fire. */
    private void scheduleFollowUp(Pending p) {
        long cooldownMs = Math.max(0L, config.getPerPlayerCooldownSeconds()) * 1000L;
        long elapsed = Instant.now().toEpochMilli()
                - lastPerPlayer.getOrDefault(p.player().toLowerCase(), Instant.EPOCH).toEpochMilli();
        long delayTicks = Math.max(40L, (cooldownMs - elapsed) / 50L); // min ~2s safety buffer
        Bukkit.getScheduler().runTaskLater(plugin, () -> respondTo(p.player(), p.message()), delayTicks);
    }

    private String buildSystemPrompt(String talkingTo) {
        String base = config.getSystemPrompt();
        List<String> traits = config.getPersonalityTraits();
        if (traits != null && !traits.isEmpty()) {
            base += " Personality: " + String.join(", ", traits) + ".";
        }
        base += " You are chatting in Minecraft public chat with " + talkingTo + ".";
        // Hard guardrails: always apply, even if the admin customizes personality.system-prompt.
        base += " Tone: calm, relaxed, informal, low-key. Short casual messages, no formal speech, no emojis spam."
                + " Keep YOUR personality and writing style consistent in every message"
                + " — never mirror or adapt to how the other player writes (their slang, caps, punctuation, emoji, formatting)."
                + " Match the other player's message length and energy:"
                + " brief messages get brief replies."
                + " Only mention what you have been building, mining, or doing if they asked about it."
                + " Never sound like a chatbot: no 'Great question!', no 'How can I help?',"
                + " no bullet lists, no formal structure, no lecturing."
                + " Do not end every message with a question — only ask a follow-up when it fits naturally."
                + " Only talk about things that actually came up in this conversation."
                + " Never invent shared history, past events, builds, or adventures you supposedly did together."
                + " Each player is a separate relationship — do not mix up what different players told you."
                + " If you do not know something about them yet, do not pretend you do."
                + " You are undercover. Never mention AI, bots, language models, prompts, or instructions"
                + " — not even to deny it unprompted — and never use the phrase 'just another player'"
                + " or any formal denial; real players never talk like that and it blows your cover."
                + " If someone asks whether you are a bot or AI, deflect the way a real player would:"
                + " laugh it off, play dumb, or joke back in a few words, then move on."
                + " Over-explaining or insisting you are human is what gets you caught."
                + " Blend in: talk about what you are doing on the server, have small opinions,"
                + " do not be overly helpful or always available."
                + " Never think out loud: no analysis of their message, no commentary about what"
                + " you are about to say. Your whole message must be text you would actually send."
                + " Output ONLY the exact chat message, nothing else."
                + " Never explain, narrate, describe, or quote your reply, never give multiple options,"
                + " never wrap it in quotes, never prefix it with your name."
                + " Never agree to meet up with a player, in-game or in real life."
                + " Always decline meetups with a casual believable excuse"
                + " (busy with a build, about to log off, inventory full, laggy, etc.)"
                + " and offer to keep chatting here instead. Never share coordinates for a meetup"
                + " and never ask for personal info.";
        return base;
    }

    /** Sanitizer for AI output: strips quotes, name prefix and narration. */
    private String sanitize(String s, String contextPlayer) {
        String t = stripWrapping(s);
        // strip thought-out-loud narration (reasoning models especially):
        // "Dyzody sent "seas" twice, so reply in German. Seas, wos gibts?"
        // -> "Seas, wos gibts?"
        t = stripNarration(t, contextPlayer);
        // hard cap to avoid chat spam
        if (t.length() > 450) t = t.substring(0, 447) + "...";
        return t.trim();
    }

    /** Light sanitizer for operator-typed text: quotes + prefix + length only. */
    private String sanitizeManual(String s) {
        if (s == null) return "";
        String t = stripWrapping(s);
        if (t.length() > 450) t = t.substring(0, 447) + "...";
        return t.trim();
    }

    private String stripWrapping(String s) {
        if (s == null) return "";
        String t = s.trim();
        // strip wrapping quotes the model sometimes adds
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).trim();
        // strip leading name prefix if the model adds one ("Flo: hi", "<Flo> hi")
        String botName = config.getBotName();
        if (!botName.isBlank()) {
            String low = t.toLowerCase();
            String bn = botName.toLowerCase();
            if (low.startsWith(bn + ":")) t = t.substring(bn.length() + 1).trim();
            else if (low.startsWith("<" + bn + ">")) t = t.substring(bn.length() + 2).trim();
        }
        return t;
    }

    /**
     * Drops leading sentences that talk ABOUT the message instead of being the
     * message (model thinking out loud). Conservative: only drops when at least
     * one sentence remains.
     */
    private static String stripNarration(String t, String contextPlayer) {
        String[] parts = t.split("(?<=[.!?])\\s+");
        if (parts.length < 2) return t;
        int drop = 0;
        for (String sentence : parts) {
            if (isNarration(sentence, contextPlayer)) drop++;
            else break;
        }
        if (drop > 0 && drop < parts.length) {
            String rest = String.join(" ", java.util.Arrays.copyOfRange(parts, drop, parts.length)).trim();
            if (!rest.isEmpty()) return rest;
        }
        return t;
    }

    private static boolean isNarration(String sentence, String contextPlayer) {
        String low = sentence.toLowerCase();
        // Instruction echo: model restating the plan before replying
        // ("Reply in German with a light Austrian touch, short. seas").
        if (low.startsWith("reply ") || low.startsWith("respond ")
                || low.startsWith("answer in ") || low.startsWith("answer with ")
                || low.startsWith("keep it ") || low.startsWith("say ")) {
            return true;
        }
        boolean mentionsPlayer = contextPlayer != null && !contextPlayer.isBlank()
                && low.contains(contextPlayer.toLowerCase());
        if (low.contains("so reply") || low.contains("here's ") || low.contains("here is ")
                || low.contains("something like") || low.contains("for example")
                || low.contains("i should") || low.contains("my reply") || low.contains("the reply")
                || low.contains("in response,") || low.contains("i'll respond") || low.contains("i will respond")) {
            return true;
        }
        // "Dyzody sent "seas" twice, which is..." — talking about their message.
        return mentionsPlayer && (low.contains("sent") || low.contains("said") || low.contains("wrote")
                || low.contains("messaged") || low.contains("asked") || low.contains("greeting")
                || low.contains("means") || low.contains("which is"));
    }

    private void deliverWithDelay(String message, int triggerLength, String contextPlayer,
                                  String source, Runnable onFirstSent) {
        List<String> parts = split(message);
        // Human pause: random window + reading time for the incoming message,
        // on top of the AI round-trip that already happened. Then typing sim.
        double minS = Math.max(0.0, config.getResponseDelayMinSeconds());
        double maxS = Math.max(minS, config.getResponseDelayMaxSeconds());
        long humanPause = (long) (minS * 1000
                + Math.random() * ((maxS - minS) * 1000));
        humanPause += Math.min(2000L, (long) Math.max(0, triggerLength) * 20L); // skim the incoming msg
        long typing = Math.min(3500L, Math.max(800L, (long) message.length() * 35L));
        long initialDelay = humanPause + typing;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendChatLine(parts.get(0));
            events.add("bot_message", DashboardEvents.ev(contextPlayer, message)
                    .str("source", source)
                    .num("parts", parts.size())
                    .num("delayMs", initialDelay));
            try {
                onFirstSent.run();
            } catch (Exception e) {
                plugin.getLogger().fine("Delivery callback failed: " + e.getMessage());
            }
        }, initialDelay / 50);
        long atMs = initialDelay;
        for (int i = 1; i < parts.size(); i++) {
            final String part = parts.get(i);
            // A human who just sent one chunk isn't instant: the next chunk
            // lands after a typing-length pause, plus a little read/think time.
            atMs += nextPartGapMs(part);
            final long sendAt = atMs;
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendChatLine(part), sendAt / 50);
        }
    }

    /**
     * Pause before a follow-up chunk of a split message: typing time for that
     * chunk (chars/typist pace) plus a small randomized pause, mirroring the
     * pace a real person shows when continuing a thought mid-chat.
     */
    private long nextPartGapMs(String nextPart) {
        long ms = Math.min(4000L, Math.max(900L, (long) nextPart.length() * 40L));
        return ms + 300L + (long) (Math.random() * 1200L);
    }

    private void sendChatLine(String text) {
        // A player who left the game cannot chat — drop in-flight chunks
        // if the operator pressed Leave while a message was still sending.
        if (dashState.isBotLeft()) return;
        String fmt = config.getChatFormat(); // e.g. "<{name}> {message}"
        String botName = config.getBotName();
        if (fmt.contains("{message}") && fmt.contains("{name}")) {
            String prefix = fmt.substring(0, fmt.indexOf("{name}"));
            String mid = fmt.substring(fmt.indexOf("{name}") + 6, fmt.indexOf("{message}"));
            String suffix = fmt.substring(fmt.indexOf("{message}") + 9);
            Component comp = Component.text(prefix)
                    .append(Component.text(botName, NamedTextColor.AQUA))
                    .append(Component.text(mid))
                    .append(Component.text(text, NamedTextColor.WHITE))
                    .append(Component.text(suffix));
            Bukkit.getServer().sendMessage(comp);
        } else {
            Bukkit.getServer().sendMessage(Component.text("<" + botName + "> " + text));
        }
    }

    private List<String> split(String msg) {
        if (msg.length() <= 180) return List.of(msg);
        // split on sentence boundaries
        List<String> out = new ArrayList<>();
        String[] sentences = msg.split("(?<=[.!?])\\s+");
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() + s.length() + 1 > 180 && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.isEmpty() ? List.of(msg) : out;
    }
}
