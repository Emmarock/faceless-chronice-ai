package com.faceless.ai.service.lesson;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.faceless.ai.exceptions.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Claude-backed lesson script generator (the Anthropic Java SDK,
 * {@code claude-opus-4-8} with adaptive thinking). Active when
 * {@code chronicleai.lesson.provider=claude}.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.lesson.provider", havingValue = "claude")
public class ClaudeLessonService implements LessonScriptGenerator {

    @Value("${chronicleai.anthropic.api-key:}")
    private String apiKey;
    @Value("${chronicleai.anthropic.model:claude-opus-4-8}")
    private String model;

    /** Lazily built once the @Value fields are injected; reused across calls. */
    private volatile AnthropicClient client;

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String generateLessonScript(String topic, String style) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Claude is not configured — set ANTHROPIC_API_KEY (or switch chronicleai.lesson.provider to openai).");
        }
        log.info("Generating lesson script with {} for topic: {}", model, topic);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(buildUserPrompt(topic, style))
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
