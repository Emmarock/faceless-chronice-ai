package com.faceless.ai.model;

/**
 * Wrapper for endpoints that return a single URL the frontend should
 * redirect the user to (Stripe Checkout, Stripe Portal). A typed object
 * rather than a bare string so we can extend later (e.g. add an expiry).
 */
public record RedirectUrlDTO(String url) {}