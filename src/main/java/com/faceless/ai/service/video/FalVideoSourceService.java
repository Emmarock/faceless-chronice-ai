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
 * Generative video source backed by fal.ai's queue API
 * (https://docs.fal.ai/queue). Submits a text-to-video job, polls until the
 * job completes, then downloads the rendered MP4 to the same local cache
 * directory used by the Pexels provider.
 *
 * <p>Activated by setting {@code chronicleai.video.provider=fal}. The Pexels
 * provider remains the default — flipping the flag swaps it without any
 * change in {@link com.faceless.ai.service.consumer.ImageGenerationConsumer},
 * because both implement {@link VideoSourceService} and the consumer only
 * speaks to the interface.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code chronicleai.fal.api-key} — required, sent as
 *       {@code Authorization: Key <value>}</li>
 *   <li>{@code chronicleai.fal.model} — model id, e.g.
 *       {@code fal-ai/minimax/hailuo-02/standard/text-to-video}</li>
 *   <li>{@code chronicleai.fal.poll-interval-seconds} — gap between status
 *       polls (default 5)</li>
 *   <li>{@code chronicleai.fal.timeout-seconds} — give up on the request after
 *       this long (default 600)</li>
 * </ul>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.video.provider", havingValue = "fal")
public class FalVideoSourceService implements VideoSourceService {

    /** Base URL for the fal.ai queue API. The model id is appended as the path. */
    private static final String FAL_QUEUE_BASE = "https://queue.fal.run/";

    private final String apiKey;
    private final String modelId;
    private final long pollIntervalSeconds;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public FalVideoSourceService(
            @Value("${chronicleai.fal.api-key}") String apiKey,
            @Value("${chronicleai.fal.model:fal-ai/minimax/hailuo-02/standard/text-to-video}") String modelId,
            @Value("${chronicleai.fal.poll-interval-seconds:5}") long pollIntervalSeconds,
            @Value("${chronicleai.fal.timeout-seconds:600}") long timeoutSeconds,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "chronicleai.fal.api-key is required when chronicleai.video.provider=fal");
        }
        this.apiKey = apiKey;
        this.modelId = modelId;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public ImageGenerationService.PromptStyle preferredPromptStyle() {
        // Generative models reward descriptive cinematic prompts, not
        // stock-photo keyword strings.
        return ImageGenerationService.PromptStyle.DESCRIPTIVE;
    }

    @Override
    public Path generateVideo(String prompt, String jobId, int sceneId) throws Exception {
        ensureOutputDir();
        log.info("Submitting fal.ai job for job {} scene {} (model={})", jobId, sceneId, modelId);

        SubmitResult submitted = submit(prompt);
        log.info("fal.ai accepted request {} for job {} scene {} — polling status_url",
                submitted.requestId, jobId, sceneId);

        String videoUrl = waitForCompletion(submitted);
        log.info("fal.ai produced video for job {} scene {}: {}", jobId, sceneId, videoUrl);
        return downloadVideo(videoUrl, jobId, sceneId);
    }

    /**
     * POSTs the prompt to {@code https://queue.fal.run/{model}} and returns
     * the queue handles needed to poll for completion.
     */
    private SubmitResult submit(String prompt) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(FAL_QUEUE_BASE + modelId))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "fal.ai submit failed [" + res.statusCode() + "]: " + res.body());
        }
        JsonNode root = objectMapper.readTree(res.body());
        String requestId = textOrNull(root, "request_id");
        String statusUrl = textOrNull(root, "status_url");
        String responseUrl = textOrNull(root, "response_url");
        if (requestId == null || statusUrl == null || responseUrl == null) {
            throw new IllegalStateException(
                    "fal.ai submit response missing request_id / status_url / response_url: " + res.body());
        }
        return new SubmitResult(requestId, statusUrl, responseUrl);
    }

    /**
     * Polls {@code status_url} on a fixed interval until the queue reports
     * {@code COMPLETED}, then fetches {@code response_url} and extracts the
     * rendered video URL. Throws if the queue reports {@code FAILED} or the
     * configured timeout is exceeded.
     */
    private String waitForCompletion(SubmitResult submitted) throws Exception {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> statusRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(submitted.statusUrl))
                            .header("Authorization", "Key " + apiKey)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (statusRes.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "fal.ai status poll failed [" + statusRes.statusCode() + "]: " + statusRes.body());
            }
            JsonNode statusJson = objectMapper.readTree(statusRes.body());
            String status = textOrNull(statusJson, "status");
            if ("COMPLETED".equalsIgnoreCase(status)) {
                return fetchVideoUrl(submitted.responseUrl);
            }
            if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                throw new IllegalStateException(
                        "fal.ai request " + submitted.requestId + " failed: " + statusRes.body());
            }
            // Statuses we treat as "still working": IN_QUEUE, IN_PROGRESS, anything else.
            log.debug("fal.ai status for {}: {} — sleeping {}s", submitted.requestId, status, pollIntervalSeconds);
            Thread.sleep(Duration.ofSeconds(pollIntervalSeconds).toMillis());
        }
        throw new IllegalStateException(
                "fal.ai request " + submitted.requestId + " did not complete within "
                        + timeoutSeconds + "s — increase chronicleai.fal.timeout-seconds.");
    }

    /**
     * GETs the response_url and pulls the video URL out of the model output.
     * fal.ai conventionally returns either {@code video.url} (single clip
     * models) or {@code video_url} at the root — handle both.
     */
    private String fetchVideoUrl(String responseUrl) throws Exception {
        HttpResponse<String> res = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(responseUrl))
                        .header("Authorization", "Key " + apiKey)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "fal.ai response fetch failed [" + res.statusCode() + "]: " + res.body());
        }
        JsonNode root = objectMapper.readTree(res.body());
        JsonNode video = root.path("video");
        String url = video.isObject() ? textOrNull(video, "url") : textOrNull(root, "video_url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "fal.ai response did not contain a video URL: " + res.body());
        }
        return url;
    }

    /**
     * Streams the MP4 from the given URL into the same on-disk cache the
     * Pexels provider uses, so the consumer's S3 upload step doesn't need
     * to know which provider produced the file.
     */
    private Path downloadVideo(String url, String jobId, int sceneId) throws Exception {
        HttpResponse<InputStream> res = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() == 200) {
            throw new IllegalStateException("fal.ai video download failed: HTTP " + res.statusCode());
        }
        Path output = Paths.get(VIDEO_OUTPUT_DIR, "video_" + jobId + "_scene_" + sceneId + ".mp4");
        Files.copy(res.body(), output, StandardCopyOption.REPLACE_EXISTING);
        log.info("fal.ai video saved to {}", output.toAbsolutePath());
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

    /** Triple of queue handles returned by the submit call. */
    private record SubmitResult(String requestId, String statusUrl, String responseUrl) {}
}