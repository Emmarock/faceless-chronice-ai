package com.faceless.ai.entity;

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
     * Optional publishing destination chosen at job-creation time.
     * Set to NULL automatically if the connection is later disconnected,
     * so historical jobs are preserved even after the user revokes access.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_connection_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private SocialConnection socialConnection;
}