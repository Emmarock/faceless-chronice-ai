package com.faceless.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateJobRequest {

    private String question;       // Example: "History of Israel-Palestine conflict"
    private String style;          // Example: "documentary"
    private Integer durationSeconds; // Total duration estimate (optional)
    private java.util.UUID socialConnectionId; // Optional: publishing destination
    /**
     * Short-form ({@link VideoFormat#REELS}) vs. long-form ({@link VideoFormat#VIDEO}).
     * Null is treated as {@link VideoFormat#VIDEO} so older clients keep
     * producing long-form jobs.
     */
    private VideoFormat videoFormat;
}