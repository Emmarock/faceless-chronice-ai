package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;

import java.time.Instant;

public record SocialConnectionRequest(
        SocialPlatform platform,
        String accessToken,
        String refreshToken,
        String accountHandle,
        Instant expiresAt
) {}