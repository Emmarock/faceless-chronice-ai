package com.faceless.ai.model;

/**
 * Payload for exchanging a Google OAuth authorization code (issued by the
 * frontend's auth-code flow) for an access + refresh token pair.
 *
 * @param code        the authorization code Google returned to the popup
 * @param redirectUri usually {@code "postmessage"} when the popup-based
 *                    auth-code flow is used; required by Google's token
 *                    endpoint and must match what the frontend used
 */
public record YouTubeOAuthExchangeRequest(String code, String redirectUri) {}