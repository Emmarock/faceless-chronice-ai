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

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Posts a video to a Facebook Page using the Graph API.
 *
 * <p>The {@link SocialConnection#getAccessToken() access token} stored for
 * {@link SocialPlatform#FACEBOOK} is a <i>page access token</i> — exchanged
 * during OAuth from a long-lived user token, so it does not expire (when
 * {@code expiresAt} is {@code null}). When the token is still scoped to a
 * specific page, calling {@code /me/videos} with it posts to that page.
 *
 * <p>This implementation does a single multipart POST to {@code /me/videos}.
 * Files larger than ~1GB should switch to the resumable upload API
 * ({@code upload_phase=start/transfer/finish}); rendered videos in this
 * pipeline are short, so the simpler path is fine.
 */
@Service
@Slf4j
public class FacebookUploadService implements VideoUploadService {

    private final SocialConnectionRepository socialConnectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiVersion;
    private final ExecutorService virtualThreadExecutor;
    public FacebookUploadService(SocialConnectionRepository socialConnectionRepository,
                                 HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 @Value("${chronicleai.facebook.api-version:v19.0}") String apiVersion, ExecutorService virtualThreadExecutor) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiVersion = apiVersion;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.FACEBOOK;
    }

    /**
     * @return the Graph API video object id (e.g. {@code "1234567890"}).
     */
    @Override
    public CompletableFuture<String> uploadVideo(VideoUploadRequest request) {

        return CompletableFuture.supplyAsync(() -> {

            try {

                SocialConnection connection = socialConnectionRepository
                        .findByUserIdAndPlatform(
                                request.userId(),
                                SocialPlatform.FACEBOOK
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "User " + request.userId()
                                        + " has no FACEBOOK connection. " +
                                        "Reconnect on the Connections page and retry."
                        ));

                validateConnection(connection, request.userId());

                long bytes = Files.size(request.videoFile());

                log.info(
                        "Facebook video upload starting (user {}, page '{}', {} bytes)",
                        request.userId(),
                        connection.getAccountHandle(),
                        bytes
                );

                String boundary =
                        "----chronicleai" + System.nanoTime();

                URI uri = URI.create(
                        "https://graph.facebook.com/"
                                + apiVersion
                                + "/me/videos"
                );

                HttpRequest requestUpload = HttpRequest.newBuilder(uri)
                        .header(
                                "Content-Type",
                                "multipart/form-data; boundary=" + boundary
                        )
                        .timeout(Duration.ofMinutes(30))
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () -> {
                                            try {

                                                return buildMultipartStream(
                                                        boundary,
                                                        connection.getAccessToken(),
                                                        request
                                                );

                                            } catch (Exception e) {

                                                throw new UncheckedIOException(
                                                        new IOException(e)
                                                );
                                            }
                                        }
                                )
                        )
                        .build();

                HttpResponse<String> response =
                        httpClient.send(
                                requestUpload,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() / 100 != 2) {

                    throw new IOException(
                            "Facebook video upload failed: "
                                    + response.statusCode()
                                    + " "
                                    + response.body()
                    );
                }

                JsonNode json =
                        objectMapper.readTree(response.body());

                String videoId =
                        json.path("id").asText();

                if (videoId.isBlank()) {

                    throw new IOException(
                            "Facebook upload response missing video id: "
                                    + response.body()
                    );
                }

                log.info(
                        "Facebook upload complete for user {} (video id {})",
                        request.userId(),
                        videoId
                );

                return videoId;

            } catch (Exception e) {

                throw new CompletionException(e);
            }

        }, virtualThreadExecutor);
    }

    private InputStream textPart(
            String boundary,
            String name,
            String value
    ) {

        String part =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; " +
                        "name=\"" + name + "\"\r\n\r\n" +
                        value + "\r\n";

        return new ByteArrayInputStream(
                part.getBytes(StandardCharsets.UTF_8)
        );
    }
    private InputStream filePart(String boundary, String fieldName, Path file) throws Exception {

        String contentType =
                Optional.ofNullable(
                        Files.probeContentType(file)
                ).orElse("application/octet-stream");

        String header =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; " +
                        "name=\"" + fieldName + "\"; " +
                        "filename=\"" + file.getFileName() + "\"\r\n" +
                        "Content-Type: " + contentType + "\r\n\r\n";

        InputStream headerStream =
                new ByteArrayInputStream(
                        header.getBytes(StandardCharsets.UTF_8)
                );

        InputStream fileStream =
                new BufferedInputStream(
                        Files.newInputStream(file)
                );

        InputStream endingStream =
                new ByteArrayInputStream(
                        "\r\n".getBytes(StandardCharsets.UTF_8)
                );

        return new SequenceInputStream(
                Collections.enumeration(
                        List.of(
                                headerStream,
                                fileStream,
                                endingStream
                        )
                )
        );
    }
    private void validateConnection(SocialConnection connection, String userId) {

        String pageToken =
                connection.getAccessToken();

        if (pageToken == null || pageToken.isBlank()) {

            throw new IllegalStateException(
                    "Missing Facebook page access token for user "
                            + userId
                            + "."
            );
        }

        if (connection.getExpiresAt() != null
                && connection.getExpiresAt().isBefore(Instant.now())) {

            throw new IllegalStateException(
                    "Facebook page access token for user "
                            + userId
                            + " expired at "
                            + connection.getExpiresAt()
                            + ". Reconnect on the Connections page."
            );
        }
    }

    private InputStream buildMultipartStream(
            String boundary,
            String accessToken,
            VideoUploadRequest request
    ) throws Exception {

        List<InputStream> streams = new ArrayList<>();

        streams.add(textPart(
                boundary,
                "access_token",
                accessToken
        ));

        if (request.title() != null
                && !request.title().isBlank()) {

            streams.add(textPart(
                    boundary,
                    "title",
                    request.title()
            ));
        }

        String description =
                caption(
                        request.title(),
                        request.description()
                );

        if (!description.isBlank()) {

            streams.add(textPart(
                    boundary,
                    "description",
                    description
            ));
        }

        streams.add(filePart(
                boundary,
                "source",
                request.videoFile()
        ));

        streams.add(
                new ByteArrayInputStream(
                        ("--" + boundary + "--\r\n")
                                .getBytes(StandardCharsets.UTF_8)
                )
        );

        return new SequenceInputStream(
                Collections.enumeration(streams)
        );
    }
    private static String caption(String title, String description) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasDesc = description != null && !description.isBlank();
        if (hasTitle && hasDesc) return title + "\n\n" + description;
        if (hasTitle) return title;
        if (hasDesc) return description;
        return "";
    }
}