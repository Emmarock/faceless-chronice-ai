package com.faceless.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "scripts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Script extends BaseEntity {

    private UUID jobId;

    @Column(columnDefinition = "LONGTEXT")
    private String content; // full script text or JSON structure

    private int sceneCount;
}