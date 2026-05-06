package com.faceless.ai.service.authorization;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.model.SocialConnectionDTO;
import com.faceless.ai.model.SocialConnectionRequest;
import com.faceless.ai.service.SocialConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Server-side half of Twitter / X's OAuth 2.0 PKCE authorization-code flow.
 *
 * <p>The frontend opens a popup to {@code https://twitter.com/i/oauth2/authorize}
 * with a PKCE code challenge, captures the resulting authorization code, and
 * forwards it here. We exchange the code (along with the matching
 * {@code code_verifier}) for an access + refresh token pair, then store them
 * in {@link com.faceless.ai.entity.SocialConnection}.
 */
@Service
@Slf4j
public class TwitterOAuthService {

    private static final String TOKEN_URL = "https://api.twitter.com/2/oauth2/token";
    private static final String USER_URL = "https://api.twitter.com/2/users/me";

    private final SocialConnectionService socialConnectionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    public TwitterOAuthService(SocialConnectionService socialConnectionService,
                               HttpClient httpClient,
                               ObjectMapper objectMapper,
                               @Value("${chronicleai.twitter.client-id}") String clientId,
                               @Value("${chronicleai.twitter.client-secret}") String clientSecret) {
        this.socialConnectionService = socialConnectionService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public SocialConnectionDTO exchange(String userId, String code, String redirectUri, String codeVerifier) throws Exception {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Missing authorization code.");
        if (codeVerifier == null || codeVerifier.isBlank()) throw new IllegalArgumentException("Missing PKCE code_verifier.");
        if (redirectUri == null || redirectUri.isBlank()) throw new IllegalArgumentException("Missing redirect_uri.");

        String body = "code=" + enc(code)
                + "&grant_type=authorization_code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&code_verifier=" + enc(codeVerifier);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        // Confidential clients must send Basic auth; public clients may send
        // client_id only. Most app types in the X portal are now confidential
        // by default.
        if (clientSecret != null && !clientSecret.isBlank() && !"changeme".equals(clientSecret)) {
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("Twitter token exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("Twitter rejected the auth code: " + response.body());
        }

        JsonNode tokenJson = objectMapper.readTree(response.body());
        String accessToken = textOrNull(tokenJson, "access_token");
        String refreshToken = textOrNull(tokenJson, "refresh_token");
        long expiresIn = tokenJson.path("expires_in").asLong(0);
        if (accessToken == null) {
            throw new IllegalStateException("Twitter response did not include an access_token.");
        }
        if (refreshToken == null) {
            log.warn("Twitter did not return a refresh_token for user {} — re-connect needed when access expires.", userId);
        }

        Instant expiresAt = expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null;
        String handle = lookupHandle(accessToken);

        SocialConnectionRequest req2 = new SocialConnectionRequest(
                SocialPlatform.TWITTER, accessToken, refreshToken, handle, expiresAt);
        return socialConnectionService.upsert(userId, req2);
    }

    private String lookupHandle(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(USER_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return null;
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            String name = textOrNull(data, "name");
            String username = textOrNull(data, "username");
            if (name != null && username != null) return name + " (@" + username + ")";
            return username != null ? "@" + username : name;
        } catch (Exception e) {
            log.debug("Twitter users/me lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}