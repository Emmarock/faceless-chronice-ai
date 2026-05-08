package com.faceless.ai.service;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.Video;
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
import java.util.UUID;

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

    public VideoPublishResponse publish(UUID videoId, String userId, List<SocialPlatform> platforms) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));

        if (video.getCreatedBy() != null && !video.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("Video " + videoId + " does not belong to user " + userId);
        }

        List<PlatformResult> results = new ArrayList<>();
        if (platforms == null || platforms.isEmpty()) {
            return new VideoPublishResponse(videoId, results);
        }

        for (SocialPlatform platform : platforms) {
            results.add(publishOne(video, userId, platform));
        }

        return new VideoPublishResponse(videoId, results);
    }

    private PlatformResult publishOne(Video video, String userId, SocialPlatform platform) {
        boolean connected = socialConnectionRepository
                .findByUserIdAndPlatform(userId, platform)
                .isPresent();
        if (!connected) {
            return new PlatformResult(platform, "NOT_CONNECTED",
                    "Connect " + platform + " on the Connections page first.");
        }

        switch (platform) {
            case YOUTUBE -> {
                boolean alreadyUploaded = socialUploadRepository
                        .findFirstByVideoIdAndPlatform(video.getId(), SocialPlatform.YOUTUBE)
                        .map(u -> u.getProviderPostId() != null && !u.getProviderPostId().isBlank())
                        .orElse(false);
                if (alreadyUploaded) {
                    return new PlatformResult(platform, "ALREADY_UPLOADED",
                            "This video has already been uploaded to YouTube.");
                }
                pipelineProducer.send(PipelineStage.YOUTUBE_UPLOAD, video.getId().toString());
                log.info("Queued video {} for YouTube upload (user {})", video.getId(), userId);
                return new PlatformResult(platform, "QUEUED",
                        "Upload queued — it will appear on YouTube shortly.");
            }
            case TWITTER -> {
                pipelineProducer.send(PipelineStage.TWITTER_UPLOAD, video.getId().toString());
                log.info("Queued video {} for Twitter upload (user {})", video.getId(), userId);
                return new PlatformResult(platform, "QUEUED",
                        "Tweet queued — it will post once Twitter finishes processing the media.");
            }
            case TIKTOK -> {
                pipelineProducer.send(PipelineStage.TIKTOK_UPLOAD, video.getId().toString());
                log.info("Queued video {} for TikTok upload (user {})", video.getId(), userId);
                return new PlatformResult(platform, "QUEUED",
                        "Upload queued — the video will appear in your TikTok inbox to publish.");
            }
            case FACEBOOK -> {
                pipelineProducer.send(PipelineStage.FACEBOOK_UPLOAD, video.getId().toString());
                log.info("Queued video {} for Facebook upload (user {})", video.getId(), userId);
                return new PlatformResult(platform, "QUEUED",
                        "Upload queued — the video will be posted to your Facebook Page shortly.");
            }
            default -> {
                return new PlatformResult(platform, "UNSUPPORTED",
                        platform + " uploads are not wired up yet.");
            }
        }
    }
}