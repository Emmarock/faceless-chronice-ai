package com.faceless.ai.model;

import com.faceless.ai.entity.SocialPlatform;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoPublishResponse {

    private UUID videoId;
    private List<PlatformResult> results;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlatformResult {
        private SocialPlatform platform;
        /**
         * One of: QUEUED, NOT_CONNECTED, UNSUPPORTED, ALREADY_UPLOADED.
         * QUEUED means an SQS message was sent; the actual upload happens
         * asynchronously and its progress can be polled via the upload table.
         */
        private String status;
        private String message;
    }
}