package com.faceless.ai.entity;

import com.faceless.ai.model.VideoFormat;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "social_uploads",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_social_uploads_video_platform",
               columnNames = {"video_id", "platform"}),
       indexes = {
               @Index(name = "idx_social_uploads_status_scheduled",
                      columnList = "status, scheduled_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SocialUpload extends BaseEntity {

    private UUID videoId;

    @Enumerated(EnumType.STRING)
    private SocialPlatform platform;

    /**
     * Distinguishes a publish against a finished {@code Video} (job-final
     * render) vs. a single {@code Asset} of type VIDEO_CLIP (one rendered
     * scene clip from the asset library). Lets the scheduler dispatch the
     * right kind of SocialUploadEvent without re-checking the source tables.
     */
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    private String providerPostId;

    private Instant uploadedAt;

    /**
     * When set, the upload is deferred until this instant. The scheduler
     * picks up rows with status=SCHEDULED and scheduledAt &lt;= now and
     * moves them into the QUEUED state by sending the SQS event.
     */
    private Instant scheduledAt;

    /**
     * Per-platform display title. Overrides the underlying video/asset title
     * when present. Honoured by YouTube (title field) and Facebook (title).
     */
    @Column(length = 500)
    private String title;

    /**
     * Per-platform body text (description / caption). Overrides the
     * underlying video/asset description when present. Honoured by every
     * platform with a caption field.
     */
    @Column(columnDefinition = "TEXT")
    private String caption;

    /**
     * Comma-separated hashtag list (without the leading {@code #}). Appended
     * to the caption by platforms that benefit from a separate hashtag block
     * (Instagram, TikTok). Stored as a string for portability; the value is
     * always derived from a user-supplied list on the frontend.
     */
    @Column(length = 2000)
    private String hashtags;

    /**
     * The video shape the user chose at generation time. Used by upload
     * services to refuse / warn about incompatible posts (e.g. a 9:16 Reels
     * to LinkedIn, which prefers 16:9). Nullable for back-compat with rows
     * that predate this column.
     */
    @Enumerated(EnumType.STRING)
    private VideoFormat videoFormat;

    public enum SourceType {
        VIDEO,
        ASSET
    }
}