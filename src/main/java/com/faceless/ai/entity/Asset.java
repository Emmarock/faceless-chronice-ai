package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import java.util.UUID;

@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Asset extends BaseEntity {

    private UUID jobId;

    /**
     * Pinned to VARCHAR(32) on purpose. Hibernate 6's default for
     * {@code @Enumerated(EnumType.STRING)} on MySQL is to create a native
     * {@code ENUM(...)} column, and {@code ddl-auto=update} will <em>not</em>
     * ALTER that column when a new value is added to {@link AssetType} —
     * inserts of the new value fail with "Data truncated for column".
     * Using VARCHAR sidesteps the problem so adding enum values stays a
     * code-only change.
     */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(32)")
    private AssetType assetType; // e.g., image, voice, video_clip, source_video, music, thumbnail

    private String url; // storage location

    @Column(columnDefinition = "TEXT")
    private String metadata; // e.g., scene number, duration, extra info
}