package com.fakemcplayer.dashboard;

import com.fakemcplayer.FakeMCPlayer;
import com.fakemcplayer.chat.BotChatService;
import com.fakemcplayer.chat.PresenceManager;
import com.fakemcplayer.dashboard.DraftRegistry.Draft;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Embedded operator console. JDK HttpServer + SSE, zero dependencies.
 * Everything under /api requires the token; the static shell does not
 * (it holds no data and asks for the token on its first API call).
 *
 * <p>The UI is one single channel (the server channel): every event ends
 * up in the same visible feed.</p>
 */
public class DashboardServer {

    private final FakeMCPlayer plugin;
    private final DashboardState state;
    private final DashboardEvents events;
    private final DraftRegistry drafts;
    private final BotChatService chat;
    private PresenceManager presence;
    private final Gson gson = new Gson();

    private HttpServer server;
    private ExecutorService executor;
    private String token = "";
    private String host = "0.0.0.0";
    private int port = 8642;
    private int snapshotTask = -1;

    public DashboardServer(FakeMCPlayer plugin, DashboardState state, DashboardEvents events,
                           DraftRegistry drafts, BotChatService chat) {
        this.plugin = plugin;
        this.state = state;
        this.events = events;
        this.drafts = drafts;
        this.chat = chat;
        this.presence = plugin.getPresenceManager();
    }

    public boolean isRunning() { return server != null; }

    public String getToken() { return token; }

    /** URL to open, token included. Uses the LAN IP when bound to 0.0.0.0. */
    public String getUrl() {
        String h = "0.0.0.0".equals(host) ? lanIp() : host;
        String t = token.isBlank() ? "" : "/?token=" + token;
        return "http://" + h + ":" + port + t;
    }

