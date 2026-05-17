package com.faceless.ai.entity;

import com.faceless.ai.model.VideoFormat;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Job extends BaseEntity {

    @Column(columnDefinition = "TEXT")
    private String question;

    private int progress;

    @Column(columnDefinition = "TEXT")
    private String style;

    private Integer durationSeconds;

    /**
     * Short-form (REELS) or long-form (VIDEO). Persisted so downstream
     * pipeline stages and the UI can adapt — e.g. force a single scene for
     * REELS, render in 9:16, etc. Nullable for rows created before the
     * column existed; readers should treat null as {@link VideoFormat#VIDEO}.
     */
    @Enumerated(EnumType.STRING)
    private VideoFormat videoFormat;

    /**
     * When true, the final FFmpeg assembly stamps a watermark on each
     * scene's clip. Decided at job-creation time from the user's plan, then
     * frozen on the row — upgrading mid-pipeline does not retroactively
     * remove the watermark, and downgrading mid-pipeline does not add one.
     * That keeps a single user's job output deterministic regardless of
     * what happens on their account in between.
     */
    @Column(name = "watermarked", nullable = false)
    private boolean watermarked;

    /**
     * Optional publishing destination chosen at job-creation time.
     * Set to NULL automatically if the connection is later disconnected,
     * so historical jobs are preserved even after the user revokes access.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_connection_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private SocialConnection socialConnection;
}