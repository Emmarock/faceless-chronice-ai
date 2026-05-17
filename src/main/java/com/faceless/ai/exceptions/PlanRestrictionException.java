package com.faceless.ai.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a request asks for a feature the caller's current plan
 * doesn't unlock (e.g. long-form video on the FREE tier). Mapped to HTTP
 * 403 Forbidden so the frontend can render an "Upgrade to unlock" CTA
 * without confusing the message with a 401 (not signed in) or 402 (out of
 * credits).
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PlanRestrictionException extends RuntimeException {

    public PlanRestrictionException(String message) {
        super(message);
    }
}