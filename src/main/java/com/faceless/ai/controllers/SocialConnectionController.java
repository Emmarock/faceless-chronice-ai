package com.faceless.ai.controllers;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.model.OAuthCodeExchangeRequest;
import com.faceless.ai.model.SocialConnectionDTO;
import com.faceless.ai.model.SocialConnectionRequest;
import com.faceless.ai.model.YouTubeOAuthExchangeRequest;
import com.faceless.ai.service.authorization.FacebookOAuthService;
import com.faceless.ai.service.SocialConnectionService;
import com.faceless.ai.service.authorization.TikTokOAuthService;
import com.faceless.ai.service.authorization.TwitterOAuthService;
import com.faceless.ai.service.authorization.YouTubeOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/social-connections")
@RequiredArgsConstructor
public class SocialConnectionController {

    private final SocialConnectionService service;
    private final YouTubeOAuthService youTubeOAuthService;
    private final TwitterOAuthService twitterOAuthService;
    private final TikTokOAuthService tikTokOAuthService;
    private final FacebookOAuthService facebookOAuthService;

    @GetMapping
    public ResponseEntity<List<SocialConnectionDTO>> list(@RequestHeader("X-USER") String userId) {
        return ResponseEntity.ok(service.listForUser(userId));
    }

    @PostMapping
    public ResponseEntity<SocialConnectionDTO> connect(@RequestHeader("X-USER") String userId,
                                                       @RequestBody SocialConnectionRequest request) {
        return ResponseEntity.ok(service.upsert(userId, request));
    }

    /**
     * Exchanges a Google OAuth authorization code (from the frontend's
     * auth-code popup) for a refresh-token-bearing connection. Use this in
     * place of the legacy implicit flow so YouTube uploads survive past the
     * one-hour access-token lifetime.
     */
    @PostMapping("/youtube/oauth-exchange")
    public ResponseEntity<SocialConnectionDTO> exchangeYouTube(@RequestHeader("X-USER") String userId,
                                                               @RequestBody YouTubeOAuthExchangeRequest request) throws Exception {
        return ResponseEntity.ok(
                youTubeOAuthService.exchange(userId, request.code(), request.redirectUri()));
    }

    /**
     * Exchanges a Twitter / X OAuth 2.0 PKCE authorization code for an
     * access + refresh token pair. The frontend captures the code from the
     * Twitter authorize popup and forwards it (with the matching
     * {@code code_verifier}) here.
     */
    @PostMapping("/twitter/oauth-exchange")
    public ResponseEntity<SocialConnectionDTO> exchangeTwitter(@RequestHeader("X-USER") String userId,
                                                               @RequestBody OAuthCodeExchangeRequest request) throws Exception {
        return ResponseEntity.ok(
                twitterOAuthService.exchange(userId, request.code(), request.redirectUri(), request.codeVerifier()));
    }

    /**
     * Exchanges a TikTok OAuth 2.0 authorization code (PKCE) for a token
     * pair. Same shape as the Twitter exchange.
     */
    @PostMapping("/tiktok/oauth-exchange")
    public ResponseEntity<SocialConnectionDTO> exchangeTikTok(@RequestHeader("X-USER") String userId,
                                                              @RequestBody OAuthCodeExchangeRequest request) throws Exception {
        return ResponseEntity.ok(
                tikTokOAuthService.exchange(userId, request.code(), request.redirectUri(), request.codeVerifier()));
    }

    /**
     * Exchanges a Facebook OAuth 2.0 authorization code for a long-lived Page
     * access token and persists it as the user's FACEBOOK connection. The
     * {@code codeVerifier} field on the request is ignored — Facebook's
     * server-side flow authenticates with {@code client_secret} rather than
     * PKCE.
     */
    @PostMapping("/facebook/oauth-exchange")
    public ResponseEntity<SocialConnectionDTO> exchangeFacebook(@RequestHeader("X-USER") String userId,
                                                                @RequestBody OAuthCodeExchangeRequest request) throws Exception {
        return ResponseEntity.ok(
                facebookOAuthService.exchange(userId, request.code(), request.redirectUri()));
    }

    @DeleteMapping("/{platform}")
    public ResponseEntity<Void> disconnect(@RequestHeader("X-USER") String userId,
                                           @PathVariable SocialPlatform platform) {
        service.disconnect(userId, platform);
        return ResponseEntity.noContent().build();
    }
}