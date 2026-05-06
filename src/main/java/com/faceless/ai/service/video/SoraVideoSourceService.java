package com.faceless.ai.service.video;

import com.faceless.ai.service.image.ImageGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Generative video source backed by OpenAI's Sora API
 * (https://platform.openai.com/docs/guides/video). Submits a text-to-video
 * job to {@code POST /v1/videos}, polls {@code GET /v1/videos/{id}} until the
 * job reports {@code completed}, then streams the rendered MP4 from
 * {@code GET /v1/videos/{id}/content} into the same on-disk cache used by the
 * other providers.
 *
 * <p>Activated by setting {@code chronicleai.video.provider=sora}. Reuses the
 * existing {@code chronicleai.openai.api-key} — no separate credential is
 * required since Sora is part of the OpenAI account.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code chronicleai.openai.api-key} — reused, sent as
 *       {@code Authorization: Bearer <value>}</li>
 *   <li>{@code chronicleai.sora.model} — model id, e.g. {@code sora-2} or
 *       {@code sora-2-pro}</li>
 *   <li>{@code chronicleai.sora.size} — output resolution (default
 *       {@code 1280x720} landscape, matching the renderer's canvas)</li>
 *   <li>{@code chronicleai.sora.seconds} — clip length in seconds; allowed
 *       values per the Sora API are typically 4, 8, or 12</li>
 *   <li>{@code chronicleai.sora.poll-interval-seconds} — gap between status
 *       polls (default 5)</li>
 *   <li>{@code chronicleai.sora.timeout-seconds} — give up on the request
 *       after this long (default 600)</li>
 * </ul>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.video.provider", havingValue = "sora")
public class SoraVideoSourceService implements VideoSourceService {

    private static final String OPENAI_VIDEOS_URL = "https://api.openai.com/v1/videos";

    private final String apiKey;
    private final String model;
    private final String size;
    private final int seconds;
    private final long pollIntervalSeconds;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SoraVideoSourceService(
            @Value("${chronicleai.openai.api-key}") String apiKey,
            @Value("${chronicleai.sora.model:sora-2}") String model,
            @Value("${chronicleai.sora.size:1280x720}") String size,
            @Value("${chronicleai.sora.seconds:8}") int seconds,
            @Value("${chronicleai.sora.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${chronicleai.sora.timeout-seconds:600}") long timeoutSeconds,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "chronicleai.openai.api-key is required when chronicleai.video.provider=sora");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.size = size;
        this.seconds = seconds;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public ImageGenerationService.PromptStyle preferredPromptStyle() {
        // Sora rewards descriptive cinematic prompts (camera motion, lighting,
        // subject detail) — same as fal.ai's text-to-video models.
        return ImageGenerationService.PromptStyle.DESCRIPTIVE;
    }

    @Override
    public Path generateVideo(String prompt, String jobId, int sceneId) throws Exception {
        ensureOutputDir();
        log.info("Submitting Sora job for job {} scene {} (model={}, size={}, seconds={})",
                jobId, sceneId, model, size, seconds);

        String videoId = submit(prompt);
        log.info("Sora accepted video {} for job {} scene {} — polling for completion",
                videoId, jobId, sceneId);

        waitForCompletion(videoId);
        log.info("Sora finished video {} for job {} scene {} — downloading content",
                videoId, jobId, sceneId);
        return downloadContent(videoId, jobId, sceneId);
    }

    /**
     * POSTs the prompt to {@code /v1/videos} and returns the {@code id} of
     * the queued job. Body shape is the public Sora API's
     * {@code {model, prompt, size, seconds}}.
     */
    private String submit(String prompt) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("seconds", String.valueOf(seconds));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_VIDEOS_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Sora submit failed [" + res.statusCode() + "]: " + res.body());
        }
        JsonNode root = objectMapper.readTree(res.body());
        String id = textOrNull(root, "id");
        if (id == null) {
            throw new IllegalStateException(
                    "Sora submit response did not include an id: " + res.body());
        }
        return id;
    }

    /**
     * Polls {@code GET /v1/videos/{id}} on a fixed interval until
     * {@code status} is {@code completed}. Aborts on {@code failed} /
     * {@code cancelled} or after {@link #timeoutSeconds}.
     */
    private void waitForCompletion(String videoId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> res = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(OPENAI_VIDEOS_URL + "/" + videoId))
                            .header("Authorization", "Bearer " + apiKey)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Sora status poll failed [" + res.statusCode() + "]: " + res.body());
            }
            JsonNode json = objectMapper.readTree(res.body());
            String status = textOrNull(json, "status");
            if ("completed".equalsIgnoreCase(status)) return;
            if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                String err = json.path("error").path("message").asText("(no error message)");
                throw new IllegalStateException(
                        "Sora video " + videoId + " " + status + ": " + err);
            }
            // queued / in_progress — keep polling.
            int progress = json.path("progress").asInt(-1);
            log.debug("Sora status for {}: {} (progress={}) — sleeping {}s",
                    videoId, status, progress, pollIntervalSeconds);
            Thread.sleep(Duration.ofSeconds(pollIntervalSeconds).toMillis());
        }
        throw new IllegalStateException(
                "Sora video " + videoId + " did not complete within "
                        + timeoutSeconds + "s — increase chronicleai.sora.timeout-seconds.");
    }

    /**
     * Streams the MP4 from {@code GET /v1/videos/{id}/content} into the
     * shared cache directory. The endpoint returns raw video bytes, not JSON.
     */
    private Path downloadContent(String videoId, String jobId, int sceneId) throws Exception {
        HttpResponse<InputStream> res = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(OPENAI_VIDEOS_URL + "/" + videoId + "/content"))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Sora video download failed: HTTP " + res.statusCode());
        }
        Path output = Paths.get(VIDEO_OUTPUT_DIR, "video_" + jobId + "_scene_" + sceneId + ".mp4");
        Files.copy(res.body(), output, StandardCopyOption.REPLACE_EXISTING);
        log.info("Sora video saved to {}", output.toAbsolutePath());
        return output;
    }

    private void ensureOutputDir() throws java.io.IOException {
        Path outputDir = Paths.get(VIDEO_OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}