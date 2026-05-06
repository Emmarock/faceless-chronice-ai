package com.faceless.ai.service.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "chronicleai.image.provider", havingValue = "openai")
public class OpenAIImageGenerationService implements ImageGenerationService {

    private static final String OPENAI_IMAGES_URL = "https://api.openai.com/v1/images/generations";

    private final String openAiApiKey;
    private final String model;
    private final String size;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAIImageGenerationService(
            @Value("${chronicleai.openai.api-key}") String openAiApiKey,
            @Value("${chronicleai.openai.image-model:gpt-image-1}") String model,
            @Value("${chronicleai.openai.image-size:1536x1024}") String size,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.openAiApiKey = openAiApiKey;
        this.model = model;
        this.size = size;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public PromptStyle preferredPromptStyle() {
        return PromptStyle.DESCRIPTIVE;
    }

    @Override
    public List<Path> generateImages(String prompt, String jobId, int sceneId, int count) throws Exception {
        ensureOutputDir();

        log.info("Generating images via OpenAI for job {} scene {}: model={}, count={}",
                jobId, sceneId, model, count);

        JsonNode data = callImagesApi(prompt, count);
        if (data.isEmpty()) {
            throw new RuntimeException("OpenAI returned no images for prompt: " + prompt);
        }

        List<Path> results = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            results.add(saveImage(data.get(i), jobId, sceneId, i));
        }
        return results;
    }

    /**
     * One OpenAI image-generation call per prompt. Each call gets a different
     * prompt so the final clip cuts between distinct scenes — generative
     * models reuse seed/composition very heavily when called once with
     * {@code n>1}, which is what we want to avoid.
     */
    @Override
    public List<Path> generateImagesForPrompts(List<String> prompts, String jobId, int sceneId) throws Exception {
        ensureOutputDir();
        log.info("Generating {} per-prompt images via OpenAI for job {} scene {}",
                prompts.size(), jobId, sceneId);

        List<Path> results = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            JsonNode data = callImagesApi(prompts.get(i), 1);
            if (data.isEmpty()) {
                log.warn("OpenAI returned no image for prompt #{}/{} — skipping", i + 1, prompts.size());
                continue;
            }
            results.add(saveImage(data.get(0), jobId, sceneId, i));
        }
        if (results.isEmpty()) {
            throw new RuntimeException("OpenAI returned no images for any of the "
                    + prompts.size() + " prompts in scene " + sceneId);
        }
        return results;
    }

    private void ensureOutputDir() throws java.io.IOException {
        Path outputDir = Paths.get(IMAGE_OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
    }

    private JsonNode callImagesApi(String prompt, int n) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "prompt", prompt,
                "n", n,
                "size", size
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_IMAGES_URL))
                .header("Authorization", "Bearer " + openAiApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI image generation failed with status "
                    + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body()).path("data");
    }

    private Path saveImage(JsonNode item, String jobId, int sceneId, int index) throws Exception {
        byte[] imageBytes = decodeImageBytes(item);
        Path output = Paths.get(IMAGE_OUTPUT_DIR,
                "image_" + jobId + "_scene_" + sceneId + "_" + index + ".png");
        Files.write(output, imageBytes);
        log.info("Image saved to {}", output.toAbsolutePath());
        return output;
    }

    private byte[] decodeImageBytes(JsonNode item) throws Exception {
        // gpt-image-1 always returns b64_json; dall-e-* can return a URL when response_format=url.
        String b64 = item.path("b64_json").asText(null);
        if (b64 != null && !b64.isEmpty()) {
            return Base64.getDecoder().decode(b64);
        }

        String url = item.path("url").asText(null);
        if (url != null && !url.isEmpty()) {
            HttpRequest download = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("OpenAI image download failed with status " + resp.statusCode());
            }
            return resp.body();
        }

        throw new RuntimeException("OpenAI image payload missing both b64_json and url fields");
    }
}