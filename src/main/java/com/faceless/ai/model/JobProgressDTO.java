package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection of a job's pipeline progress for the polling
 * indicator on the frontend. Only carries the fields needed to drive the
 * progress bar so the endpoint can be hit on a short interval cheaply.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobProgressDTO {
    private UUID jobId;
    private String status;
    private int progress;
    private String stage;
    private Instant updatedAt;
}