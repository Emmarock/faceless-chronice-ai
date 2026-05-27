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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Posts a video to LinkedIn on behalf of an authenticated member.
 *
 * <p>LinkedIn's v2 video flow has three legs:
 * <ol>
 *   <li>{@code POST /rest/videos?action=initializeUpload} — registers the
 *       upcoming upload and returns an array of part-upload URLs plus a
 *       video URN.</li>
 *   <li>{@code PUT} each part to its signed URL in order; LinkedIn returns
 *       an {@code etag} per part.</li>
 *   <li>{@code POST /rest/videos?action=finalizeUpload} with the part etags
 *       to assemble the video.</li>
 *   <li>{@code POST /rest/posts} with the video URN as the content,
 *       authored by {@code urn:li:person:{providerAccountId}}.</li>
 * </ol>
 *
 * <p>Tokens are refreshed on demand when within 60s of expiry — LinkedIn
 * issues access tokens with a ~60-day lifetime and rotating refresh tokens.
 */
@Service
@Slf4j
public class LinkedInUploadService implements VideoUploadService {

    private static final String TOKEN_URL          = "https://www.linkedin.com/oauth/v2/accessToken";
    private static final String VIDEOS_URL         = "https://api.linkedin.com/rest/videos";
    private static final String POSTS_URL          = "https://api.linkedin.com/rest/posts";
    private static final String LINKEDIN_VERSION   = "202404";
    private static final long   PART_BYTES         = 4L * 1024 * 1024;

    private final SocialConnectionRepository socialConnectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final ExecutorService virtualThreadExecutor;

