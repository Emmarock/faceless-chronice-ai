package com.faceless.ai.model;

import com.faceless.ai.entity.Lesson;
import com.faceless.ai.entity.Status;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** Client view of a {@link Lesson}. */
@Builder
public record LessonDTO(
        UUID id,
        UUID twinId,
        String topic,
        String style,
        Status status,
        String scriptContent,
        Integer durationSeconds,
        boolean hasVideo,
        String errorMessage,
        Instant createdOn
) {
    public static LessonDTO from(Lesson l) {
        return LessonDTO.builder()
                .id(l.getId())
                .twinId(l.getTwinId())
                .topic(l.getTopic())
                .style(l.getStyle())
                .status(l.getStatus())
                .scriptContent(l.getScriptContent())
                .durationSeconds(l.getDurationSeconds())
                .hasVideo(l.getVideoUrl() != null && !l.getVideoUrl().isBlank())
                .errorMessage(l.getErrorMessage())
                .createdOn(l.getCreatedOn())
                .build();
    }
}
