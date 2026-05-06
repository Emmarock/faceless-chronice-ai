package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoSummaryDTO {
    private UUID videoId;
    private UUID jobId;
    private String title;
    private String description;
    private int durationSeconds;
    private Instant createdAt;
    /**
     * Frontend-friendly relative URL the browser can hit to play the video.
     * Resolves on the same backend host the API is served from.
     */
    private String streamUrl;
}