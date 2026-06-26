package com.faceless.ai.service;

import com.faceless.ai.config.BillingProperties;
import com.faceless.ai.entity.LedgerKind;
import com.faceless.ai.entity.Lesson;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Subscription;
import com.faceless.ai.entity.Twin;
import com.faceless.ai.repository.LessonRepository;
import com.faceless.ai.repository.TwinRepository;
import com.faceless.ai.service.lesson.LessonScriptGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Background driver for the AI tutor pipeline. On each tick it advances:
 * <ul>
 *   <li><b>Twins</b> in PROCESSING — polls HeyGen training; on ready stores the
 *       avatar/voice ids and marks COMPLETED (or FAILED + refund).</li>
 *   <li><b>Lessons</b> in QUEUED/PROCESSING — generates the script with Claude
 *       and submits the HeyGen render (QUEUED → PROCESSING), then polls the
 *       render and on completion downloads the MP4 to S3 (→ COMPLETED), or
 *       marks FAILED + refunds.</li>
 * </ul>
 *
 * <p>Each row is processed independently with its own try/catch so one bad
 * row never stalls the others. Mirrors the cadence + config style of
 * {@link PostScheduler}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TwinLessonPoller {

    private final TwinRepository twinRepository;
    private final LessonRepository lessonRepository;
    private final LessonService lessonService;
    private final HeyGenService heyGenService;
    private final LessonScriptGenerator lessonScriptGenerator;
    private final S3StorageService s3StorageService;
    private final SubscriptionService subscriptionService;
    private final BillingProperties billingProperties;

    private final HttpClient http = HttpClient.newHttpClient();

    @Scheduled(fixedDelayString = "${chronicleai.heygen.poll-interval-ms:30000}",
               initialDelayString = "${chronicleai.heygen.initial-delay-ms:20000}")
    public void poll() {
        if (!heyGenService.isConfigured()) return;
        // Twins are created synchronously (talking-photo upload is instant), so
        // there is nothing to advance for them here — only lesson renders are
        // asynchronous.
        advanceLessons();
    }

    // ------------------------------------------------------------------ //
    //  Lessons
    // ------------------------------------------------------------------ //

    private void advanceLessons() {
        List<Lesson> lessons = lessonRepository.findByStatusIn(List.of(Status.QUEUED, Status.PROCESSING));
        for (Lesson lesson : lessons) {
            try {
                if (lesson.getHeygenVideoId() == null) {
                    startLessonRender(lesson);
                } else {
                    pollLessonRender(lesson);
                }
            } catch (Exception e) {
                log.warn("Lesson {} poll error: {}", lesson.getId(), e.getMessage());
            }
        }
    }

    /** QUEUED → write script with Claude, submit HeyGen render → PROCESSING. */
    private void startLessonRender(Lesson lesson) {
        Optional<Twin> twinOpt = twinRepository.findById(lesson.getTwinId());
        if (twinOpt.isEmpty() || twinOpt.get().getHeygenAvatarId() == null) {
            failLesson(lesson, "Twin is not available/ready for rendering.");
            return;
        }
        Twin twin = twinOpt.get();
        try {
            if (lesson.getScriptContent() == null || lesson.getScriptContent().isBlank()) {
                String script = lessonScriptGenerator.generateLessonScript(lesson.getTopic(), lesson.getStyle());
                lesson.setScriptContent(script);
                lesson.setStatus(Status.PROCESSING);
                touch(lesson);
                lessonRepository.save(lesson);
            }
            String videoId = heyGenService.generateLessonVideo(
                    twin.getHeygenAvatarId(), twin.getHeygenVoiceId(), lesson.getScriptContent());
            lesson.setHeygenVideoId(videoId);
            lesson.setStatus(Status.PROCESSING);
            touch(lesson);
            lessonRepository.save(lesson);
            log.info("Lesson {} render submitted (video={})", lesson.getId(), videoId);
        } catch (Exception e) {
            failLesson(lesson, "Could not start render: " + e.getMessage());
        }
    }

    /** PROCESSING → poll HeyGen; on completion download MP4 to S3 → COMPLETED. */
    private void pollLessonRender(Lesson lesson) throws Exception {
        HeyGenService.VideoStatus status = heyGenService.getVideoStatus(lesson.getHeygenVideoId());
        if (status.isReady()) {
            String s3Url = downloadToS3(status.videoUrl(),
                    "lessons/" + lesson.getUserId() + "/" + lesson.getId() + ".mp4");
            lesson.setVideoUrl(s3Url);
            lesson.setDurationSeconds(status.durationSeconds());
            lesson.setStatus(Status.COMPLETED);
            touch(lesson);
            lessonRepository.save(lesson);
            // Register a publishable Video so the lesson can be cross-posted
            // through the existing social pipeline.
            lessonService.ensurePublishableVideo(lesson);
            log.info("Lesson {} complete ({})", lesson.getId(), s3Url);
        } else if (status.isFailed()) {
            failLesson(lesson, "HeyGen render failed: " + status.status());
        }
    }

    private void failLesson(Lesson lesson, String message) {
        lesson.setStatus(Status.FAILED);
        lesson.setErrorMessage(truncate(message));
        touch(lesson);
        lessonRepository.save(lesson);
        refund(lesson.getUserId(), LedgerKind.DEBIT_LESSON, "Refund: lesson render failed");
        log.warn("Lesson {} marked FAILED: {}", lesson.getId(), message);
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /** Streams a finished HeyGen video (presigned URL) into our S3 bucket. */
    private String downloadToS3(String url, String key) throws Exception {
        Path temp = Files.createTempFile("lesson-", ".mp4");
        try {
            HttpResponse<Path> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(temp));
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Download of rendered video failed [" + resp.statusCode() + "]");
            }
            return s3StorageService.upload(temp, key);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void refund(String userId, LedgerKind debitKind, String memo) {
        try {
            int cost = billingProperties.costFor(debitKind);
            if (cost <= 0) return;
            Subscription subscription = subscriptionService.getOrCreateForExternalId(userId);
            subscriptionService.grantCredits(subscription, cost, LedgerKind.REFUND, memo, null);
        } catch (Exception e) {
            log.warn("Refund failed for user {} ({}): {}", userId, debitKind, e.getMessage());
        }
    }

    private static void touch(com.faceless.ai.entity.BaseEntity e) {
        e.setLastModifiedOn(Instant.now());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1900 ? s.substring(0, 1900) : s;
    }
}
