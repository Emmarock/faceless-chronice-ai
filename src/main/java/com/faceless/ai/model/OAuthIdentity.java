package com.faceless.ai.model;

import com.faceless.ai.entity.AuthProvider;

/**
 * The identity facts an OAuth service extracts about the connecting user
 * after a successful token exchange. Passed from per-provider OAuth services
 * (Google, Facebook) into {@code UserService} so the User table can be kept
 * in sync without each service having to know about the persistence layer.
 *
 * @param provider        which OAuth provider authenticated the user
 * @param providerUserId  stable provider-side identifier (Google {@code sub},
 *                        Facebook user id, …). Required.
 * @param email           primary email, when the provider returned one
 * @param displayName     human-readable name
 * @param avatarUrl       URL to the provider's profile picture, when present
 */
public record OAuthIdentity(
        AuthProvider provider,
        String providerUserId,
        String email,
        String displayName,
        String avatarUrl) {
}