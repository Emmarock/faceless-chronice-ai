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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Publishes a Reels video to an Instagram Business account via the Facebook
 * Graph API.
 *
 * <p>Three-stage flow (per Meta's IG content-publish docs):
 * <ol>
 *   <li>{@code POST /{ig_user_id}/media} with {@code media_type=REELS} +
 *       {@code video_url=<presigned-mp4>} + {@code caption=<text>}.
 *       Returns a container id.</li>
 *   <li>Poll {@code GET /{container_id}?fields=status_code} until status is
 *       {@code FINISHED}. IG transcodes server-side; this can take up to a
 *       minute for short clips.</li>
 *   <li>{@code POST /{ig_user_id}/media_publish} with
 *       {@code creation_id=<container_id>}. Returns the IG media id we
 *       persist as {@code providerPostId}.</li>
 * </ol>
 *
 * <p>The {@code SocialConnection} for IG stores the linked Page access token
 * as {@code accessToken} and the IG Business Account id as
 * {@code providerAccountId} — both populated by
 * {@link com.faceless.ai.service.authorization.InstagramOAuthService} on
 * connect. The token does not rotate (it's a long-lived Page token), so
 * there is no refresh step here.
 */
@Service
@Slf4j
public class InstagramUploadService implements VideoUploadService {

    private static final int  STATUS_POLL_ATTEMPTS    = 60;
    private static final long STATUS_POLL_INTERVAL_MS = 5_000L;

    private final SocialConnectionRepository socialConnectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiVersion;
    private final ExecutorService virtualThreadExecutor;

    public InstagramUploadService(SocialConnectionRepository socialConnectionRepository,
                                  HttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  @Value("${chronicleai.facebook.api-version:v25.0}") String apiVersion,
                                  ExecutorService virtualThreadExecutor) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiVersion = apiVersion;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.INSTAGRAM;
    }

    @Override
    public CompletableFuture<String> uploadVideo(VideoUploadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SocialConnection connection = socialConnectionRepository
                        .findByUserIdAndPlatform(request.userId(), SocialPlatform.INSTAGRAM)
                        .orElseThrow(() -> new IllegalStateException(
                                "User " + request.userId() + " has no INSTAGRAM connection. "
                                        + "Reconnect on the Connections page and retry."));

                String pageToken = connection.getAccessToken();
                String igUserId = connection.getProviderAccountId();
                if (pageToken == null || pageToken.isBlank()) {
                    throw new IllegalStateException("Missing Instagram page access token for user " + request.userId());
                }
                if (igUserId == null || igUserId.isBlank()) {
                    throw new IllegalStateException(
                            "Missing Instagram Business Account id for user " + request.userId()
                                    + " — reconnect on the Connections page to refresh the linked IG handle.");
                }
                if (request.publicVideoUrl() == null || request.publicVideoUrl().isBlank()) {
                    throw new IllegalStateException(
                            "Instagram requires a publicly fetchable video URL. None was provided.");
                }

                String caption = composeCaption(request);
                String containerId = createContainer(igUserId, pageToken, request.publicVideoUrl(), caption);
                waitUntilFinished(containerId, pageToken);
                String mediaId = publish(igUserId, pageToken, containerId);
                log.info("Instagram upload complete for user {} (media id {})", request.userId(), mediaId);
                return mediaId;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, virtualThreadExecutor);
    }

    private String createContainer(String igUserId, String pageToken, String videoUrl, String caption) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/" + enc(igUserId) + "/media"
                + "?media_type=REELS"
                + "&video_url=" + enc(videoUrl)
                + (caption == null || caption.isBlank() ? "" : "&caption=" + enc(caption))
                + "&access_token=" + enc(pageToken);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Instagram /media create failed: " + res.statusCode() + " " + res.body());
        }
        String id = objectMapper.readTree(res.body()).path("id").asText("");
        if (id.isBlank()) {
            throw new IOException("Instagram /media response missing container id: " + res.body());
        }
        return id;
    }

    private void waitUntilFinished(String containerId, String pageToken) throws Exception {
        for (int i = 0; i < STATUS_POLL_ATTEMPTS; i++) {
            String url = "https://graph.facebook.com/" + apiVersion + "/" + enc(containerId)
                    + "?fields=status_code"
                    + "&access_token=" + enc(pageToken);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("Instagram status poll failed: " + res.statusCode() + " " + res.body());
            }
            JsonNode json = objectMapper.readTree(res.body());
            String status = json.path("status_code").asText("");
            if ("FINISHED".equals(status)) return;
            if ("ERROR".equals(status) || "EXPIRED".equals(status)) {
                throw new IOException("Instagram media container terminal status: " + status + " body=" + res.body());
            }
            //noinspection BusyWait
            Thread.sleep(STATUS_POLL_INTERVAL_MS);
        }
        throw new IOException("Instagram media container did not finish processing in time: " + containerId);
    }

    private String publish(String igUserId, String pageToken, String containerId) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/" + enc(igUserId) + "/media_publish"
                + "?creation_id=" + enc(containerId)
                + "&access_token=" + enc(pageToken);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Instagram media_publish failed: " + res.statusCode() + " " + res.body());
        }
        String id = objectMapper.readTree(res.body()).path("id").asText("");
        if (id.isBlank()) {
            throw new IOException("Instagram media_publish missing id: " + res.body());
        }
        return id;
    }

    /**
     * Build the caption block IG will render under the Reel. The user's
     * per-platform caption (set in the redesigned PublishModal) wins when
     * present; otherwise we fall back to the video title + description.
     * Hashtags get a trailing block so they sit visually separate from the
     * body — matches IG creator-best-practice.
     */
    private static String composeCaption(VideoUploadRequest request) {
        String body;
        if (request.caption() != null && !request.caption().isBlank()) {
            body = request.caption();
        } else {
            boolean hasTitle = request.title() != null && !request.title().isBlank();
            boolean hasDesc = request.description() != null && !request.description().isBlank();
            if (hasTitle && hasDesc) body = request.title() + "\n\n" + request.description();
            else if (hasTitle)       body = request.title();
            else if (hasDesc)        body = request.description();
            else                     body = "";
        }
        List<String> tags = request.hashtags() == null ? List.of() : request.hashtags();
        if (tags.isEmpty()) return body;
        StringBuilder sb = new StringBuilder(body);
        if (!body.isEmpty()) sb.append("\n\n");
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            if (tag.startsWith("#")) sb.append(tag);
            else sb.append("#").append(tag);
            if (i < tags.size() - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
