package com.faceless.ai.model;

import com.faceless.ai.entity.AssetType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of an {@code Asset} row for the user-facing asset
 * library. Carries the originating job's title (when available) so the UI
 * can show "from <Job>" labels without an extra round-trip per row.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetSummaryDTO {
    private UUID id;
    private AssetType assetType;
    private UUID jobId;
    private String jobTitle;
    private String metadata;
    /**
     * Frontend-friendly URL the browser can use to stream the asset's bytes
     * via the backend (avoids exposing raw S3 URLs to the client).
     */
    private String streamUrl;
    private Instant createdAt;
}