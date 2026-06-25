package com.faceless.ai.service;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.Twin;
import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.TwinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Onboarding + lifecycle for AI tutor twins. Creating a twin uploads the user's
 * clip to HeyGen, kicks off avatar/voice training, and debits the
 * {@link LedgerKind#DEBIT_TWIN_TRAINING} cost. {@code TwinLessonPoller} then
 * advances the row to COMPLETED as training finishes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TwinService {

    private final TwinRepository twinRepository;
    private final HeyGenService heyGenService;
    private final S3StorageService s3StorageService;
    private final SubscriptionService subscriptionService;
    private final AppUserRepository appUserRepository;
    private final BillingProperties billingProperties;

    @Transactional
    public Twin createTwin(String userId, String name, MultipartFile videoFile) throws Exception {
        if (!heyGenService.isConfigured()) {
            throw new ExternalApiException(
                    "AI Tutor is not configured — set HEYGEN_API_KEY to enable twin creation.");
        }
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("A source video clip is required to create a twin.");
        }
        String twinName = (name == null || name.isBlank()) ? "My teaching twin" : name.trim();

        // Fail fast on out-of-credits before any upload/HeyGen work.
        Subscription subscription = resolveSubscription(userId);

        // Stash the clip in S3 (our record + UI playback of the source) and
        // create the row before debiting so the ledger can reference the id.
        Path temp = Files.createTempFile("twin-src-", suffixFor(videoFile));
        try {
            videoFile.transferTo(temp.toFile());
            String key = "twins/" + userId + "/" + UUID.randomUUID() + suffixFor(videoFile);
            String sourceUrl = s3StorageService.upload(temp, key);

            Twin twin = twinRepository.save(Twin.builder()
                    .userId(userId)
                    .name(twinName)
                    .sourceVideoUrl(sourceUrl)
                    .status(Status.QUEUED)
                    .createdBy(userId)
                    .lastModifiedBy(userId)
                    .createdOn(Instant.now())
                    .lastModifiedOn(Instant.now())
                    .build());

            subscriptionService.consume(subscription, LedgerKind.DEBIT_TWIN_TRAINING,
                    "Twin training: " + twinName, twin.getId());

            // Submit training to HeyGen using the uploaded bytes. On failure,
            // mark the twin FAILED and refund so the user isn't charged for a
            // twin they can't use.
            try {
                String trainingId = heyGenService.submitAvatarTraining(temp, twinName);
                twin.setHeygenTrainingId(trainingId);
                twin.setStatus(Status.PROCESSING);
                twin.setLastModifiedOn(Instant.now());
                return twinRepository.save(twin);
            } catch (Exception e) {
                log.error("HeyGen avatar training submit failed for twin {}: {}", twin.getId(), e.getMessage());
                twin.setStatus(Status.FAILED);
                twin.setErrorMessage(truncate(e.getMessage()));
                twin.setLastModifiedOn(Instant.now());
                twinRepository.save(twin);
                refund(subscription, LedgerKind.DEBIT_TWIN_TRAINING, "Refund: twin training failed");
                throw new ExternalApiException("Could not start twin training: " + e.getMessage(), e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public List<Twin> listForUser(String userId) {
        return twinRepository.findByUserIdOrderByCreatedOnDesc(userId);
    }

    public Twin getForUser(UUID id, String userId) {
        return twinRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Twin not found: " + id));
    }

    @Transactional
    public void deleteForUser(UUID id, String userId) {
        Twin twin = getForUser(id, userId);
        twinRepository.delete(twin);
    }

    // ------------------------------------------------------------------ //

    private Subscription resolveSubscription(String userId) {
        AppUser user = appUserRepository.findByExternalId(userId)
                .orElseGet(() -> appUserRepository.save(AppUser.builder()
                        .externalId(userId)
                        .createdBy(userId)
                        .lastModifiedBy(userId)
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build()));
        return subscriptionService.getOrCreateForUser(user);
    }

    private void refund(Subscription subscription, LedgerKind debitKind, String memo) {
        int cost = billingProperties.costFor(debitKind);
        if (cost > 0) {
            subscriptionService.grantCredits(subscription, cost, LedgerKind.REFUND, memo, null);
        }
    }

    private static String suffixFor(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) return name.substring(dot);
        }
        return ".mp4";
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1900 ? s.substring(0, 1900) : s;
    }
}
