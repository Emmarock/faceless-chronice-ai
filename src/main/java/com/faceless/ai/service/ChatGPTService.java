package com.faceless.ai.service;

import com.faceless.ai.exceptions.ExternalApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatGPTService {

    @Value("${chronicleai.openai.api-key}")
    private String OPENAI_API_KEY;
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";


    private final ObjectMapper objectMapper;
    public String generateJob(String prompt) throws Exception {

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4.1");

        List<Map<String, String>> messages = List.of(
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        body.put("messages", messages);

        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }

    /**
     * Given a documentary scene narration, asks GPT to produce an optimised
     * Stable Diffusion prompt that yields a photorealistic image.
     *
     * GPT is instructed to:
     *  - Identify real persons mentioned and describe them with known physical traits
     *  - Frame the scene as a cinematic/photographic shot
     *  - Stay within SD's 77-token budget
     *
     * @return a ready-to-use SD positive prompt (plain text, no JSON wrapper)
     */
    public String generateImagePrompt(String sceneText) throws Exception {
        log.info("Generating SD image prompt for scene text ({}chars)...", sceneText.length());

        // Realistic Vision V6 is a fine-tuned SD 1.5 checkpoint trained on
        // heavily curated photographic datasets.  Its quality conditioning
        // tokens differ from vanilla SD: weighted brackets like (RAW photo:1.4)
        // activate the model's photorealism heads far more reliably than plain
        // text equivalents.  The system prompt below produces prompts that
        // exploit these RV6-specific embeddings.
        String systemPrompt = """
                You are an expert Stable Diffusion prompt engineer for the Realistic Vision V6 checkpoint.

                When given a documentary narration scene produce a single SD positive prompt.

                Rules:
                - Always begin with: (RAW photo:1.4), (best quality:1.4), (photorealistic:1.4), (8k UHD:1.2), (DSLR:1.1), sharp focus, film grain,
                - Identify any real persons by name and describe them precisely: known ethnicity, approximate age, skin tone, hair colour/style, eye colour, and current attire
                - Ground the scene: specific location, time of day, natural or artificial lighting
                - Add one lens detail (e.g. 85mm portrait lens, f/1.8, bokeh)
                - Maximum 75 words total
                - Return ONLY the prompt text — no explanations, no quotes, no markdown
                """;

        String prompt = callChatCompletion(systemPrompt, "Scene narration:\n" + sceneText);
        log.info("Generated image prompt: {}", prompt);
        return prompt;
    }

    /**
     * Converts a documentary scene narration into a short image search query
     * suitable for stock-photo APIs (Pexels, Unsplash, etc.).
     *
     * @return 3–5 keywords, e.g. "Boris Johnson Downing Street morning"
     */
    /**
     * Asks GPT for {@code count} <em>distinct</em> prompts that all describe
     * different visual beats of the same scene. The intent is one prompt per
     * image so the final clip cuts between meaningfully different shots
     * (different angles, subjects, moments) instead of one repeated frame.
     *
     * <p>Output style depends on the downstream image provider:
     * <ul>
     *   <li>{@link com.faceless.ai.service.image.ImageGenerationService.PromptStyle#SEARCH_QUERY}
     *       — short Pexels-friendly keyword strings.</li>
     *   <li>{@link com.faceless.ai.service.image.ImageGenerationService.PromptStyle#DESCRIPTIVE}
     *       — full Realistic-Vision-style cinematic prompts.</li>
     * </ul>
     *
     * <p>Returns exactly {@code count} prompts; if GPT under-delivers we pad
     * by repeating the last prompt rather than failing the pipeline. If GPT
     * over-delivers we truncate.
     */
    public List<String> generateImagePrompts(
            String sceneText,
            int count,
            com.faceless.ai.service.image.ImageGenerationService.PromptStyle style) throws Exception {
        if (count <= 0) return List.of();
        log.info("Generating {} {} prompts for scene ({} chars)...", count, style, sceneText.length());

        String systemPrompt = switch (style) {
            case SEARCH_QUERY -> """
                    You write short stock-photo search queries for documentary B-roll.

                    Given a single narration scene, output a JSON array of EXACTLY %d distinct
                    search queries that together cover different visual beats of that scene
                    (different subjects, angles, moments, locations).

                    Rules per query:
                    - 3 to 5 keywords, real persons / real locations / concrete nouns
                    - No abstract words, no quotes, no punctuation, no markdown
                    - Each query must describe a DIFFERENT shot — never repeat keywords across queries

                    Output ONLY the JSON array of strings — nothing else.
                    """.formatted(count);
            case DESCRIPTIVE -> """
                    You are an expert Stable Diffusion prompt engineer for the Realistic Vision V6 checkpoint.

                    Given a single narration scene, output a JSON array of EXACTLY %d distinct
                    photorealistic prompts that depict different visual beats of the same scene
                    (different angles, subjects, moments). The prompts are sequenced as cuts
                    in the final video, so they should flow visually but not duplicate each other.

                    Rules per prompt:
                    - Begin with: (RAW photo:1.4), (best quality:1.4), (photorealistic:1.4), (8k UHD:1.2), (DSLR:1.1), sharp focus, film grain,
                    - Identify any real persons by name with ethnicity, age, hair, attire
                    - Ground each prompt: location, time of day, lighting, one lens detail
                    - Maximum 75 words per prompt
                    - Each prompt MUST describe a different shot — different angle, framing, subject, or moment

                    Output ONLY a JSON array of strings — nothing else.
                    """.formatted(count);
        };

        String content = callChatCompletion(systemPrompt, "Scene narration:\n" + sceneText);
        List<String> prompts = parsePromptArray(content);
        if (prompts.isEmpty()) {
            log.warn("Could not parse GPT prompt array; falling back to single prompt for all {} images.", count);
            String fallback = switch (style) {
                case SEARCH_QUERY -> generateImageSearchQuery(sceneText);
                case DESCRIPTIVE  -> generateImagePrompt(sceneText);
            };
            prompts = new ArrayList<>();
            for (int i = 0; i < count; i++) prompts.add(fallback);
            return prompts;
        }
        if (prompts.size() < count) {
            String last = prompts.get(prompts.size() - 1);
            while (prompts.size() < count) prompts.add(last);
        } else if (prompts.size() > count) {
            prompts = new ArrayList<>(prompts.subList(0, count));
        }
        return prompts;
    }

    private List<String> parsePromptArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.trim();
        // Strip ```json … ``` fences GPT sometimes adds despite "no markdown".
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0) cleaned = cleaned.substring(firstNewline + 1);
            int closingFence = cleaned.lastIndexOf("```");
            if (closingFence >= 0) cleaned = cleaned.substring(0, closingFence);
            cleaned = cleaned.trim();
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                String s = item.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse prompt JSON array: {}", e.getMessage());
            return List.of();
        }
    }

    public String generateImageSearchQuery(String sceneText) throws Exception {
        log.info("Generating image search query for scene ({}chars)...", sceneText.length());

        String systemPrompt = """
                You are an assistant that produces stock-photo search queries.

                Given a documentary narration scene, output ONLY a short search query
                of 3–5 keywords that would find a relevant, real photograph on Pexels.

                Rules:
                - Use real person names, real locations, and concrete nouns
                - Avoid abstract words; prefer visual, tangible subjects
                - No punctuation, no quotes, no markdown
                - Return ONLY the query text
                """;

        String query = callChatCompletion(systemPrompt, "Scene narration:\n" + sceneText);
        log.info("Generated image search query: {}", query);
        return query;
    }

    /**
     * Public system+user chat helper for callers outside the documentary
     * pipeline (e.g. the AI-tutor {@code OpenAiLessonService}) that just need a
     * single completion. Thin pass-through to {@link #callChatCompletion} so
     * the OpenAI key/endpoint/error handling stay in one place.
     */
    public String chat(String systemPrompt, String userContent) throws Exception {
        return callChatCompletion(systemPrompt, userContent);
    }

    /**
     * Calls OpenAI chat/completions with a system + user message, validates the
     * response, and returns the assistant's content string. Throws
     * {@link ExternalApiException} on non-2xx responses or missing content so
     * the real API error surfaces instead of a downstream NPE.
     */
    private String callChatCompletion(String systemPrompt, String userContent) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4.1");
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_ENDPOINT))
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new ExternalApiException(
                    "OpenAI chat completion failed [" + response.statusCode() + "]: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ExternalApiException(
                    "OpenAI chat completion returned no choices: " + response.body());
        }

        return choices.get(0).path("message").path("content").asText().trim();
    }

    public String generateScript(String question, String style) throws Exception {
        return generateScript(question, style, com.faceless.ai.model.VideoFormat.VIDEO);
    }

    /**
     * Generates a script tailored to the chosen {@link com.faceless.ai.model.VideoFormat}:
     * <ul>
     *   <li>{@link com.faceless.ai.model.VideoFormat#REELS} — exactly 1 scene,
     *       ≤30s of spoken content total (title + hook + scene + closing).</li>
     *   <li>{@link com.faceless.ai.model.VideoFormat#VIDEO} — 8–10 scenes,
     *       2–3 minutes of spoken content (legacy long-form).</li>
     * </ul>
     * The shape of the returned JSON is identical for both formats so the
     * downstream mapper / pipeline doesn't need to branch.
     */
    public String generateScript(String question, String style, com.faceless.ai.model.VideoFormat format) throws Exception {
        log.info("Calling ChatGPT to generate {} script for question: {}", format, question);
        boolean reels = format == com.faceless.ai.model.VideoFormat.REELS;

        String lengthRequirement = reels
                ? "- Length: AT MOST 30 SECONDS of spoken content TOTAL (title + hook + the one scene + closing combined). Aim for ~70 words across all fields.\n"
                : "- Length: 2–3 minutes of spoken content\n";

        String scenesShape = reels
                ? "" +
                  "   {\"scene\":1,\"text\":\"\"}\n"
                : "" +
                  "   {\"scene\":1,\"text\":\"\"},\n" +
                  "   {\"scene\":2,\"text\":\"\"}\n" +
                  "   {\"scene\":3,\"text\":\"\"}\n" +
                  "   {\"scene\":4,\"text\":\"\"}\n" +
                  "   {\"scene\":5,\"text\":\"\"}\n" +
                  "   {\"scene\":6,\"text\":\"\"}\n" +
                  "   {\"scene\":7,\"text\":\"\"}\n" +
                  "   {\"scene\":8,\"text\":\"\"}\n" +
                  "   {\"scene\":9,\"text\":\"\"}\n" +
                  "   {\"scene\":10,\"text\":\"\"}\n";

        String sceneCountRule = reels
                ? "- The \"scenes\" array MUST contain EXACTLY 1 object (scene 1). Do not add more.\n"
                : "";

        String muchBetterPrompt = "You are a professional YouTube documentary scriptwriter.\n" +
                "\n" +
                "Write a script for a faceless educational " + (reels ? "short-form (Reels / TikTok / YouTube Shorts) " : "YouTube ") + "video.\n" +
                "\n" +
                "Requirements:\n" +
                "- Strong hook in first 5 seconds\n" +
                "- Clear storytelling\n" +
                "- Dramatic but factual\n" +
                lengthRequirement +
                sceneCountRule +
                "\n" +
                FORBIDDEN_WORDS_RULE +
                "\n" +
                "Topic:\n" + question +
                "\n" +
                "Style:\n" + style +
                "\n" +
                "\n" +
                "Return response in JSON using format below :\n" +
                "\n" +
                "{\n" +
                " \"title\": \"\",\n" +
                " \"hook\": \"\",\n" +
                " \"scenes\": [\n" +
                scenesShape +
                " ],\n" +
                " \"closing\":\"\"\n" +
                "}";
        return generateJob(muchBetterPrompt);
    }

    /**
     * Forbidden vocabulary that must never appear inside any user-visible
     * "text" / "title" / "hook" / "closing" field. The viewer hears these
     * fields read aloud — words like "scene" or "narration" break immersion
     * because they refer to the production, not the story.
     *
     * <p>The words may still appear as JSON keys (e.g. {@code "scenes":[…]}),
     * which the AI handles correctly when the rule is phrased as "inside text
     * fields only".
     */
    private static final String FORBIDDEN_WORDS_RULE =
            "STRICT VOCABULARY RULE — must be obeyed in every regeneration:\n" +
            "Inside any \"text\", \"title\", \"hook\", or \"closing\" string field, " +
            "you MUST NOT use any of these words (or their plural / possessive forms): " +
            "scene, scenes, narration, narrator, narrate. " +
            "These words may only appear as JSON keys, never inside the spoken content. " +
            "If a regeneration would naturally use one of those words, rephrase to avoid it.\n";

    /**
     * Regenerates the {@code scenes[]} array for an existing job, keeping the
     * same scene count. Title / hook / closing are passed in for context so
     * the new scenes flow naturally between them, but they are not modified
     * here — the caller is responsible for preserving them.
     *
     * <p>Returns the JSON array as a string so the caller can deserialize into
     * {@code List<Scene>} via the same ObjectMapper used elsewhere.
     */
    public String regenerateScenes(String question,
                                   String style,
                                   String title,
                                   String hook,
                                   String closing,
                                   int sceneCount) throws Exception {
        log.info("Regenerating {} scenes for topic: {}", sceneCount, question);

        String userPrompt = "Topic:\n" + (question == null ? "" : question) + "\n\n"
                + "Style:\n" + (style == null ? "" : style) + "\n\n"
                + "Existing title (keep flow consistent with this):\n" + (title == null ? "" : title) + "\n\n"
                + "Existing hook (your first scene should follow naturally from this):\n"
                + (hook == null ? "" : hook) + "\n\n"
                + "Existing closing (your last scene should set up this):\n"
                + (closing == null ? "" : closing) + "\n\n"
                + "Produce EXACTLY " + sceneCount + " scenes numbered 1.." + sceneCount + ".";

        String systemPrompt = "You are a professional YouTube documentary scriptwriter.\n" +
                "Regenerate ONLY the scenes array of a faceless educational video script.\n" +
                "Each scene's \"text\" is what the viewer hears — write tight, vivid, factual prose.\n" +
                "\n" +
                FORBIDDEN_WORDS_RULE +
                "\n" +
                "Return ONLY a JSON array of objects with this exact shape, nothing else " +
                "(no markdown fences, no commentary):\n" +
                "[ {\"scene\":1,\"text\":\"...\"}, {\"scene\":2,\"text\":\"...\"} ]";

        return callChatCompletion(systemPrompt, userPrompt);
    }

    /**
     * Regenerates the spoken text for a single scene, using the surrounding
     * scenes (and title / hook / closing) as flow context so the new line
     * connects naturally to its neighbours. Returns ONLY the new text string —
     * no JSON wrapper, no quotes.
     */
    public String regenerateSceneText(String question,
                                      String style,
                                      String title,
                                      String hook,
                                      String closing,
                                      int sceneId,
                                      String previousSceneText,
                                      String nextSceneText,
                                      String currentText) throws Exception {
        log.info("Regenerating scene {} text", sceneId);

        StringBuilder ctx = new StringBuilder();
        ctx.append("Topic:\n").append(question == null ? "" : question).append("\n\n");
        ctx.append("Style:\n").append(style == null ? "" : style).append("\n\n");
        if (title != null && !title.isBlank())   ctx.append("Title: ").append(title).append("\n");
        if (hook != null && !hook.isBlank())     ctx.append("Hook: ").append(hook).append("\n");
        if (closing != null && !closing.isBlank()) ctx.append("Closing: ").append(closing).append("\n");
        ctx.append("\n");
        if (previousSceneText != null && !previousSceneText.isBlank()) {
            ctx.append("Previous scene's spoken text (your line should follow on from this):\n")
                    .append(previousSceneText).append("\n\n");
        }
        if (nextSceneText != null && !nextSceneText.isBlank()) {
            ctx.append("Next scene's spoken text (your line should set this up):\n")
                    .append(nextSceneText).append("\n\n");
        }
        if (currentText != null && !currentText.isBlank()) {
            ctx.append("Current text being replaced (write something different but on-topic):\n")
                    .append(currentText).append("\n\n");
        }
        ctx.append("Write the new spoken text for slot #").append(sceneId).append(".");

        String systemPrompt = "You are a professional YouTube documentary scriptwriter.\n" +
                "Rewrite the spoken text for a single slot in an existing script. " +
                "Match the surrounding tone, keep the same approximate length, " +
                "and connect smoothly to the neighbouring lines.\n" +
                "\n" +
                FORBIDDEN_WORDS_RULE +
                "\n" +
                "Return ONLY the new spoken text — plain prose, no JSON, no quotes, no markdown, " +
                "no labels, no preamble.";

        return callChatCompletion(systemPrompt, ctx.toString());
    }
}