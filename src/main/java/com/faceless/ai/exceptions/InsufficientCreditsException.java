package com.faceless.ai.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a metered AI action would push the user's credit balance below
 * zero. Mapped to HTTP 402 (Payment Required) so the frontend can react
 * unambiguously with an "out of credits — upgrade?" UX rather than a generic
 * 500.
 */
@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class InsufficientCreditsException extends RuntimeException {

    public InsufficientCreditsException(String message) {
        super(message);
    }
}