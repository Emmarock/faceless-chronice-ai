package com.faceless.ai.entity;

/**
 * Identifies an external identity provider that can authenticate a user.
 *
 * <p>Distinct from {@link SocialPlatform}, which describes a destination we
 * <em>publish to</em>. A platform can be both (e.g. Google → both YouTube
 * publishing and OAuth identity), but the concepts are different and the
 * provider list is expected to grow on a different cadence (Apple, GitHub,
 * email/password, etc.) than the publishing list.
 */
public enum AuthProvider {
    GOOGLE,
    FACEBOOK
}