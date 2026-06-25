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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the HeyGen API. All HeyGen wire details (endpoints, header
 * name, JSON field paths) are isolated to this one class so the rest of the
 * vertical works in terms of stable domain calls.
 *
 * <p>Two operations are the stable, widely-used HeyGen v2/v1 surface:
 * <ul>
 *   <li>{@link #generateLessonVideo} — {@code POST /v2/video/generate}</li>
 *   <li>{@link #getVideoStatus} — {@code GET /v1/video_status.get}</li>
 * </ul>
 * Avatar onboarding (asset upload + instant-avatar training + voice clone) is
 * plan-gated on HeyGen and its exact shapes vary by account tier; those calls
 * target the documented v2 endpoints and may need a one-line adjustment in this
 * file to match the account's plan / current docs. Everything is keyed off
 * {@code chronicleai.heygen.*}; with no API key the service refuses to make a
 * call (see {@link #requireConfigured}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HeyGenService {

    @Value("${chronicleai.heygen.api-key:}")
    private String apiKey;
    @Value("${chronicleai.heygen.base-url:https://api.heygen.com}")
    private String baseUrl;
    @Value("${chronicleai.heygen.upload-url:https://upload.heygen.com}")
    private String uploadUrl;
    @Value("${chronicleai.heygen.default-voice-id:}")
    private String defaultVoiceId;

    private static final int CANVAS_WIDTH = 1280;
    private static final int CANVAS_HEIGHT = 720;

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String defaultVoiceId() {
        return defaultVoiceId;
    }

    // ------------------------------------------------------------------ //
    //  Avatar onboarding
    // ------------------------------------------------------------------ //

    /**
     * Uploads the user's source clip to HeyGen and kicks off instant-avatar
     * training (avatar + voice clone). Returns the HeyGen-side training handle
     * to poll via {@link #getAvatarStatus}.
     */
    public String submitAvatarTraining(Path sourceVideo, String name) throws Exception {
        requireConfigured();
        String assetUrl = uploadAsset(sourceVideo);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("video_url", assetUrl);

        JsonNode data = post(baseUrl + "/v2/avatar/train", body);
        String trainingId = firstNonBlank(data.path("train_id").asText(null),
                data.path("id").asText(null));
        if (trainingId == null) {
            throw new ExternalApiException("HeyGen avatar training returned no id: " + data);
        }
        return trainingId;
    }

    /**
     * Polls a training job. Returns the current {@link AvatarStatus}; when
     * {@code ready} is true, {@code avatarId} (and {@code voiceId} when the
     * plan supports cloning) are populated.
     */
    public AvatarStatus getAvatarStatus(String trainingId) throws Exception {
        requireConfigured();
        JsonNode data = get(baseUrl + "/v2/avatar/train/status?train_id=" + enc(trainingId));
        String status = data.path("status").asText("");
        String avatarId = firstNonBlank(data.path("avatar_id").asText(null),
                data.path("talking_photo_id").asText(null));
        String voiceId = data.path("voice_id").asText(null);
        return new AvatarStatus(status, avatarId, voiceId);
    }

    /**
     * Uploads a local file to HeyGen's asset endpoint and returns its hosted
     * URL. Content-Type is inferred from the file extension (HeyGen requires a
     * concrete media type, not octet-stream).
     */
    public String uploadAsset(Path file) throws Exception {
        requireConfigured();
        byte[] bytes = Files.readAllBytes(file);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl + "/v1/asset"))
                .header("x-api-key", apiKey)
                .header("Content-Type", contentTypeFor(file))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode data = unwrap(response, "asset upload");
        String url = firstNonBlank(data.path("url").asText(null), data.path("id").asText(null));
        if (url == null) {
            throw new ExternalApiException("HeyGen asset upload returned no url: " + response.body());
        }
        return url;
    }

    // ------------------------------------------------------------------ //
    //  Lesson rendering
    // ------------------------------------------------------------------ //

    /**
     * Submits a talking-avatar render of {@code scriptText} read in the twin's
     * likeness/voice. Returns the HeyGen render job id to poll via
     * {@link #getVideoStatus}. When {@code voiceId} is blank, falls back to the
     * configured default voice.
     */
    public String generateLessonVideo(String avatarId, String voiceId, String scriptText) throws Exception {
        requireConfigured();
        String voice = firstNonBlank(voiceId, defaultVoiceId);
        if (voice == null) {
            throw new ExternalApiException(
                    "No HeyGen voice available — set chronicleai.heygen.default-voice-id "
                            + "or train a twin whose plan supports voice cloning.");
        }

        Map<String, Object> character = new HashMap<>();
        character.put("type", "avatar");
        character.put("avatar_id", avatarId);
        character.put("avatar_style", "normal");

        Map<String, Object> voicePayload = new HashMap<>();
        voicePayload.put("type", "text");
        voicePayload.put("input_text", scriptText);
        voicePayload.put("voice_id", voice);

        Map<String, Object> videoInput = new HashMap<>();
        videoInput.put("character", character);
        videoInput.put("voice", voicePayload);

        Map<String, Object> body = new HashMap<>();
        body.put("video_inputs", List.of(videoInput));
        body.put("dimension", Map.of("width", CANVAS_WIDTH, "height", CANVAS_HEIGHT));

        JsonNode data = post(baseUrl + "/v2/video/generate", body);
        String videoId = data.path("video_id").asText(null);
        if (videoId == null) {
            throw new ExternalApiException("HeyGen video generate returned no video_id: " + data);
        }
        return videoId;
    }

    /** Polls a render job. {@code videoUrl} is populated once status==completed. */
    public VideoStatus getVideoStatus(String videoId) throws Exception {
        requireConfigured();
        JsonNode data = get(baseUrl + "/v1/video_status.get?video_id=" + enc(videoId));
        String status = data.path("status").asText("");
        String videoUrl = data.path("video_url").asText(null);
        Integer duration = data.has("duration") && !data.path("duration").isNull()
                ? (int) Math.round(data.path("duration").asDouble())
                : null;
        return new VideoStatus(status, videoUrl, duration);
    }

    // ------------------------------------------------------------------ //
    //  HTTP helpers
    // ------------------------------------------------------------------ //

    private JsonNode post(String url, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return unwrap(http.send(request, HttpResponse.BodyHandlers.ofString()), url);
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        return unwrap(http.send(request, HttpResponse.BodyHandlers.ofString()), url);
    }

    /**
     * Validates the HTTP status and HeyGen's envelope ({@code {code, data,
     * error}} on most v2 endpoints) and returns the {@code data} node. Throws
     * {@link ExternalApiException} on transport or API errors so the real
     * message surfaces in logs and the poller can mark the row FAILED.
     */
    private JsonNode unwrap(HttpResponse<String> response, String context) throws Exception {
        if (response.statusCode() / 100 != 2) {
            throw new ExternalApiException(
                    "HeyGen " + context + " failed [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode error = root.path("error");
        if (!error.isNull() && !error.isMissingNode() && error.size() > 0) {
            throw new ExternalApiException("HeyGen " + context + " error: " + error);
        }
        return root.has("data") && !root.path("data").isNull() ? root.path("data") : root;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "HeyGen is not configured — set HEYGEN_API_KEY to enable the AI Tutor feature.");
        }
    }

    private static String contentTypeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".mp4"))  return "video/mp4";
        if (name.endsWith(".mov"))  return "video/quicktime";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Snapshot of an avatar-training job. */
    public record AvatarStatus(String status, String avatarId, String voiceId) {
        public boolean isReady()  { return avatarId != null && !avatarId.isBlank(); }
        public boolean isFailed() { return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status); }
    }

    /** Snapshot of a render job. */
    public record VideoStatus(String status, String videoUrl, Integer durationSeconds) {
        public boolean isReady()  { return "completed".equalsIgnoreCase(status) && videoUrl != null && !videoUrl.isBlank(); }
        public boolean isFailed() { return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status); }
    }
}
