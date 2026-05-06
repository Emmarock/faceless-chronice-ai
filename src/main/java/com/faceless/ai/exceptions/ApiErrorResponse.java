package com.faceless.ai.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    /**
     * Optional machine-readable code the frontend can branch on (e.g.
     * {@code NO_FACEBOOK_PAGE}). Null on errors where the human message is
     * the only useful signal — kept out of the JSON in that case via
     * {@link JsonInclude.Include#NON_NULL}.
     */
    private String errorCode;
}