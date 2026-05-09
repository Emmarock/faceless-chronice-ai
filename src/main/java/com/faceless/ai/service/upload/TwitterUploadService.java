package com.faceless.ai.service.upload;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.repository.SocialConnectionRepository;
import com.faceless.ai.service.VideoUploadRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Posts a video as a tweet on the user's authenticated Twitter / X account.
 *
 * <p>Three-stage flow per Twitter's chunked-media docs:
 * <ol>
 *   <li>INIT — declare total bytes + media type, get a {@code media_id}.</li>
 *   <li>APPEND — upload the bytes in chunks (≤ 5MB each).</li>
 *   <li>FINALIZE — tell Twitter we're done. For video, Twitter then runs an
 *       async transcode and we poll {@code STATUS} until it's ready.</li>
 * </ol>
 * Then we publish a tweet via {@code POST /2/tweets} with the {@code media_id}.
 *
 * <p>Tokens issued by the v2 OAuth flow expire after ~2 hours; we use the
 * stored refresh token to mint a new access token on demand and persist the
 * rotated values back onto the {@link SocialConnection} row.
 */
@Service
@Slf4j
public class TwitterUploadService implements VideoUploadService {

    private static final String TOKEN_URL = "https://api.twitter.com/2/oauth2/token";
    /**
     * X v2 chunked media upload uses REST-style URLs (the legacy
     * {@code command=INIT|APPEND|FINALIZE} query-param shape on
     * {@code /2/media/upload} is the simple image upload — it rejects
     * {@code command} as unknown and only accepts image MIME types).
     *
     * <ul>
     *   <li>INIT     → {@code POST {base}/initialize}        (JSON body)</li>
     *   <li>APPEND   → {@code POST {base}/{mediaId}/append}  (multipart: segment_index, media)</li>
     *   <li>FINALIZE → {@code POST {base}/{mediaId}/finalize}(empty body)</li>
     *   <li>STATUS   → {@code GET  {base}?command=STATUS&amp;media_id=...}</li>
     * </ul>
     */
    private static final String UPLOAD_BASE_URL  = "https://api.x.com/2/media/upload";
    private static final String INITIALIZE_URL   = UPLOAD_BASE_URL + "/initialize";
    private static final String TWEETS_URL = "https://api.twitter.com/2/tweets";

    private static final long CHUNK_BYTES = 4L * 1024 * 1024; // 4MB — under the 5MB Twitter cap
    private static final int  STATUS_POLL_ATTEMPTS = 60;
    private static final long STATUS_POLL_INTERVAL_MS = 5_000;

    private final SocialConnectionRepository socialConnectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final ExecutorService virtualThreadExecutor;

