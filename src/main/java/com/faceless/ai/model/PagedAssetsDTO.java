package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One page of the user's asset library plus the totals needed to render
 * a "Page X of N — showing A–B of T" footer without a second round-trip.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedAssetsDTO {
    private List<AssetSummaryDTO> items;
    private int page;        // zero-indexed
    private int size;
    private long totalItems;
    private int totalPages;
}