package com.faceless.ai.model;

/**
 * Generic payload used by the Twitter and TikTok auth-code → token exchange
 * endpoints. PKCE is used for both, so {@code codeVerifier} is required.
 */
public record OAuthCodeExchangeRequest(String code, String redirectUri, String codeVerifier) {}