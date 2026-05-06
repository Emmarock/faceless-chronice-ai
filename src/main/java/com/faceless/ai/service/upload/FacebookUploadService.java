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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

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

    public FacebookUploadService(SocialConnectionRepository socialConnectionRepository,
                                 HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 @Value("${chronicleai.facebook.api-version:v19.0}") String apiVersion) {
        this.socialConnectionRepository = socialConnectionRepository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiVersion = apiVersion;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.FACEBOOK;
    }

    /**
     * @return the Graph API video object id (e.g. {@code "1234567890"}).
     */
    @Override
    public String uploadVideo(VideoUploadRequest request) throws Exception {
        SocialConnection connection = socialConnectionRepository
                .findByUserIdAndPlatform(request.userId(), SocialPlatform.FACEBOOK)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + request.userId() + " has no FACEBOOK connection. " +
                                "Reconnect on the Connections page and retry."));

        String pageToken = connection.getAccessToken();
        if (pageToken == null || pageToken.isBlank()) {
            throw new IllegalStateException(
                    "Missing Facebook page access token for user " + request.userId() + ".");
        }
        if (connection.getExpiresAt() != null && connection.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException(
                    "Facebook page access token for user " + request.userId() + " expired at " +
                            connection.getExpiresAt() + ". Reconnect on the Connections page.");
        }

        long bytes = Files.size(request.videoFile());
        log.info("Facebook video upload starting (user {}, page '{}', {} bytes)",
                request.userId(), connection.getAccountHandle(), bytes);

        String boundary = "----chronicleai" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeTextPart(body, boundary, "access_token", pageToken);
        if (request.title() != null && !request.title().isBlank()) {
            writeTextPart(body, boundary, "title", request.title());
        }
        String description = caption(request.title(), request.description());
        if (!description.isBlank()) {
            writeTextPart(body, boundary, "description", description);
        }
        writeFilePart(body, boundary, "source", request.videoFile().getFileName().toString(),
                Files.readAllBytes(request.videoFile()));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        URI uri = URI.create("https://graph.facebook.com/" + apiVersion + "/me/videos");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Facebook video upload failed: "
                    + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String videoId = json.path("id").asText();
        if (videoId.isBlank()) {
            throw new IOException("Facebook upload response missing video id: " + response.body());
        }
        log.info("Facebook upload complete for user {} (video id {})", request.userId(), videoId);
        return videoId;
    }

    private static String caption(String title, String description) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasDesc = description != null && !description.isBlank();
        if (hasTitle && hasDesc) return title + "\n\n" + description;
        if (hasTitle) return title;
        if (hasDesc) return description;
        return "";
    }

    private static void writeTextPart(ByteArrayOutputStream out, String boundary,
                                      String name, String value) throws IOException {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary,
                                      String name, String filename, byte[] data) throws IOException {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}