package com.faceless.ai.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ---------------- External API Errors ---------------- //
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiErrorResponse> handleExternalApiException(
            ExternalApiException ex,
            HttpServletRequest request) {

        log.error("External API error: {}", ex.getMessage());

        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("External API Error")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    // ---------------- Illegal Arguments ---------------- //
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.badRequest().body(error);
    }

    // ---------------- Insufficient Credits ---------------- //
    @ExceptionHandler(InsufficientCreditsException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientCredits(
            InsufficientCreditsException ex,
            HttpServletRequest request) {

        // 402 Payment Required — the request was well-formed and authenticated;
        // it failed because the user's credit balance is too low. The frontend
        // keys off the status to render an inline plan picker rather than a
        // generic error string.
        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.PAYMENT_REQUIRED.value())
                .error("Insufficient Credits")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error);
    }

    // ---------------- Plan Restriction ---------------- //
    @ExceptionHandler(PlanRestrictionException.class)
    public ResponseEntity<ApiErrorResponse> handlePlanRestriction(
            PlanRestrictionException ex,
            HttpServletRequest request) {

        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Plan Restriction")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // ---------------- No Facebook Page on user's account ---------------- //
    @ExceptionHandler(NoFacebookPageException.class)
    public ResponseEntity<ApiErrorResponse> handleNoFacebookPage(
            NoFacebookPageException ex,
            HttpServletRequest request) {

        // 422 (not 500): the request was well-formed and authenticated; it
        // failed a precondition that's the user's to fix (no Page on their
        // account). The frontend keys off `errorCode` to render an
        // empty-state card with a "Create a Page" call-to-action instead of
        // surfacing the message as a raw red error string.
        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .error("No Facebook Page")
                .message(ex.getMessage())
                .errorCode(NoFacebookPageException.CODE)
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    // ---------------- Generic Exception ---------------- //
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled error", ex);

        ApiErrorResponse error = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("Something went wrong")
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}