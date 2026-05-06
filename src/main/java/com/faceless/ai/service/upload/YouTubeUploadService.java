package com.faceless.ai.service.upload;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.repository.SocialConnectionRepository;
import com.faceless.ai.service.VideoUploadRequest;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Uploads a rendered video to a specific user's YouTube channel.
 *
 * <p>Stateless: each call resolves the user's {@link SocialConnection} and
 * builds a fresh {@link YouTube} client using those tokens. When a refresh
 * token is present we wire up {@link UserCredentials}, which lets Google's
 * auth library automatically refresh the access token whenever it expires —
 * so this class can keep uploading on the user's behalf indefinitely. When
 * only an access token is available (legacy implicit-flow connections) we
 * fall back to a one-shot non-refreshing credential.
 */
@Service
@Slf4j
public class YouTubeUploadService implements VideoUploadService {

    private static final String APP_NAME = "FacelessChronicleAI";
    private static final List<String> SCOPES =
            List.of("https://www.googleapis.com/auth/youtube.upload");

    private final SocialConnectionRepository socialConnectionRepository;
    private final String clientId;
    private final String clientSecret;

    public YouTubeUploadService(SocialConnectionRepository socialConnectionRepository,
                                @Value("${chronicleai.youtube.client-id}") String clientId,
                                @Value("${chronicleai.youtube.client-secret}") String clientSecret) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.YOUTUBE;
    }

    @Override
    public String uploadVideo(VideoUploadRequest request) throws Exception {
        SocialConnection connection = socialConnectionRepository
                .findByUserIdAndPlatform(request.userId(), SocialPlatform.YOUTUBE)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + request.userId() + " has no YOUTUBE connection. " +
                                "Reconnect on the Connections page and retry."));

        // Only fail on expiry when we have nothing to refresh with — a refresh
        // token lets Google's library mint a new access token on demand, so an
        // expired access_token is fine in that case.
        boolean hasRefresh = connection.getRefreshToken() != null
                && !connection.getRefreshToken().isBlank();
        boolean hasAccess = connection.getAccessToken() != null
                && !connection.getAccessToken().isBlank();
        if (!hasAccess && !hasRefresh) {
            throw new IllegalArgumentException("Missing YouTube credentials for this user.");
        }
        if (!hasRefresh
                && connection.getExpiresAt() != null
                && connection.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException(
                    "YOUTUBE access token for user " + request.userId() + " expired at " +
                            connection.getExpiresAt() + " and no refresh token is stored. " +
                            "Reconnect on the Connections page.");
        }

        log.info("Uploading '{}' to YouTube ({}refresh token)...",
                request.title(), hasRefresh ? "with " : "no ");

        GoogleCredentials credentials = buildCredentials(
                connection.getAccessToken(), connection.getRefreshToken(), connection.getExpiresAt());

        YouTube youTube = new YouTube.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APP_NAME)
                .build();

        VideoSnippet snippet = new VideoSnippet()
                .setTitle(request.title())
                .setDescription(request.description())
                .setCategoryId("22"); // People & Blogs

        VideoStatus status = new VideoStatus()
                .setPrivacyStatus("public");

        Video videoMetadata = new Video()
                .setSnippet(snippet)
                .setStatus(status);

        FileContent mediaContent = new FileContent("video/mp4", request.videoFile().toFile());

        YouTube.Videos.Insert insert = youTube.videos()
                .insert(List.of("snippet", "status"), videoMetadata, mediaContent);

        Video uploaded = insert.execute();
        String youtubeVideoId = uploaded.getId();
        log.info("YouTube upload complete. Video ID: {}", youtubeVideoId);
        return youtubeVideoId;
    }

    private GoogleCredentials buildCredentials(String accessToken, String refreshToken, Instant expiresAt) {
        Date expiry = expiresAt != null ? Date.from(expiresAt) : null;
        AccessToken token = (accessToken != null && !accessToken.isBlank())
                ? new AccessToken(accessToken, expiry)
                : null;

        if (refreshToken != null && !refreshToken.isBlank()) {
            UserCredentials.Builder builder = UserCredentials.newBuilder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(refreshToken);
            if (token != null) builder.setAccessToken(token);
            return builder.build().createScoped(SCOPES);
        }

        // No refresh token — best effort with the (possibly expired) access token.
        return GoogleCredentials.create(token);
    }
}