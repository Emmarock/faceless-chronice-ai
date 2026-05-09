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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Posts a video to the user's TikTok account using the Content Posting API.
 *
 * <p>Two-stage flow:
 * <ol>
 *   <li>POST {@code /v2/post/publish/inbox/video/init/} — declares the upload
 *       and gets back a {@code publish_id} plus an {@code upload_url}.</li>
 *   <li>PUT the video bytes to {@code upload_url} with a {@code Content-Range}
 *       header. We use a single-chunk PUT for simplicity.</li>
 * </ol>
 * The video lands in the user's TikTok inbox as a draft they can review and
 * publish; this is the lower-permission path that doesn't require the more
 * sensitive {@code video.publish} scope to be reviewed.
 *
 * <p>Access tokens last ~24h; we refresh on demand using the stored
 * refresh token and rotate both values back onto the row.
 */
@Service
@Slf4j
public class TikTokUploadService implements VideoUploadService {

    private static final String TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/";
    private static final String INIT_URL = "https://open.tiktokapis.com/v2/post/publish/inbox/video/init/";
    private static final String STATUS_URL = "https://open.tiktokapis.com/v2/post/publish/status/fetch/";

    private static final int  STATUS_POLL_ATTEMPTS = 60;
    private static final long STATUS_POLL_INTERVAL_MS = 5_000;
    private final ExecutorService virtualThreadExecutor;
    private final SocialConnectionRepository socialConnectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientKey;
    private final String clientSecret;

    public TikTokUploadService(ExecutorService virtualThreadExecutor, SocialConnectionRepository socialConnectionRepository,
                               HttpClient httpClient,
                               ObjectMapper objectMapper,
                               @Value("${chronicleai.tiktok.client-key}") String clientKey,
                               @Value("${chronicleai.tiktok.client-secret}") String clientSecret) {
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientKey = clientKey;
        this.clientSecret = clientSecret;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.TIKTOK;
    }

    /**
     * @return TikTok's {@code publish_id}. The actual TikTok video id is only
     *         available once the user publishes the inbox draft, so we keep
     *         this as the durable handle. The request's title/description are
     *         ignored — the inbox flow doesn't accept caption metadata; the
     *         user fills it in when they publish the draft.
     */

    @Override
    public CompletableFuture<String> uploadVideo(VideoUploadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doUpload(request);
            } catch (Exception e) {

                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);
    }

    public String doUpload(VideoUploadRequest request) throws Exception {
        String userId = request.userId();
        Path videoFile = request.videoFile();
        SocialConnection connection = socialConnectionRepository
                .findByUserIdAndPlatform(userId, SocialPlatform.TIKTOK)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + userId + " has no TIKTOK connection."));

        String accessToken = ensureFreshAccessToken(connection);
        long size = Files.size(videoFile);

        // Init: declare the upload, get a signed upload URL.
        Map<String, Object> initBody = Map.of(
                "source_info", Map.of(
                        "source", "FILE_UPLOAD",
                        "video_size", size,
                        "chunk_size", size,
                        "total_chunk_count", 1));

        HttpRequest initReq = HttpRequest.newBuilder(URI.create(INIT_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(initBody)))
                .build();
        HttpResponse<String> initResp = httpClient.send(initReq, HttpResponse.BodyHandlers.ofString());
        if (initResp.statusCode() / 100 != 2) {
            throw new IOException("TikTok init failed: " + initResp.statusCode() + " " + initResp.body());
        }
        JsonNode initJson = objectMapper.readTree(initResp.body());
        String publishId = initJson.path("data").path("publish_id").asText();
        String uploadUrl = initJson.path("data").path("upload_url").asText();
        if (publishId.isBlank() || uploadUrl.isBlank()) {
            throw new IOException("TikTok init missing publish_id or upload_url: " + initResp.body());
        }
        log.info("TikTok upload initialized for user {} (publish_id={})", userId, publishId);

        // Upload: single-chunk PUT to the signed URL.
        byte[] bytes = Files.readAllBytes(videoFile);
        HttpRequest uploadReq = HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "video/mp4")
                .header("Content-Range", "bytes 0-" + (size - 1) + "/" + size)
                .PUT(
                        HttpRequest.BodyPublishers.ofInputStream(
                                () -> {
                                    try {
                                        return new BufferedInputStream(
                                                Files.newInputStream(videoFile)
                                        );
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                }
                        )
                )
                .build();
        HttpResponse<String> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        if (uploadResp.statusCode() / 100 != 2) {
            throw new IOException("TikTok upload PUT failed: " + uploadResp.statusCode() + " " + uploadResp.body());
        }

        waitForProcessing(accessToken, publishId);
        return publishId;
    }

    @Transactional
    String ensureFreshAccessToken(SocialConnection connection) throws Exception {
        boolean expired = connection.getExpiresAt() != null
                && connection.getExpiresAt().isBefore(Instant.now().plusSeconds(60));
        if (!expired) return connection.getAccessToken();
        if (connection.getRefreshToken() == null || connection.getRefreshToken().isBlank()) {
            throw new IllegalStateException(
                    "TikTok access token expired and no refresh token is stored. Reconnect on the Connections page.");
        }

        String body = "client_key=" + enc(clientKey)
                + "&client_secret=" + enc(clientSecret)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(connection.getRefreshToken());
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("TikTok refresh failed: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String newAccess = json.path("access_token").asText();
        String newRefresh = json.path("refresh_token").asText(connection.getRefreshToken());
        long expiresIn = json.path("expires_in").asLong(0);

        connection.setAccessToken(newAccess);
        connection.setRefreshToken(newRefresh);
        connection.setExpiresAt(expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
        socialConnectionRepository.save(connection);
        log.info("TikTok access token refreshed for user {}", connection.getUserId());
        return newAccess;
    }

    private void waitForProcessing(String accessToken, String publishId) throws Exception {
        for (int i = 0; i < STATUS_POLL_ATTEMPTS; i++) {
            Map<String, Object> body = Map.of("publish_id", publishId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(STATUS_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            String status = data.path("status").asText("");
            if ("PROCESSING_DOWNLOAD".equals(status) || "PROCESSING_UPLOAD".equals(status)) {
                //noinspection BusyWait
                Thread.sleep(STATUS_POLL_INTERVAL_MS);
                continue;
            }
            if ("FAILED".equals(status)) {
                throw new IOException("TikTok upload failed: " + response.body());
            }
            // SEND_TO_USER_INBOX, PUBLISH_COMPLETE, etc. — done.
            return;
        }
        log.warn("TikTok upload still processing after {} attempts (publish_id={}); leaving status to platform",
                STATUS_POLL_ATTEMPTS, publishId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}