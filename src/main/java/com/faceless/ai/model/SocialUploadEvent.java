package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;

import java.util.Set;
import java.util.UUID;

/**
 * SQS payload for the social-upload queue. Exactly one of {@code videoId}
 * or {@code assetId} must be set:
 * <ul>
 *   <li>{@code videoId} — the legacy path used by job-final renders. The
 *       consumer loads a {@link com.faceless.ai.entity.Video} row, downloads
 *       its mp4, and uploads.</li>
 *   <li>{@code assetId} — the per-clip path used when a user publishes a
 *       single rendered scene clip from the asset library. The consumer
 *       loads the {@link com.faceless.ai.entity.Asset} row and uploads its
 *       S3 object directly.</li>
 * </ul>
 * Older messages on the queue predate {@code assetId} — Jackson defaults the
 * missing field to {@code null} so the consumer's video branch keeps working
 * untouched.
 */
public record SocialUploadEvent(UUID videoId, UUID assetId, Set<SocialPlatform> platforms) {

    /** Back-compat constructor for the existing video-publish path. */
    public SocialUploadEvent(UUID videoId, Set<SocialPlatform> platforms) {
        this(videoId, null, platforms);
    }

    public static SocialUploadEvent forAsset(UUID assetId, Set<SocialPlatform> platforms) {
        return new SocialUploadEvent(null, assetId, platforms);
    }
}