package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A human user of the application.
 *
 * <p>Today this row is created lazily the first time a user authenticates via
 * an OAuth provider — there is no separate signup endpoint yet. The
 * {@link #externalId} field stores the opaque identifier the frontend already
 * sends in the {@code X-USER} header, which is what every other table's
 * {@code userId} / {@code createdBy} string currently references. Keeping it
 * here lets us bolt on real signup / login later without backfilling FKs
 * across the schema.
 *
 * <p>Identity attached to specific OAuth providers (Google, Facebook, …) lives
 * on {@link UserIdentity}. A single {@code AppUser} can own multiple
 * identities — e.g. the same person logging in with both Google and Facebook
 * resolves to one row here, two rows there.
 *
 * <p>Named {@code AppUser} (not {@code User}) so it doesn't collide with the
 * SQL reserved word and to leave room for a Spring Security {@code UserDetails}
 * implementation later without naming confusion.
 */
@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_app_users_external_id", columnNames = "external_id"),
                @UniqueConstraint(name = "uk_app_users_email",       columnNames = "email")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AppUser extends BaseEntity {

    /**
     * Opaque external identifier supplied by the frontend (today: the
     * {@code X-USER} header value, generated client-side and stored in
     * localStorage). Stable across sessions on the same browser. Will be
     * superseded — but not replaced — once real auth is added.
     */
    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    /**
     * Primary email address as reported by the most recent OAuth provider.
     * Nullable: Facebook users may withhold email permission, and we still
     * want to record the rest of the identity.
     */
    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    /**
     * Which OAuth provider first introduced this user. Useful for analytics
     * and for picking a sensible "primary" identity in the UI later.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "first_seen_provider", length = 32)
    private AuthProvider firstSeenProvider;
}