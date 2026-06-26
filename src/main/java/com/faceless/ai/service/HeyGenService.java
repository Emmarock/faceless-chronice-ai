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
    //  Avatar onboarding (Talking Photo)
    // ------------------------------------------------------------------ //

    /**
     * Uploads a still image and returns its {@code talking_photo_id} — an
     * immediately-usable "talking photo" avatar. There is no async training:
     * the returned id can be rendered straight away via
     * {@link #generateLessonVideo}.
     *
     * <p>This is the avatar path available on standard HeyGen API plans. The
     * video-based avatar + voice-clone product (HeyGen "Digital Twin" /
     * Video Avatar API) is Enterprise-only and requires consent footage at
     * public URLs with multi-hour training, so it is intentionally not used
     * here. The caller extracts a frame from the user's clip and passes the
     * image in. Voice is supplied separately (the configured default voice),
     * since this path does not clone the speaker's voice.
     */
    public String uploadTalkingPhoto(Path image) throws Exception {
        requireConfigured();
        byte[] bytes = Files.readAllBytes(image);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl + "/v1/talking_photo"))
                .header("x-api-key", apiKey)
                .header("Content-Type", contentTypeFor(image))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        JsonNode data = unwrap(http.send(request, HttpResponse.BodyHandlers.ofString()), "talking photo upload");
        String id = firstNonBlank(data.path("talking_photo_id").asText(null), data.path("id").asText(null));
        if (id == null) {
            throw new ExternalApiException("HeyGen talking photo upload returned no id: " + data);
        }
        return id;
    }

    /**
     * Clones the speaker's voice from an audio sample (HeyGen Instant Voice
     * Clone, {@code POST /v3/voices/clone}; Creator plan and above). Uploads
     * the audio via the asset endpoint, then requests the clone, returning the
     * resulting {@code voice_id} for use in {@link #generateLessonVideo}.
     *
     * <p>~30s of clear speech is enough for an instant clone. NOTE: the v3
     * clone request/response field names are not in the public OpenAPI spec
     * (they live in the authenticated dashboard reference). The body keys and
     * response paths below are best-effort and isolated here — if HeyGen names
     * them differently, this is the one method to adjust. Callers treat a
     * failure as non-fatal and fall back to the default voice.
     */
    public String cloneVoice(Path audioFile, String name) throws Exception {
        requireConfigured();

        // 1) Upload the audio sample → asset id / url.
        byte[] bytes = Files.readAllBytes(audioFile);
        HttpRequest upload = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl + "/v1/asset"))
                .header("x-api-key", apiKey)
                .header("Content-Type", contentTypeFor(audioFile))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        JsonNode asset = unwrap(http.send(upload, HttpResponse.BodyHandlers.ofString()), "audio asset upload");
        String audioAssetId = firstNonBlank(asset.path("asset_id").asText(null), asset.path("id").asText(null));
        String audioUrl = asset.path("url").asText(null);

        // 2) Request the clone.
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (audioAssetId != null) body.put("audio_asset_id", audioAssetId);
        else if (audioUrl != null) body.put("audio_url", audioUrl);
        else throw new ExternalApiException("Audio upload returned neither an asset id nor a url: " + asset);

        JsonNode data = post(baseUrl + "/v3/voices/clone", body);
        String voiceId = firstNonBlank(
                data.path("voice_id").asText(null),
                data.path("id").asText(null),
                data.path("voice").path("voice_id").asText(null));
        if (voiceId == null) {
            throw new ExternalApiException("HeyGen voice clone returned no voice_id: " + data);
        }
        return voiceId;
    }

    // ------------------------------------------------------------------ //
    //  Lesson rendering
    // ------------------------------------------------------------------ //

    /**
     * Submits a talking-photo render of {@code scriptText}. Returns the HeyGen
     * render job id to poll via {@link #getVideoStatus}. {@code talkingPhotoId}
     * is the id from {@link #uploadTalkingPhoto}; when {@code voiceId} is blank
     * it falls back to the configured default voice.
     */
    public String generateLessonVideo(String talkingPhotoId, String voiceId, String scriptText) throws Exception {
        requireConfigured();
        String voice = firstNonBlank(voiceId, defaultVoiceId);
        if (voice == null) {
            throw new ExternalApiException(
                    "No HeyGen voice available — set chronicleai.heygen.default-voice-id (HEYGEN_DEFAULT_VOICE_ID).");
        }

        Map<String, Object> character = new HashMap<>();
        character.put("type", "talking_photo");
        character.put("talking_photo_id", talkingPhotoId);

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
        if (name.endsWith(".mp3"))  return "audio/mpeg";
        if (name.endsWith(".wav"))  return "audio/wav";
        if (name.endsWith(".m4a"))  return "audio/mp4";
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

    /** Snapshot of a render job. */
    public record VideoStatus(String status, String videoUrl, Integer durationSeconds) {
        public boolean isReady()  { return "completed".equalsIgnoreCase(status) && videoUrl != null && !videoUrl.isBlank(); }
        public boolean isFailed() { return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status); }
    }
}