    public TwitterUploadService(SocialConnectionRepository socialConnectionRepository,
                                HttpClient httpClient,
                                ObjectMapper objectMapper,
                                @Value("${chronicleai.twitter.client-id}") String clientId,
                                @Value("${chronicleai.twitter.client-secret}") String clientSecret, ExecutorService virtualThreadExecutor) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.TWITTER;
    }

    /**
     * @return the resulting tweet id. The request's title and description are
     *         joined into the tweet text; either may be blank.
     */
    @Override
    public CompletableFuture<String> uploadVideo(VideoUploadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String userId = request.userId();
                Path videoFile = request.videoFile();
                SocialConnection connection =
                        socialConnectionRepository
                                .findByUserIdAndPlatform(
                                        userId,
                                        SocialPlatform.TWITTER
                                )
                                .orElseThrow(() ->
                                        new IllegalStateException(
                                                "User "
                                                        + userId
                                                        + " has no TWITTER connection."
                                        ));
                String accessToken = ensureFreshAccessToken(connection);
                long totalBytes = Files.size(videoFile);

                log.info("Twitter video upload starting (user {}, {} bytes)", userId,totalBytes);

                String mediaId = mediaInit(accessToken, totalBytes);
                mediaAppend(accessToken, mediaId, videoFile);

                JsonNode finalizeJson = mediaFinalize(accessToken, mediaId);
                if (finalizeJson.has("processing_info")) {
                    waitForProcessing(accessToken,mediaId);
                }
                return createTweet(accessToken, caption(request.title(), request.description()), mediaId);
            } catch (Exception e) {

                throw new CompletionException(e);
            }

        }, virtualThreadExecutor);
    }

    private static String caption(String title, String description) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasDesc = description != null && !description.isBlank();
        if (hasTitle && hasDesc) return title + "\n\n" + description;
        if (hasTitle) return title;
        if (hasDesc) return description;
        return "";
    }

    // ------------------------------------------------------------------ //
    //  Auth: refresh access token using the stored refresh_token
    // ------------------------------------------------------------------ //

    @Transactional
    String ensureFreshAccessToken(SocialConnection connection) throws Exception {
        boolean expired = connection.getExpiresAt() != null
                && connection.getExpiresAt().isBefore(Instant.now().plusSeconds(60));
        if (!expired) return connection.getAccessToken();
        if (connection.getRefreshToken() == null || connection.getRefreshToken().isBlank()) {
            throw new IllegalStateException(
                    "Twitter access token expired and no refresh token is stored. Reconnect on the Connections page.");
        }

        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(connection.getRefreshToken())
                + "&client_id=" + enc(clientId);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (clientSecret != null && !clientSecret.isBlank() && !"changeme".equals(clientSecret)) {
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Twitter refresh failed: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String newAccess = json.path("access_token").asText();
        String newRefresh = json.path("refresh_token").asText(connection.getRefreshToken());
        long expiresIn = json.path("expires_in").asLong(0);

        connection.setAccessToken(newAccess);
        connection.setRefreshToken(newRefresh);
        connection.setExpiresAt(expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
        socialConnectionRepository.save(connection);
        log.info("Twitter access token refreshed for user {}", connection.getUserId());
        return newAccess;
    }

    // ------------------------------------------------------------------ //
    //  Chunked media upload (v1.1)
    // ------------------------------------------------------------------ //

    private String mediaInit(String accessToken, long totalBytes) throws Exception {
        // X v2 INIT: POST /2/media/upload/initialize with a JSON body.
        // Schema (from X docs): { media_category, media_type, total_bytes,
        // optional shared, optional additional_owners }.
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "media_category", "tweet_video",
                "media_type", "video/mp4",
                "total_bytes", totalBytes));

        HttpRequest request = HttpRequest.newBuilder(URI.create(INITIALIZE_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Twitter INIT failed: " + response.statusCode() + " " + response.body());
        }
        // v2 returns { "data": { "id": "...", ... } }. Fall back to legacy
        // top-level keys in case X serves a transitional response shape.
        JsonNode json = objectMapper.readTree(response.body());
        String id = json.path("data").path("id").asText("");
        if (id.isEmpty()) id = json.path("media_id_string").asText("");
        if (id.isEmpty()) id = json.path("media_id").asText("");
        if (id.isEmpty()) {
            throw new IOException("Twitter INIT response missing media id: " + response.body());
        }
        return id;
    }

    private void mediaAppend(String accessToken, String mediaId, Path videoFile) throws Exception {
        long total = Files.size(videoFile);
        int segmentIndex = 0;
        try (var in = Files.newInputStream(videoFile)) {
            byte[] buffer = new byte[(int) Math.min(CHUNK_BYTES, total)];
            int read;
            long offset = 0;
            while ((read = in.read(buffer)) > 0) {
                byte[] chunk = (read == buffer.length) ? buffer : java.util.Arrays.copyOf(buffer, read);
                appendChunk(accessToken, mediaId, segmentIndex, chunk);
                segmentIndex++;
                offset += read;
                log.debug("Twitter APPEND segment {} ({}/{} bytes) for media {}", segmentIndex, offset, total, mediaId);
            }
        }
    }

    private void appendChunk(String accessToken, String mediaId, int segmentIndex, byte[] chunk) throws Exception {
        // X v2 APPEND: POST /2/media/upload/{id}/append. The media id moves
        // into the path; only segment_index + media binary go in the body.
        String boundary = newBoundary();
        var out = new java.io.ByteArrayOutputStream();
        writePart(out, boundary, "segment_index", String.valueOf(segmentIndex));
        writeFilePart(out, boundary, "media", "chunk.bin", chunk);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        URI uri = URI.create(UPLOAD_BASE_URL + "/" + enc(mediaId) + "/append");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Twitter APPEND segment " + segmentIndex + " failed: "
                    + response.statusCode() + " " + response.body());
        }
    }

    private JsonNode mediaFinalize(String accessToken, String mediaId) throws Exception {
        // X v2 FINALIZE: POST /2/media/upload/{id}/finalize with no body.
        URI uri = URI.create(UPLOAD_BASE_URL + "/" + enc(mediaId) + "/finalize");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Twitter FINALIZE failed: " + response.statusCode() + " " + response.body());
        }
        // v2 wraps the result under "data"; flatten to the same shape callers
        // already expect (top-level "processing_info") so waitForProcessing
        // doesn't need to know about the schema change.
        JsonNode raw = objectMapper.readTree(response.body());
        if (raw.has("data") && !raw.has("processing_info")) {
            return raw.path("data");
        }
        return raw;
    }

    private static String newBoundary() {
        return "----chronicleai" + System.nanoTime();
    }

    private void waitForProcessing(String accessToken, String mediaId) throws Exception {
        for (int i = 0; i < STATUS_POLL_ATTEMPTS; i++) {
            // STATUS still uses the legacy query-param shape on the base URL
            // — X kept it that way through the v2 migration. If they ever
            // switch to GET /2/media/upload/{id}, swap the URL here only.
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            UPLOAD_BASE_URL + "?command=STATUS&media_id=" + enc(mediaId)))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode info = objectMapper.readTree(response.body()).path("processing_info");
            String state = info.path("state").asText("");
            if ("succeeded".equals(state)) return;
            if ("failed".equals(state)) {
                throw new IOException("Twitter media processing failed: " + response.body());
            }
            long wait = info.path("check_after_secs").asLong(STATUS_POLL_INTERVAL_MS / 1000) * 1000L;
            //noinspection BusyWait
            Thread.sleep(Math.max(wait, STATUS_POLL_INTERVAL_MS));
        }
        throw new IOException("Twitter media processing did not finish in time for media_id=" + mediaId);
    }

    private String createTweet(String accessToken, String caption, String mediaId) throws Exception {
        String safeCaption = caption == null ? "" : caption;
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "text", safeCaption,
                "media", java.util.Map.of("media_ids", java.util.List.of(mediaId))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TWEETS_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Twitter tweet creation failed: " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body()).path("data").path("id").asText();
    }

    // ------------------------------------------------------------------ //
    //  Multipart helpers
    // ------------------------------------------------------------------ //

    private static void writePart(java.io.ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(java.io.ByteArrayOutputStream out, String boundary, String name, String filename, byte[] data) throws IOException {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}