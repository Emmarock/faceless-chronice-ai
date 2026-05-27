package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat view of a scheduled SocialUpload row for the ScheduledPostsPage.
 *
 * <p>Includes just enough to render the row: which platform, which video /
 * asset, when it fires, and the caption the user picked at publish-time so
 * they can read it back without opening the source.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduledUploadDTO(
        UUID id,
        UUID sourceId,
        SocialUpload.SourceType sourceType,
        SocialPlatform platform,
        Status status,
        Instant scheduledAt,
        String title,
        String caption,
        String hashtags
) {
    public static ScheduledUploadDTO from(SocialUpload row) {
        return new ScheduledUploadDTO(
                row.getId(),
                row.getVideoId(),
                row.getSourceType(),
                row.getPlatform(),
                row.getStatus(),
                row.getScheduledAt(),
                row.getTitle(),
                row.getCaption(),
                row.getHashtags());
    }
}
