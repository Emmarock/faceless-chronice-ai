package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;

import java.time.Instant;

public record SocialConnectionRequest(
        SocialPlatform platform,
        String accessToken,
        String refreshToken,
        String accountHandle,
        String providerAccountId,
        Instant expiresAt
) {
    /**
     * Back-compat constructor for callers that predate the
     * {@code providerAccountId} addition (used by Instagram / LinkedIn).
     * Defaults the new field to {@code null}.
     */
    public SocialConnectionRequest(SocialPlatform platform,
                                   String accessToken,
                                   String refreshToken,
                                   String accountHandle,
                                   Instant expiresAt) {
        this(platform, accessToken, refreshToken, accountHandle, null, expiresAt);
    }
}