package com.faceless.ai.service.authorization;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.exceptions.NoFacebookPageException;
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
 * Server-side half of Facebook's OAuth 2.0 authorization-code flow.
 *
 * <p>Frontend opens a popup to {@code https://www.facebook.com/{api}/dialog/oauth}
 * requesting page-management scopes ({@code pages_show_list},
 * {@code pages_manage_posts}, {@code pages_read_engagement}) and forwards the
 * resulting code here. The legacy {@code publish_video} scope was deprecated
 * by Meta and is rejected by modern Graph API versions — uploads to
 * {@code /{page}/videos} with a Page access token are now authorized by
 * {@code pages_manage_posts}.
 *
 * <p>We then:
 * <ol>
 *   <li>Exchange the code for a short-lived user access token.</li>
 *   <li>Exchange that for a long-lived user token (~60 days).</li>
 *   <li>Fetch {@code /me/accounts}, which lists the user's manageable Pages
 *       and returns a Page access token for each. Page tokens minted from a
 *       long-lived user token <i>do not expire</i>.</li>
 *   <li>Pick the first Page (this can be enhanced later to let the user
 *       choose) and persist its Page token as the connection's access token.</li>
 * </ol>
 *
 * <p>Facebook does not issue OAuth refresh tokens — the long-lived Page token
 * is the durable handle. We store {@code expiresAt = null} to indicate it
 * does not expire.
 */
@Service
@Slf4j
public class FacebookOAuthService {

    private final SocialConnectionService socialConnectionService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;
    private final String apiVersion;

    public FacebookOAuthService(SocialConnectionService socialConnectionService,
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

        String shortLivedUserToken = exchangeCodeForUserToken(code, redirectUri);
        String longLivedUserToken = exchangeForLongLivedUserToken(shortLivedUserToken);
        PageToken page = pickFirstPage(longLivedUserToken);

        SocialConnectionRequest req = new SocialConnectionRequest(
                SocialPlatform.FACEBOOK,
                page.accessToken(),
                null,                       // no refresh tokens — page token is durable
                page.name(),
                null);                      // never expires for page tokens minted from long-lived user token
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
            log.warn("Facebook code exchange failed ({}): {}", response.statusCode(), response.body());
            throw new IllegalStateException("Facebook rejected the auth code: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = textOrNull(json, "access_token");
        if (token == null) {
            throw new IllegalStateException("Facebook response did not include an access_token: " + response.body());
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
            throw new IllegalStateException("Facebook long-lived token exchange failed: " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = textOrNull(json, "access_token");
        if (token == null) {
            throw new IllegalStateException("Facebook long-lived token response missing access_token: " + response.body());
        }
        return token;
    }

    private PageToken pickFirstPage(String longLivedUserToken) throws Exception {
        String url = "https://graph.facebook.com/" + apiVersion + "/me/accounts"
                + "?fields=" + enc("id,name,access_token")
                + "&access_token=" + enc(longLivedUserToken);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Facebook /me/accounts failed: " + response.body());
        }
        JsonNode data = objectMapper.readTree(response.body()).path("data");
        if (!data.isArray() || data.isEmpty()) {
            // Diagnostic detail (granted permissions, Business-Manager nuance) goes
            // to the log so support can tell apart "user dropped pages_show_list"
            // from "user has no Page". The exception message itself is the short
            // user-facing copy the frontend will render.
            String granted = fetchGrantedPermissions(longLivedUserToken);
            log.warn("Facebook /me/accounts empty for connecting user. Granted permissions: {}. "
                            + "Likely cause: account has no Page with full Facebook access "
                            + "(Business-Manager-only access does not count).", granted);
            throw new NoFacebookPageException(
                    "Your Facebook account doesn't manage any Page yet. "
                            + "Faceless Chronicle publishes to Facebook Pages, not personal timelines — "
                            + "create a Page (or grant your account full Facebook access to an existing one) and try again.");
        }
        JsonNode first = data.get(0);
        String pageToken = textOrNull(first, "access_token");
        String pageName = textOrNull(first, "name");
        if (pageToken == null) {
            throw new IllegalStateException("Facebook page entry missing access_token: " + first);
        }
        return new PageToken(pageToken, pageName);
    }

    private String fetchGrantedPermissions(String userToken) {
        try {
            String url = "https://graph.facebook.com/" + apiVersion + "/me/permissions"
                    + "?access_token=" + enc(userToken);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                return "<failed to fetch: HTTP " + res.statusCode() + ">";
            }
            JsonNode data = objectMapper.readTree(res.body()).path("data");
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < data.size(); i++) {
                JsonNode entry = data.get(i);
                if (i > 0) sb.append(", ");
                sb.append(textOrNull(entry, "permission"))
                        .append("=")
                        .append(textOrNull(entry, "status"));
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "<failed to fetch: " + e.getMessage() + ">";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record PageToken(String accessToken, String name) {}
}