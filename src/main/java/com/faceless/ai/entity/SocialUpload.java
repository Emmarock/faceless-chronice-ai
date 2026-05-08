package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "social_uploads",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_social_uploads_video_platform",
               columnNames = {"video_id", "platform"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SocialUpload extends BaseEntity {

    private UUID videoId;

    @Enumerated(EnumType.STRING)
    private SocialPlatform platform;

    private String providerPostId;

    private Instant uploadedAt;
}