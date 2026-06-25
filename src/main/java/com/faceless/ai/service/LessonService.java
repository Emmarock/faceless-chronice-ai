package com.faceless.ai.service;

import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Lesson;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.Twin;
import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.model.CreateLessonRequest;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.LessonRepository;
import com.faceless.ai.repository.TwinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creates + lists lesson videos. Creation validates the twin and debits the
 * {@link LedgerKind#DEBIT_LESSON} cost; the actual scripting (Claude) and
 * rendering (HeyGen) happen asynchronously in {@code TwinLessonPoller} so the
 * request returns immediately.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LessonService {

    private final LessonRepository lessonRepository;
    private final TwinRepository twinRepository;
    private final ClaudeLessonService claudeLessonService;
    private final HeyGenService heyGenService;
    private final SubscriptionService subscriptionService;
    private final AppUserRepository appUserRepository;

    @Transactional
    public Lesson createLesson(String userId, CreateLessonRequest request) {
        if (!claudeLessonService.isConfigured() || !heyGenService.isConfigured()) {
            throw new ExternalApiException(
                    "AI Tutor is not configured — set ANTHROPIC_API_KEY and HEYGEN_API_KEY to generate lessons.");
        }
        if (request.getTopic() == null || request.getTopic().isBlank()) {
            throw new IllegalArgumentException("A lesson topic is required.");
        }
        if (request.getTwinId() == null) {
            throw new IllegalArgumentException("A twinId is required.");
        }

        Twin twin = twinRepository.findByIdAndUserId(request.getTwinId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("Twin not found: " + request.getTwinId()));
        if (twin.getStatus() != Status.COMPLETED || twin.getHeygenAvatarId() == null) {
            throw new IllegalArgumentException(
                    "Twin is not ready yet — wait for training to finish before creating a lesson.");
        }

        Subscription subscription = resolveSubscription(userId);

        Lesson lesson = lessonRepository.save(Lesson.builder()
                .userId(userId)
                .twinId(twin.getId())
                .topic(request.getTopic().trim())
                .style(request.getStyle())
                .status(Status.QUEUED)
                .createdBy(userId)
                .lastModifiedBy(userId)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build());

        subscriptionService.consume(subscription, LedgerKind.DEBIT_LESSON,
                "Lesson: " + lesson.getTopic(), lesson.getId());

        return lesson;
    }

    public List<Lesson> listForUser(String userId) {
        return lessonRepository.findByUserIdOrderByCreatedOnDesc(userId);
    }

    public Lesson getForUser(UUID id, String userId) {
        return lessonRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Lesson not found: " + id));
    }

    /**
     * Lookup by id alone, for the unauthenticated streaming endpoint (same
     * posture as the video stream — the UUID is the bearer). Ownership is not
     * checked here because the {@code <video>} tag can't send the X-USER header.
     */
    public Lesson getForStream(UUID id) {
        return lessonRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lesson not found: " + id));
    }

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
}
