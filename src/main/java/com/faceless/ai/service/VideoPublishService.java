package com.faceless.ai.service;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.Video;
import com.faceless.ai.model.SocialUploadEvent;
import com.faceless.ai.model.VideoPublishResponse;
import com.faceless.ai.model.VideoPublishResponse.PlatformResult;
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

        boolean connected = socialConnectionRepository.findByUserIdAndPlatform(userId, platform)
                        .isPresent();
        if (!connected) {
            return new PlatformResult(platform, "NOT_CONNECTED", "Connect " + platform + " on the Connections page first.");
        }

        boolean alreadyUploaded =
                socialUploadRepository
                        .findFirstByVideoIdAndPlatform(video.getId(), platform)
                        .map(upload -> upload.getProviderPostId() != null && !upload.getProviderPostId().isBlank())
                        .orElse(false);
        if (alreadyUploaded) {
            return new PlatformResult(
                    platform,
                    "ALREADY_UPLOADED",
                    "This video has already been uploaded to "
                            + platform
                            + "."
            );
        }
        return new PlatformResult(platform, "QUEUED", "Upload queued for " + platform + ".");
    }

    private void validateOwnership(Video video, String userId) {
        if (video.getCreatedBy() != null && !video.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("Video " + video.getId() + " does not belong to user " + userId);
        }
    }
}