package com.faceless.ai.service.consumer;

import com.faceless.ai.entity.SocialPlatform;
import com.faceless.ai.entity.SocialUpload;
import com.faceless.ai.entity.Status;
import com.faceless.ai.entity.Video;
import com.faceless.ai.repository.SocialUploadRepository;
import com.faceless.ai.repository.VideoRepository;
import com.faceless.ai.service.S3StorageService;
import com.faceless.ai.service.VideoUploadRequest;
import com.faceless.ai.service.upload.TwitterUploadService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class TwitterUploadConsumer {

    private final VideoRepository videoRepository;
    private final S3StorageService s3StorageService;
    private final TwitterUploadService twitterUploadService;
    private final SocialUploadRepository socialUploadRepository;

    @SqsListener(value = "${chronicleai.queue.twitter-upload}",
                 messageVisibilitySeconds = "900")
    public void consume(String videoIdStr) {
        log.info("Received Twitter upload request for video {}", videoIdStr);
        UUID videoId = UUID.fromString(videoIdStr);

        SocialUpload upload = claimUploadOrSkip(videoId);
        if (upload == null) return;

        try {
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));
            String userId = video.getCreatedBy();
            if (userId == null || userId.isBlank()) {
                throw new IllegalStateException(
                        "Video " + videoId + " has no createdBy — cannot resolve uploader.");
            }

            Path local = s3StorageService.downloadToTemp(video.getStorageUrl(), ".mp4");
            String tweetId = twitterUploadService.uploadVideo(new VideoUploadRequest(
                    userId, local, video.getTitle(), video.getDescription()));

            upload.setProviderPostId(tweetId);
            upload.setStatus(Status.COMPLETED);
            upload.setUploadedAt(Instant.now());
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);

            log.info("Twitter upload complete for video {} (user {}) — tweet id {}", videoId, userId, tweetId);
        } catch (Exception e) {
            log.error("Twitter upload failed for video {}: {}", videoIdStr, e.getMessage(), e);
            upload.setStatus(Status.FAILED);
            upload.setLastModifiedOn(Instant.now());
            socialUploadRepository.save(upload);
            throw new RuntimeException(e);
        }
    }

    private SocialUpload claimUploadOrSkip(UUID videoId) {
        SocialUpload existing = socialUploadRepository
                .findFirstByVideoIdAndPlatform(videoId, SocialPlatform.TWITTER)
                .orElse(null);
        if (existing != null) {
            existing.setStatus(Status.PROCESSING);
            existing.setLastModifiedOn(Instant.now());
            return socialUploadRepository.save(existing);
        }
        SocialUpload claim = SocialUpload.builder()
                .id(UUID.randomUUID())
                .videoId(videoId)
                .platform(SocialPlatform.TWITTER)
                .status(Status.PROCESSING)
                .createdOn(Instant.now())
                .lastModifiedOn(Instant.now())
                .build();
        try {
            return socialUploadRepository.save(claim);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent duplicate SQS delivery — another worker already claimed this (videoId, platform).
            log.info("Skipping duplicate Twitter upload delivery for video {}", videoId);
            return null;
        }
    }
}