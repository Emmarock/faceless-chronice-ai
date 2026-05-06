package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Entity
@Table(
        name = "social_connections",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "platform"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SocialConnection extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SocialPlatform platform;

    @Column(length = 4000)
    private String accessToken;

    @Column(length = 4000)
    private String refreshToken;

    private String accountHandle;

    private Instant connectedAt;

    private Instant expiresAt;
}