package com.fakemcplayer.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** AI replies held for operator review in approve mode — one per player. */
public class DraftRegistry {

    public record Draft(long id, String player, String trigger, String text, String source, long at) {}

    private final Map<String, Draft> byPlayer = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    public Draft put(String player, String text, String trigger, String source) {
        Draft d = new Draft(ids.getAndIncrement(), player,
                trigger == null ? "" : trigger, text, source, System.currentTimeMillis());
        byPlayer.put(key(player), d);
        return d;
    }

    public Draft get(String player) {
        return byPlayer.get(key(player));
    }

    public List<Draft> all() {
        return new ArrayList<>(byPlayer.values());
    }

    public Draft remove(String player) {
        return byPlayer.remove(key(player));
    }

    public Draft getById(long id) {
        for (Draft d : byPlayer.values()) {
            if (d.id() == id) return d;
        }
        return null;
    }

    public Draft removeById(long id) {
        for (Map.Entry<String, Draft> e : byPlayer.entrySet()) {
            if (e.getValue().id() == id) {
                byPlayer.remove(e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    /** Regenerate support: swap the draft's text, keeping id + player + trigger. */
    public Draft replaceText(long id, String text) {
        for (Draft d : byPlayer.values()) {
            if (d.id() == id) {
                Draft updated = new Draft(d.id(), d.player(), d.trigger(), text, d.source(), d.at());
                byPlayer.put(key(d.player()), updated);
                return updated;
            }
        }
        return null;
    }

    private static String key(String player) {
        return player == null ? "" : player.toLowerCase(Locale.ROOT);
    }
}
