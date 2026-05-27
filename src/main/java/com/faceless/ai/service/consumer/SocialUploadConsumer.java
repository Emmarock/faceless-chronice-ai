package com.faceless.ai.service.consumer;
import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.JobRepository;
import com.faceless.ai.repository.ScriptRepository;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.model.VideoScript;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.VideoUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class SocialUploadConsumer {

    /**
     * How long the IG / LinkedIn ingestion side gets to fetch the presigned
     * URL before it expires. Both platforms transcode after fetch and the
     * fetch itself usually finishes in &lt;30s, so 30 minutes is comfortable.
     */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(30);

    private final VideoRepository videoRepository;

    private final AssetRepository assetRepository;

    private final JobRepository jobRepository;

    private final ScriptRepository scriptRepository;

    private final S3StorageService s3StorageService;

    private final List<VideoUploadService> services;

    private final SocialUploadRepository socialUploadRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SqsListener(value = "${chronicleai.queue.social-upload}", messageVisibilitySeconds = "900")
    public void consume(SocialUploadEvent event) {
        UUID assetId = event.assetId();
        if (assetId != null) {
            uploadAsset(assetId, event.platforms());
            return;
        }
        uploadVideo(event.videoId(), event.platforms());
    }

    private void uploadVideo(UUID videoId, Set<SocialPlatform> requestedPlatforms) {
        Video video = videoRepository.findById(videoId).orElseThrow();
        Path local;
        String presigned;
        try {
            local = s3StorageService.downloadToTemp(video.getStorageUrl(), ".mp4");
            presigned = s3StorageService.presignedUrl(video.getStorageUrl(), PRESIGN_TTL);
        } catch (IOException e) {
            log.error("Unable to download mp4 file at {}", video.getStorageUrl(), e);
            return;
        }
        runUploads(
                videoId,
                requestedPlatforms,
                local,
                presigned,
                video.getCreatedBy(),
                video.getTitle(),
                video.getDescription());
    }

    /**
     * Upload a single rendered clip (an {@link Asset} of type
     * {@code VIDEO_CLIP}) — used when the user publishes one scene's clip
     * from the asset library rather than the full job-final video. The
     * asset's id is recorded in the {@code SocialUpload.videoId} column;
     * asset and video UUIDs are disjoint, so this stays unambiguous.
     */
    private void uploadAsset(UUID assetId, Set<SocialPlatform> requestedPlatforms) {
        Asset asset = assetRepository.findById(assetId).orElseThrow();
        Path local;
        String presigned;
        try {
            local = s3StorageService.downloadToTemp(asset.getUrl(), ".mp4");
            presigned = s3StorageService.presignedUrl(asset.getUrl(), PRESIGN_TTL);
        } catch (IOException e) {
            log.error("Unable to download asset {} from {}", assetId, asset.getUrl(), e);
            return;
        }
        String title = resolveAssetTitle(asset);
        runUploads(
                assetId,
                requestedPlatforms,
                local,
                presigned,
                asset.getCreatedBy(),
                title,
                "");
    }

    private void runUploads(UUID sourceId,
                            Set<SocialPlatform> requestedPlatforms,
                            Path local,
                            String publicUrl,
                            String userId,
                            String defaultTitle,
                            String defaultDescription) {
        List<CompletableFuture<Void>> uploads =
                services.stream()
                        .filter(service -> requestedPlatforms.contains(service.platform()))
                        .map(service -> uploadToPlatform(
                                service,
                                sourceId,
                                local,
                                publicUrl,
                                userId,
                                defaultTitle,
                                defaultDescription))
                        .toList();
        CompletableFuture.allOf(uploads.toArray(new CompletableFuture[0])).join();
    }

    private String resolveAssetTitle(Asset asset) {
        if (asset.getJobId() == null) return "Rendered clip";
        return jobRepository.findById(asset.getJobId())
                .map(job -> scriptRepository.findByJobId(job.getId())
                        .map(s -> {
                            try {
                                VideoScript vs = objectMapper.readValue(s.getContent(), VideoScript.class);
                                if (vs.getTitle() != null && !vs.getTitle().isBlank()) return vs.getTitle();
                            } catch (Exception ignored) {
                                // Fall through to the question / default.
                            }
                            return null;
                        })
                        .filter(t -> t != null && !t.isBlank())
                        .orElseGet(() -> job.getQuestion() != null ? job.getQuestion() : "Rendered clip"))
                .orElse("Rendered clip");
    }

    private CompletableFuture<Void> uploadToPlatform(VideoUploadService service,
                                                     UUID videoId,
                                                     Path local,
                                                     String publicUrl,
                                                     String userId,
                                                     String defaultTitle,
                                                     String defaultDescription) {
        SocialPlatform platform = service.platform();

        SocialUpload upload = claimUploadOrSkip(videoId, platform, userId);

        if (upload == null) {
            return CompletableFuture.completedFuture(null);
        }

        VideoUploadRequest request = new VideoUploadRequest(
                userId,
                local,
                publicUrl,
                upload.getTitle() != null && !upload.getTitle().isBlank()
                        ? upload.getTitle() : defaultTitle,
                defaultDescription,
                upload.getCaption(),
                splitHashtags(upload.getHashtags()),
                upload.getVideoFormat());

        return service.uploadVideo(request)
                .thenAccept(providerPostId -> {
                    upload.setProviderPostId(providerPostId);
                    upload.setStatus(Status.COMPLETED);
                    upload.setUploadedAt(Instant.now());
                    upload.setLastModifiedOn(Instant.now());
                    socialUploadRepository.save(upload);
                    log.info("{} upload completed for video {}", platform, videoId);
                })
                .exceptionally(ex -> {
                    log.error("{} upload failed for video {}", platform, videoId, ex);
                    upload.setStatus(Status.FAILED);
                    upload.setLastModifiedOn(Instant.now());
                    socialUploadRepository.save(upload);
                    return null;
                });
    }

    /**
     * Reserves the {@link SocialUpload} row for processing.
     *
     * <p>The redesigned publish flow pre-creates rows in
     * {@code VideoPublishService} (status=QUEUED for immediate publishes,
     * SCHEDULED for deferred ones). The consumer normally just transitions
     * the existing row to PROCESSING. The legacy fallback path (no row yet
     * — possible for SQS messages enqueued by older code) still creates one
     * defensively so duplicate deliveries don't fail open.
     */
    private SocialUpload claimUploadOrSkip(UUID videoId, SocialPlatform platform, String userId) {
        SocialUpload existing =
                socialUploadRepository
                        .findFirstByVideoIdAndPlatform(videoId, platform)
                        .orElse(null);
        if (existing != null) {
            if (existing.getProviderPostId() != null && !existing.getProviderPostId().isBlank()) {
                log.info("Skipping {} upload for {} — already uploaded (postId={})",
                        platform, videoId, existing.getProviderPostId());
                return null;
            }
            existing.setStatus(Status.PROCESSING);
            existing.setLastModifiedOn(Instant.now());
            return socialUploadRepository.save(existing);
        }
        SocialUpload claim =
                SocialUpload.builder()
                        .id(UUID.randomUUID())
                        .videoId(videoId)
                        .platform(platform)
                        .status(Status.PROCESSING)
                        .createdBy(userId)
                        .createdOn(Instant.now())
                        .lastModifiedOn(Instant.now())
                        .build();
        try {
            return socialUploadRepository.save(claim);
        } catch (DataIntegrityViolationException dup) {
            log.info("Skipping duplicate {} upload delivery for video {}", platform, videoId);
            return null;
        }
    }

    private static List<String> splitHashtags(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
