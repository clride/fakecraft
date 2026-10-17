package com.fakemcplayer.chat;

import com.fakemcplayer.ai.AiProvider;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small in-memory conversation store: per-player history + global recent chat. */
public class ConversationMemory {

    public record Entry(String player, String text, boolean fromBot, Instant at) {}

    private final int maxMessages;
    private final int timeoutMinutes;
    private final Map<String, Deque<Entry>> perPlayer = new ConcurrentHashMap<>();
    private final Deque<Entry> global = new ArrayDeque<>();
    private volatile Instant lastChatAt = Instant.EPOCH;

    public ConversationMemory(int maxMessages, int timeoutMinutes) {
        this.maxMessages = Math.max(2, maxMessages);
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
    }

    public synchronized void addPlayerMessage(String player, String text) {
        Entry e = new Entry(player, text, false, Instant.now());
        perPlayer.computeIfAbsent(player.toLowerCase(), k -> new ArrayDeque<>()).addLast(e);
        trim(perPlayer.get(player.toLowerCase()));
        global.addLast(e);
        trim(global);
        lastChatAt = Instant.now();
    }

    public synchronized void addBotMessage(String text, String contextPlayer) {
        Entry e = new Entry("bot", text, true, Instant.now());
        global.addLast(e);
        trim(global);
        if (contextPlayer != null) {
            perPlayer.computeIfAbsent(contextPlayer.toLowerCase(), k -> new ArrayDeque<>()).addLast(e);
            trim(perPlayer.get(contextPlayer.toLowerCase()));
        }
        lastChatAt = Instant.now();
    }

    private void trim(Deque<Entry> dq) {
        while (dq.size() > maxMessages) dq.removeFirst();
        // expire old
        dq.removeIf(en -> en.at().isBefore(Instant.now().minusSeconds(timeoutMinutes * 60L)));
    }

    public synchronized List<AiProvider.ChatMessage> historyFor(String player, int limit) {
        Deque<Entry> dq = perPlayer.get(player.toLowerCase());
        List<AiProvider.ChatMessage> out = new ArrayList<>();
        if (dq == null) return out;
        List<Entry> copy = new ArrayList<>(dq);
        int from = Math.max(0, copy.size() - limit);
        for (int i = from; i < copy.size(); i++) {
            Entry e = copy.get(i);
            out.add(new AiProvider.ChatMessage(e.fromBot() ? "assistant" : "user",
                    (e.fromBot() ? "" : e.player() + ": ") + e.text()));
        }
        return out;
    }

    public synchronized List<String> recentGlobal(int n) {
        List<Entry> copy = new ArrayList<>(global);
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, copy.size() - n); i < copy.size(); i++) {
            Entry e = copy.get(i);
            out.add((e.fromBot() ? "BOT" : e.player()) + ": " + e.text());
        }
        return out;
    }

    public Instant getLastChatAt() { return lastChatAt; }
}
