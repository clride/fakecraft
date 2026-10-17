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

public class OllamaClient implements AiProvider {

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String host;
    private final String defaultModel;
    private final double temperature;
    private final int maxTokens;
    private final boolean reasoningEnabled;

    public OllamaClient(HttpClient http, String host, String defaultModel, double temperature, int maxTokens,
                        boolean reasoningEnabled) {
        this.http = http;
        this.host = (host == null || host.isBlank()) ? "http://localhost:11434" : host.replaceAll("/+$", "");
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningEnabled = reasoningEnabled;
    }

    @Override
    public CompletableFuture<String> chat(String systemPrompt, List<ChatMessage> history, String userMessage) {
        return chatWithModel(defaultModel, systemPrompt, history, userMessage);
    }

    @Override
    public CompletableFuture<String> chatWithModel(String modelOverride, String systemPrompt,
                                                   List<ChatMessage> history, String userMessage) {
        String model = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("think", reasoningEnabled);
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

            JsonObject options = new JsonObject();
            options.addProperty("temperature", temperature);
            if (maxTokens > 0) options.addProperty("num_predict", maxTokens);
            body.add("options", options);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/chat"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() != 200) {
                            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
                        }
                        try {
                            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                            if (json.has("message")) {
                                return json.getAsJsonObject("message").get("content").getAsString().trim();
                            }
                            // fallback for /api/generate style
                            if (json.has("response")) return json.get("response").getAsString().trim();
                            throw new RuntimeException("Unexpected Ollama response: " + truncate(resp.body(), 300));
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("Ollama parse error", e);
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
    public String providerName() { return "ollama"; }
}
