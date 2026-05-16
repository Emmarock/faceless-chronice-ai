package com.faceless.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * A single {@link AppUser}'s identity at one external OAuth provider.
 *
 * <p>The natural key is ({@link #provider}, {@link #providerUserId}) — that
 * pair is what an OAuth provider hands back as a stable identifier across
 * sessions, regardless of email changes. Rows are upserted on every OAuth
 * connect so the snapshot fields ({@link #email}, {@link #displayName},
 * {@link #avatarUrl}, {@link #lastSeenAt}) stay current.
 *
 * <p>Why both {@code AppUser.email} and {@code UserIdentity.email}? They can
 * diverge — a user's Google primary email and their Facebook email aren't
 * guaranteed to match. {@code AppUser.email} is the canonical one we use
 * for login lookups; {@code UserIdentity.email} is the per-provider snapshot.
 */
@Entity
@Table(
        name = "user_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_identities_provider_provider_user_id",
                columnNames = {"provider", "provider_user_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser appUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthProvider provider;

    /**
     * The provider's stable user identifier — Google's {@code sub} claim,
     * Facebook's user id, etc. Never changes for a given account.
     */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}