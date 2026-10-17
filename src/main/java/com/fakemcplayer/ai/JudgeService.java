package com.fakemcplayer.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** LLM-as-judge: decides whether a group-chat message was addressed to the bot. */
public class JudgeService {

    private JudgeService() {}

    public static CompletableFuture<Boolean> isAddressedToBot(
            AiProvider judgeProvider,
            String judgeModel,
            String judgePromptTemplate,
            String botName,
            String playerName,
            String message) {

        String prompt = judgePromptTemplate
                .replace("{name}", botName)
                .replace("{triggers}", "")
                .replace("{player}", playerName)
                .replace("{message}", message);

        // Judge gets an empty history; the whole task is in the single user message.
        // Keep max_tokens tiny (configured on the judge provider).
        return judgeProvider.chatWithModel(judgeModel, "Answer with exactly YES or NO.", List.of(), prompt)
                .thenApply(raw -> {
                    if (raw == null) return false;
                    String t = raw.trim().toUpperCase();
                    if (t.startsWith("YES")) return true;
                    if (t.startsWith("NO")) return false;
                    return t.contains("YES") && !t.contains("NO");
                })
                .exceptionally(ex -> false);
    }
}
