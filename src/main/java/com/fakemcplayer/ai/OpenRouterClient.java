package com.fakemcplayer.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
        int budget = maxTokens > 0 ? maxTokens : 1024;
        try {
            return attempt(model, systemPrompt, history, userMessage, budget, 0);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * One API round. When the model burns its whole completion budget on
     * reasoning and never prints any text ({@code finish_reason: length},
     * {@code content: null}), one automatic retry runs with a bigger budget —
     * 3x/min 800 for plain models, 4x/min 600 with reasoning on — because a
     * casual chat message should still get an answer even when
     * {@code ai.max-tokens} is set small.
     */
    private CompletableFuture<String> attempt(String model, String systemPrompt,
                                              List<ChatMessage> history, String userMessage,
                                              int budget, int round) {
        HttpRequest req = buildRequest(model, systemPrompt, history, userMessage, budget);
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenCompose(resp -> {
            if (resp.statusCode() != 200) {
                return CompletableFuture.failedFuture(new RuntimeException(
                        "OpenRouter HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300)));
            }
            Outcome o = parse(resp.body());
            if (o.content != null) {
                return CompletableFuture.completedFuture(o.content);
            }
            if (round == 0 && "length".equals(o.finishReason)) {
                int bumped = bumpBudget(budget);
                if (bumped > budget) {
                    return attempt(model, systemPrompt, history, userMessage, bumped, round + 1);
                }
            }
            return CompletableFuture.failedFuture(new RuntimeException(cutoffMessage(o, model, budget)));
        });
    }

    private HttpRequest buildRequest(String model, String systemPrompt,
                                     List<ChatMessage> history, String userMessage, int budget) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        if (budget > 0) body.addProperty("max_tokens", budget);
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

        return HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", siteUrl == null ? "https://localhost" : siteUrl)
                .header("X-Title", siteName == null ? "FakeMCPlayer" : siteName)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
    }

    private int bumpBudget(int budget) {
        // Hybrid "think by default" free models often spend 300+ tokens on
        // invisible reasoning even with reasoning off, so the retry gets real room.
        if (reasoningEnabled) return Math.min(Math.max(budget * 4, 600), 2048);
        return Math.min(Math.max(budget * 3, 800), 1600);
    }

    /** Parsed completion: visible text (when any was produced) or the cutoff reason. */
    private record Outcome(String content, String finishReason, int completionTokens) {}

    private Outcome parse(String raw) {
        JsonObject json;
        try {
            json = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("OpenRouter parse error (response was not JSON): "
                    + truncate(raw, 200), e);
        }
        JsonArray choices = (json.has("choices") && !json.get("choices").isJsonNull())
                ? json.getAsJsonArray("choices") : null;
        if (choices == null || choices.size() == 0) {
            throw new RuntimeException("OpenRouter returned no choices: " + truncate(raw, 200));
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = optString(choice, "finish_reason");
        if (finish == null) finish = optString(choice, "native_finish_reason");

        JsonObject message = (choice.has("message") && !choice.get("message").isJsonNull())
                ? choice.getAsJsonObject("message") : null;
        if (message == null) {
            throw new RuntimeException("OpenRouter response has no message: " + truncate(raw, 200));
        }
        String content = optString(message, "content");
        if (content == null || content.isBlank()) {
            int used = 0;
            if (json.has("usage") && !json.get("usage").isJsonNull()) {
                JsonObject u = json.getAsJsonObject("usage");
                if (u.has("completion_tokens") && !u.get("completion_tokens").isJsonNull()) {
                    used = u.get("completion_tokens").getAsInt();
                }
            }
            return new Outcome(null, finish == null ? "unknown" : finish, used);
        }
        return new Outcome(content.trim(), finish == null ? "unknown" : finish, -1);
    }

    private String cutoffMessage(Outcome o, String model, int budget) {
        if (!"length".equals(o.finishReason)) {
            return "OpenRouter: model '" + model + "' returned no text (finish_reason="
                    + o.finishReason + ")";
        }
        return "OpenRouter: model '" + model + "' spent its whole completion budget ("
                + budget + " max_tokens, " + o.completionTokens + " used) before printing any"
                + " text — raise ai.max-tokens in config.yml (reasoning models usually need 400+)";
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    @Override
    public String providerName() { return "openrouter"; }
}
