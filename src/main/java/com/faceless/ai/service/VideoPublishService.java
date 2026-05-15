package com.faceless.ai.service;

import com.faceless.ai.entity.Asset;
import com.faceless.ai.entity.AssetType;
import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.model.VideoPublishResponse;
import com.faceless.ai.model.VideoPublishResponse.PlatformResult;
import com.faceless.ai.repository.AssetRepository;
import com.faceless.ai.repository.SocialConnectionRepository;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.producer.PipelineProducer;
import com.faceless.ai.service.producer.PipelineStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Routes a finished {@link Video} to the upload pipeline for one or more
 * social platforms. Each platform that has an existing
 * {@link com.faceless.ai.entity.SocialConnection} for the calling user gets
 * a message on its dedicated SQS upload queue; platforms without a
 * connection are reported as NOT_CONNECTED. Platforms whose backend consumer
 * is not yet implemented are reported as UNSUPPORTED so the UI can render an
 * accurate status without silently dropping requests.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPublishService {

    private final VideoRepository videoRepository;
    private final AssetRepository assetRepository;
    private final SocialConnectionRepository socialConnectionRepository;
    private final SocialUploadRepository socialUploadRepository;
    private final PipelineProducer pipelineProducer;

    public VideoPublishResponse publish(UUID videoId, String userId, Set<SocialPlatform> platforms) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
        validateOwnership(video, userId);

        if (platforms == null || platforms.isEmpty()) {
            return new VideoPublishResponse(videoId, List.of());
        }
        List<PlatformResult> results = platforms.stream()
                        .map(platform -> validatePlatform(video, userId, platform))
                        .toList();

        Set<SocialPlatform> queueablePlatforms =
                results.stream()
                        .filter(r -> "QUEUED".equals(r.getStatus()))
                        .map(PlatformResult::getPlatform)
                        .collect(Collectors.toSet());

        if (!queueablePlatforms.isEmpty()) {
            SocialUploadEvent event = new SocialUploadEvent(video.getId(), queueablePlatforms);
            pipelineProducer.publishVideo(PipelineStage.SOCIAL_UPLOAD, event);
            log.info("Queued video {} for platforms {}", video.getId(), queueablePlatforms);
        }
        return new VideoPublishResponse(videoId, results);
    }
    private PlatformResult validatePlatform(Video video, String userId, SocialPlatform platform) {
        return validatePlatformBySourceId(video.getId(), userId, platform, "video");
    }

    /**
     * Publish a single rendered clip ({@code VIDEO_CLIP} {@link Asset}) to
     * the social platforms. Mirrors {@link #publish(UUID, String, Set)}
     * exactly except the SQS event carries an {@code assetId} instead of a
     * {@code videoId} — the consumer downloads the asset's S3 object
     * directly without touching the {@code videos} table.
     */
    public VideoPublishResponse publishAsset(UUID assetId, String userId, Set<SocialPlatform> platforms) {
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

        List<PlatformResult> results = platforms.stream()
                .map(platform -> validatePlatformBySourceId(asset.getId(), userId, platform, "clip"))
                .toList();

        Set<SocialPlatform> queueablePlatforms = results.stream()
                .filter(r -> "QUEUED".equals(r.getStatus()))
                .map(PlatformResult::getPlatform)
                .collect(Collectors.toSet());

        if (!queueablePlatforms.isEmpty()) {
            pipelineProducer.publishVideo(PipelineStage.SOCIAL_UPLOAD,
                    SocialUploadEvent.forAsset(asset.getId(), queueablePlatforms));
            log.info("Queued clip {} for platforms {}", asset.getId(), queueablePlatforms);
        }
        return new VideoPublishResponse(assetId, results);
    }

    /**
     * Shared connection / already-uploaded check used by both the video
     * and asset publish paths. The {@code SocialUpload.videoId} column
     * stores whichever source id is being uploaded; video and asset UUIDs
     * are disjoint so this stays unambiguous.
     */
    private PlatformResult validatePlatformBySourceId(UUID sourceId, String userId, SocialPlatform platform, String label) {
        boolean connected = socialConnectionRepository.findByUserIdAndPlatform(userId, platform)
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
        return new PlatformResult(platform, "QUEUED", "Upload queued for " + platform + ".");
    }

    private void validateOwnership(Video video, String userId) {
        if (video.getCreatedBy() != null && !video.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("Video " + video.getId() + " does not belong to user " + userId);
        }
    }
}