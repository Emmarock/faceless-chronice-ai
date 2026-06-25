package com.faceless.ai.model;

import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Twin;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Client view of a {@link Twin}. Deliberately omits HeyGen-internal handles
 * (training id, raw avatar/voice ids) — the UI only needs identity, readiness,
 * and any failure reason.
 */
@Builder
public record TwinDTO(
        UUID id,
        String name,
        Status status,
        boolean ready,
        String errorMessage,
        Instant createdOn
) {
    public static TwinDTO from(Twin t) {
        return TwinDTO.builder()
                .id(t.getId())
                .name(t.getName())
                .status(t.getStatus())
                .ready(t.getStatus() == Status.COMPLETED && t.getHeygenAvatarId() != null)
                .errorMessage(t.getErrorMessage())
                .createdOn(t.getCreatedOn())
                .build();
    }
}
