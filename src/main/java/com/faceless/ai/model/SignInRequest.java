package com.faceless.ai.model;

import com.faceless.ai.entity.AuthProvider;

/**
 * Payload the frontend posts on every successful client-side OAuth login
 * (Google One Tap, Facebook JS SDK) so the backend can record the
 * {@code AppUser} / {@code UserIdentity} rows.
 *
 * <p>This endpoint does <em>not</em> verify the OAuth signature today — the
 * frontend is trusted to forward the identity it just received from the
 * provider. Once real session management lands, the JWT / access-token should
 * be re-verified server-side before any of these fields are trusted for
 * authorization decisions. For now they're used only as identity hints to
 * populate the directory tables.
 *
 * @param provider        which OAuth provider the user signed in with
 * @param providerUserId  the provider's stable identifier (Google {@code sub},
 *                        Facebook user id). Required.
 * @param email           primary email reported by the provider
 * @param name            display name reported by the provider
 * @param picture         avatar URL reported by the provider
 */
public record SignInRequest(
        AuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String picture) {
}