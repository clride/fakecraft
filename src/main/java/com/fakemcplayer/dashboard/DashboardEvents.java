package com.fakemcplayer.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Append-only in-memory log of everything the bot does: chat, judge
 * verdicts, skips, drafts, errors. The operator console is a viewer over
 * this one stream, and SSE subscribers get every event pushed live.
 */
public class DashboardEvents {

    public record Event(long id, long at, String type, JsonObject data) {
        public JsonObject toJson() {
            // Payload FIRST, then the stream meta — a payload key like a draft's
            // own "id" must never shadow the event id (that broke SSE dedupe).
            JsonObject o = new JsonObject();
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            o.addProperty("id", id);
            o.addProperty("at", at);
            o.addProperty("type", type);
            return o;
        }
    }

    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private final int max;
    private final Deque<Event> buffer = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    public DashboardEvents(int max) {
        this.max = Math.max(100, max);
    }

    /** Fluent payload builder (Gson's addProperty returns void, so chaining needs this). */
    public static final class P {
        private final JsonObject o = new JsonObject();
        public P str(String k, String v) { if (v != null) o.addProperty(k, v); return this; }
        public P num(String k, Number v) { if (v != null) o.addProperty(k, v); return this; }
        public P bool(String k, Boolean v) { if (v != null) o.addProperty(k, v); return this; }
        public JsonObject json() { return o; }
    }

    /** Empty payload, e.g. for mode/pause events. */
    public static P p() { return new P(); }

    /** Convenience payload builder: player/text fields, both optional. */
    public static P ev(String player, String text) {
        P p = new P();
        if (player != null && !player.isBlank()) p.str("player", player);
        if (text != null) p.str("text", text);
        return p;
    }

    public Event add(String type, P payload) {
        return add(type, payload == null ? new JsonObject() : payload.json());
    }

    public Event add(String type, JsonObject data) {
        Event ev;
        synchronized (this) {
            ev = new Event(seq.getAndIncrement(), System.currentTimeMillis(), type,
                    data == null ? new JsonObject() : data);
            buffer.addLast(ev);
            while (buffer.size() > max) buffer.removeFirst();
        }
        for (Consumer<Event> s : subscribers) {
            try {
                s.accept(ev);
            } catch (Exception ignored) {
                // a dead dashboard tab must never affect the bot
            }
        }
        return ev;
    }

    public synchronized List<Event> snapshot(int limit) {
        List<Event> all = new ArrayList<>(buffer);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public synchronized List<Event> since(long id, int limit) {
        List<Event> out = new ArrayList<>();
        for (Event e : buffer) {
            if (e.id() > id) out.add(e);
        }
        int from = Math.max(0, out.size() - Math.max(1, limit));
        return new ArrayList<>(out.subList(from, out.size()));
    }

    public Subscription subscribe(Consumer<Event> listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    public synchronized int size() { return buffer.size(); }

    public int max() { return max; }
}
