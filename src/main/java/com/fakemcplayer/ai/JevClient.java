package com.fakemcplayer.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TypeSafe AI Jev via OpenRouter's Decisions API — the "is this message
 * addressed to the bot?" call. State in, typed probability out; no text
 * generation, no YES/NO parsing. Billed to the OpenRouter account.
 *
 * @see <a href="https://openrouter.ai/docs/guides/community/jev">Jev on OpenRouter</a>
 */
public class JevClient {

    private static final String ENDPOINT = "https://openrouter.ai/api/alpha/decisions";
    private static final String QUESTION_ID = "addressed_to_bot";

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final String siteUrl;
    private final String siteName;

    public JevClient(HttpClient http, String openRouterKey, String model,
                     int timeoutSeconds, String siteUrl, String siteName) {
        this.http = http;
        this.apiKey = openRouterKey == null ? "" : openRouterKey;
        this.model = (model == null || model.isBlank()) ? "typesafe/jev-1.14" : model;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
        this.siteUrl = siteUrl;
        this.siteName = siteName;
    }

    public boolean isConfigured() { return !apiKey.isBlank(); }

    /**
     * @return P(message is addressed to the bot), 0.0–1.0.
     * Caller compares against the configured threshold.
     */
    public CompletableFuture<Double> isAddressed(String botName, String playerName,
                                                 String message, List<String> recentChat) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "OpenRouter API key missing (openrouter.api-key) — Jev is billed to it"));
        }
        try {
            JsonObject state = new JsonObject();
            state.addProperty("bot_name", botName);
            state.addProperty("player_name", playerName);
            state.addProperty("message", message);
            JsonArray recent = new JsonArray();
            if (recentChat != null) {
                for (String line : recentChat) {
                    if (line != null && !line.isBlank()) recent.add(line);
                }
            }
            state.add("recent_chat", recent);

            JsonObject question = new JsonObject();
            question.addProperty("type", "noul");
            question.addProperty("instructions",
                    "Is this Minecraft chat message addressed to or intended for " + botName
                    + "? Consider name mentions, @mentions, a direct question to them,"
                    + " or a reply to something they just said.");
            JsonObject questions = new JsonObject();
            questions.add(QUESTION_ID, question);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("state", state);
            body.add("questions", questions);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", siteUrl == null ? "https://localhost" : siteUrl)
                    .header("X-Title", siteName == null ? "FakeMCPlayer" : siteName)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                            throw new RuntimeException("OpenRouter auth failed (HTTP " + resp.statusCode()
                                    + ") — check openrouter.api-key");
                        }
                        if (resp.statusCode() != 200) {
                            throw new RuntimeException("Decisions API HTTP " + resp.statusCode()
                                    + ": " + truncate(resp.body(), 300));
                        }
                        try {
                            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                            JsonObject container = root.has("answers")
                                    && root.get("answers").isJsonObject()
                                    ? root.getAsJsonObject("answers") : root;
                            if (!container.has(QUESTION_ID)) {
                                throw new RuntimeException("Missing '" + QUESTION_ID + "' in: "
                                        + truncate(resp.body(), 300));
                            }
                            double p = container.getAsJsonObject(QUESTION_ID).get("noul").getAsDouble();
                            if (Double.isNaN(p)) throw new RuntimeException("Non-numeric noul value");
                            return Math.min(1.0, Math.max(0.0, p));
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("Decisions API parse error: "
                                    + truncate(resp.body(), 300), e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
