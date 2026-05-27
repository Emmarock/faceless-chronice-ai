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

/**
 * OAuth half of the Instagram cross-posting flow.
 *
 * <p>Instagram Business publishing rides on the Facebook Graph API — there is
 * no standalone Instagram OAuth for content-publish. The connect flow is:
 * <ol>
 *   <li>Frontend opens the Facebook authorize dialog with IG-specific scopes
 *       ({@code instagram_basic}, {@code instagram_content_publish},
 *       {@code pages_show_list}, {@code pages_read_engagement},
 *       {@code business_management}).</li>
 *   <li>Backend exchanges the code for a long-lived user token.</li>
 *   <li>We call {@code /me/accounts?fields=instagram_business_account,name,access_token},
 *       which returns each Page the user manages plus, if linked, the
 *       Instagram Business Account id on that Page.</li>
 *   <li>The first Page whose response includes an
 *       {@code instagram_business_account} is the one we keep. Its Page
 *       access token becomes the SocialConnection access token; the IG
 *       account id is stored as {@code providerAccountId} (needed on
 *       every IG content-publish API call).</li>
 * </ol>
 *
 * <p>The Facebook app id / secret used here are the same as
 * {@link FacebookOAuthService} — Meta treats Facebook and Instagram as one
 * application surface — so the {@code chronicleai.facebook.*} config block
 * is reused.
 */
@Service
@Slf4j
public class InstagramOAuthService {

    private final SocialConnectionService socialConnectionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;
    private final String apiVersion;

    public InstagramOAuthService(SocialConnectionService socialConnectionService,
                                 HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 @Value("${chronicleai.facebook.app-id}") String appId,
                                 @Value("${chronicleai.facebook.app-secret}") String appSecret,
                                 @Value("${chronicleai.facebook.api-version:v25.0}") String apiVersion) {
        this.socialConnectionService = socialConnectionService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.appId = appId;
        this.appSecret = appSecret;
        this.apiVersion = apiVersion;
    }

    public SocialConnectionDTO exchange(String userId, String code, String redirectUri) throws Exception {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Missing authorization code.");
        if (redirectUri == null || redirectUri.isBlank()) throw new IllegalArgumentException("Missing redirect_uri.");

        String shortLivedToken = exchangeCodeForUserToken(code, redirectUri);
        String longLivedToken = exchangeForLongLivedUserToken(shortLivedToken);

        IgAccount ig = pickFirstIgAccount(longLivedToken);

        SocialConnectionRequest req = new SocialConnectionRequest(
                SocialPlatform.INSTAGRAM,
                ig.pageToken(),
                null,                      // IG/Facebook Page tokens don't rotate
                ig.handle(),               // human-readable: "@business_handle"
                ig.igUserId(),             // machine: required on every IG /{ig_user_id}/* call
                null);
        return socialConnectionService.upsert(userId, req);
    }

    private String exchangeCodeForUserToken(String code, String redirectUri) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/oauth/access_token"
                + "?client_id=" + enc(appId)
                + "&client_secret=" + enc(appSecret)
                + "&redirect_uri=" + enc(redirectUri)
                + "&code=" + enc(code);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("Instagram code exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("Instagram rejected the auth code: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = textOrNull(json, "access_token");
        if (token == null) {
            throw new IllegalStateException("Instagram response missing access_token: " + response.body());
        }
        return token;
    }

    private String exchangeForLongLivedUserToken(String shortLivedToken) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/oauth/access_token"
                + "?grant_type=fb_exchange_token"
                + "&client_id=" + enc(appId)
                + "&client_secret=" + enc(appSecret)
                + "&fb_exchange_token=" + enc(shortLivedToken);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Instagram long-lived token exchange failed: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = textOrNull(json, "access_token");
        if (token == null) {
            throw new IllegalStateException("Instagram long-lived token response missing access_token: " + response.body());
        }
        return token;
    }

    private IgAccount pickFirstIgAccount(String longLivedToken) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/me/accounts"
                + "?fields=" + enc("id,name,access_token,instagram_business_account{id,username}")
                + "&access_token=" + enc(longLivedToken);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Instagram /me/accounts failed: " + response.body());
        }
        JsonNode data = objectMapper.readTree(response.body()).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException(
                    "Your Facebook account doesn't manage any Page. Instagram Business publishing "
                            + "requires the IG account be linked to a Facebook Page that you manage.");
        }
        for (JsonNode page : data) {
            JsonNode ig = page.get("instagram_business_account");
            if (ig == null || ig.isNull()) continue;
            String igId = textOrNull(ig, "id");
            String igHandle = textOrNull(ig, "username");
            String pageToken = textOrNull(page, "access_token");
            if (igId == null || pageToken == null) continue;
            return new IgAccount(
                    pageToken,
                    igHandle == null ? textOrNull(page, "name") : "@" + igHandle,
                    igId);
        }
        throw new IllegalStateException(
                "No Instagram Business account is linked to your Facebook Pages. "
                        + "Convert your IG profile to a Business account and link it to a Page you manage, "
                        + "then try connecting again.");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record IgAccount(String pageToken, String handle, String igUserId) {}
}
