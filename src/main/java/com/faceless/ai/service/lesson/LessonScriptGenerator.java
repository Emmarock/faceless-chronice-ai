package com.faceless.ai.service.lesson;

/**
 * Generates the spoken narration for an AI-tutor lesson — a single block of
 * clean prose the HeyGen avatar reads verbatim.
 *
 * <p>Two implementations are selected at runtime via
 * {@code chronicleai.lesson.provider} (mirrors the {@code tts}/{@code image}/
 * {@code video} provider toggles):
 * <ul>
 *   <li>{@code openai} (default) — {@link OpenAiLessonService}</li>
 *   <li>{@code claude} — {@link ClaudeLessonService}</li>
 * </ul>
 * Exactly one bean is active, so callers inject {@code LessonScriptGenerator}
 * and stay provider-agnostic. The system prompt + user-prompt shape live here
 * so both providers produce the same kind of script.
 */
public interface LessonScriptGenerator {

    String SYSTEM_PROMPT = """
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

    /** Writes the spoken narration for {@code topic} in the given (nullable) {@code style}. */
    String generateLessonScript(String topic, String style);

    /** True when the active provider has the API key it needs. */
    boolean isConfigured();

    /** Shared user-prompt shape so both providers receive identical instructions. */
    default String buildUserPrompt(String topic, String style) {
        return "Topic to teach:\n" + topic
                + (style != null && !style.isBlank() ? "\n\nDesired tone/style:\n" + style : "");
    }
}
