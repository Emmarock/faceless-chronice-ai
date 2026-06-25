package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * An "AI tutor twin": a HeyGen avatar + cloned voice trained from a short clip
 * the user recorded of themselves. Once {@link Status#COMPLETED}, the
 * {@link #heygenAvatarId} / {@link #heygenVoiceId} pair can be reused to render
 * any number of {@link Lesson} videos that teach in the user's likeness.
 *
 * <p>Creation is asynchronous on HeyGen's side: we upload the source clip,
 * kick off avatar training, and store the returned {@link #heygenTrainingId}.
 * {@code TwinLessonPoller} then advances the row QUEUED → PROCESSING →
 * COMPLETED (or FAILED, with {@link #errorMessage} set) as training finishes.
 */
@Entity
@Table(name = "twins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Twin extends BaseEntity {

    /** Owning user — the X-USER header value, matching other tables' userId. */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** User-facing label, e.g. "My teaching twin". */
    @Column(nullable = false)
    private String name;

    /** S3 URL of the source clip the user uploaded/recorded. */
    @Column(name = "source_video_url", length = 1024)
    private String sourceVideoUrl;

    /**
     * HeyGen-side handle for the in-flight avatar training job. Polled by
     * {@code TwinLessonPoller} until the avatar is ready. Null once training
     * has resolved into {@link #heygenAvatarId}.
     */
    @Column(name = "heygen_training_id", length = 255)
    private String heygenTrainingId;

    /** Ready avatar id used as the {@code character} when rendering lessons. */
    @Column(name = "heygen_avatar_id", length = 255)
    private String heygenAvatarId;

    /**
     * Cloned voice id used as the {@code voice} when rendering lessons. May be
     * null when voice cloning is unavailable on the account's plan — callers
     * then fall back to the configured default HeyGen voice.
     */
    @Column(name = "heygen_voice_id", length = 255)
    private String heygenVoiceId;

    /** Populated when {@code status == FAILED} so the UI can show why. */
    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
