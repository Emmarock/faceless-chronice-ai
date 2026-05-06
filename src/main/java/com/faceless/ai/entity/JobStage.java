package com.faceless.ai.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class JobStage extends BaseEntity {

    private UUID jobId;

    private String stageName;

    private Instant startedAt;

    private Instant finishedAt;

    private String error;
}