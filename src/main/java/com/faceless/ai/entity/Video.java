package com.faceless.ai.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "videos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Video extends BaseEntity {

    private UUID jobId;

    private String title;

    private String description;

    private int durationSeconds;

    private String storageUrl; // e.g., S3, Cloudflare R2
}