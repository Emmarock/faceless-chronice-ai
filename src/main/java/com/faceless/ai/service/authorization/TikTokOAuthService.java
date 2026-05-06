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

/**
 * Server-side half of TikTok's OAuth 2.0 authorization-code flow with PKCE.
 *
 * <p>Frontend opens a popup to {@code https://www.tiktok.com/v2/auth/authorize/}
 * and forwards the issued code here. We exchange it for access + refresh
 * tokens at TikTok's v2 token endpoint and persist them.
 */
@Service
@Slf4j
public class TikTokOAuthService {

    private static final String TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/";
    private static final String USER_URL = "https://open.tiktokapis.com/v2/user/info/?fields=open_id,union_id,display_name";

    private final SocialConnectionService socialConnectionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientKey;
    private final String clientSecret;

    public TikTokOAuthService(SocialConnectionService socialConnectionService,
                              HttpClient httpClient,
                              ObjectMapper objectMapper,
                              @Value("${chronicleai.tiktok.client-key}") String clientKey,
                              @Value("${chronicleai.tiktok.client-secret}") String clientSecret) {
        this.socialConnectionService = socialConnectionService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientKey = clientKey;
        this.clientSecret = clientSecret;
    }

    public SocialConnectionDTO exchange(String userId, String code, String redirectUri, String codeVerifier) throws Exception {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Missing authorization code.");
        if (redirectUri == null || redirectUri.isBlank()) throw new IllegalArgumentException("Missing redirect_uri.");

        StringBuilder body = new StringBuilder()
                .append("client_key=").append(enc(clientKey))
                .append("&client_secret=").append(enc(clientSecret))
                .append("&code=").append(enc(code))
                .append("&grant_type=authorization_code")
                .append("&redirect_uri=").append(enc(redirectUri));
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            body.append("&code_verifier=").append(enc(codeVerifier));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("TikTok token exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("TikTok rejected the auth code: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        // The v2 endpoint returns the token fields at the top level.
        String accessToken = textOrNull(json, "access_token");
        String refreshToken = textOrNull(json, "refresh_token");
        long expiresIn = json.path("expires_in").asLong(0);
        if (accessToken == null) {
            throw new IllegalStateException("TikTok response did not include an access_token: " + response.body());
        }

        Instant expiresAt = expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null;
        String handle = lookupHandle(accessToken);

        SocialConnectionRequest req = new SocialConnectionRequest(
                SocialPlatform.TIKTOK, accessToken, refreshToken, handle, expiresAt);
        return socialConnectionService.upsert(userId, req);
    }

    private String lookupHandle(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(USER_URL))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return null;
            JsonNode user = objectMapper.readTree(response.body()).path("data").path("user");
            return textOrNull(user, "display_name");
        } catch (Exception e) {
            log.debug("TikTok user.info lookup failed: {}", e.getMessage());
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