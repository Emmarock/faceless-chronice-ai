package com.faceless.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateLessonRequest {
    /** The (COMPLETED) twin that will teach the lesson. */
    private UUID twinId;
    /** What to teach, e.g. "How interest rates work". */
    private String topic;
    /** Optional tone/style hint, e.g. "friendly", "exam-prep". */
    private String style;
}
