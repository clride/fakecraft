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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OpenRouterClient implements AiProvider {

    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String defaultModel;
    private final double temperature;
    private final int maxTokens;
    private final String siteUrl;
    private final String siteName;
    private final boolean reasoningEnabled;
    private final String reasoningEffort;
    private final boolean reasoningExclude;

    public OpenRouterClient(HttpClient http, String apiKey, String defaultModel,
                            double temperature, int maxTokens, String siteUrl, String siteName,
                            boolean reasoningEnabled, String reasoningEffort, boolean reasoningExclude) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.siteUrl = siteUrl;
        this.siteName = siteName;
        this.reasoningEnabled = reasoningEnabled;
        this.reasoningEffort = normalizeEffort(reasoningEffort);
        this.reasoningExclude = reasoningExclude;
    }

    private static String normalizeEffort(String effort) {
        if (effort == null) return "low";
        String e = effort.trim().toLowerCase();
        if (e.equals("low") || e.equals("medium") || e.equals("high")) return e;
        return "low";
    }

    @Override
    public CompletableFuture<String> chat(String systemPrompt, List<ChatMessage> history, String userMessage) {
        return chatWithModel(defaultModel, systemPrompt, history, userMessage);
    }

    @Override
    public CompletableFuture<String> chatWithModel(String modelOverride, String systemPrompt,
                                                   List<ChatMessage> history, String userMessage) {
        if (apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "OpenRouter API key missing (openrouter.api-key). Set it in config.yml or use provider: ollama"));
        }
        String model = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);
            if (reasoningEnabled) {
                JsonObject reasoning = new JsonObject();
                reasoning.addProperty("effort", reasoningEffort);
                reasoning.addProperty("exclude", reasoningExclude);
                body.add("reasoning", reasoning);
            }
            JsonArray messages = new JsonArray();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", systemPrompt);
                messages.add(sys);
            }
            if (history != null) {
                for (ChatMessage m : history) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", m.role());
                    o.addProperty("content", m.content());
                    messages.add(o);
                }
            }
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", siteUrl == null ? "https://localhost" : siteUrl)
                    .header("X-Title", siteName == null ? "FakeMCPlayer" : siteName)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() != 200) {
                            throw new RuntimeException("OpenRouter HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
                        }
                        try {
                            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                            return json.getAsJsonArray("choices")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("message")
                                    .get("content").getAsString().trim();
                        } catch (Exception e) {
                            throw new RuntimeException("OpenRouter parse error: " + truncate(resp.body(), 500), e);
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

    @Override
    public String providerName() { return "openrouter"; }
}
