package com.faceless.ai.controllers;

import com.faceless.ai.entity.AppUser;
import com.faceless.ai.model.OAuthIdentity;
import com.faceless.ai.model.SignInRequest;
import com.faceless.ai.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity-only endpoints used by the client-side OAuth login flows
 * (Google One Tap + Facebook JS SDK on the {@code /login} page).
 *
 * <p>Unlike {@code /api/social-connections/*}, this controller is <em>not</em>
 * for connecting publish destinations — it exists so the backend learns who a
 * user is the moment they sign in, even before they ever connect a Page or a
 * YouTube channel.
 *
 * <p>Intentionally does not require the {@code X-USER} header: at first
 * sign-in the frontend hasn't yet written {@code fc.userId} to localStorage,
 * so the request would arrive header-less. The {@link SignInRequest#email()}
 * value is used as the {@code externalId} that subsequent {@code X-USER}-bearing
 * requests will carry.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;

    @PostMapping("/sign-in")
    public ResponseEntity<Void> signIn(@RequestBody SignInRequest request) {
        if (request.providerUserId() == null || request.providerUserId().isBlank()) {
            throw new IllegalArgumentException("providerUserId is required.");
        }
        // The frontend uses the email as the X-USER header value; keep that
        // contract here so every other table's userId/createdBy string keeps
        // pointing at the right AppUser row. Fall back to providerUserId when
        // email is missing (Facebook users who withheld the email scope).
        String externalId = request.email() != null && !request.email().isBlank()
                ? request.email()
                : request.providerUserId();

        AppUser user = userService.recordOAuthIdentity(
                externalId,
                new OAuthIdentity(
                        request.provider(),
                        request.providerUserId(),
                        request.email(),
                        request.name(),
                        request.picture()));
        log.info("Recorded sign-in for AppUser {} via {}", user.getId(), request.provider());
        return ResponseEntity.noContent().build();
    }
}