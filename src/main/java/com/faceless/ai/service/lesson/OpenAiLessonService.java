package com.faceless.ai.service.lesson;

import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.service.ChatGPTService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * OpenAI-backed lesson script generator — the default provider. Reuses the
 * existing {@link ChatGPTService} HTTP plumbing (key, endpoint, error
 * handling) so the AI-tutor vertical shares the same OpenAI integration the
 * documentary pipeline already uses. Active unless
 * {@code chronicleai.lesson.provider} is explicitly set to a non-openai value.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chronicleai.lesson.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiLessonService implements LessonScriptGenerator {

    @Value("${chronicleai.openai.api-key:}")
    private String apiKey;

    private final ChatGPTService chatGPTService;

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String generateLessonScript(String topic, String style) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "OpenAI is not configured — set OPENAI_KEY (or switch chronicleai.lesson.provider to claude).");
        }
        log.info("Generating lesson script with OpenAI for topic: {}", topic);
        try {
            String script = chatGPTService.chat(SYSTEM_PROMPT, buildUserPrompt(topic, style)).trim();
            if (script.isEmpty()) {
                throw new ExternalApiException("OpenAI returned an empty lesson script for topic: " + topic);
            }
            return script;
        } catch (ExternalApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalApiException("OpenAI lesson generation failed: " + e.getMessage(), e);
        }
    }
}
