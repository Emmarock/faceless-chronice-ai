package com.faceless.ai.service;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drains due {@link SocialUpload} rows (status=SCHEDULED, scheduledAt &lt;= now)
 * onto the {@code social-upload} SQS queue and flips their status to QUEUED
 * so the {@code SocialUploadConsumer} picks them up.
 *
 * <p>Polls every 30 seconds — fine-grained enough that a user scheduling
 * "post in 5 minutes" sees their post fire within ~30s of the requested
 * instant, while keeping query pressure on the {@code social_uploads} table
 * negligible (the index on {@code (status, scheduled_at)} makes the lookup
 * a constant-time range scan on the SCHEDULED slice).
 *
 * <p>Rows are grouped by (sourceId, sourceType) so multiple platforms
 * scheduled for the same video at the same time share one SQS event —
 * matches the shape immediate publishes already use, which lets the
 * consumer's parallel fan-out kick in unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostScheduler {

    private final SocialUploadRepository socialUploadRepository;
    private final PipelineProducer pipelineProducer;

    @Scheduled(fixedDelayString = "${chronicleai.scheduler.poll-interval-ms:30000}",
               initialDelayString = "${chronicleai.scheduler.initial-delay-ms:15000}")
    @Transactional
    public void drainDueUploads() {
        List<SocialUpload> due = socialUploadRepository
                .findAllByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        Status.SCHEDULED, Instant.now());
        if (due.isEmpty()) return;

        log.info("PostScheduler firing {} due upload(s).", due.size());

        // Group by source so we send one SQS event per (sourceId, sourceType)
        // with the set of platforms — matches the shape the immediate path
        // already uses.
        Map<GroupKey, List<SocialUpload>> grouped = new HashMap<>();
        for (SocialUpload row : due) {
            GroupKey key = new GroupKey(row.getVideoId(), row.getSourceType());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<GroupKey, List<SocialUpload>> entry : grouped.entrySet()) {
            GroupKey key = entry.getKey();
            Set<SocialPlatform> platforms = entry.getValue().stream()
                    .map(SocialUpload::getPlatform)
                    .collect(java.util.stream.Collectors.toSet());

            SocialUploadEvent event = key.sourceType() == SocialUpload.SourceType.ASSET
                    ? SocialUploadEvent.forAsset(key.sourceId(), platforms)
                    : new SocialUploadEvent(key.sourceId(), platforms);

            try {
                pipelineProducer.publishVideo(PipelineStage.SOCIAL_UPLOAD, event);
            } catch (Exception e) {
                // Don't move the rows forward if we couldn't enqueue — leave
                // them as SCHEDULED so the next tick retries. Bumping
                // lastModifiedOn would risk hot-looping the same failure;
                // logging is enough to surface it.
                log.error("Failed to enqueue SOCIAL_UPLOAD for {} {} — will retry on next tick",
                        key.sourceType(), key.sourceId(), e);
                continue;
            }

            for (SocialUpload row : entry.getValue()) {
                row.setStatus(Status.QUEUED);
                row.setLastModifiedOn(Instant.now());
                socialUploadRepository.save(row);
            }
            log.info("PostScheduler queued {} {} for platforms {}", key.sourceType(), key.sourceId(), platforms);
        }
    }

    private record GroupKey(UUID sourceId, SocialUpload.SourceType sourceType) {}
}
