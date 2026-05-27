package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Cross-posting payload from {@code POST /api/videos/{id}/publish} and the
 * matching asset endpoint.
 *
 * <p>{@code platforms} — the set of targets the user picked. Required.
 *
 * <p>{@code scheduledAt} — when set to a future instant, the upload is
 * pre-recorded as a SocialUpload row with status=SCHEDULED and the
 * {@code PostScheduler} drains it onto SQS at the right time. Null / past
 * instant => publish immediately.
 *
 * <p>{@code overrides} — optional per-platform caption / title / hashtag
 * overrides. Missing entries fall back to the underlying video's title and
 * description.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VideoPublishRequest {
    private Set<SocialPlatform> platforms;
    private Instant scheduledAt;
    private Map<SocialPlatform, PlatformPostOptions> overrides;
}