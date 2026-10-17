package com.fakemcplayer.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface AiProvider {

    record ChatMessage(String role, String content) {
        public Map<String, String> toMap() {
            return Map.of("role", role, "content", content);
        }
    }

    CompletableFuture<String> chat(String systemPrompt, List<ChatMessage> history, String userMessage);

    CompletableFuture<String> chatWithModel(String modelOverride, String systemPrompt,
                                            List<ChatMessage> history, String userMessage);

    String providerName();
}