    public void start() {
        stop();
        var cfg = plugin.getBotConfig();
        this.host = cfg.getDashboardHost();
        this.port = cfg.getDashboardPort();
        String configured = cfg.getDashboardToken();
        this.token = (configured == null || configured.isBlank()) ? generateToken() : configured.trim();
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "FakeMCPlayer-Dashboard");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            snapshotTask = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin,
                            () -> state.setPlayersOnline(plugin.getServer().getOnlinePlayers().size()),
                            20L, 40L)
                    .getTaskId();
            plugin.getLogger().info("Dashboard: " + getUrl());
            if ("0.0.0.0".equals(host)) {
                plugin.getLogger().info("Dashboard is bound to all interfaces — anyone on your "
                        + "network can open it with the token link above (keep that link private).");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Dashboard failed to start on " + host + ":" + port
                    + " — " + e.getMessage());
            stop();
        }
    }

    public void stop() {
        if (snapshotTask != -1) {
            plugin.getServer().getScheduler().cancelTask(snapshotTask);
            snapshotTask = -1;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------ http

    private void handle(HttpExchange ex) {
        try {
            route(ex);
        } catch (Exception e) {
            plugin.getLogger().fine("Dashboard request failed: " + e);
            try {
                respond(ex, 500, jsonError("internal error"));
            } catch (IOException ignored) {
                // client already gone
            }
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        switch (path) {
            case "/", "/index.html" -> {
                staticFile(ex, "dashboard/index.html", "text/html; charset=utf-8");
                return;
            }
            case "/app.css" -> {
                staticFile(ex, "dashboard/app.css", "text/css; charset=utf-8");
                return;
            }
            case "/app.js" -> {
                staticFile(ex, "dashboard/app.js", "application/javascript; charset=utf-8");
                return;
            }
            case "/favicon.ico" -> {
                respond(ex, 204, "");
                return;
            }
            default -> { }
        }

        if (!path.startsWith("/api/")) {
            respond(ex, 404, jsonError("not found"));
            return;
        }
        if (!authed(ex)) {
            respond(ex, 401, jsonError("missing or invalid token"));
            return;
        }

        if (path.startsWith("/api/conversations/")) {
            handleTranscript(ex, URLDecoder.decode(
                    path.substring("/api/conversations/".length()), StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/api/draft/")) {
            handleDraftAction(ex, path.substring("/api/draft/".length()));
            return;
        }

        switch (path) {
            case "/api/state" -> handleState(ex);
            case "/api/conversations" -> handleConversations(ex);
            case "/api/activity" -> handleActivity(ex);
            case "/api/events" -> handleSse(ex);
            case "/api/send" -> handleSend(ex);
            case "/api/mode" -> handleMode(ex);
            case "/api/pause" -> handlePause(ex);
            case "/api/presence" -> handlePresence(ex);
            case "/api/mute" -> handleMute(ex);
            case "/api/draft" -> handleSuggest(ex);
            default -> respond(ex, 404, jsonError("not found"));
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "GET")) return;
        var cfg = plugin.getBotConfig();
        JsonObject o = new JsonObject();
        o.addProperty("mode", state.getMode());
        o.addProperty("paused", state.isPaused());
        o.addProperty("left", state.isBotLeft());
        o.addProperty("botName", cfg.getBotName());
        o.addProperty("provider", cfg.getProvider());
        o.addProperty("playersOnline", state.getPlayersOnline());
        o.addProperty("bufferSize", events.size());
        respond(ex, 200, gson.toJson(o));
    }

    private void handleConversations(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "GET")) return;

        JsonObject srv = new JsonObject();
        srv.addProperty("key", "#server");
        srv.addProperty("name", "#server");

        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(srv);
        o.add("conversations", arr);
        respond(ex, 200, gson.toJson(o));
    }

    private void handleTranscript(HttpExchange ex, String key) throws IOException {
        if (!requirePost(ex, "GET")) return;

        JsonArray arr = new JsonArray();
        for (DashboardEvents.Event e : events.snapshot(Integer.MAX_VALUE)) {
            arr.add(e.toJson());
        }

        JsonObject o = new JsonObject();
        o.addProperty("player", "#server");
        o.add("events", arr);
        respond(ex, 200, gson.toJson(o));
    }

    private void handleActivity(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "GET")) return;
        int limit = intParam(ex, "limit", 400, 1, events.max());
        JsonArray arr = new JsonArray();
        for (DashboardEvents.Event e : events.snapshot(limit)) arr.add(e.toJson());
        JsonObject o = new JsonObject();
        o.add("events", arr);
        respond(ex, 200, gson.toJson(o));
    }

    private void handleSend(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        if (requireBotHere(ex)) return;
        JsonObject body = readJson(ex);
        String to = str(body, "to");
        String text = str(body, "text");
        if (text == null || text.isBlank()) {
            respond(ex, 400, jsonError("empty message"));
            return;
        }
        final String target = to;
        final String msg = text;
        runMain(() -> chat.sendAsBot(target, msg));
        respond(ex, 200, "{\"ok\":true}");
    }

    /** Operator write actions need the bot on the server. 409 when it has left. */
    private boolean requireBotHere(HttpExchange ex) throws IOException {
        if (!state.isBotLeft()) return false;
        respond(ex, 409, jsonError("bot has left the server — press Rejoin first"));
        return true;
    }

    /** Leave / rejoin the bot (operator console button). */
    private void handlePresence(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        JsonObject body = readJson(ex);
        String action = String.valueOf(str(body, "action")).toLowerCase(Locale.ROOT);
        boolean leave;
        switch (action) {
            case "leave" -> leave = true;
            case "rejoin" -> leave = false;
            default -> {
                respond(ex, 400, jsonError("action must be \"leave\" or \"rejoin\""));
                return;
            }
        }
        final boolean leaveAction = leave;
        runMain(() -> {
            if (leaveAction) presence.leave();
            else presence.rejoin();
        });
        respond(ex, 200, "{\"ok\":true,\"left\":" + leaveAction + "}");
    }

    /** Latest player who spoke publicly, for suggest without an explicit target. */
    private String latestPlayerMentioned() {
        var snap = events.snapshot(100);
        for (int i = snap.size() - 1; i >= 0; i--) {
            DashboardEvents.Event e = snap.get(i);
            if (e.type().equals("player_message")) {
                String p = str(e.data(), "player");
                if (p != null && !p.isBlank()) {
                    return p;
                }
            }
        }
        return null;
    }

    private void handleMode(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        JsonObject body = readJson(ex);
        String mode = DashboardState.normalize(str(body, "mode"));
        state.setMode(mode);
        events.add("mode", DashboardEvents.p().str("mode", mode));
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        respond(ex, 200, gson.toJson(o));
    }

    private void handlePause(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        JsonObject body = readJson(ex);
        boolean paused = bool(body, "paused", !state.isPaused());
        state.setPaused(paused);
        events.add("pause", DashboardEvents.p().bool("paused", paused));
        JsonObject o = new JsonObject();
        o.addProperty("paused", paused);
        respond(ex, 200, gson.toJson(o));
    }

    private void handleMute(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        JsonObject body = readJson(ex);
        String player = str(body, "player");
        if (player == null || player.isBlank()) {
            respond(ex, 400, jsonError("player required"));
            return;
        }
        boolean muted = bool(body, "muted", true);
        state.setMuted(player, muted);
        events.add("mute", DashboardEvents.ev(player, null).bool("muted", muted));
        respond(ex, 200, "{\"ok\":true}");
    }

    /** "Suggest reply": generate a draft into the operator's composer (never sent). */
    private void handleSuggest(HttpExchange ex) throws IOException {
        if (!requirePost(ex, "POST")) return;
        if (requireBotHere(ex)) return;
        JsonObject body = readJson(ex);
        String player = str(body, "player");
        if (player == null || player.isBlank()
                || player.equalsIgnoreCase("#server") || player.equalsIgnoreCase("server")) {
            // No explicit target: whoever spoke last gets the reply.
            player = latestPlayerMentioned();
        }
        if (player == null || player.isBlank()) {
            respond(ex, 400, jsonError("no player to reply to yet"));
            return;
        }
        String text = "";
        try {
            CompletableFuture<String> f = chat.suggestReply(player);
            String r = f.get(25, TimeUnit.SECONDS);
            if (r != null) text = r;
        } catch (Exception e) {
            plugin.getLogger().fine("Suggest reply failed: " + e.getMessage());
        }
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        respond(ex, 200, gson.toJson(o));
    }

    private void handleDraftAction(HttpExchange ex, String idRaw) throws IOException {
        if (!requirePost(ex, "POST")) return;
        if (requireBotHere(ex)) return;
        long id;
        try {
            id = Long.parseLong(idRaw.trim());
        } catch (NumberFormatException e) {
            respond(ex, 400, jsonError("bad draft id"));
            return;
        }
        Draft d = drafts.getById(id);
        if (d == null) {
            respond(ex, 404, jsonError("draft not found"));
            return;
        }
        JsonObject body = readJson(ex);
        String action = String.valueOf(str(body, "action")).toLowerCase(Locale.ROOT);
        switch (action) {
            case "approve" -> {
                drafts.removeById(id);
                runMain(() -> chat.deliverDraft(d, null));
            }
            case "edit" -> {
                String text = str(body, "text");
                if (text == null || text.isBlank()) {
                    respond(ex, 400, jsonError("empty text"));
                    return;
                }
                drafts.removeById(id);
                runMain(() -> chat.deliverDraft(d, text));
            }
            case "discard" -> {
                drafts.removeById(id);
                runMain(() -> chat.discardDraft(d));
            }
            case "regenerate" -> runMain(() -> chat.regenerateDraft(d));
            default -> {
                respond(ex, 400, jsonError("unknown action"));
                return;
            }
        }
        respond(ex, 200, "{\"ok\":true}");
    }

    // ------------------------------------------------------------------- sse

    private void handleSse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        BlockingQueue<DashboardEvents.Event> queue = new LinkedBlockingQueue<>(1024);
        DashboardEvents.Subscription sub = events.subscribe(queue::offer);
        try (OutputStream out = ex.getResponseBody()) {
            Long since = parseSince(ex);
            if (since != null) {
                for (DashboardEvents.Event ev : events.since(since, 500)) {
                    writeSse(out, ev);
                }
            }
            while (!Thread.currentThread().isInterrupted()) {
                DashboardEvents.Event ev = queue.poll(15, TimeUnit.SECONDS);
                if (ev == null) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                writeSse(out, ev);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // browser tab closed
        } finally {
            sub.close();
        }
    }

    private void writeSse(OutputStream out, DashboardEvents.Event ev) throws IOException {
        String chunk = "id: " + ev.id() + "\ndata: " + gson.toJson(ev.toJson()) + "\n\n";
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Long parseSince(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (h == null || h.isBlank()) h = queryParam(ex, "since");
        if (h == null || h.isBlank()) return null;
        try {
            return Long.parseLong(h.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean authed(HttpExchange ex) {
        if (token.isBlank()) return false;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            return token.equals(h.substring(7).trim());
        }
        return token.equals(queryParam(ex, "token"));
    }

    private void runMain(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private void staticFile(HttpExchange ex, String resource, String contentType) throws IOException {
        // ClassLoader.getResource() rejects leading slashes — normalize defensively.
        String res = resource.startsWith("/") ? resource.substring(1) : resource;
        try (InputStream in = plugin.getResource(res)) {
            if (in == null) {
                respond(ex, 404, jsonError("missing resource " + res));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private boolean requirePost(HttpExchange ex, String method) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase(method)) {
            respond(ex, 405, jsonError("use " + method));
            return false;
        }
        return true;
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        if (code == 204) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String jsonError(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o.toString();
    }

    private JsonObject readJson(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.isBlank()) return new JsonObject();
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isBlank()) return null;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            if (pair.substring(0, i).equals(name)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intParam(HttpExchange ex, String name, int def, int min, int max) {
        String v = queryParam(ex, name);
        if (v == null) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private static String generateToken() {
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String lanIp() {
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            Set<String> candidates = new HashSet<>();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        candidates.add(a.getHostAddress());
                    }
                }
            }
            if (!candidates.isEmpty()) return candidates.iterator().next();
        } catch (Exception ignored) {
            // fall through
        }
        return "localhost";
    }
}
