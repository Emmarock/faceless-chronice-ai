package com.faceless.ai.service.upload;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.repository.SocialConnectionRepository;
import com.faceless.ai.service.VideoUploadRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FacebookUploadServiceTest {

    private SocialConnectionRepository repo;
    private HttpClient httpClient;
    private FacebookUploadService service;

    @BeforeEach
    void setUp() {
        repo = mock(SocialConnectionRepository.class);
        httpClient = mock(HttpClient.class);
        service = new FacebookUploadService(
                repo,
                httpClient,
                new ObjectMapper(),
                "v19.0",
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Regression for the {@code Invalid UUID string: <email>} crash we hit
     * uploading to Facebook. Internally we identify users by email, so the
     * upload service must accept any non-blank string as the user id — never
     * try to parse it as a {@link java.util.UUID}.
     */
    @Test
    void acceptsEmailUserIdAndCompletesUpload(@TempDir Path tempDir) throws Exception {
        String emailUserId = "apatababajide@gmail.com";
        SocialConnection connection = SocialConnection.builder()
                .userId(emailUserId)
                .platform(SocialPlatform.FACEBOOK)
                .accessToken("page-token")
                .accountHandle("Chronicle Page")
                .build();
        when(repo.findByUserIdAndPlatform(emailUserId, SocialPlatform.FACEBOOK))
                .thenReturn(Optional.of(connection));

        @SuppressWarnings("unchecked")
        HttpResponse<String> graphResponse = mock(HttpResponse.class);
        when(graphResponse.statusCode()).thenReturn(200);
        when(graphResponse.body()).thenReturn("{\"id\":\"fb-video-123\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(graphResponse);

        Path video = tempDir.resolve("clip.mp4");
        Files.write(video, new byte[]{0, 1, 2, 3});

        VideoUploadRequest request = new VideoUploadRequest(
                emailUserId, video, "Title", "Description");

        String videoId = service.uploadVideo(request).get();

        assertThat(videoId).isEqualTo("fb-video-123");
    }

    /**
     * The previous implementation called {@code UUID.fromString(userId)} for
     * an error-message helper and crashed before the Graph API call. Guard
     * against that specific regression by asserting the future does not fail
     * with an {@link IllegalArgumentException} when the user id is an email.
     */
    @Test
    void emailUserIdDoesNotTriggerUuidParseException(@TempDir Path tempDir) throws Exception {
        String emailUserId = "user@example.com";
        when(repo.findByUserIdAndPlatform(emailUserId, SocialPlatform.FACEBOOK))
                .thenReturn(Optional.of(SocialConnection.builder()
                        .userId(emailUserId)
                        .platform(SocialPlatform.FACEBOOK)
                        .accessToken("page-token")
                        .build()));

        @SuppressWarnings("unchecked")
        HttpResponse<String> graphResponse = mock(HttpResponse.class);
        when(graphResponse.statusCode()).thenReturn(200);
        when(graphResponse.body()).thenReturn("{\"id\":\"x\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(graphResponse);

        Path video = tempDir.resolve("clip.mp4");
        Files.write(video, new byte[]{0});

        VideoUploadRequest request = new VideoUploadRequest(
                emailUserId, video, null, null);

        assertThatCode(() -> service.uploadVideo(request).get())
                .doesNotThrowAnyException();
    }
}