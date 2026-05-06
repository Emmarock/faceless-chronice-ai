package com.faceless.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "youtube_uploads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class YouTubeUpload extends BaseEntity {

    @Column(unique = true)
    private UUID videoId;

    private String youtubeVideoId;

    private Status status; // QUEUED, UPLOADING, COMPLETED, FAILED

    private Instant uploadedAt;
}