    public LinkedInUploadService(SocialConnectionRepository socialConnectionRepository,
                                 HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 @Value("${chronicleai.linkedin.client-id}") String clientId,
                                 @Value("${chronicleai.linkedin.client-secret}") String clientSecret,
                                 ExecutorService virtualThreadExecutor) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.LINKEDIN;
    }

    @Override
    public CompletableFuture<String> uploadVideo(VideoUploadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SocialConnection connection = socialConnectionRepository
                        .findByUserIdAndPlatform(request.userId(), SocialPlatform.LINKEDIN)
                        .orElseThrow(() -> new IllegalStateException(
                                "User " + request.userId() + " has no LINKEDIN connection."));

                String accessToken = ensureFreshAccessToken(connection);
                String memberId = connection.getProviderAccountId();
                if (memberId == null || memberId.isBlank()) {
                    throw new IllegalStateException(
                            "Missing LinkedIn member id for user " + request.userId()
                                    + " — reconnect on the Connections page.");
                }
                String memberUrn = "urn:li:person:" + memberId;
                long totalBytes = Files.size(request.videoFile());

                InitResult init = initializeUpload(accessToken, memberUrn, totalBytes);
                List<String> etags = uploadParts(accessToken, init.uploadUrls(), request.videoFile(), totalBytes);
                finalizeUpload(accessToken, init.videoUrn(), etags);
                String postUrn = createPost(accessToken, memberUrn, init.videoUrn(), composeText(request));
                log.info("LinkedIn upload complete for user {} (post {})", request.userId(), postUrn);
                return postUrn;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);
    }

    @Transactional
    String ensureFreshAccessToken(SocialConnection connection) throws Exception {
        boolean expired = connection.getExpiresAt() != null
                && connection.getExpiresAt().isBefore(Instant.now().plusSeconds(60));
        if (!expired) return connection.getAccessToken();
        if (connection.getRefreshToken() == null || connection.getRefreshToken().isBlank()) {
            throw new IllegalStateException(
                    "LinkedIn access token expired and no refresh token is stored. Reconnect on the Connections page.");
        }
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(connection.getRefreshToken())
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("LinkedIn refresh failed: " + res.body());
        }
        JsonNode json = objectMapper.readTree(res.body());
        String newAccess = json.path("access_token").asText();
        String newRefresh = json.path("refresh_token").asText(connection.getRefreshToken());
        long expiresIn = json.path("expires_in").asLong(0);
        connection.setAccessToken(newAccess);
        connection.setRefreshToken(newRefresh);
        connection.setExpiresAt(expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
        socialConnectionRepository.save(connection);
        log.info("LinkedIn access token refreshed for user {}", connection.getUserId());
        return newAccess;
    }

    private InitResult initializeUpload(String accessToken, String memberUrn, long totalBytes) throws Exception {
        Map<String, Object> body = Map.of(
                "initializeUploadRequest", Map.of(
                        "owner", memberUrn,
                        "fileSizeBytes", totalBytes,
                        "uploadCaptions", false,
                        "uploadThumbnail", false));
        HttpRequest req = HttpRequest.newBuilder(URI.create(VIDEOS_URL + "?action=initializeUpload"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("LinkedIn-Version", LINKEDIN_VERSION)
                .header("X-Restli-Protocol-Version", "2.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("LinkedIn initializeUpload failed: " + res.statusCode() + " " + res.body());
        }
        JsonNode value = objectMapper.readTree(res.body()).path("value");
        String videoUrn = value.path("video").asText();
        if (videoUrn == null || videoUrn.isBlank()) {
            throw new IOException("LinkedIn initializeUpload missing video urn: " + res.body());
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode instr : value.path("uploadInstructions")) {
            String url = instr.path("uploadUrl").asText();
            if (url != null && !url.isBlank()) urls.add(url);
        }
        if (urls.isEmpty()) {
            throw new IOException("LinkedIn initializeUpload returned no upload URLs: " + res.body());
        }
        return new InitResult(videoUrn, urls);
    }

    private List<String> uploadParts(String accessToken, List<String> urls, Path file, long totalBytes) throws Exception {
        List<String> etags = new ArrayList<>();
        try (var in = Files.newInputStream(file)) {
            for (int i = 0; i < urls.size(); i++) {
                long remaining = totalBytes - (i * PART_BYTES);
                int partSize = (int) Math.min(PART_BYTES, remaining);
                byte[] part = in.readNBytes(partSize);
                HttpRequest req = HttpRequest.newBuilder(URI.create(urls.get(i)))
                        .header("Authorization", "Bearer " + accessToken)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(part))
                        .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) {
                    throw new IOException("LinkedIn part " + (i + 1) + "/" + urls.size()
                            + " upload failed: " + res.statusCode() + " " + res.body());
                }
                final int partIndex = i;
                String etag = res.headers().firstValue("etag")
                        .or(() -> res.headers().firstValue("ETag"))
                        .orElseThrow(() -> new IOException(
                                "LinkedIn part " + (partIndex + 1) + " response missing etag header"));
                etags.add(stripQuotes(etag));
            }
        }
        return etags;
    }

    private void finalizeUpload(String accessToken, String videoUrn, List<String> etags) throws Exception {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < etags.size(); i++) {
            parts.add(Map.of("partNumber", i + 1, "etag", etags.get(i)));
        }
        Map<String, Object> body = Map.of(
                "finalizeUploadRequest", Map.of(
                        "video", videoUrn,
                        "uploadToken", "",
                        "uploadedPartIds", etags));
        HttpRequest req = HttpRequest.newBuilder(URI.create(VIDEOS_URL + "?action=finalizeUpload"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("LinkedIn-Version", LINKEDIN_VERSION)
                .header("X-Restli-Protocol-Version", "2.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("LinkedIn finalizeUpload failed: " + res.statusCode() + " " + res.body());
        }
    }

    private String createPost(String accessToken, String memberUrn, String videoUrn, String commentary) throws Exception {
        Map<String, Object> body = Map.of(
                "author", memberUrn,
                "commentary", commentary == null ? "" : commentary,
                "visibility", "PUBLIC",
                "distribution", Map.of(
                        "feedDistribution", "MAIN_FEED",
                        "targetEntities", List.of(),
                        "thirdPartyDistributionChannels", List.of()),
                "content", Map.of(
                        "media", Map.of(
                                "title", commentary == null ? "" : commentary,
                                "id", videoUrn)),
                "lifecycleState", "PUBLISHED",
                "isReshareDisabledByAuthor", false);
        HttpRequest req = HttpRequest.newBuilder(URI.create(POSTS_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("LinkedIn-Version", LINKEDIN_VERSION)
                .header("X-Restli-Protocol-Version", "2.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("LinkedIn /rest/posts failed: " + res.statusCode() + " " + res.body());
        }
        // LinkedIn returns the post URN in the `x-restli-id` header.
        return res.headers().firstValue("x-restli-id")
                .or(() -> res.headers().firstValue("X-RestLi-Id"))
                .orElseGet(() -> {
                    try {
                        return objectMapper.readTree(res.body()).path("id").asText("");
                    } catch (Exception ignored) {
                        return "";
                    }
                });
    }

    private static String composeText(VideoUploadRequest request) {
        if (request.caption() != null && !request.caption().isBlank()) {
            return appendHashtags(request.caption(), request.hashtags());
        }
        boolean hasTitle = request.title() != null && !request.title().isBlank();
        boolean hasDesc = request.description() != null && !request.description().isBlank();
        String body;
        if (hasTitle && hasDesc) body = request.title() + "\n\n" + request.description();
        else if (hasTitle)       body = request.title();
        else if (hasDesc)        body = request.description();
        else                     body = "";
        return appendHashtags(body, request.hashtags());
    }

    private static String appendHashtags(String body, List<String> tags) {
        if (tags == null || tags.isEmpty()) return body;
        StringBuilder sb = new StringBuilder(body == null ? "" : body);
        if (sb.length() > 0) sb.append("\n\n");
        for (int i = 0; i < tags.size(); i++) {
            String t = tags.get(i);
            sb.append(t.startsWith("#") ? t : "#" + t);
            if (i < tags.size() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private static String stripQuotes(String etag) {
        if (etag == null) return null;
        String trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record InitResult(String videoUrn, List<String> uploadUrls) {}
}
