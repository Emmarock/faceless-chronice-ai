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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Stock-footage provider backed by the Pexels Videos API
 * (https://www.pexels.com/api/documentation/#videos). Reuses the existing
 * {@code chronicleai.pexels.api-key} — Pexels' single key authorizes both
 * the photo and video endpoints.
 *
 * <p>Active by default ({@code matchIfMissing = true}); flip
 * {@code chronicleai.video.provider} to {@code fal} (or another future
 * provider id) to swap implementations. Each implementation registers
 * itself via {@link ConditionalOnProperty}; only one bean is active at a time.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.video.provider", havingValue = "pexels", matchIfMissing = true)
public class PexelsVideoSourceService implements VideoSourceService {

    private static final String PEXELS_VIDEO_SEARCH_URL = "https://api.pexels.com/videos/search";

    /**
     * Skip 4K/larger files — they pull in 50+ MB per scene, slow the render
     * pipeline, and yield no quality win once we re-encode at 1280×720.
     * The Pexels response orders {@code video_files} arbitrarily; we pick
     * the highest-resolution entry whose width is ≤ this cap.
     */
    private static final int MAX_WIDTH = 1920;

    private final String pexelsApiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PexelsVideoSourceService(
            @Value("${chronicleai.pexels.api-key}") String pexelsApiKey,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.pexelsApiKey = pexelsApiKey;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public ImageGenerationService.PromptStyle preferredPromptStyle() {
        // Pexels is keyword search, same as the photo endpoint.
        return ImageGenerationService.PromptStyle.SEARCH_QUERY;
    }

    @Override
    public Path generateVideo(String prompt, String jobId, int sceneId) throws Exception {
        ensureOutputDir();

        log.info("Searching Pexels videos for job {} scene {}: query='{}'", jobId, sceneId, prompt);
        JsonNode videos = searchVideos(prompt, 1);
        if (videos.isEmpty()) {
            throw new RuntimeException("Pexels returned no videos for query: " + prompt);
        }
        return downloadBestRendition(videos.get(0), jobId, sceneId);
    }

    private void ensureOutputDir() throws java.io.IOException {
        Path outputDir = Paths.get(VIDEO_OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
    }

    private JsonNode searchVideos(String query, int perPage) throws Exception {
        // orientation=landscape mirrors the photo path so the final 1280×720
        // canvas isn't filled by portrait clips with letterbox bars.
        String url = PEXELS_VIDEO_SEARCH_URL
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&per_page=" + perPage
                + "&orientation=landscape";

        HttpRequest searchRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", pexelsApiKey)
                .GET()
                .build();
        HttpResponse<String> searchResponse = httpClient.send(searchRequest, HttpResponse.BodyHandlers.ofString());
        if (searchResponse.statusCode() != 200) {
            throw new RuntimeException("Pexels video search failed with status "
                    + searchResponse.statusCode() + ": " + searchResponse.body());
        }
        return objectMapper.readTree(searchResponse.body()).path("videos");
    }

    /**
     * Pick the best-fit MP4 rendition from a video object. Pexels returns
     * multiple {@code video_files} entries (typically 240p / 360p / 720p /
     * 1080p / 4K); we want the highest resolution that is ≤ {@link #MAX_WIDTH}
     * AND an MP4. Falls back to the first MP4 entry if no entry meets the
     * width cap.
     */
    private Path downloadBestRendition(JsonNode video, String jobId, int sceneId) throws Exception {
        JsonNode files = video.path("video_files");
        if (!files.isArray() || files.isEmpty()) {
            throw new RuntimeException("Pexels video had no video_files array");
        }

        JsonNode best = null;
        int bestWidth = -1;
        JsonNode firstMp4 = null;
        for (JsonNode f : files) {
            String type = f.path("file_type").asText("");
            if (!"video/mp4".equals(type)) continue;
            if (firstMp4 == null) firstMp4 = f;
            int width = f.path("width").asInt(0);
            if (width <= MAX_WIDTH && width > bestWidth) {
                bestWidth = width;
                best = f;
            }
        }
        JsonNode chosen = best != null ? best : firstMp4;
        if (chosen == null) {
            throw new RuntimeException("No MP4 rendition found in Pexels video_files");
        }
        String videoUrl = chosen.path("link").asText();
        log.info("Downloading Pexels video for scene {} ({}x{}): {}",
                sceneId, chosen.path("width").asInt(), chosen.path("height").asInt(), videoUrl);

        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .GET()
                .build();
        HttpResponse<InputStream> downloadResponse =
                httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (downloadResponse.statusCode() != 200) {
            throw new RuntimeException("Video download failed with status " + downloadResponse.statusCode());
        }

        Path output = Paths.get(VIDEO_OUTPUT_DIR,
                "video_" + jobId + "_scene_" + sceneId + ".mp4");
        Files.copy(downloadResponse.body(), output, StandardCopyOption.REPLACE_EXISTING);
        log.info("Source video saved to {}", output.toAbsolutePath());
        return output;
    }
}