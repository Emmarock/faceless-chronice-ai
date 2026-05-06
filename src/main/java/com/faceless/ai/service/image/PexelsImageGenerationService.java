package com.faceless.ai.service.image;

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
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.image.provider", havingValue = "pexels", matchIfMissing = true)
public class PexelsImageGenerationService implements ImageGenerationService {

    private static final String PEXELS_SEARCH_URL = "https://api.pexels.com/v1/search";

    private final String pexelsApiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PexelsImageGenerationService(
            @Value("${chronicleai.pexels.api-key}") String pexelsApiKey,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.pexelsApiKey = pexelsApiKey;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public PromptStyle preferredPromptStyle() {
        return PromptStyle.SEARCH_QUERY;
    }

    /**
     * Search Pexels for {@code count} landscape photos matching the query,
     * download each one, and return the local paths.
     * If fewer than {@code count} results are returned, only those are downloaded.
     */
    @Override
    public List<Path> generateImages(String prompt, String jobId, int sceneId, int count) throws Exception {
        ensureOutputDir();

        log.info("Searching Pexels for job {} scene {}: query='{}', count={}", jobId, sceneId, prompt, count);
        JsonNode photos = searchPhotos(prompt, count);
        if (photos.isEmpty()) {
            throw new RuntimeException("Pexels returned no photos for query: " + prompt);
        }

        List<Path> results = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            results.add(downloadPhoto(photos.get(i), jobId, sceneId, i));
        }
        return results;
    }

    /**
     * One Pexels search per prompt — each scene image comes from a distinct
     * query, so the final clip cuts between visually different B-roll
     * instead of N similar photos from one search hit.
     *
     * <p>If a prompt happens to return zero results we skip the index but
     * keep going; the consumer accepts a sparser list and ffmpeg handles
     * any non-empty count.
     */
    @Override
    public List<Path> generateImagesForPrompts(List<String> prompts, String jobId, int sceneId) throws Exception {
        ensureOutputDir();
        log.info("Searching Pexels with {} per-image prompts for job {} scene {}",
                prompts.size(), jobId, sceneId);

        List<Path> results = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            String prompt = prompts.get(i);
            JsonNode photos = searchPhotos(prompt, 1);
            if (photos.isEmpty()) {
                log.warn("Pexels returned no photo for prompt #{}/{} ('{}') — skipping",
                        i + 1, prompts.size(), prompt);
                continue;
            }
            results.add(downloadPhoto(photos.get(0), jobId, sceneId, i));
        }
        if (results.isEmpty()) {
            throw new RuntimeException(
                    "Pexels returned no photos for any of the " + prompts.size() + " prompts in scene " + sceneId);
        }
        return results;
    }

    private void ensureOutputDir() throws java.io.IOException {
        Path outputDir = Paths.get(IMAGE_OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
    }

    private JsonNode searchPhotos(String query, int perPage) throws Exception {
        String url = PEXELS_SEARCH_URL
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
            throw new RuntimeException("Pexels search failed with status "
                    + searchResponse.statusCode() + ": " + searchResponse.body());
        }
        return objectMapper.readTree(searchResponse.body()).path("photos");
    }

    private Path downloadPhoto(JsonNode photo, String jobId, int sceneId, int index) throws Exception {
        String imageUrl = photo.path("src").path("large2x").asText();
        log.info("Downloading Pexels image #{} for scene {}: {}", index, sceneId, imageUrl);

        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .build();

        HttpResponse<InputStream> downloadResponse =
                httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (downloadResponse.statusCode() != 200) {
            throw new RuntimeException("Image download failed with status " + downloadResponse.statusCode());
        }

        Path output = Paths.get(IMAGE_OUTPUT_DIR,
                "image_" + jobId + "_scene_" + sceneId + "_" + index + ".jpg");
        Files.copy(downloadResponse.body(), output, StandardCopyOption.REPLACE_EXISTING);
        log.info("Image saved to {}", output.toAbsolutePath());
        return output;
    }
}