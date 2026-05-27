package com.faceless.ai.service;

import com.faceless.ai.model.VideoFormat;

import java.nio.file.Path;
import java.util.List;

/**
 * Inputs handed to a per-platform {@code VideoUploadService}. Carries both
 * the local mp4 (some platforms multipart-upload bytes directly) and an
 * optional publicly-reachable URL (some platforms — Instagram, LinkedIn —
 * ingest from a URL the platform fetches itself).
 *
 * <p>{@code caption} and {@code hashtags} are per-platform overrides
 * resolved upstream from the user's PublishModal input. Implementations
 * should prefer {@code caption} when present, falling back to
 * {@code title + description} for back-compat with the older call sites.
 */
public record VideoUploadRequest(
        String userId,
        Path videoFile,
        String publicVideoUrl,
        String title,
        String description,
        String caption,
        List<String> hashtags,
        VideoFormat format) {

    /**
     * Back-compat factory for callers that don't yet supply per-platform
     * options. Equivalent to publishing with the raw video/asset metadata
     * and no overrides — preserves the pre-cross-posting-redesign behaviour
     * exactly.
     */
    public static VideoUploadRequest legacy(String userId, Path videoFile, String title, String description) {
        return new VideoUploadRequest(userId, videoFile, null, title, description, null, List.of(), null);
    }
}