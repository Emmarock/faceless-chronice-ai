package com.faceless.ai.service.authorization;

import com.faceless.ai.entity.AuthProvider;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.model.OAuthIdentity;
import com.faceless.ai.model.SocialConnectionDTO;
import com.faceless.ai.model.SocialConnectionRequest;
import com.faceless.ai.service.SocialConnectionService;
import com.faceless.ai.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * Handles the server-side half of Google's OAuth 2.0 authorization-code flow
 * for YouTube uploads.
 *
 * <p>The frontend triggers Google's popup with {@code flow: "auth-code"} and
 * receives a short-lived authorization code. That code arrives here and is
 * exchanged for an {@code access_token} and (critically) a long-lived
 * {@code refresh_token}, which lets us keep uploading on the user's behalf
 * after the initial access token expires (~1 hour).
 *
 * <p>The implicit flow used previously never returned a refresh token, which
 * is why YouTube uploads broke an hour after the user connected.
 */
@Service
@Slf4j
public class YouTubeOAuthService {

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";

    private final SocialConnectionService socialConnectionService;
    private final UserService userService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    public YouTubeOAuthService(SocialConnectionService socialConnectionService,
                               UserService userService,
                               HttpClient httpClient,
                               ObjectMapper objectMapper,
                               @Value("${chronicleai.youtube.client-id}") String clientId,
                               @Value("${chronicleai.youtube.client-secret}") String clientSecret) {
        this.socialConnectionService = socialConnectionService;
        this.userService = userService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public SocialConnectionDTO exchange(String userId, String code, String redirectUri) throws Exception {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Missing authorization code.");
        }

        String body = "code=" + urlEncode(code)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret)
                + "&redirect_uri=" + urlEncode(redirectUri == null || redirectUri.isBlank() ? "postmessage" : redirectUri)
                + "&grant_type=authorization_code";

        HttpRequest request = HttpRequest.newBuilder(URI.create(GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("Google token exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("Google rejected the auth code: " + response.body());
        }

        JsonNode tokenJson = objectMapper.readTree(response.body());
        String accessToken = textOrNull(tokenJson, "access_token");
        String refreshToken = textOrNull(tokenJson, "refresh_token");
        long expiresIn = tokenJson.path("expires_in").asLong(0);

        if (accessToken == null) {
            throw new IllegalStateException("Google response did not include an access_token.");
        }
        if (refreshToken == null) {
            // This usually means the user already authorized this app before and
            // Google decided not to re-issue a refresh token. The frontend forces
            // prompt=consent on every connect, so this should be rare — but flag
            // it loudly because uploads will silently expire after ~1 hour
            // without it.
            log.warn("Google did not return a refresh_token for user {} — uploads will expire when the access token does.", userId);
        }

        Instant expiresAt = expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null;
        GoogleUserInfo info = lookupUserInfo(accessToken);
        String accountHandle = info == null ? null : info.toHandle();

        // Record the identity before persisting the social connection so that
        // the connection row's userId is unquestionably backed by a known
        // AppUser. If the userinfo lookup failed (no `sub` claim) we skip
        // silently — the social connection itself doesn't depend on it.
        if (info != null && info.id() != null) {
            userService.recordOAuthIdentity(
                    userId,
                    new OAuthIdentity(
                            AuthProvider.GOOGLE,
                            info.id(),
                            info.email(),
                            info.name(),
                            info.picture()));
        } else {
            log.warn("Skipping AppUser record — Google userinfo had no `sub` for userId {}", userId);
        }

        SocialConnectionRequest req = new SocialConnectionRequest(
                SocialPlatform.YOUTUBE,
                accessToken,
                refreshToken,
                accountHandle,
                expiresAt);

        return socialConnectionService.upsert(userId, req);
    }

    private GoogleUserInfo lookupUserInfo(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(GOOGLE_USERINFO_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.debug("userinfo lookup non-2xx ({}): {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            return new GoogleUserInfo(
                    textOrNull(json, "id"),
                    textOrNull(json, "email"),
                    textOrNull(json, "name"),
                    textOrNull(json, "picture"));
        } catch (Exception e) {
            log.debug("userinfo lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private record GoogleUserInfo(String id, String email, String name, String picture) {
        /** Same human-readable handle the connection card showed before this refactor. */
        String toHandle() {
            if (name != null && email != null) return name + " (" + email + ")";
            return name != null ? name : email;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}