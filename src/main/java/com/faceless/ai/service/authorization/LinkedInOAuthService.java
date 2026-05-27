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
 * OAuth 2.0 authorization-code flow against LinkedIn.
 *
 * <p>LinkedIn issues:
 * <ul>
 *   <li>An access token (~60 days lifetime, but treat as ~2 months and
 *       refresh proactively when within 60s of expiry).</li>
 *   <li>A refresh token (also ~365 days; rotates on use).</li>
 * </ul>
 *
 * <p>After token exchange we call {@code /v2/userinfo} to capture the
 * member's display name and stable {@code sub} (the LinkedIn member id).
 * The member id is stored as {@code providerAccountId} and used to build
 * the {@code urn:li:person:{id}} actor every UGC post needs.
 *
 * <p>Scopes (configured on the LinkedIn Developer app):
 * <ul>
 *   <li>{@code openid profile email} — userinfo lookup</li>
 *   <li>{@code w_member_social} — post on behalf of the member</li>
 * </ul>
 */
@Service
@Slf4j
public class LinkedInOAuthService {

    private static final String TOKEN_URL    = "https://www.linkedin.com/oauth/v2/accessToken";
    private static final String USERINFO_URL = "https://api.linkedin.com/v2/userinfo";

    private final SocialConnectionService socialConnectionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    public LinkedInOAuthService(SocialConnectionService socialConnectionService,
                                HttpClient httpClient,
                                ObjectMapper objectMapper,
                                @Value("${chronicleai.linkedin.client-id}") String clientId,
                                @Value("${chronicleai.linkedin.client-secret}") String clientSecret) {
        this.socialConnectionService = socialConnectionService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public SocialConnectionDTO exchange(String userId, String code, String redirectUri) throws Exception {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Missing authorization code.");
        if (redirectUri == null || redirectUri.isBlank()) throw new IllegalArgumentException("Missing redirect_uri.");

        TokenResponse tokens = exchangeCode(code, redirectUri);
        Userinfo info = fetchUserinfo(tokens.accessToken());

        SocialConnectionRequest req = new SocialConnectionRequest(
                SocialPlatform.LINKEDIN,
                tokens.accessToken(),
                tokens.refreshToken(),
                info.name(),
                info.sub(),
                tokens.expiresAt());
        return socialConnectionService.upsert(userId, req);
    }

    private TokenResponse exchangeCode(String code, String redirectUri) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirectUri);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("LinkedIn code exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("LinkedIn rejected the auth code: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String access = textOrNull(json, "access_token");
        if (access == null) {
            throw new IllegalStateException("LinkedIn token response missing access_token: " + response.body());
        }
        long expiresIn = json.path("expires_in").asLong(0);
        return new TokenResponse(
                access,
                textOrNull(json, "refresh_token"),
                expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
    }

    private Userinfo fetchUserinfo(String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(USERINFO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("LinkedIn /userinfo failed: " + res.body());
        }
        JsonNode json = objectMapper.readTree(res.body());
        String sub = textOrNull(json, "sub");
        if (sub == null) {
            throw new IllegalStateException("LinkedIn /userinfo missing sub: " + res.body());
        }
        return new Userinfo(sub, textOrNull(json, "name"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record TokenResponse(String accessToken, String refreshToken, Instant expiresAt) {}
    private record Userinfo(String sub, String name) {}
}
