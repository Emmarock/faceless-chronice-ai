package com.faceless.ai.model;

import com.faceless.ai.entity.SocialConnection;
import com.faceless.ai.entity.SocialPlatform;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record SocialConnectionDTO(
        UUID id,
        SocialPlatform platform,
        String accountHandle,
        Instant connectedAt,
        Instant expiresAt
) {
    public static SocialConnectionDTO from(SocialConnection c) {
        return SocialConnectionDTO.builder()
                .id(c.getId())
                .platform(c.getPlatform())
                .accountHandle(c.getAccountHandle())
                .connectedAt(c.getConnectedAt())
                .expiresAt(c.getExpiresAt())
                .build();
    }
}