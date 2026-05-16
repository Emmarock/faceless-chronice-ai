package com.faceless.ai.service;

import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.UserIdentity;
import com.faceless.ai.model.OAuthIdentity;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.UserIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Owns the {@link AppUser} / {@link UserIdentity} tables.
 *
 * <p>The only side door into these tables today is
 * {@link #recordOAuthIdentity(String, OAuthIdentity)}, called by each
 * per-provider OAuth service after a successful token exchange. The contract
 * is intentionally narrow so it's easy to bolt a real signup / login endpoint
 * onto the same primitives later.
 *
 * <h3>Linking rules</h3>
 * <ol>
 *   <li>If a {@code UserIdentity} already exists for the
 *       ({@link OAuthIdentity#provider}, {@link OAuthIdentity#providerUserId})
 *       pair, that row's {@code appUser} is the source of truth — we update
 *       its snapshot fields and re-point {@code externalId} so the legacy
 *       {@code X-USER} string follows the human across browser resets.</li>
 *   <li>Otherwise, the {@code externalId} is the next signal: find or create
 *       an {@code AppUser} matching it, then attach a fresh identity.</li>
 *   <li>Email-only matching is intentionally <em>not</em> used here. Two
 *       different humans can share an email at two providers (work vs.
 *       personal), and silently merging accounts is harder to undo than
 *       splitting them. We surface that linkage decision to product later.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final AppUserRepository appUserRepository;
    private final UserIdentityRepository userIdentityRepository;

    /**
     * Records (or refreshes) the OAuth identity of the user identified by
     * {@code externalId} (the value the frontend sends in {@code X-USER}).
     *
     * @return the {@link AppUser} this identity now resolves to. Useful if the
     *         caller wants to surface profile data back to the client.
     */
    @Transactional
    public AppUser recordOAuthIdentity(String externalId, OAuthIdentity identity) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required to record an OAuth identity.");
        }
        if (identity == null || identity.providerUserId() == null || identity.providerUserId().isBlank()) {
            throw new IllegalArgumentException("OAuthIdentity.providerUserId is required.");
        }

        Optional<UserIdentity> existingIdentity = userIdentityRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());

        AppUser user;
        if (existingIdentity.isPresent()) {
            user = existingIdentity.get().getAppUser();
            // A returning user may have a fresh X-USER (cleared localStorage,
            // new browser). Carry their externalId forward so subsequent calls
            // by either value resolve to the same row.
            if (!externalId.equals(user.getExternalId())) {
                log.info("Re-linking AppUser {} externalId {} -> {} via {} identity {}",
                        user.getId(), user.getExternalId(), externalId,
                        identity.provider(), identity.providerUserId());
                user.setExternalId(externalId);
            }
        } else {
            user = appUserRepository.findByExternalId(externalId)
                    .orElseGet(() -> createUser(externalId, identity));
        }

        applyProfileSnapshot(user, identity);
        user = appUserRepository.save(user);

        upsertIdentity(user, identity, existingIdentity.orElse(null));
        return user;
    }

    private AppUser createUser(String externalId, OAuthIdentity identity) {
        log.info("Creating AppUser for externalId={} (first seen via {})", externalId, identity.provider());
        return AppUser.builder()
                .externalId(externalId)
                .firstSeenProvider(identity.provider())
                .createdBy(externalId)
                .lastModifiedBy(externalId)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
    }

    /**
     * Copies snapshot fields from the OAuth identity onto the user row.
     * Only fills blanks so a later provider call doesn't clobber a value the
     * user set explicitly elsewhere (room for that exists once profile-edit
     * lands).
     */
    private void applyProfileSnapshot(AppUser user, OAuthIdentity identity) {
        if (isBlank(user.getEmail())       && !isBlank(identity.email()))       user.setEmail(identity.email());
        if (isBlank(user.getDisplayName()) && !isBlank(identity.displayName())) user.setDisplayName(identity.displayName());
        if (isBlank(user.getAvatarUrl())   && !isBlank(identity.avatarUrl()))   user.setAvatarUrl(identity.avatarUrl());
        user.setLastModifiedOn(Instant.now());
    }

    private void upsertIdentity(AppUser user, OAuthIdentity identity, UserIdentity existing) {
        UserIdentity row = existing != null
                ? existing
                : UserIdentity.builder()
                        .provider(identity.provider())
                        .providerUserId(identity.providerUserId())
                        .createdBy(user.getExternalId())
                        .lastModifiedBy(user.getExternalId())
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build();

        row.setAppUser(user);
        // Always overwrite the per-provider snapshot — the provider is the
        // authoritative source for these.
        row.setEmail(identity.email());
        row.setDisplayName(identity.displayName());
        row.setAvatarUrl(identity.avatarUrl());
        row.setLastSeenAt(Instant.now());
        row.setLastModifiedOn(Instant.now());
        row.setLastModifiedBy(user.getExternalId());
        userIdentityRepository.save(row);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}