package com.faceless.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobSummaryDTO {
    private UUID jobId;
    private String title;
    private String question;
    private String style;
    private String status;
    private Integer progress;
    private Instant createdAt;
}