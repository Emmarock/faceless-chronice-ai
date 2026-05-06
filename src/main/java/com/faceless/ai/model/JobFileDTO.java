package com.faceless.ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobFileDTO {
    private UUID jobId;
    private String createdBy;
    private Path jobFilePath;
    private VideoScript videoScript;
    private UUID socialConnectionId;

    public JobFileDTO(UUID jobId, String createdBy, Path jobFilePath, VideoScript videoScript) {
        this(jobId, createdBy, jobFilePath, videoScript, null);
    }
}