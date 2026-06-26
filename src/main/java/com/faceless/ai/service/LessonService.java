package com.faceless.ai.service;

import com.faceless.ai.entity.AppUser;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Lesson;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.Twin;
import com.faceless.ai.entity.Video;
import com.faceless.ai.exceptions.ExternalApiException;
import com.faceless.ai.model.CreateLessonRequest;
import com.faceless.ai.repository.AppUserRepository;
import com.faceless.ai.repository.LessonRepository;
import com.faceless.ai.repository.TwinRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.lesson.LessonScriptGenerator;
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
    private final LessonScriptGenerator lessonScriptGenerator;
    private final HeyGenService heyGenService;
    private final SubscriptionService subscriptionService;
    private final AppUserRepository appUserRepository;
    private final VideoRepository videoRepository;

    @Transactional
    public Lesson createLesson(String userId, CreateLessonRequest request) {
        if (!lessonScriptGenerator.isConfigured() || !heyGenService.isConfigured()) {
            throw new ExternalApiException(
                    "AI Tutor is not configured — set HEYGEN_API_KEY and the configured lesson "
                            + "provider's API key (OPENAI_KEY by default, or ANTHROPIC_API_KEY for claude).");
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
        Lesson lesson = lessonRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Lesson not found: " + id));
        // Lazily register a publishable Video for completed lessons (covers
        // lessons that finished before this feature existed). Idempotent.
        ensurePublishableVideo(lesson);
        return lesson;
    }

    /**
     * Registers a {@link Video} row for a completed lesson so it can be
     * cross-posted via the existing publish pipeline, and stores its id on the
     * lesson. No-op unless the lesson is COMPLETED with a rendered video and
     * doesn't already have one. Returns the linked video id (or null).
     */
    @Transactional
    public UUID ensurePublishableVideo(Lesson lesson) {
        if (lesson.getVideoId() != null) return lesson.getVideoId();
        if (lesson.getStatus() != Status.COMPLETED
                || lesson.getVideoUrl() == null || lesson.getVideoUrl().isBlank()) {
            return null;
        }
        Video video = videoRepository.save(Video.builder()
                .id(UUID.randomUUID())
                .jobId(null) // lesson render — not tied to a documentary job
                .title(lesson.getTopic())
                .description(lesson.getStyle())
                .durationSeconds(lesson.getDurationSeconds() != null ? lesson.getDurationSeconds() : 0)
                .storageUrl(lesson.getVideoUrl())
                .status(Status.COMPLETED)
                .createdBy(lesson.getUserId())
                .lastModifiedBy(lesson.getUserId())
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build());
        lesson.setVideoId(video.getId());
        lesson.setLastModifiedOn(Instant.now());
        lessonRepository.save(lesson);
        log.info("Registered publishable Video {} for lesson {}", video.getId(), lesson.getId());
        return video.getId();
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
