package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.PlatformPostOptions;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.model.VideoFormat;
import com.faceless.ai.model.VideoPublishResponse;
import com.faceless.ai.model.VideoPublishResponse.PlatformResult;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.JobRepository;
import com.faceless.ai.repository.SocialConnectionRepository;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Routes a finished {@link Video} (or a single rendered clip {@link Asset})
 * to the upload pipeline for one or more social platforms.
 *
 * <p>Cross-posting redesign (2026-05-27): the publish path now <i>always</i>
 * pre-creates a {@code SocialUpload} row per target. This is what lets
 * scheduled posts work — they sit in the table with status=SCHEDULED until
 * the scheduler queues them — and what lets per-platform captions / titles
 * survive between publish-time and upload-time (which can be hours apart
 * for scheduled posts).
 *
 * <ul>
 *   <li>Each platform yields one PlatformResult:
 *     <ul>
 *       <li>{@code NOT_CONNECTED} — no OAuth connection. Nothing persisted.</li>
 *       <li>{@code ALREADY_UPLOADED} — prior upload row already has a
 *           providerPostId. Nothing persisted.</li>
 *       <li>{@code SCHEDULED} — a SocialUpload row was created with
 *           status=SCHEDULED and the requested scheduledAt. No SQS message
 *           is sent — the scheduler will drain it.</li>
 *       <li>{@code QUEUED} — a SocialUpload row was created with
 *           status=QUEUED and an SQS message was sent.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPublishService {

    private final VideoRepository videoRepository;
    private final AssetRepository assetRepository;
    private final JobRepository jobRepository;
    private final SocialConnectionRepository socialConnectionRepository;
    private final SocialUploadRepository socialUploadRepository;
    private final PipelineProducer pipelineProducer;

    @Transactional
    public VideoPublishResponse publish(UUID videoId,
                                        String userId,
                                        Set<SocialPlatform> platforms,
                                        Instant scheduledAt,
                                        Map<SocialPlatform, PlatformPostOptions> overrides) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
        validateOwnership(video, userId);

        if (platforms == null || platforms.isEmpty()) {
            return new VideoPublishResponse(videoId, List.of());
        }

        VideoFormat format = resolveVideoFormat(video);
        return runPublish(
                videoId,
                userId,
                platforms,
                scheduledAt,
                overrides == null ? Map.of() : overrides,
                SocialUpload.SourceType.VIDEO,
                format,
                "video");
    }

    @Transactional
    public VideoPublishResponse publishAsset(UUID assetId,
                                             String userId,
                                             Set<SocialPlatform> platforms,
                                             Instant scheduledAt,
                                             Map<SocialPlatform, PlatformPostOptions> overrides) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));
        if (asset.getCreatedBy() != null && !asset.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("Asset " + assetId + " does not belong to user " + userId);
        }
        if (asset.getAssetType() != AssetType.VIDEO_CLIP) {
            throw new IllegalArgumentException(
                    "Only rendered clips (VIDEO_CLIP) can be published to social — got " + asset.getAssetType());
        }

        if (platforms == null || platforms.isEmpty()) {
            return new VideoPublishResponse(assetId, List.of());
        }

        VideoFormat format = resolveAssetFormat(asset);
        return runPublish(
                assetId,
                userId,
                platforms,
                scheduledAt,
                overrides == null ? Map.of() : overrides,
                SocialUpload.SourceType.ASSET,
                format,
                "clip");
    }

    private VideoPublishResponse runPublish(UUID sourceId,
                                            String userId,
                                            Set<SocialPlatform> platforms,
                                            Instant scheduledAt,
                                            Map<SocialPlatform, PlatformPostOptions> overrides,
                                            SocialUpload.SourceType sourceType,
                                            VideoFormat format,
                                            String label) {
        boolean defer = scheduledAt != null && scheduledAt.isAfter(Instant.now());
        Map<SocialPlatform, PlatformResult> resultByPlatform = new HashMap<>();

        for (SocialPlatform platform : platforms) {
            PlatformResult result = validate(sourceId, userId, platform, label);
            if ("ALREADY_UPLOADED".equals(result.getStatus()) || "NOT_CONNECTED".equals(result.getStatus())) {
                resultByPlatform.put(platform, result);
                continue;
            }

            PlatformPostOptions options = overrides.get(platform);
            SocialUpload upload = upsertUploadRow(
                    sourceId,
                    userId,
                    platform,
                    sourceType,
                    format,
                    options,
                    scheduledAt,
                    defer);
            log.info("{} upload row {} persisted for {} {} ({})",
                    platform, upload.getId(), label, sourceId, upload.getStatus());
            resultByPlatform.put(platform,
                    new PlatformResult(platform,
                            defer ? "SCHEDULED" : "QUEUED",
                            defer
                                    ? "Scheduled for " + scheduledAt + " on " + platform + "."
                                    : "Upload queued for " + platform + "."));
        }

        if (!defer) {
            Set<SocialPlatform> queueable = resultByPlatform.entrySet().stream()
                    .filter(e -> "QUEUED".equals(e.getValue().getStatus()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (!queueable.isEmpty()) {
                SocialUploadEvent event = sourceType == SocialUpload.SourceType.ASSET
                        ? SocialUploadEvent.forAsset(sourceId, queueable)
                        : new SocialUploadEvent(sourceId, queueable);
                pipelineProducer.publishVideo(PipelineStage.SOCIAL_UPLOAD, event);
                log.info("Queued {} {} for platforms {}", label, sourceId, queueable);
            }
        }

        List<PlatformResult> ordered = platforms.stream()
                .map(resultByPlatform::get)
                .toList();
        return new VideoPublishResponse(sourceId, ordered);
    }

    private SocialUpload upsertUploadRow(UUID sourceId,
                                         String userId,
                                         SocialPlatform platform,
                                         SocialUpload.SourceType sourceType,
                                         VideoFormat format,
                                         PlatformPostOptions options,
                                         Instant scheduledAt,
                                         boolean defer) {
        SocialUpload row = socialUploadRepository
                .findFirstByVideoIdAndPlatform(sourceId, platform)
                .orElseGet(() -> SocialUpload.builder()
                        .id(UUID.randomUUID())
                        .videoId(sourceId)
                        .platform(platform)
                        .sourceType(sourceType)
                        .createdBy(userId)
                        .createdOn(Instant.now())
                        .build());
        row.setStatus(defer ? Status.SCHEDULED : Status.QUEUED);
        row.setSourceType(sourceType);
        row.setVideoFormat(format);
        row.setScheduledAt(defer ? scheduledAt : null);
        if (options != null) {
            row.setTitle(blankToNull(options.getTitle()));
            row.setCaption(blankToNull(options.getCaption()));
            row.setHashtags(joinHashtags(options.getHashtags()));
        }
        row.setLastModifiedBy(userId);
        row.setLastModifiedOn(Instant.now());
        return socialUploadRepository.save(row);
    }

    private PlatformResult validate(UUID sourceId, String userId, SocialPlatform platform, String label) {
        boolean connected = socialConnectionRepository
                .findByUserIdAndPlatform(userId, platform)
                .isPresent();
        if (!connected) {
            return new PlatformResult(platform, "NOT_CONNECTED",
                    "Connect " + platform + " on the Connections page first.");
        }
        boolean alreadyUploaded = socialUploadRepository
                .findFirstByVideoIdAndPlatform(sourceId, platform)
                .map(upload -> upload.getProviderPostId() != null && !upload.getProviderPostId().isBlank())
                .orElse(false);
        if (alreadyUploaded) {
            return new PlatformResult(platform, "ALREADY_UPLOADED",
                    "This " + label + " has already been uploaded to " + platform + ".");
        }
        return new PlatformResult(platform, "QUEUED", "Pending");
    }

    private VideoFormat resolveVideoFormat(Video video) {
        if (video.getJobId() == null) return null;
        return jobRepository.findById(video.getJobId())
                .map(j -> j.getVideoFormat() == null ? VideoFormat.VIDEO : j.getVideoFormat())
                .orElse(null);
    }

    private VideoFormat resolveAssetFormat(Asset asset) {
        if (asset.getJobId() == null) return null;
        return jobRepository.findById(asset.getJobId())
                .map(j -> j.getVideoFormat() == null ? VideoFormat.VIDEO : j.getVideoFormat())
                .orElse(null);
    }

    private void validateOwnership(Video video, String userId) {
        if (video.getCreatedBy() != null && !video.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("Video " + video.getId() + " does not belong to user " + userId);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String joinHashtags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.startsWith("#") ? t.substring(1) : t)
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining(","));
    }
}
