package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * A single lesson video: the user's {@link Twin} teaching a chosen topic.
 *
 * <p>Pipeline (driven by {@code TwinLessonPoller}, all asynchronous):
 * <ol>
 *   <li>QUEUED — created with {@link #topic} / {@link #style}, no script yet.</li>
 *   <li>PROCESSING — Claude writes {@link #scriptContent}; the script + the
 *       twin's avatar/voice are submitted to HeyGen, yielding
 *       {@link #heygenVideoId}; render is polled to completion.</li>
 *   <li>COMPLETED — finished MP4 downloaded to S3 ({@link #videoUrl}),
 *       {@link #durationSeconds} recorded.</li>
 *   <li>FAILED — {@link #errorMessage} set; any reserved credits refunded.</li>
 * </ol>
 */
@Entity
@Table(name = "lessons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Lesson extends BaseEntity {

    /** Owning user — the X-USER header value. */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** The twin doing the teaching. */
    @Column(name = "twin_id", nullable = false)
    private UUID twinId;

    /** What the lesson is about, e.g. "How photosynthesis works". */
    @Column(nullable = false, length = 1000)
    private String topic;

    /** Optional tone/style hint, e.g. "friendly", "exam-prep". */
    private String style;

    /** Claude-generated spoken narration the twin reads verbatim. */
    @Column(name = "script_content", columnDefinition = "TEXT")
    private String scriptContent;

    /** HeyGen render job id, polled until the video is ready. */
    @Column(name = "heygen_video_id", length = 255)
    private String heygenVideoId;

    /** S3 URL of the finished lesson video. */
    @Column(name = "video_url", length = 1024)
    private String videoUrl;

    /**
     * The {@link Video} row registered for this lesson once it completes, so it
     * can ride the existing cross-posting publish path (jobId is null on that
     * Video — it's a lesson render, not a documentary job). Null until the
     * lesson finishes rendering.
     */
    @Column(name = "video_id")
    private UUID videoId;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Populated when {@code status == FAILED}. */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
