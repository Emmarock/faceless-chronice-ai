package com.faceless.ai.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.faceless.ai.exceptions.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates AI-tutor lesson scripts with Claude (the Anthropic Java SDK).
 *
 * <p>This is intentionally separate from {@link ChatGPTService}: the faceless
 * video pipeline produces scene-structured JSON via OpenAI, whereas a tutor
 * lesson is a single block of clean spoken narration the HeyGen avatar reads
 * verbatim — so we use Claude ({@code claude-opus-4-8}) with adaptive thinking
 * and a teaching-focused system prompt.
 */
@Service
@Slf4j
public class ClaudeLessonService {

    @Value("${chronicleai.anthropic.api-key:}")
    private String apiKey;
    @Value("${chronicleai.anthropic.model:claude-opus-4-8}")
    private String model;

    /** Lazily built once the @Value fields are injected; reused across calls. */
    private volatile AnthropicClient client;

    private static final String SYSTEM_PROMPT = """
            You are a warm, clear teacher writing the spoken script for a short
            lesson video. The script will be read aloud, word for word, by an AI
            avatar of the presenter — so write ONLY the words to be spoken.

            Rules:
            - Plain spoken prose. No markdown, no headings, no bullet points,
              no stage directions, no speaker labels, no scene numbers.
            - Open with a one-sentence hook, teach the core idea in a logical
              flow with a concrete example or analogy, then close with a short
              recap.
            - Target roughly 150–250 words (about 60–110 seconds spoken).
            - Conversational and encouraging; address the learner as "you".
            - Do not mention that you are an AI or that this is a script.
            """;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Writes the spoken narration for a lesson on {@code topic} in the given
     * {@code style} (nullable). Returns plain text suitable for HeyGen to read.
     */
    public String generateLessonScript(String topic, String style) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Claude is not configured — set ANTHROPIC_API_KEY to enable the AI Tutor feature.");
        }
        log.info("Generating lesson script with {} for topic: {}", model, topic);

        String userPrompt = "Topic to teach:\n" + topic
                + (style != null && !style.isBlank() ? "\n\nDesired tone/style:\n" + style : "");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(userPrompt)
                .build();

        try {
            Message response = client().messages().create(params);
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(t -> sb.append(t.text()));
            }
            String script = sb.toString().trim();
            if (script.isEmpty()) {
                throw new ExternalApiException("Claude returned an empty lesson script for topic: " + topic);
            }
            return script;
        } catch (ExternalApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiException("Claude lesson generation failed: " + e.getMessage(), e);
        }
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                    client = c;
                }
            }
        }
        return c;
    }
}
