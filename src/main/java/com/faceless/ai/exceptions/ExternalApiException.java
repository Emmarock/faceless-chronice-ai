package com.faceless.ai.exceptions;

public class ExternalApiException extends RuntimeException {

    public ExternalApiException(String message) {
        super(message);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExternalApiException(Throwable cause) {
        super(cause);
    }

    public ExternalApiException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public ExternalApiException() {
    }
}